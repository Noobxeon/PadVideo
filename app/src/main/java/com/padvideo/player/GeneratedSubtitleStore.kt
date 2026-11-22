package com.padvideo.player

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.padvideo.data.model.BatchSubtitleTarget
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.SubtitleWriteDestination
import com.padvideo.data.model.SubtitleWriteResult
import com.padvideo.data.preferences.AppSettingsRepository
import com.padvideo.data.repository.SmbLocation
import com.padvideo.data.repository.SmbRepository
import com.padvideo.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Writes generated .srt subtitles directly back to the video's original directory
 * (SMB share, Local folder, SAF/USB tree, or WebDAV folder) and falls back cleanly
 * to an app-private indexed subtitle store whenever the source is read-only or cloud-only (Quark).
 *
 * Next playback automatically detects sidecar subtitles in BOTH the original directory
 * AND the local index store!
 */
internal class GeneratedSubtitleStore(
    private val context: Context?,
    private val baseDir: File,
    private val smbRepository: SmbRepository,
    private val webDavRepository: WebDavRepository,
    private val settingsRepository: AppSettingsRepository
) {

    private val indexFile = File(baseDir, "generated_subtitle_index.json")
    private val indexLock = Any()

    init {
        baseDir.mkdirs()
    }

    /**
     * Checks whether a locally indexed fallback subtitle already exists for this video URI.
     */
    fun findIndexedSubtitle(videoUri: String): File? {
        synchronized(indexLock) {
            val map = readIndexLocked()
            val path = map[videoUri] ?: return null
            val f = File(path)
            return if (f.exists() && f.length() > 0L) f else null
        }
    }

    fun hasIndexedSubtitle(videoUri: String): Boolean = findIndexedSubtitle(videoUri) != null

    suspend fun writeSubtitleForTarget(
        target: BatchSubtitleTarget,
        srtFileName: String,
        srtContentUtf8: String
    ): SubtitleWriteResult = withContext(Dispatchers.IO) {
        val bytes = srtContentUtf8.toByteArray(Charsets.UTF_8)
        val video = target.video
        val uri = video.uriString

        val directAttempt: Result<String> = when {
            uri.startsWith("smb://", ignoreCase = true) -> runCatching {
                smbRepository.registerSavedMounts(settingsRepository.mountedSources.value)
                val videoLoc = SmbLocation.parse(uri)
                val srtLoc = videoLoc.parent().child(srtFileName)
                smbRepository.writeSmallFileBytes(srtLoc, bytes)
                srtLoc.uri
            }

            !video.filePath.isNullOrBlank() -> runCatching {
                val parentDir = File(video.filePath).parentFile
                    ?: throw IOException("无法解析本地视频所在目录")
                val outFile = File(parentDir, srtFileName)
                outFile.writeBytes(bytes)
                outFile.absolutePath
            }

            uri.startsWith("content://", ignoreCase = true) && context != null -> runCatching {
                val parentUriStr = target.safParentDirUri ?: target.safParentTreeUri
                    ?: throw IOException("未提供 SAF 挂载目录写入句柄")
                val parentDoc = DocumentFile.fromTreeUri(context, Uri.parse(parentUriStr))
                    ?: DocumentFile.fromSingleUri(context, Uri.parse(parentUriStr))
                    ?: throw IOException("无法打开 SAF 目标目录")
                if (!parentDoc.canWrite()) {
                    throw IOException("当前外接存储/SAF 目录为只读权限")
                }
                parentDoc.findFile(srtFileName)?.delete()
                val created = parentDoc.createFile("application/x-subrip", srtFileName)
                    ?: parentDoc.createFile("application/octet-stream", srtFileName)
                    ?: throw IOException("无法在 SAF 目录创建字幕文件")
                context.contentResolver.openOutputStream(created.uri, "wt")?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: throw IOException("无法写入 SAF 字幕输出流")
                created.uri.toString()
            }

            (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) &&
                !video.mountSourceId.isNullOrBlank() -> runCatching {
                val mount = settingsRepository.getMountedSource(video.mountSourceId)
                    ?.takeIf { it.type == StorageMountType.WEBDAV }
                    ?: throw IOException("未找到对应 WebDAV 挂载配置")
                val parentUrl = video.folderPath.ifBlank {
                    uri.substringBeforeLast('/') + "/"
                }
                val encodedName = URLEncoder.encode(srtFileName, "UTF-8").replace("+", "%20")
                val targetUrl = if (parentUrl.endsWith("/")) "$parentUrl$encodedName" else "$parentUrl/$encodedName"
                // Validate URL syntax
                URI(targetUrl)
                webDavRepository.uploadFileBytes(mount, targetUrl, bytes)
                targetUrl
            }

            else -> Result.failure(IOException("该来源不支持直接写回原目录"))
        }

        if (directAttempt.isSuccess) {
            return@withContext SubtitleWriteResult(
                destination = SubtitleWriteDestination.ORIGINAL_DIRECTORY,
                savedLocationDisplay = directAttempt.getOrThrow()
            )
        }

        // Seamless automatic fallback to app-private indexed subtitle store so next playback STILL auto-loads!
        val safeName = srtFileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val keyHash = sha256Short(uri)
        baseDir.mkdirs()
        val fallbackFile = File(baseDir, "${keyHash}_${safeName}")
        fallbackFile.writeBytes(bytes)
        recordIndex(uri, fallbackFile.absolutePath)

        SubtitleWriteResult(
            destination = SubtitleWriteDestination.LOCAL_CACHE_FALLBACK,
            savedLocationDisplay = fallbackFile.absolutePath,
            fallbackReason = directAttempt.exceptionOrNull()?.message
        )
    }

    private fun recordIndex(videoUri: String, localSrtPath: String) {
        synchronized(indexLock) {
            val map = readIndexLocked().toMutableMap()
            map[videoUri] = localSrtPath
            val json = JSONObject()
            map.forEach { (k, v) -> json.put(k, v) }
            indexFile.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    private fun readIndexLocked(): Map<String, String> {
        if (!indexFile.exists()) return emptyMap()
        return runCatching {
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            val out = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = json.optString(k)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
