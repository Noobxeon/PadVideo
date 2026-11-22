package com.padvideo.data.repository

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.VideoItem
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class SmbRepository {

    class Credentials(user: String, password: String, domain: String) {
        private val account = user.trim().split('\\', limit = 2)
        val username: String = account.last().trim()
        private val qualifiedDomain: String? = account.first().trim().takeIf { account.size == 2 }
        private val enteredDomain: String = domain.trim()
        val isGuest: Boolean = username.isEmpty()

        init {
            require(
                qualifiedDomain == null ||
                    (qualifiedDomain.isNotEmpty() && username.isNotEmpty() && '\\' !in username)
            ) { "Windows 账号格式应为 电脑名\\用户名 或 域\\用户名" }
            require(
                qualifiedDomain == null || enteredDomain.isEmpty() ||
                    enteredDomain.equals(qualifiedDomain, ignoreCase = true)
            ) { "账号前缀和单独填写的域不一致" }
            require(!isGuest || (enteredDomain.isEmpty() && password.isEmpty())) {
                "填写密码或域时，请同时填写 SMB 账号"
            }
        }

        val resolvedDomain: String = qualifiedDomain ?: enteredDomain
        val context: AuthenticationContext = if (isGuest) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.toCharArray(), resolvedDomain)
        }

        val fallbackGuestContext: AuthenticationContext? =
            if (isGuest) AuthenticationContext.anonymous() else null
    }

    data class Entry(
        val name: String,
        val location: SmbLocation,
        val isFolder: Boolean,
        val size: Long,
        val isSubtitle: Boolean = false,
        val sidecarSubtitles: List<String> = emptyList()
    ) {
        fun asVideo() = VideoItem(
            id = location.uri,
            title = name,
            uriString = location.uri,
            folderName = location.segments.dropLast(1).lastOrNull() ?: location.share ?: location.host,
            folderPath = location.parent().uri,
            sizeBytes = size,
            sidecarSubtitlePaths = sidecarSubtitles
        )
    }

    private val sessions = ConcurrentHashMap<SmbLocation, Credentials>()

    /**
     * Never force signing on client side (`withSigningRequired(false)`):
     * SMBJ still negotiates signing automatically when a server mandates it,
     * while remaining compatible with Windows 10/11 Home/Pro, Synology, QNAP, fnOS, UGREEN, and OpenWrt Samba!
     */
    private val defaultConfig by lazy {
        SmbConfig.builder()
            .withSigningRequired(false)
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            .withTimeout(12, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun newClient() = SMBClient(defaultConfig)

    fun registerSavedMounts(mounts: List<MountedStorageSource>) {
        mounts.filter { it.type == StorageMountType.SMB }.forEach { mount ->
            runCatching {
                val loc = SmbLocation.parse(mount.address)
                val creds = Credentials(mount.username, mount.password, mount.domain)
                sessions[loc.shareRoot()] = creds
                sessions[loc.serverRoot()] = creds
            }
        }
    }

    private fun resolveCredentials(location: SmbLocation, explicit: Credentials?): Credentials? {
        return explicit ?: sessions[location.shareRoot()] ?: sessions[location.serverRoot()]
    }

    suspend fun list(location: SmbLocation, credentials: Credentials? = null): List<Entry> =
        withContext(Dispatchers.IO) {
            val auth = resolveCredentials(location, credentials)
                ?: throw IOException("请重新连接 SMB 共享")

            val entries = withSession(location, auth) { session ->
                val shareName = location.share
                if (shareName == null) {
                    operation(SmbConnectionStep.LIST_SHARES, auth, location) {
                        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                        ServerService(transport).getShares1()
                            .filter { it.type == 0 && !it.netName.endsWith('$') && SmbLocation.isPathSegment(it.netName) }
                            .map { Entry(it.netName, location.child(it.netName), isFolder = true, size = 0L) }
                    }
                } else {
                    operation(SmbConnectionStep.SHARE, auth, location) {
                        session.connectShare(shareName) as? DiskShare
                            ?: throw IOException("SMB share is not a disk share")
                    }.use { share ->
                        operation(SmbConnectionStep.BROWSE, auth, location) {
                            val rawList = share.list(location.path)
                            val subtitleNames = rawList.mapNotNull { info ->
                                val name = info.fileName
                                val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                                if (!isDir && SmbLocation.isPathSegment(name) && FileExplorerRepository.isSubtitleFileName(name)) {
                                    name
                                } else {
                                    null
                                }
                            }
                            rawList.mapNotNull { info ->
                                val name = info.fileName
                                if (!SmbLocation.isPathSegment(name)) return@mapNotNull null
                                val isFolder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                                val isVid = !isFolder && FileExplorerRepository.isVideoFileName(name)
                                val isSub = !isFolder && FileExplorerRepository.isSubtitleFileName(name)
                                if (!isFolder && !isVid && !isSub) return@mapNotNull null
                                val sidecars = if (isVid) {
                                    val base = name.substringBeforeLast('.').lowercase()
                                    subtitleNames.filter { sub ->
                                        val stem = sub.substringBeforeLast('.').lowercase()
                                        stem == base || stem.startsWith("$base.") || stem.startsWith("${base}_")
                                    }.sortedBy { sub ->
                                        val lower = sub.lowercase()
                                        when {
                                            ".bilingual." in lower -> 0
                                            ".zh." in lower || ".chs." in lower -> 1
                                            ".asr." in lower -> 2
                                            else -> 3
                                        }
                                    }.map { location.child(it).uri }
                                } else {
                                    emptyList()
                                }
                                Entry(
                                    name = name,
                                    location = location.child(name),
                                    isFolder = isFolder,
                                    size = info.endOfFile,
                                    isSubtitle = isSub,
                                    sidecarSubtitles = sidecars
                                )
                            }
                        }
                    }
                }
            }
            sessions[location.shareRoot()] = auth
            sessions[location.serverRoot()] = auth
            entries.sortedWith(
                compareBy<Entry> { !it.isFolder }.thenComparator { a, b ->
                    FileExplorerRepository.compareNaturalStrings(a.name, b.name)
                }
            )
        }

    private fun <T> withSession(location: SmbLocation, auth: Credentials, action: (Session) -> T): T =
        newClient().use { client ->
            operation(SmbConnectionStep.CONNECT, auth, location) {
                client.connect(location.host.removeSurrounding("[", "]"), location.port)
            }.use { connection ->
                val session = authenticateWithFallback(connection, auth, location)
                session.use(action)
            }
        }

    private fun authenticateWithFallback(
        connection: com.hierynomus.smbj.connection.Connection,
        auth: Credentials,
        location: SmbLocation
    ): Session {
        return try {
            operation(SmbConnectionStep.AUTHENTICATE, auth, location) {
                connection.authenticate(auth.context)
            }
        } catch (primaryError: SmbConnectionFailure) {
            val fallback = auth.fallbackGuestContext ?: throw primaryError
            operation(SmbConnectionStep.AUTHENTICATE, auth, location) {
                connection.authenticate(fallback)
            }
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
    } catch (error: SmbConnectionFailure) {
        throw error
    } catch (error: Exception) {
        throw SmbConnectionFailure(step, auth.isGuest, location.port, error)
    }

    fun open(location: SmbLocation): OpenFile {
        val shareName = location.share ?: throw IOException("请选择 SMB 共享中的视频")
        val credentials = resolveCredentials(location, null)
            ?: throw IOException("请在文件页重新连接 SMB 共享")
        val resources = mutableListOf<AutoCloseable>()
        try {
            val client = newClient().also { resources.add(it) }
            val connection = client.connect(location.host.removeSurrounding("[", "]"), location.port)
                .also { resources.add(it) }
            val session = authenticateWithFallback(connection, credentials, location)
                .also { resources.add(it) }
            val share = (session.connectShare(shareName) as DiskShare).also { resources.add(it) }
            val file = share.openFile(
                location.path,
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).also { resources.add(it) }
            return OpenFile(file, file.fileInformation.standardInformation.endOfFile, resources)
        } catch (error: Exception) {
            resources.asReversed().forEach { runCatching { it.close() } }
            throw IOException("无法打开 SMB 视频，请检查共享连接和读取权限", error)
        }
    }

    suspend fun readSmallFileBytes(location: SmbLocation, maxBytes: Int = 4 * 1024 * 1024): ByteArray =
        withContext(Dispatchers.IO) {
            open(location).use { opened ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var offset = 0L
                val limit = minOf(opened.length, maxBytes.toLong())
                while (offset < limit) {
                    val toRead = minOf(buf.size.toLong(), limit - offset).toInt()
                    val count = opened.file.read(buf, offset, 0, toRead)
                    if (count <= 0) break
                    out.write(buf, 0, count)
                    offset += count
                }
                out.toByteArray()
            }
        }

    /**
     * Writes a small file (such as an auto-generated .srt subtitle) directly into the target SMB share directory.
     */
    suspend fun writeSmallFileBytes(location: SmbLocation, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val shareName = location.share ?: throw IOException("无效的 SMB 共享路径")
            val auth = resolveCredentials(location, null)
                ?: throw IOException("未找到 SMB 认证信息，请重新连接共享")
            withSession(location, auth) { session ->
                val share = (session.connectShare(shareName) as? DiskShare)
                    ?: throw IOException("目标 SMB 共享不是磁盘共享")
                share.use { diskShare ->
                    diskShare.openFile(
                        location.path,
                        setOf(AccessMask.GENERIC_WRITE),
                        setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    ).use { smbFile ->
                        smbFile.outputStream.use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            }
        }

    /**
     * Scans the device's active local IPv4 /24 subnet for hosts listening on SMB port 445.
     */
    suspend fun scanLanSmbHosts(timeoutMs: Int = 280): List<String> = withContext(Dispatchers.IO) {
        val localIp = findLocalIpv4Address() ?: return@withContext emptyList()
        val prefix = localIp.substringBeforeLast('.') + "."
        val myLastOctet = localIp.substringAfterLast('.').toIntOrNull() ?: -1

        coroutineScope {
            (1..254)
                .filter { it != myLastOctet }
                .chunked(32)
                .flatMap { batch ->
                    batch.map { hostIdx ->
                        async {
                            val ip = "$prefix$hostIdx"
                            val reachable = runCatching {
                                Socket().use { sock ->
                                    sock.connect(InetSocketAddress(ip, 445), timeoutMs)
                                }
                                true
                            }.getOrDefault(false)
                            if (reachable) ip else null
                        }
                    }.awaitAll().filterNotNull()
                }
        }
    }

    private fun findLocalIpv4Address(): String? {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }

    class OpenFile(val file: File, val length: Long, private val resources: List<AutoCloseable>) : Closeable {
        override fun close() {
            resources.asReversed().forEach { runCatching { it.close() } }
        }
    }
}
