package com.padvideo.data.repository

import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavRepository(
    private val httpClient: OkHttpClient = defaultClient()
) {

    fun buildAuthHeaders(source: MountedStorageSource): Map<String, String> {
        if (source.username.isBlank() && source.password.isBlank()) return emptyMap()
        return mapOf("Authorization" to Credentials.basic(source.username.trim(), source.password))
    }

    fun normalizeRootUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "请输入 WebDAV 服务器地址，例如 http://192.168.1.10:5244/dav" }
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull()
        require(
            uri != null &&
                (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        ) { "WebDAV 地址格式无效，请以 http:// 或 https:// 开头" }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    suspend fun listDirectory(
        source: MountedStorageSource,
        directoryUrl: String,
        searchQuery: String = ""
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val normalizedDir = if (directoryUrl.endsWith("/")) directoryUrl else "$directoryUrl/"
        val headers = buildAuthHeaders(source)

        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url(normalizedDir)
            .header("Depth", "1")
            .header("User-Agent", USER_AGENT)
            .method("PROPFIND", propfindBody)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val xmlText = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            when (resp.code) {
                200, 207 -> resp.body?.string().orEmpty()
                401, 403 -> throw IOException("WebDAV 认证失败 (${resp.code})，请检查用户名与密码/应用令牌")
                404 -> throw IOException("WebDAV 目录不存在 (404)，请核对路径")
                else -> throw IOException("WebDAV 请求失败 (HTTP ${resp.code})")
            }
        }

        val parsed = parsePropfindXml(normalizedDir, xmlText)
        val subtitles = parsed.filter { !it.isDirectory && FileExplorerRepository.isSubtitleFileName(it.name) }

        parsed.mapNotNull { item ->
            if (searchQuery.isNotBlank() && !item.name.contains(searchQuery.trim(), ignoreCase = true)) {
                return@mapNotNull null
            }
            if (item.isDirectory) {
                FileEntry(
                    name = item.name,
                    path = item.url,
                    uriString = item.url,
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModifiedMs = item.lastModifiedMs
                )
            } else {
                val isVid = FileExplorerRepository.isVideoFileName(item.name)
                val isSub = FileExplorerRepository.isSubtitleFileName(item.name)
                if (!isVid && !isSub) return@mapNotNull null
                val sidecars = if (isVid) {
                    val base = item.name.substringBeforeLast('.').lowercase()
                    subtitles.filter { s ->
                        val stem = s.name.substringBeforeLast('.').lowercase()
                        stem == base || stem.startsWith("$base.") || stem.startsWith("${base}_")
                    }.map { it.url }
                } else {
                    emptyList()
                }
                val videoItem = if (isVid) {
                    VideoItem(
                        id = item.url,
                        title = item.name,
                        uriString = item.url,
                        folderName = source.name,
                        folderPath = normalizedDir,
                        sizeBytes = item.sizeBytes,
                        mimeType = FileExplorerRepository.guessVideoMimeType(item.name),
                        sidecarSubtitlePaths = sidecars,
                        httpHeaders = headers,
                        mountSourceId = source.id
                    )
                } else {
                    null
                }
                FileEntry(
                    name = item.name,
                    path = item.url,
                    uriString = item.url,
                    isDirectory = false,
                    sizeBytes = item.sizeBytes,
                    lastModifiedMs = item.lastModifiedMs,
                    isVideoFile = isVid,
                    isSubtitleFile = isSub,
                    sidecarSubtitles = sidecars,
                    videoItem = videoItem
                )
            }
        }.sortedWith(
            compareBy<FileEntry> { !it.isDirectory }.thenComparator { a, b ->
                FileExplorerRepository.compareNaturalStrings(a.name, b.name)
            }
        )
    }

    suspend fun downloadBytes(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("WebDAV 下载失败 (${resp.code})")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private data class DavItem(
        val name: String,
        val url: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    private fun parsePropfindXml(requestUrl: String, xmlText: String): List<DavItem> {
        val baseUri = URI(requestUrl)
        val reqNormalizedPath = decodePath(baseUri.rawPath ?: "/").trimEnd('/')

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText.trim())))
        val responseNodes = doc.getElementsByTagNameNS("*", "response")

        val items = mutableListOf<DavItem>()
        for (i in 0 until responseNodes.length) {
            val respEl = responseNodes.item(i) as? Element ?: continue
            val currentHref = firstChildTagText(respEl, "href").trim()
            if (currentHref.isBlank()) continue

            val currentDisplayName = firstChildTagText(respEl, "displayname").trim()
            val currentSize = firstChildTagText(respEl, "getcontentlength").trim().toLongOrNull() ?: 0L
            val currentIsDir = respEl.getElementsByTagNameNS("*", "collection").length > 0

            val resolved = resolveHref(baseUri, currentHref)
            val resolvedPath = decodePath(resolved.rawPath ?: "/").trimEnd('/')
            if (resolvedPath != reqNormalizedPath) {
                val isDir = currentIsDir || currentHref.endsWith("/")
                val finalUrl = resolved.toASCIIString().let { u ->
                    if (isDir && !u.endsWith("/")) "$u/" else u
                }
                val derivedName = currentDisplayName
                    .ifBlank { resolvedPath.substringAfterLast('/').trim() }
                if (derivedName.isNotBlank()) {
                    items.add(
                        DavItem(
                            name = derivedName,
                            url = finalUrl,
                            isDirectory = isDir,
                            sizeBytes = currentSize,
                            lastModifiedMs = 0L
                        )
                    )
                }
            }
        }
        return items
    }

    private fun firstChildTagText(parent: Element, localName: String): String {
        val list = parent.getElementsByTagNameNS("*", localName)
        if (list.length == 0) return ""
        return list.item(0)?.textContent.orEmpty()
    }

    private fun resolveHref(baseUri: URI, href: String): URI {
        val cleaned = href.trim()
        return runCatching {
            if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) {
                URI(cleaned)
            } else {
                baseUri.resolve(cleaned)
            }
        }.getOrElse {
            URI(baseUri.scheme, baseUri.authority, cleaned, null, null)
        }
    }

    private fun decodePath(raw: String): String {
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    suspend fun uploadFileBytes(
        source: MountedStorageSource,
        targetFileUrl: String,
        bytes: ByteArray,
        contentType: String = "application/x-subrip; charset=utf-8"
    ) = withContext(Dispatchers.IO) {
        val headers = buildAuthHeaders(source)
        val reqBuilder = Request.Builder()
            .url(targetFileUrl)
            .header("User-Agent", USER_AGENT)
            .put(bytes.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("WebDAV 写入字幕失败 (HTTP ${resp.code})")
            }
        }
    }

    companion object {
        private const val USER_AGENT = "PadVideo/1.2 WebDAVClient"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
