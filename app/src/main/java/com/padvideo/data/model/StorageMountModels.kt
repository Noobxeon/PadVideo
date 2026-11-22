package com.padvideo.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class StorageMountType(val label: String) {
    SMB("Windows / NAS (SMB)"),
    WEBDAV("WebDAV (NAS / AList)"),
    QUARK("夸克网盘")
}

@Serializable
data class MountedStorageSource(
    val id: String = UUID.randomUUID().toString(),
    val type: StorageMountType,
    val name: String,
    /**
     * - For SMB: host or full path (e.g. "192.168.1.10", "smb://192.168.1.10/Movies", "\\\\PC\\Share")
     * - For WEBDAV: base URL (e.g. "http://192.168.1.10:5244/dav")
     * - For QUARK: "https://drive-pc.quark.cn" (or custom root fid)
     */
    val address: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** Used by Quark Cloud Drive direct auth cookie */
    val cookie: String = "",
    val addedAtMs: Long = System.currentTimeMillis()
)
