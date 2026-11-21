package com.padvideo.data.repository

import java.net.URI
import java.util.Locale

internal data class SmbLocation(
    val host: String,
    val port: Int,
    val share: String,
    val segments: List<String>
) {
    val path: String get() = segments.joinToString("\\")
    val uri: String get() = URI(
        "smb", null, host, if (port == 445) -1 else port,
        "/" + (listOf(share) + segments).joinToString("/"), null, null
    ).toASCIIString()

    fun child(name: String) = copy(segments = segments + name)
    fun parent() = copy(segments = segments.dropLast(1))

    companion object {
        fun parse(value: String): SmbLocation {
            val address = value.trim()
            val uri = runCatching {
                if (address.startsWith("\\\\")) {
                    val unc = address.drop(2)
                    val host = unc.substringBefore('\\')
                    val path = unc.drop(host.length).replace('\\', '/')
                    URI("smb", host, path, null, null)
                } else {
                    URI(address.replace(" ", "%20"))
                }
            }.getOrNull()
            require(uri != null && uri.scheme.equals("smb", ignoreCase = true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
            ) { "请输入 smb://服务器/共享名，或粘贴 Windows 共享路径；账号和密码请单独填写" }
            val parts = uri.path.trim('/').split('/')
            require(parts.all { it.isNotBlank() && it != "." && it != ".." &&
                '\\' !in it && it.none(Char::isISOControl) } &&
                !Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath)
            ) { "SMB 地址需要包含有效的共享名和目录" }
            return SmbLocation(
                uri.host.lowercase(Locale.ROOT),
                if (uri.port == -1) 445 else uri.port,
                parts.first(),
                parts.drop(1)
            )
        }
    }
}
