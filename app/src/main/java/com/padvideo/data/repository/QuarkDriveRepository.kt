package com.padvideo.data.repository

import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct Quark Cloud Drive (夸克网盘) browser & streaming resolver.
 * Uses user-provided Cookie (captured via built-in pan.quark.cn WebView login or manual paste).
 */
class QuarkDriveRepository(
    private val httpClient: OkHttpClient = defaultClient(),
    private val apiBaseUrl: String = "https://drive-pc.quark.cn/1/clouddrive"
) {

    fun buildRequestHeaders(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie.trim(),
        "Referer" to "https://pan.quark.cn/",
        "Origin" to "https://pan.quark.cn",
        "User-Agent" to QUARK_USER_AGENT
    )

    suspend fun listFolder(
        source: MountedStorageSource,
        parentFid: String = "0",
        searchQuery: String = ""
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val cookie = source.cookie.trim()
        if (cookie.isEmpty()) {
            throw IOException("请先登录夸克网盘或填写有效的 Cookie")
        }

        val url = "${apiBaseUrl.trimEnd('/')}/file/sort".toHttpUrl().newBuilder()
            .addQueryParameter("pr", "ucpro")
            .addQueryParameter("fr", "pc")
            .addQueryParameter("uc_param_str", "")
            .addQueryParameter("pdir_fid", parentFid.ifBlank { "0" })
            .addQueryParameter("_page", "1")
            .addQueryParameter("_size", "200")
            .addQueryParameter("_fetch_total", "1")
            .addQueryParameter("_fetch_sub_dirs", "0")
            .addQueryParameter("_sort", "file_type:asc,file_name:asc")
            .build()

        val reqBuilder = Request.Builder().url(url).get()
        buildRequestHeaders(cookie).forEach { (k, v) -> reqBuilder.header(k, v) }

        val bodyText = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            when (resp.code) {
                200 -> resp.body?.string().orEmpty()
                401, 403 -> throw IOException("夸克网盘登录已过期 (${resp.code})，请重新网页登录或更新 Cookie")
                else -> throw IOException("夸克网盘请求失败 (HTTP ${resp.code})")
            }
        }

        val root = JSONObject(bodyText)
        val status = root.optInt("status", 200)
        val code = root.optInt("code", 0)
        if (status != 200 || code != 0) {
            val msg = root.optString("message").ifBlank { "授权可能已失效，请重新登录夸克网盘" }
            throw IOException("夸克网盘返回错误: $msg")
        }

        val listArr = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val parsed = mutableListOf<QuarkNode>()
        for (i in 0 until listArr.length()) {
            val obj = listArr.optJSONObject(i) ?: continue
            val fid = obj.optString("fid")
            val name = obj.optString("file_name")
            if (fid.isBlank() || name.isBlank()) continue
            val isDir = obj.optBoolean("dir", false) || obj.optInt("file_type", 1) == 0
            val size = obj.optLong("size", 0L)
            val updatedAt = obj.optLong("updated_at", obj.optLong("l_updated_at", 0L))
            parsed.add(QuarkNode(fid = fid, name = name, isDir = isDir, size = size, updatedAtMs = updatedAt))
        }

        val subtitles = parsed.filter { !it.isDir && FileExplorerRepository.isSubtitleFileName(it.name) }

        parsed.mapNotNull { node ->
            if (searchQuery.isNotBlank() && !node.name.contains(searchQuery.trim(), ignoreCase = true)) {
                return@mapNotNull null
            }
            if (node.isDir) {
                FileEntry(
                    name = node.name,
                    path = "quark://${source.id}/${node.fid}",
                    uriString = "quark://${source.id}/${node.fid}",
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModifiedMs = node.updatedAtMs,
                    remoteId = node.fid
                )
            } else {
                val isVid = FileExplorerRepository.isVideoFileName(node.name)
                val isSub = FileExplorerRepository.isSubtitleFileName(node.name)
                if (!isVid && !isSub) return@mapNotNull null
                val sidecars = if (isVid) {
                    val base = node.name.substringBeforeLast('.').lowercase()
                    subtitles.filter { s ->
                        val stem = s.name.substringBeforeLast('.').lowercase()
                        stem == base || stem.startsWith("$base.") || stem.startsWith("${base}_")
                    }.map { "quark://${source.id}/${it.fid}/${it.name}" }
                } else {
                    emptyList()
                }
                val videoItem = if (isVid) {
                    VideoItem(
                        id = "quark_${source.id}_${node.fid}",
                        title = node.name,
                        uriString = "quark://${source.id}/${node.fid}",
                        folderName = source.name,
                        folderPath = parentFid,
                        sizeBytes = node.size,
                        mimeType = FileExplorerRepository.guessVideoMimeType(node.name),
                        sidecarSubtitlePaths = sidecars,
                        mountSourceId = source.id,
                        remoteFileId = node.fid
                    )
                } else {
                    null
                }
                FileEntry(
                    name = node.name,
                    path = "quark://${source.id}/${node.fid}",
                    uriString = "quark://${source.id}/${node.fid}",
                    isDirectory = false,
                    sizeBytes = node.size,
                    lastModifiedMs = node.updatedAtMs,
                    isVideoFile = isVid,
                    isSubtitleFile = isSub,
                    sidecarSubtitles = sidecars,
                    remoteId = node.fid,
                    videoItem = videoItem
                )
            }
        }.sortedWith(
            compareBy<FileEntry> { !it.isDirectory }.thenComparator { a, b ->
                FileExplorerRepository.compareNaturalStrings(a.name, b.name)
            }
        )
    }

    /**
     * Resolves a Quark file ID (fid) to a temporary direct CDN playback URL + required HTTP headers.
     */
    suspend fun resolveStreamUrl(
        source: MountedStorageSource,
        fid: String
    ): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val cookie = source.cookie.trim()
        if (cookie.isEmpty()) throw IOException("夸克网盘 Cookie 为空，请重新登录")

        val url = "${apiBaseUrl.trimEnd('/')}/file/download".toHttpUrl().newBuilder()
            .addQueryParameter("pr", "ucpro")
            .addQueryParameter("fr", "pc")
            .addQueryParameter("uc_param_str", "")
            .build()

        val payload = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val headers = buildRequestHeaders(cookie)
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val bodyText = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("获取夸克播放直链失败 (HTTP ${resp.code})，请重新登录夸克网盘")
            }
            resp.body?.string().orEmpty()
        }

        val root = JSONObject(bodyText)
        val dataArr = root.optJSONArray("data")
        val downloadUrl = dataArr?.optJSONObject(0)?.optString("download_url").orEmpty()
        if (downloadUrl.isBlank()) {
            val msg = root.optString("message").ifBlank { "未返回播放地址，请检查夸克登录状态" }
            throw IOException("夸克直链解析失败: $msg")
        }

        downloadUrl to headers
    }

    suspend fun downloadSubtitleBytes(source: MountedStorageSource, fid: String): ByteArray = withContext(Dispatchers.IO) {
        val (dlUrl, headers) = resolveStreamUrl(source, fid)
        val reqBuilder = Request.Builder().url(dlUrl).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载夸克字幕失败 (${resp.code})")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private data class QuarkNode(
        val fid: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val updatedAtMs: Long
    )

    companion object {
        const val QUARK_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
