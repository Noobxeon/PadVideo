package com.padvideo.data.repository

import java.net.URI
import java.util.Locale

internal data class SmbLocation(
    val host: String,
    val port: Int,
    val share: String?,
    val segments: List<String>
) {
    val path: String get() = segments.joinToString("\\")
    val uri: String get() = URI(
        "smb", null, host, if (port == 445) -1 else port,
        share?.let { "/" + (listOf(it) + segments).joinToString("/") }.orEmpty(), null, null
    ).toASCIIString()

    val isServer: Boolean get() = share == null

    fun child(name: String) = if (isServer) copy(share = name) else copy(segments = segments + name)
    fun parent() = if (segments.isEmpty()) serverRoot() else copy(segments = segments.dropLast(1))
    fun serverRoot() = copy(share = null, segments = emptyList())
    fun shareRoot() = copy(segments = emptyList())

    companion object {
        fun isPathSegment(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && name.none(Char::isISOControl)

        fun parse(value: String): SmbLocation {
            val address = value.trim()
            require(address.isNotEmpty()) { "请输入服务器 IP、电脑名或完整 SMB 共享地址" }
            val uri = runCatching {
                if (address.startsWith("\\\\")) {
                    val unc = address.drop(2).trimEnd('\\')
                    val hostAndPort = unc.substringBefore('\\')
                    val host = hostAndPort.substringBefore(':')
                    val port = hostAndPort.substringAfter(':', "").toIntOrNull() ?: -1
                    val rawPath = unc.drop(hostAndPort.length).replace('\\', '/')
                    URI("smb", null, host, port, rawPath, null, null)
                } else {
                    val normalized = address.replace('\\', '/')
                    val url = if ("://" in normalized) normalized else "smb://$normalized"
                    URI(url.replace(" ", "%20"))
                }
            }.getOrNull()
            require(
                uri != null &&
                    uri.scheme.equals("smb", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535)
            ) { "请输入电脑 IP、电脑名或完整路径（如 192.168.1.10 或 \\\\192.168.1.10\\Movies），账号密码请填在下方" }
            val cleanPath = (uri.path ?: "").trim('/')
            val parts = if (cleanPath.isEmpty()) emptyList() else cleanPath.split('/').filter { it.isNotEmpty() }
            require(
                parts.all(::isPathSegment) &&
                    !Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath ?: "")
            ) { "共享路径中的目录格式无效" }
            return SmbLocation(
                host = uri.host.lowercase(Locale.ROOT),
                port = if (uri.port == -1) 445 else uri.port,
                share = parts.firstOrNull(),
                segments = parts.drop(1)
            )
        }
    }
}
