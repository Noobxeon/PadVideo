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
            val uri = runCatching {
                if (address.startsWith("\\\\")) {
                    val unc = address.drop(2)
                    val host = unc.substringBefore('\\')
                    val path = unc.drop(host.length).replace('\\', '/')
                    URI("smb", host, path, null, null)
                } else {
                    val url = if ("://" in address) address else "smb://$address"
                    URI(url.replace(" ", "%20"))
                }
            }.getOrNull()
            require(uri != null && uri.scheme.equals("smb", ignoreCase = true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
            ) { "请输入电脑 IP、电脑名或完整的 SMB 共享路径，账号和密码请单独填写" }
            val path = uri.path.trim('/')
            val parts = if (path.isEmpty()) emptyList() else path.split('/')
            require(parts.all(::isPathSegment) &&
                !Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath)
            ) { "共享路径中的目录格式无效" }
            return SmbLocation(
                uri.host.lowercase(Locale.ROOT),
                if (uri.port == -1) 445 else uri.port,
                parts.firstOrNull(),
                parts.drop(1)
            )
        }
    }
}
