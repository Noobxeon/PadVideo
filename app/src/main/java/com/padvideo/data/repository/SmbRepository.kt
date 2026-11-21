package com.padvideo.data.repository

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.padvideo.data.model.VideoItem
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SmbRepository {
    class Credentials(user: String, password: String, domain: String) {
        private val account = user.trim().split('\\', limit = 2)
        private val username = account.last().trim()
        private val qualifiedDomain = account.first().trim().takeIf { account.size == 2 }
        private val enteredDomain = domain.trim()
        val isGuest = username.isEmpty()

        init {
            require(qualifiedDomain == null ||
                (qualifiedDomain.isNotEmpty() && username.isNotEmpty() && '\\' !in username)
            ) { "Windows 账号格式应为 电脑名\\用户名 或 域\\用户名" }
            require(qualifiedDomain == null || enteredDomain.isEmpty() ||
                enteredDomain.equals(qualifiedDomain, ignoreCase = true)
            ) { "账号前缀和单独填写的域不一致" }
            require(!isGuest || (enteredDomain.isEmpty() && password.isEmpty())) {
                "填写密码或域时，请同时填写 SMB 账号"
            }
        }

        val context = if (isGuest) AuthenticationContext.anonymous()
        else AuthenticationContext(username, password.toCharArray(), qualifiedDomain ?: enteredDomain)
    }

    data class Entry(val name: String, val location: SmbLocation, val isFolder: Boolean, val size: Long) {
        fun asVideo() = VideoItem(
            id = location.uri,
            title = name,
            uriString = location.uri,
            folderName = location.segments.dropLast(1).lastOrNull() ?: checkNotNull(location.share),
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
            val root = location.shareRoot()
            val auth = credentials ?: sessions[root] ?: sessions[location.serverRoot()]
                ?: throw IOException("请重新连接 SMB 共享")
            val entries = withSession(location, auth) { session ->
                val shareName = location.share
                if (shareName == null) {
                    operation(SmbConnectionStep.LIST_SHARES, auth, location) {
                        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                        ServerService(transport).getShares1()
                            .filter { it.type == 0 && !it.netName.endsWith('$') && SmbLocation.isPathSegment(it.netName) }
                            .map { Entry(it.netName, location.child(it.netName), true, 0L) }
                    }
                } else {
                    operation(SmbConnectionStep.SHARE, auth, location) {
                        session.connectShare(shareName) as? DiskShare
                            ?: throw IOException("SMB share is not a disk share")
                    }.use { share ->
                        operation(SmbConnectionStep.BROWSE, auth, location) {
                            share.list(location.path).mapNotNull { info ->
                                val name = info.fileName
                                if (!SmbLocation.isPathSegment(name)) return@mapNotNull null
                                val isFolder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                                if (!isFolder && !FileExplorerRepository.isVideoFileName(name)) return@mapNotNull null
                                Entry(name, location.child(name), isFolder, info.endOfFile)
                            }
                        }
                    }
                }
            }
            sessions[root] = auth
            entries.sortedWith(compareBy<Entry> { !it.isFolder }.thenComparator { a, b ->
                FileExplorerRepository.compareNaturalStrings(a.name, b.name)
            })
        }

    private fun <T> withSession(location: SmbLocation, auth: Credentials, action: (Session) -> T): T =
        client(auth).use { client ->
            operation(SmbConnectionStep.CONNECT, auth, location) {
                client.connect(location.host.removeSurrounding("[", "]"), location.port)
            }.use { connection ->
                operation(SmbConnectionStep.AUTHENTICATE, auth, location) {
                    connection.authenticate(auth.context)
                }.use(action)
            }
        }

    private inline fun <T> operation(
        step: SmbConnectionStep,
        auth: Credentials,
        location: SmbLocation,
        action: () -> T
    ): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw SmbConnectionFailure(step, auth.isGuest, location.port, error)
    }

    fun open(location: SmbLocation): OpenFile {
        val shareName = location.share ?: throw IOException("请选择 SMB 共享中的视频")
        val credentials = sessions[location.shareRoot()]
            ?: throw IOException("请在文件页重新连接 SMB 共享")
        val resources = mutableListOf<AutoCloseable>()
        try {
            val client = client(credentials).also { resources.add(it) }
            val connection = client.connect(location.host.removeSurrounding("[", "]"), location.port)
                .also { resources.add(it) }
            val session = connection.authenticate(credentials.context).also { resources.add(it) }
            val share = (session.connectShare(shareName) as DiskShare).also { resources.add(it) }
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
