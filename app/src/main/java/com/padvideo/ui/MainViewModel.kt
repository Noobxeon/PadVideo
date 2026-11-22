package com.padvideo.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.padvideo.AppContainer
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.BatchSubtitleJobConfig
import com.padvideo.data.model.BatchSubtitleTarget
import com.padvideo.data.model.BatchSubtitleTaskProgress
import com.padvideo.data.model.BreadcrumbSegment
import com.padvideo.data.model.FavoriteFolder
import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.SafRootBookmark
import com.padvideo.data.model.SortField
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.StreamPreset
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.ViewLayoutMode
import com.padvideo.data.repository.SmbLocation
import com.padvideo.data.repository.SmbRepository
import com.padvideo.player.AsrModelDownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class MainNavTab(val label: String) {
    LIBRARY("媒体库"),
    FILES("文件浏览"),
    HISTORY_STREAM("历史与串流"),
    SETTINGS("设置")
}

enum class ExplorerBackend {
    LOCAL,
    SAF,
    SMB,
    WEBDAV,
    QUARK
}

data class ActivePlaybackLaunch(
    val video: VideoItem,
    val playlist: List<VideoItem> = listOf(video),
    val inlineInitialSrt: String? = null,
    val forceStartFromBeginning: Boolean = false
)

class MainViewModel(
    private val container: AppContainer
) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val history: StateFlow<List<PlaybackHistoryItem>> = container.settingsRepository.history
    val favorites: StateFlow<List<FavoriteFolder>> = container.settingsRepository.favorites
    val safRoots: StateFlow<List<SafRootBookmark>> = container.settingsRepository.safRoots
    val mountedSources: StateFlow<List<MountedStorageSource>> = container.settingsRepository.mountedSources
    val batchSubtitleProgress: StateFlow<BatchSubtitleTaskProgress> = container.batchSubtitleGenerator.progress
    val asrDownloadProgress: StateFlow<AsrModelDownloadProgress?> = container.videoSpeechRecognizer.downloadProgress
    private val _readyAsrEngines = MutableStateFlow(computeReadyAsrEngines())
    val readyAsrEngines: StateFlow<Set<AsrEngineType>> = _readyAsrEngines.asStateFlow()

    private val _selectedTab = MutableStateFlow(MainNavTab.LIBRARY)
    val selectedTab: StateFlow<MainNavTab> = _selectedTab.asStateFlow()

    // Media Library State
    private val _rawLibraryVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    private val _isScanningLibrary = MutableStateFlow(false)
    val isScanningLibrary: StateFlow<Boolean> = _isScanningLibrary.asStateFlow()

    private val _librarySearchQuery = MutableStateFlow("")
    val librarySearchQuery: StateFlow<String> = _librarySearchQuery.asStateFlow()

    private val _selectedLibraryFolder = MutableStateFlow<String?>(null)
    val selectedLibraryFolder: StateFlow<String?> = _selectedLibraryFolder.asStateFlow()

    private val _selectedInspectorVideo = MutableStateFlow<VideoItem?>(null)
    val selectedInspectorVideo: StateFlow<VideoItem?> = _selectedInspectorVideo.asStateFlow()

    val libraryFolders: StateFlow<List<String>> = combine(_rawLibraryVideos) { (videos) ->
        videos.map { it.folderName }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredLibraryVideos: StateFlow<List<VideoItem>> = combine(
        _rawLibraryVideos,
        _librarySearchQuery,
        _selectedLibraryFolder,
        settings
    ) { videos, query, folder, appSettings ->
        val filtered = videos.filter { item ->
            (folder == null || item.folderName == folder) &&
                (query.isBlank() || item.title.contains(query.trim(), ignoreCase = true) ||
                    item.folderName.contains(query.trim(), ignoreCase = true))
        }
        container.fileExplorerRepository.sortVideos(
            filtered,
            appSettings.sortField,
            appSettings.sortAscending
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Unified File Explorer State (Local + SAF + SMB + WebDAV + Quark all in ONE tab)
    private val _explorerBackend = MutableStateFlow(ExplorerBackend.LOCAL)
    val explorerBackend: StateFlow<ExplorerBackend> = _explorerBackend.asStateFlow()

    private val _currentDir = MutableStateFlow(container.fileExplorerRepository.defaultStorageRoot)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _activeSafTreeUri = MutableStateFlow<String?>(null)
    val activeSafTreeUri: StateFlow<String?> = _activeSafTreeUri.asStateFlow()

    private val _activeMountId = MutableStateFlow<String?>(null)
    val activeMountId: StateFlow<String?> = _activeMountId.asStateFlow()

    private val _fileEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileEntries: StateFlow<List<FileEntry>> = _fileEntries.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbSegment>>(emptyList())
    val breadcrumbs: StateFlow<List<BreadcrumbSegment>> = _breadcrumbs.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    private val _isExplorerLoading = MutableStateFlow(false)
    val isExplorerLoading: StateFlow<Boolean> = _isExplorerLoading.asStateFlow()

    private val _explorerError = MutableStateFlow<String?>(null)
    val explorerError: StateFlow<String?> = _explorerError.asStateFlow()

    // LAN SMB Auto-Discovery State
    private val _discoveredLanHosts = MutableStateFlow<List<String>>(emptyList())
    val discoveredLanHosts: StateFlow<List<String>> = _discoveredLanHosts.asStateFlow()

    private val _isScanningLan = MutableStateFlow(false)
    val isScanningLan: StateFlow<Boolean> = _isScanningLan.asStateFlow()

    // Multi-level navigation stacks for non-File backends
    private var safDirStack: MutableList<DocumentFile> = mutableListOf()
    private var safCurrentChildrenByUri: Map<String, DocumentFile> = emptyMap()
    private var activeSmbMount: MountedStorageSource? = null
    private var activeSmbRootLocation: SmbLocation? = null
    private var currentSmbLocation: SmbLocation? = null
    private var activeWebDavMount: MountedStorageSource? = null
    private var webDavStack: MutableList<Pair<String, String>> = mutableListOf() // (label, url)
    private var activeQuarkMount: MountedStorageSource? = null
    private var quarkStack: MutableList<Pair<String, String>> = mutableListOf() // (label, fid)

    private var explorerLoadJob: Job? = null

    // Active Video Player Session
    private val _activeLaunch = MutableStateFlow<ActivePlaybackLaunch?>(null)
    val activeLaunch: StateFlow<ActivePlaybackLaunch?> = _activeLaunch.asStateFlow()

    val builtInStreamPresets: List<StreamPreset> = listOf(
        StreamPreset(
            title = "Sintel (开源奇幻动画短片 · 含英文字幕演示实时翻译)",
            subtitle = "Blender Foundation 出品 · 适合一键体验双语实时翻译、A-B 复读与手势操控",
            streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            sampleEnglishSrt = SAMPLE_SINTEL_SRT,
            badge = "HD · 双语同传演示"
        ),
        StreamPreset(
            title = "Tears of Steel (开源科幻真人电影短片)",
            subtitle = "Blender Institute 实景特效短片 · 适合测试平板影院色彩与在线字幕搜索",
            streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
            sampleEnglishSrt = SAMPLE_TEARS_SRT,
            badge = "1080P · 科幻实景"
        ),
        StreamPreset(
            title = "Big Buck Bunny (经典开源测试动画)",
            subtitle = "高帧率动画测试源 · 支持画中画小窗、200% 音量增益与自由缩放",
            streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            sampleEnglishSrt = null,
            badge = "HD · 流畅度测试"
        )
    )

    init {
        container.smbRepository.registerSavedMounts(mountedSources.value)
        refreshMediaLibrary()
        refreshFileExplorer()
    }

    fun selectTab(tab: MainNavTab) {
        _selectedTab.value = tab
    }

    fun getQuickAccessDirs(): List<Pair<String, File>> {
        return container.fileExplorerRepository.getQuickAccessDirectories()
    }

    fun refreshMediaLibrary() {
        viewModelScope.launch {
            _isScanningLibrary.value = true
            val historyMap = history.value.associateBy { it.uriString }
            val scanned = container.fileExplorerRepository.scanMediaLibrary(historyMap)
            _rawLibraryVideos.value = scanned
            if (_selectedInspectorVideo.value == null && scanned.isNotEmpty()) {
                _selectedInspectorVideo.value = scanned.first()
            }
            _isScanningLibrary.value = false
        }
    }

    fun setLibrarySearchQuery(query: String) {
        _librarySearchQuery.value = query
    }

    fun selectLibraryFolder(folderName: String?) {
        _selectedLibraryFolder.value = folderName
    }

    fun inspectVideo(video: VideoItem?) {
        _selectedInspectorVideo.value = video
    }

    // --- Unified Mount Management (SMB / WebDAV / Quark / SAF) ---
    fun scanLanSmbHosts() {
        if (_isScanningLan.value) return
        viewModelScope.launch {
            _isScanningLan.value = true
            _discoveredLanHosts.value = container.smbRepository.scanLanSmbHosts()
            _isScanningLan.value = false
        }
    }

    fun saveAndOpenMount(source: MountedStorageSource): String? {
        // Validate input cleanly before saving
        val validated = try {
            when (source.type) {
                StorageMountType.SMB -> {
                    val loc = SmbLocation.parse(source.address)
                    SmbRepository.Credentials(source.username, source.password, source.domain)
                    source.copy(
                        name = source.name.trim().ifBlank { loc.share ?: loc.host },
                        address = loc.uri
                    )
                }
                StorageMountType.WEBDAV -> {
                    val normUrl = container.webDavRepository.normalizeRootUrl(source.address)
                    val hostLabel = runCatching { java.net.URI(normUrl).host }.getOrNull() ?: "WebDAV"
                    source.copy(
                        name = source.name.trim().ifBlank { hostLabel },
                        address = normUrl
                    )
                }
                StorageMountType.QUARK -> {
                    require(source.cookie.isNotBlank()) { "请先通过网页登录夸克网盘，或粘贴有效的 Cookie" }
                    source.copy(
                        name = source.name.trim().ifBlank { "夸克网盘" },
                        address = source.address.ifBlank { "0" }
                    )
                }
            }
        } catch (err: IllegalArgumentException) {
            return err.message ?: "输入参数无效"
        }

        container.settingsRepository.saveMountedSource(validated)
        if (validated.type == StorageMountType.SMB) {
            container.smbRepository.registerSavedMounts(mountedSources.value)
        }
        _selectedTab.value = MainNavTab.FILES
        openMountedSource(validated)
        return null
    }

    fun removeMountedSource(sourceId: String) {
        container.settingsRepository.removeMountedSource(sourceId)
        if (_activeMountId.value == sourceId) {
            openDirectory(container.fileExplorerRepository.defaultStorageRoot)
        }
    }

    fun removeSafRoot(treeUriString: String) {
        container.settingsRepository.removeSafRoot(treeUriString)
        if (_activeSafTreeUri.value == treeUriString) {
            openDirectory(container.fileExplorerRepository.defaultStorageRoot)
        }
    }

    fun openMountedSource(source: MountedStorageSource) {
        _fileSearchQuery.value = ""
        _explorerError.value = null
        _activeSafTreeUri.value = null
        safDirStack.clear()
        safCurrentChildrenByUri = emptyMap()
        _activeMountId.value = source.id

        when (source.type) {
            StorageMountType.SMB -> {
                _explorerBackend.value = ExplorerBackend.SMB
                activeSmbMount = source
                val parsed = runCatching { SmbLocation.parse(source.address) }.getOrElse { err ->
                    _explorerError.value = err.message ?: "SMB 地址无效"
                    return
                }
                activeSmbRootLocation = parsed
                currentSmbLocation = parsed
                loadCurrentSmbDirectory()
            }
            StorageMountType.WEBDAV -> {
                _explorerBackend.value = ExplorerBackend.WEBDAV
                activeWebDavMount = source
                val rootUrl = runCatching { container.webDavRepository.normalizeRootUrl(source.address) }.getOrElse { err ->
                    _explorerError.value = err.message ?: "WebDAV 地址无效"
                    return
                }
                webDavStack = mutableListOf(source.name to rootUrl)
                loadCurrentWebDavDirectory()
            }
            StorageMountType.QUARK -> {
                _explorerBackend.value = ExplorerBackend.QUARK
                activeQuarkMount = source
                val rootFid = source.address.ifBlank { "0" }
                quarkStack = mutableListOf(source.name to rootFid)
                loadCurrentQuarkDirectory()
            }
        }
    }

    // --- Unified Local / SAF / Remote Navigation ---
    fun openDirectory(dir: File) {
        explorerLoadJob?.cancel()
        _explorerBackend.value = ExplorerBackend.LOCAL
        _activeSafTreeUri.value = null
        _activeMountId.value = null
        _explorerError.value = null
        safDirStack.clear()
        safCurrentChildrenByUri = emptyMap()
        _currentDir.value = dir
        refreshFileExplorer()
    }

    fun openSafRootBookmark(context: Context, bookmark: SafRootBookmark) {
        explorerLoadJob?.cancel()
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(bookmark.treeUriString))
        if (rootDoc == null || !rootDoc.canRead()) {
            _explorerError.value = "无法读取该授权目录，权限可能已失效，请重新挂载"
            return
        }
        _explorerBackend.value = ExplorerBackend.SAF
        _activeSafTreeUri.value = bookmark.treeUriString
        _activeMountId.value = null
        _explorerError.value = null
        safDirStack = mutableListOf(rootDoc)
        loadCurrentSafDirectory()
    }

    fun registerSafTree(context: Context, treeUri: Uri) {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val label = doc?.name?.ifBlank { "外接存储目录" } ?: "外接存储目录"
        val bookmark = SafRootBookmark(name = label, treeUriString = treeUri.toString())
        container.settingsRepository.addSafRoot(bookmark)
        _selectedTab.value = MainNavTab.FILES
        openSafRootBookmark(context, bookmark)
    }

    /**
     * Handles clicking ANY directory or video entry uniformly across Local, SAF, SMB, WebDAV, and Quark!
     */
    fun openFileEntry(entry: FileEntry) {
        if (!entry.isDirectory) {
            val video = entry.videoItem ?: return
            playVideo(video)
            return
        }

        _fileSearchQuery.value = ""
        _explorerError.value = null

        when (_explorerBackend.value) {
            ExplorerBackend.LOCAL -> {
                openDirectory(File(entry.path))
            }
            ExplorerBackend.SAF -> {
                // Use the exact DocumentFile instance returned by listFiles() so deep nested subfolders ALWAYS work!
                val childDoc = safCurrentChildrenByUri[entry.uriString]
                    ?: safDirStack.lastOrNull()?.findFile(entry.name)
                if (childDoc != null && childDoc.isDirectory) {
                    safDirStack.add(childDoc)
                    loadCurrentSafDirectory()
                } else {
                    _explorerError.value = "无法打开子目录：${entry.name}"
                }
            }
            ExplorerBackend.SMB -> {
                val nextLoc = runCatching { SmbLocation.parse(entry.path) }.getOrNull()
                    ?: currentSmbLocation?.child(entry.name)
                if (nextLoc != null) {
                    currentSmbLocation = nextLoc
                    loadCurrentSmbDirectory()
                }
            }
            ExplorerBackend.WEBDAV -> {
                webDavStack.add(entry.name to entry.path)
                loadCurrentWebDavDirectory()
            }
            ExplorerBackend.QUARK -> {
                val fid = entry.remoteId ?: entry.path.substringAfterLast('/')
                if (fid.isNotBlank()) {
                    quarkStack.add(entry.name to fid)
                    loadCurrentQuarkDirectory()
                }
            }
        }
    }

    fun openBreadcrumb(segment: BreadcrumbSegment) {
        _explorerError.value = null
        when (_explorerBackend.value) {
            ExplorerBackend.LOCAL -> {
                openDirectory(File(segment.path))
            }
            ExplorerBackend.SAF -> {
                val targetIdx = segment.stackIndex
                if (targetIdx in safDirStack.indices) {
                    safDirStack = safDirStack.take(targetIdx + 1).toMutableList()
                    loadCurrentSafDirectory()
                }
            }
            ExplorerBackend.SMB -> {
                val loc = runCatching { SmbLocation.parse(segment.path) }.getOrNull() ?: return
                currentSmbLocation = loc
                loadCurrentSmbDirectory()
            }
            ExplorerBackend.WEBDAV -> {
                val targetIdx = segment.stackIndex
                if (targetIdx in webDavStack.indices) {
                    webDavStack = webDavStack.take(targetIdx + 1).toMutableList()
                    loadCurrentWebDavDirectory()
                }
            }
            ExplorerBackend.QUARK -> {
                val targetIdx = segment.stackIndex
                if (targetIdx in quarkStack.indices) {
                    quarkStack = quarkStack.take(targetIdx + 1).toMutableList()
                    loadCurrentQuarkDirectory()
                }
            }
        }
    }

    fun navigateUpInExplorer(): Boolean {
        _explorerError.value = null
        return when (_explorerBackend.value) {
            ExplorerBackend.LOCAL -> {
                val cur = _currentDir.value
                val root = container.fileExplorerRepository.defaultStorageRoot
                val parent = cur.parentFile
                if (parent != null && cur.absolutePath != root.absolutePath && parent.canRead()) {
                    _currentDir.value = parent
                    refreshFileExplorer()
                    true
                } else {
                    false
                }
            }
            ExplorerBackend.SAF -> {
                if (safDirStack.size > 1) {
                    safDirStack.removeAt(safDirStack.lastIndex)
                    loadCurrentSafDirectory()
                    true
                } else {
                    openDirectory(container.fileExplorerRepository.defaultStorageRoot)
                    true
                }
            }
            ExplorerBackend.SMB -> {
                val cur = currentSmbLocation ?: return false
                val root = activeSmbRootLocation
                if (cur == root || (cur.isServer && cur.segments.isEmpty())) {
                    false
                } else {
                    currentSmbLocation = cur.parent()
                    loadCurrentSmbDirectory()
                    true
                }
            }
            ExplorerBackend.WEBDAV -> {
                if (webDavStack.size > 1) {
                    webDavStack.removeAt(webDavStack.lastIndex)
                    loadCurrentWebDavDirectory()
                    true
                } else {
                    false
                }
            }
            ExplorerBackend.QUARK -> {
                if (quarkStack.size > 1) {
                    quarkStack.removeAt(quarkStack.lastIndex)
                    loadCurrentQuarkDirectory()
                    true
                } else {
                    false
                }
            }
        }
    }

    fun setFileSearchQuery(query: String) {
        _fileSearchQuery.value = query
        refreshFileExplorer()
    }

    fun refreshFileExplorer(contextForSaf: Context? = null) {
        when (_explorerBackend.value) {
            ExplorerBackend.LOCAL -> {
                val s = settings.value
                val historyMap = history.value.associateBy { it.uriString }
                val dir = _currentDir.value
                _isExplorerLoading.value = false
                _explorerError.value = null
                _breadcrumbs.value = container.fileExplorerRepository.buildBreadcrumbs(dir)
                _fileEntries.value = enrichWithIndexedSubtitles(
                    container.fileExplorerRepository.listDirectory(
                        directory = dir,
                        showHidden = s.showHiddenFiles,
                        searchQuery = _fileSearchQuery.value,
                        sortField = s.sortField,
                        sortAscending = s.sortAscending,
                        historyByUri = historyMap
                    )
                )
            }
            ExplorerBackend.SAF -> {
                if (safDirStack.isEmpty() && contextForSaf != null && _activeSafTreeUri.value != null) {
                    DocumentFile.fromTreeUri(contextForSaf, Uri.parse(_activeSafTreeUri.value!!))?.let {
                        safDirStack.add(it)
                    }
                }
                loadCurrentSafDirectory()
            }
            ExplorerBackend.SMB -> loadCurrentSmbDirectory()
            ExplorerBackend.WEBDAV -> loadCurrentWebDavDirectory()
            ExplorerBackend.QUARK -> loadCurrentQuarkDirectory()
        }
    }

    private fun loadCurrentSafDirectory() {
        val currentDoc = safDirStack.lastOrNull() ?: return
        val s = settings.value
        val historyMap = history.value.associateBy { it.uriString }
        val query = _fileSearchQuery.value

        explorerLoadJob?.cancel()
        explorerLoadJob = viewModelScope.launch {
            _isExplorerLoading.value = true
            _explorerError.value = null
            _breadcrumbs.value = safDirStack.mapIndexed { idx, doc ->
                BreadcrumbSegment(
                    label = doc.name?.ifBlank { "挂载目录" } ?: "挂载目录",
                    path = doc.uri.toString(),
                    isSafUri = true,
                    stackIndex = idx
                )
            }
            runCatching {
                // Cache child DocumentFile references so clicking nested folders works cleanly on every Android version
                val rawChildren = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    currentDoc.listFiles()
                }
                safCurrentChildrenByUri = rawChildren.associateBy { it.uri.toString() }
                container.fileExplorerRepository.listSafDirectory(
                    treeOrDirDoc = currentDoc,
                    showHidden = s.showHiddenFiles,
                    searchQuery = query,
                    sortField = s.sortField,
                    sortAscending = s.sortAscending,
                    historyByUri = historyMap
                )
            }.onSuccess { entries ->
                _fileEntries.value = enrichWithIndexedSubtitles(entries)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _explorerError.value = "读取挂载目录失败：${err.message ?: "请重新授权目录"}"
                _fileEntries.value = emptyList()
            }
            _isExplorerLoading.value = false
        }
    }

    private fun loadCurrentSmbDirectory() {
        val mount = activeSmbMount ?: return
        val loc = currentSmbLocation ?: return
        val query = _fileSearchQuery.value.trim()
        val creds = runCatching {
            SmbRepository.Credentials(mount.username, mount.password, mount.domain)
        }.getOrElse { err ->
            _explorerError.value = err.message
            return
        }

        explorerLoadJob?.cancel()
        explorerLoadJob = viewModelScope.launch {
            _isExplorerLoading.value = true
            _explorerError.value = null
            _breadcrumbs.value = buildSmbBreadcrumbs(mount.name, loc)
            try {
                val smbEntries = container.smbRepository.list(loc, creds)
                val mapped = smbEntries.mapNotNull { entry ->
                    if (query.isNotEmpty() && !entry.name.contains(query, ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    FileEntry(
                        name = entry.name,
                        path = entry.location.uri,
                        uriString = entry.location.uri,
                        isDirectory = entry.isFolder,
                        sizeBytes = entry.size,
                        isVideoFile = !entry.isFolder && !entry.isSubtitle,
                        isSubtitleFile = entry.isSubtitle,
                        sidecarSubtitles = entry.sidecarSubtitles,
                        videoItem = if (!entry.isFolder && !entry.isSubtitle) {
                            entry.asVideo().copy(mountSourceId = mount.id)
                        } else {
                            null
                        }
                    )
                }
                _fileEntries.value = enrichWithIndexedSubtitles(mapped)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _explorerError.value = err.message ?: "SMB 连接失败，请检查网络和账号权限"
                _fileEntries.value = emptyList()
            } finally {
                _isExplorerLoading.value = false
            }
        }
    }

    private fun buildSmbBreadcrumbs(mountName: String, loc: SmbLocation): List<BreadcrumbSegment> {
        val list = mutableListOf<BreadcrumbSegment>()
        val serverLoc = loc.serverRoot()
        list.add(BreadcrumbSegment(label = mountName, path = serverLoc.uri, stackIndex = 0))
        if (loc.share != null) {
            var running = serverLoc.child(loc.share)
            list.add(BreadcrumbSegment(label = loc.share, path = running.uri, stackIndex = list.size))
            loc.segments.forEach { seg ->
                running = running.child(seg)
                list.add(BreadcrumbSegment(label = seg, path = running.uri, stackIndex = list.size))
            }
        }
        return list
    }

    private fun loadCurrentWebDavDirectory() {
        val mount = activeWebDavMount ?: return
        val currentUrl = webDavStack.lastOrNull()?.second ?: return
        val query = _fileSearchQuery.value

        explorerLoadJob?.cancel()
        explorerLoadJob = viewModelScope.launch {
            _isExplorerLoading.value = true
            _explorerError.value = null
            _breadcrumbs.value = webDavStack.mapIndexed { idx, (label, url) ->
                BreadcrumbSegment(label = label, path = url, stackIndex = idx)
            }
            try {
                _fileEntries.value = enrichWithIndexedSubtitles(
                    container.webDavRepository.listDirectory(mount, currentUrl, query)
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _explorerError.value = err.message ?: "WebDAV 读取失败，请检查地址与账号密码"
                _fileEntries.value = emptyList()
            } finally {
                _isExplorerLoading.value = false
            }
        }
    }

    private fun loadCurrentQuarkDirectory() {
        val mount = activeQuarkMount ?: return
        val currentFid = quarkStack.lastOrNull()?.second ?: "0"
        val query = _fileSearchQuery.value

        explorerLoadJob?.cancel()
        explorerLoadJob = viewModelScope.launch {
            _isExplorerLoading.value = true
            _explorerError.value = null
            _breadcrumbs.value = quarkStack.mapIndexed { idx, (label, fid) ->
                BreadcrumbSegment(label = label, path = fid, stackIndex = idx)
            }
            try {
                _fileEntries.value = enrichWithIndexedSubtitles(
                    container.quarkDriveRepository.listFolder(mount, currentFid, query)
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _explorerError.value = err.message ?: "夸克网盘读取失败，请检查登录状态或 Cookie"
                _fileEntries.value = emptyList()
            } finally {
                _isExplorerLoading.value = false
            }
        }
    }

    fun toggleCurrentDirectoryFavorite() {
        when (_explorerBackend.value) {
            ExplorerBackend.SAF -> {
                val saf = _activeSafTreeUri.value ?: return
                val label = _breadcrumbs.value.lastOrNull()?.label ?: "授权目录"
                container.settingsRepository.toggleFavoriteFolder(
                    FavoriteFolder(name = label, path = saf, isSafUri = true)
                )
            }
            ExplorerBackend.LOCAL -> {
                val dir = _currentDir.value
                container.settingsRepository.toggleFavoriteFolder(
                    FavoriteFolder(
                        name = dir.name.ifBlank { "内部存储" },
                        path = dir.absolutePath,
                        isSafUri = false
                    )
                )
            }
            else -> Unit
        }
    }

    fun updateSort(field: SortField, ascending: Boolean) {
        container.settingsRepository.updateSettings {
            it.copy(sortField = field, sortAscending = ascending)
        }
        refreshFileExplorer()
    }

    fun toggleLayoutMode() {
        container.settingsRepository.updateSettings {
            val next = if (it.viewLayoutMode == ViewLayoutMode.GRID) ViewLayoutMode.LIST else ViewLayoutMode.GRID
            it.copy(viewLayoutMode = next)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settingsRepository.updateSettings(transform)
        refreshFileExplorer()
    }

    fun playVideo(
        video: VideoItem,
        forceFromBeginning: Boolean = false,
        inlineInitialSrt: String? = null
    ) {
        val currentFolderVideos = _fileEntries.value.mapNotNull { it.videoItem }
        val playlist = if (currentFolderVideos.any { it.uriString == video.uriString }) {
            currentFolderVideos
        } else {
            container.fileExplorerRepository.findSiblingEpisodePlaylist(
                currentVideo = video,
                libraryFallback = _rawLibraryVideos.value
            )
        }
        _activeLaunch.value = ActivePlaybackLaunch(
            video = video,
            playlist = playlist,
            inlineInitialSrt = inlineInitialSrt,
            forceStartFromBeginning = forceFromBeginning
        )
    }

    fun playNetworkStream(url: String, customTitle: String? = null, inlineSrt: String? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val derivedTitle = customTitle?.takeIf { it.isNotBlank() }
            ?: Uri.parse(trimmed).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "网络串流视频"
        val item = VideoItem(
            id = "stream_${trimmed.hashCode()}",
            title = derivedTitle,
            uriString = trimmed,
            folderName = "网络串流"
        )
        _activeLaunch.value = ActivePlaybackLaunch(
            video = item,
            playlist = listOf(item),
            inlineInitialSrt = inlineSrt
        )
    }

    fun playExternalIntentUri(uri: Uri, displayTitle: String? = null) {
        val filePath = if (uri.scheme == "file") uri.path else null
        val title = displayTitle
            ?: filePath?.let { File(it).name }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "外部视频"
        val sidecars = filePath?.let { container.fileExplorerRepository.findSidecarSubtitles(File(it)) } ?: emptyList()
        val video = VideoItem(
            id = "ext_${uri.toString().hashCode()}",
            title = title,
            uriString = uri.toString(),
            filePath = filePath,
            folderName = "外部打开",
            sidecarSubtitlePaths = sidecars
        )
        playVideo(video)
    }

    fun closePlayer() {
        _activeLaunch.value = null
        refreshMediaLibrary()
        if (_explorerBackend.value == ExplorerBackend.LOCAL) {
            refreshFileExplorer()
        }
    }

    fun clearHistory() {
        container.settingsRepository.clearHistory()
        refreshMediaLibrary()
    }

    fun removeHistoryItem(uriString: String) {
        container.settingsRepository.removeHistoryItem(uriString)
    }

    // --- Directory Batch Subtitle Generation (SMB / Local / SAF / WebDAV / Quark) ---

    private fun computeReadyAsrEngines(): Set<AsrEngineType> =
        AsrEngineType.entries.filter { container.videoSpeechRecognizer.isModelReady(it) }.toSet()

    fun refreshReadyAsrEngines() {
        _readyAsrEngines.value = computeReadyAsrEngines()
    }

    fun downloadAsrModelInBrowser(engine: AsrEngineType) {
        viewModelScope.launch {
            container.videoSpeechRecognizer.downloadOfflineModel(engine)
            refreshReadyAsrEngines()
        }
    }

    fun getCurrentDirectoryTitle(): String {
        return _breadcrumbs.value.lastOrNull()?.label?.takeIf { it.isNotBlank() }
            ?: _currentDir.value.name.ifBlank { "当前目录" }
    }

    fun startBatchSubtitleForSingleVideo(entry: FileEntry, config: BatchSubtitleJobConfig) {
        val video = entry.videoItem ?: return
        val safTreeUri = _activeSafTreeUri.value
        val safParentDirUri = safDirStack.lastOrNull()?.uri?.toString()
        val target = BatchSubtitleTarget(
            video = video,
            safParentTreeUri = safTreeUri,
            safParentDirUri = safParentDirUri
        )
        container.batchSubtitleGenerator.startBatch(
            directoryTitle = video.title,
            targetsProvider = { listOf(target) },
            config = config.copy(skipExistingSubtitles = false),
            onVideoSubtitleWritten = {
                viewModelScope.launch {
                    refreshReadyAsrEngines()
                    refreshFileExplorer()
                }
            }
        )
    }

    fun startBatchSubtitlesForCurrentDirectory(config: BatchSubtitleJobConfig) {
        val backend = _explorerBackend.value
        val title = getCurrentDirectoryTitle()
        val currentEntriesSnapshot = _fileEntries.value
        val localDirSnapshot = _currentDir.value
        val safTreeSnapshot = _activeSafTreeUri.value
        val safDirSnapshot = safDirStack.lastOrNull()
        val smbMountSnapshot = activeSmbMount
        val smbLocSnapshot = currentSmbLocation
        val webDavMountSnapshot = activeWebDavMount
        val webDavUrlSnapshot = webDavStack.lastOrNull()?.second
        val quarkMountSnapshot = activeQuarkMount
        val quarkFidSnapshot = quarkStack.lastOrNull()?.second ?: "0"

        val targetsProvider: suspend () -> List<BatchSubtitleTarget> = {
            if (!config.includeSubfolders) {
                currentEntriesSnapshot.mapNotNull { entry ->
                    entry.videoItem?.let { vid ->
                        BatchSubtitleTarget(
                            video = vid,
                            safParentTreeUri = safTreeSnapshot,
                            safParentDirUri = safDirSnapshot?.uri?.toString()
                        )
                    }
                }
            } else {
                when (backend) {
                    ExplorerBackend.SMB -> {
                        val mount = smbMountSnapshot
                        val loc = smbLocSnapshot
                        if (mount == null || loc == null) {
                            emptyList()
                        } else {
                            collectSmbVideosRecursively(mount, loc, maxDepth = 5)
                        }
                    }
                    ExplorerBackend.LOCAL -> collectLocalVideosRecursively(localDirSnapshot, maxDepth = 5)
                    ExplorerBackend.SAF -> {
                        if (safDirSnapshot == null) emptyList()
                        else collectSafVideosRecursively(safDirSnapshot, safTreeSnapshot, maxDepth = 5)
                    }
                    ExplorerBackend.WEBDAV -> {
                        val mount = webDavMountSnapshot
                        val url = webDavUrlSnapshot
                        if (mount == null || url == null) emptyList()
                        else collectWebDavVideosRecursively(mount, url, maxDepth = 4)
                    }
                    ExplorerBackend.QUARK -> {
                        val mount = quarkMountSnapshot
                        if (mount == null) emptyList()
                        else collectQuarkVideosRecursively(mount, quarkFidSnapshot, maxDepth = 4)
                    }
                }
            }
        }

        container.batchSubtitleGenerator.startBatch(
            directoryTitle = title,
            targetsProvider = targetsProvider,
            config = config,
            onVideoSubtitleWritten = {
                viewModelScope.launch {
                    refreshReadyAsrEngines()
                    refreshFileExplorer()
                }
            }
        )
    }

    fun cancelBatchSubtitle() {
        container.batchSubtitleGenerator.cancel()
    }

    fun dismissBatchSubtitleSummary() {
        container.batchSubtitleGenerator.dismissSummary()
    }

    private fun enrichWithIndexedSubtitles(entries: List<FileEntry>): List<FileEntry> {
        return entries.map { entry ->
            if (!entry.isVideoFile || entry.sidecarSubtitles.isNotEmpty()) {
                entry
            } else {
                val indexed = container.generatedSubtitleStore.findIndexedSubtitle(entry.uriString)
                if (indexed != null) {
                    val pathList = listOf(indexed.absolutePath)
                    entry.copy(
                        sidecarSubtitles = pathList,
                        videoItem = entry.videoItem?.copy(sidecarSubtitlePaths = pathList)
                    )
                } else {
                    entry
                }
            }
        }
    }

    private suspend fun collectSmbVideosRecursively(
        mount: MountedStorageSource,
        location: SmbLocation,
        maxDepth: Int
    ): List<BatchSubtitleTarget> {
        val creds = SmbRepository.Credentials(mount.username, mount.password, mount.domain)
        val out = mutableListOf<BatchSubtitleTarget>()
        suspend fun walk(loc: SmbLocation, depth: Int) {
            val list = runCatching { container.smbRepository.list(loc, creds) }.getOrDefault(emptyList())
            for (item in list) {
                if (item.isFolder) {
                    if (depth < maxDepth) walk(item.location, depth + 1)
                } else if (!item.isSubtitle) {
                    out.add(BatchSubtitleTarget(video = item.asVideo().copy(mountSourceId = mount.id)))
                }
            }
        }
        walk(location, 0)
        return out
    }

    private fun collectLocalVideosRecursively(dir: File, maxDepth: Int): List<BatchSubtitleTarget> {
        val s = settings.value
        val out = mutableListOf<BatchSubtitleTarget>()
        fun walk(folder: File, depth: Int) {
            val entries = container.fileExplorerRepository.listDirectory(
                directory = folder,
                showHidden = s.showHiddenFiles,
                sortField = s.sortField,
                sortAscending = s.sortAscending
            )
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) walk(File(entry.path), depth + 1)
                } else if (entry.videoItem != null) {
                    out.add(BatchSubtitleTarget(video = entry.videoItem))
                }
            }
        }
        walk(dir, 0)
        return out
    }

    private suspend fun collectSafVideosRecursively(
        docDir: DocumentFile,
        treeUri: String?,
        maxDepth: Int
    ): List<BatchSubtitleTarget> {
        val s = settings.value
        val out = mutableListOf<BatchSubtitleTarget>()
        suspend fun walk(doc: DocumentFile, depth: Int) {
            val entries = container.fileExplorerRepository.listSafDirectory(
                treeOrDirDoc = doc,
                showHidden = s.showHiddenFiles,
                sortField = s.sortField,
                sortAscending = s.sortAscending
            )
            val childDocs = doc.listFiles().associateBy { it.uri.toString() }
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) {
                        childDocs[entry.uriString]?.let { walk(it, depth + 1) }
                    }
                } else if (entry.videoItem != null) {
                    out.add(
                        BatchSubtitleTarget(
                            video = entry.videoItem,
                            safParentTreeUri = treeUri,
                            safParentDirUri = doc.uri.toString()
                        )
                    )
                }
            }
        }
        walk(docDir, 0)
        return out
    }

    private suspend fun collectWebDavVideosRecursively(
        mount: MountedStorageSource,
        rootUrl: String,
        maxDepth: Int
    ): List<BatchSubtitleTarget> {
        val out = mutableListOf<BatchSubtitleTarget>()
        suspend fun walk(url: String, depth: Int) {
            val entries = runCatching { container.webDavRepository.listDirectory(mount, url) }.getOrDefault(emptyList())
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) walk(entry.uriString, depth + 1)
                } else if (entry.videoItem != null) {
                    out.add(BatchSubtitleTarget(video = entry.videoItem))
                }
            }
        }
        walk(rootUrl, 0)
        return out
    }

    private suspend fun collectQuarkVideosRecursively(
        mount: MountedStorageSource,
        rootFid: String,
        maxDepth: Int
    ): List<BatchSubtitleTarget> {
        val out = mutableListOf<BatchSubtitleTarget>()
        suspend fun walk(fid: String, depth: Int) {
            val entries = runCatching { container.quarkDriveRepository.listFolder(mount, fid) }.getOrDefault(emptyList())
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) walk(entry.path, depth + 1)
                } else if (entry.videoItem != null) {
                    out.add(BatchSubtitleTarget(video = entry.videoItem))
                }
            }
        }
        walk(rootFid, 0)
        return out
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }

        private val SAMPLE_SINTEL_SRT = """
            1
            00:00:01,500 --> 00:00:05,500
            You're a fool for traveling alone, so completely unprepared.

            2
            00:00:06,000 --> 00:00:10,200
            You're lucky your blood's still flowing.

            3
            00:00:11,000 --> 00:00:14,500
            Thank you, stranger. I owe you my life.

            4
            00:00:18,000 --> 00:00:22,800
            What brings you to the land of the gatekeepers?

            5
            00:00:23,500 --> 00:00:28,000
            I'm searching for someone... a very dear friend I lost long ago.
        """.trimIndent()

        private val SAMPLE_TEARS_SRT = """
            1
            00:00:02,000 --> 00:00:06,200
            You wanted to go to space, Thom? Look around us.

            2
            00:00:07,000 --> 00:00:11,500
            Whatever happens today, remember why we started this project.

            3
            00:00:12,500 --> 00:00:17,000
            All systems are ready. Initiate memory reconstruction sequence.
        """.trimIndent()
    }
}
