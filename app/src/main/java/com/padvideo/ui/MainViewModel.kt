package com.padvideo.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.padvideo.AppContainer
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.BreadcrumbSegment
import com.padvideo.data.model.FavoriteFolder
import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.SafRootBookmark
import com.padvideo.data.model.SortField
import com.padvideo.data.model.StreamPreset
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.ViewLayoutMode
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

    // File Explorer State
    private val _currentDir = MutableStateFlow(container.fileExplorerRepository.defaultStorageRoot)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _activeSafTreeUri = MutableStateFlow<String?>(null)
    val activeSafTreeUri: StateFlow<String?> = _activeSafTreeUri.asStateFlow()

    private val _fileEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileEntries: StateFlow<List<FileEntry>> = _fileEntries.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbSegment>>(emptyList())
    val breadcrumbs: StateFlow<List<BreadcrumbSegment>> = _breadcrumbs.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

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

    fun openDirectory(dir: File) {
        _activeSafTreeUri.value = null
        _currentDir.value = dir
        refreshFileExplorer()
    }

    fun navigateUpInExplorer(): Boolean {
        if (_activeSafTreeUri.value != null) {
            _activeSafTreeUri.value = null
            refreshFileExplorer()
            return true
        }
        val cur = _currentDir.value
        val root = container.fileExplorerRepository.defaultStorageRoot
        val parent = cur.parentFile
        if (parent != null && cur.absolutePath != root.absolutePath && parent.canRead()) {
            _currentDir.value = parent
            refreshFileExplorer()
            return true
        }
        return false
    }

    fun setFileSearchQuery(query: String) {
        _fileSearchQuery.value = query
        refreshFileExplorer()
    }

    fun refreshFileExplorer(contextForSaf: Context? = null) {
        val safUriStr = _activeSafTreeUri.value
        val historyMap = history.value.associateBy { it.uriString }
        val s = settings.value

        if (safUriStr != null && contextForSaf != null) {
            viewModelScope.launch {
                val doc = DocumentFile.fromTreeUri(contextForSaf, Uri.parse(safUriStr))
                if (doc != null) {
                    _breadcrumbs.value = listOf(
                        BreadcrumbSegment(
                            label = doc.name ?: "授权目录",
                            path = safUriStr,
                            isSafUri = true
                        )
                    )
                    _fileEntries.value = container.fileExplorerRepository.listSafDirectory(
                        treeOrDirDoc = doc,
                        showHidden = s.showHiddenFiles,
                        searchQuery = _fileSearchQuery.value,
                        sortField = s.sortField,
                        sortAscending = s.sortAscending,
                        historyByUri = historyMap
                    )
                }
            }
            return
        }

        val dir = _currentDir.value
        _breadcrumbs.value = container.fileExplorerRepository.buildBreadcrumbs(dir)
        _fileEntries.value = container.fileExplorerRepository.listDirectory(
            directory = dir,
            showHidden = s.showHiddenFiles,
            searchQuery = _fileSearchQuery.value,
            sortField = s.sortField,
            sortAscending = s.sortAscending,
            historyByUri = historyMap
        )
    }

    fun openSafRootBookmark(context: Context, bookmark: SafRootBookmark) {
        _activeSafTreeUri.value = bookmark.treeUriString
        refreshFileExplorer(context)
    }

    fun registerSafTree(context: Context, treeUri: Uri) {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val label = doc?.name?.ifBlank { "外接存储目录" } ?: "外接存储目录"
        val bookmark = SafRootBookmark(name = label, treeUriString = treeUri.toString())
        container.settingsRepository.addSafRoot(bookmark)
        _activeSafTreeUri.value = treeUri.toString()
        _selectedTab.value = MainNavTab.FILES
        refreshFileExplorer(context)
    }

    fun toggleCurrentDirectoryFavorite() {
        val saf = _activeSafTreeUri.value
        if (saf != null) {
            val label = _breadcrumbs.value.lastOrNull()?.label ?: "授权目录"
            container.settingsRepository.toggleFavoriteFolder(
                FavoriteFolder(name = label, path = saf, isSafUri = true)
            )
        } else {
            val dir = _currentDir.value
            container.settingsRepository.toggleFavoriteFolder(
                FavoriteFolder(name = dir.name.ifBlank { "内部存储" }, path = dir.absolutePath, isSafUri = false)
            )
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
        val playlist = container.fileExplorerRepository.findSiblingEpisodePlaylist(
            currentVideo = video,
            libraryFallback = _rawLibraryVideos.value
        )
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
        refreshFileExplorer()
    }

    fun clearHistory() {
        container.settingsRepository.clearHistory()
        refreshMediaLibrary()
    }

    fun removeHistoryItem(uriString: String) {
        container.settingsRepository.removeHistoryItem(uriString)
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
