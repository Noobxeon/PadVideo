package com.padvideo.data.repository

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.padvideo.data.model.VideoItem
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SmbRepository {
    class Credentials(user: String, password: String, domain: String) {
        val isGuest = user.isBlank()
        val context = if (isGuest) AuthenticationContext.anonymous()
        else AuthenticationContext(user.trim(), password.toCharArray(), domain.trim())
    }

    data class Entry(val name: String, val location: SmbLocation, val isFolder: Boolean, val size: Long) {
        fun asVideo() = VideoItem(
            id = location.uri,
            title = name,
            uriString = location.uri,
            folderName = location.segments.dropLast(1).lastOrNull() ?: location.share,
            folderPath = location.parent().uri,
            sizeBytes = size
        )
    }

    private val sessions = ConcurrentHashMap<SmbLocation, Credentials>()
    private val authenticatedConfig by lazy { buildConfig(requireSigning = true) }
    private val guestConfig by lazy { buildConfig(requireSigning = false) }

    private fun buildConfig(requireSigning: Boolean) = SmbConfig.builder()
        .withSigningRequired(requireSigning)
        .withTimeout(12, TimeUnit.SECONDS)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun client(auth: Credentials) = SMBClient(if (auth.isGuest) guestConfig else authenticatedConfig)

    suspend fun list(location: SmbLocation, credentials: Credentials? = null): List<Entry> =
        withContext(Dispatchers.IO) {
            val root = location.copy(segments = emptyList())
            val auth = credentials ?: sessions[root] ?: throw IOException("请重新连接 SMB 共享")
            val entries = withShare(location, auth) { share ->
                share.list(location.path).mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == "..") return@mapNotNull null
                    val isFolder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    if (!isFolder && !FileExplorerRepository.isVideoFileName(name)) {
                        return@mapNotNull null
                    }
                    Entry(name, location.child(name), isFolder, info.endOfFile)
                }.sortedWith(compareBy<Entry> { !it.isFolder }.thenComparator { a, b ->
                    FileExplorerRepository.compareNaturalStrings(a.name, b.name)
                })
            }
            sessions[root] = auth
            entries
        }

    private fun <T> withShare(location: SmbLocation, auth: Credentials, action: (DiskShare) -> T): T =
        client(auth).use { client ->
            client.connect(location.host.removeSurrounding("[", "]"), location.port).use { connection ->
                connection.authenticate(auth.context).use { session ->
                    (session.connectShare(location.share) as DiskShare).use(action)
                }
            }
        }

    fun open(location: SmbLocation): OpenFile {
        val credentials = sessions[location.copy(segments = emptyList())]
            ?: throw IOException("请在文件页重新连接 SMB 共享")
        val resources = mutableListOf<AutoCloseable>()
        try {
            val client = client(credentials).also { resources.add(it) }
            val connection = client.connect(location.host.removeSurrounding("[", "]"), location.port)
                .also { resources.add(it) }
            val session = connection.authenticate(credentials.context).also { resources.add(it) }
            val share = (session.connectShare(location.share) as DiskShare).also { resources.add(it) }
            val file = share.openFile(
                location.path, setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null
            ).also { resources.add(it) }
            return OpenFile(file, file.fileInformation.standardInformation.endOfFile, resources)
        } catch (error: Exception) {
            resources.asReversed().forEach { runCatching { it.close() } }
            throw IOException("无法打开 SMB 视频，请检查共享连接和读取权限", error)
        }
    }

    class OpenFile(val file: File, val length: Long, private val resources: List<AutoCloseable>) : Closeable {
        override fun close() {
            resources.asReversed().forEach { runCatching { it.close() } }
        }
    }
}
