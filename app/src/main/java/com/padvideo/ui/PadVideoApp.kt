package com.padvideo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.padvideo.AppContainer
import com.padvideo.ui.screens.FileBrowserScreen
import com.padvideo.ui.screens.HistoryStreamScreen
import com.padvideo.ui.screens.MediaLibraryScreen
import com.padvideo.ui.screens.SettingsScreen
import com.padvideo.ui.screens.VideoPlayerScreen
import com.padvideo.ui.theme.PadVideoTheme
import java.io.File

@Composable
fun PadVideoApp(
    container: AppContainer,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val useNavigationRail = screenWidthDp >= 600
    val isExpandedTabletWidth = screenWidthDp >= 840

    val settings by mainViewModel.settings.collectAsState()
    val selectedTab by mainViewModel.selectedTab.collectAsState()
    val libraryVideos by mainViewModel.filteredLibraryVideos.collectAsState()
    val libraryFolders by mainViewModel.libraryFolders.collectAsState()
    val selectedFolder by mainViewModel.selectedLibraryFolder.collectAsState()
    val librarySearch by mainViewModel.librarySearchQuery.collectAsState()
    val isScanning by mainViewModel.isScanningLibrary.collectAsState()
    val inspectorVideo by mainViewModel.selectedInspectorVideo.collectAsState()

    val currentDir by mainViewModel.currentDir.collectAsState()
    val activeSafUri by mainViewModel.activeSafTreeUri.collectAsState()
    val fileEntries by mainViewModel.fileEntries.collectAsState()
    val breadcrumbs by mainViewModel.breadcrumbs.collectAsState()
    val fileSearch by mainViewModel.fileSearchQuery.collectAsState()
    val favorites by mainViewModel.favorites.collectAsState()
    val safRoots by mainViewModel.safRoots.collectAsState()
    val history by mainViewModel.history.collectAsState()
    val activeLaunch by mainViewModel.activeLaunch.collectAsState()

    val requiredMediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasMediaPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredMediaPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            } else {
                hasMediaPermission
            }
        )
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMediaPermission = granted
        if (granted) {
            mainViewModel.refreshMediaLibrary()
            mainViewModel.refreshFileExplorer(context)
        }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            }
            mainViewModel.registerSafTree(context, treeUri)
        }
    }

    val singleVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { docUri: Uri? ->
        if (docUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    docUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            mainViewModel.playExternalIntentUri(docUri)
        }
    }

    // Back navigation inside File Browser hierarchy
    BackHandler(enabled = activeLaunch == null && selectedTab == MainNavTab.FILES) {
        if (!mainViewModel.navigateUpInExplorer()) {
            mainViewModel.selectTab(MainNavTab.LIBRARY)
        }
    }

    LaunchedEffect(activeLaunch) {
        val launch = activeLaunch
        if (launch != null) {
            playerViewModel.startLaunch(launch)
        }
    }

    PadVideoTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        if (activeLaunch != null) {
            VideoPlayerScreen(
                playerViewModel = playerViewModel,
                isInPipMode = isInPipMode,
                onEnterPip = onEnterPip,
                onExitPlayer = { mainViewModel.closePlayer() }
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Scaffold(
                    bottomBar = {
                        if (!useNavigationRail) {
                            NavigationBar {
                                MainNavTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { mainViewModel.selectTab(tab) },
                                        icon = {
                                            Icon(
                                                imageVector = navIcon(tab, selected),
                                                contentDescription = tab.label
                                            )
                                        },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .systemBarsPadding()
                    ) {
                        if (useNavigationRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                header = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.PlayCircleOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier
                                                    .padding(10.dp)
                                                    .size(26.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "PadVideo",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                MainNavTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { mainViewModel.selectTab(tab) },
                                        icon = {
                                            Icon(
                                                imageVector = navIcon(tab, selected),
                                                contentDescription = tab.label
                                            )
                                        },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            when (selectedTab) {
                                MainNavTab.LIBRARY -> {
                                    MediaLibraryScreen(
                                        videos = libraryVideos,
                                        folders = libraryFolders,
                                        selectedFolder = selectedFolder,
                                        searchQuery = librarySearch,
                                        isScanning = isScanning,
                                        hasMediaPermission = hasMediaPermission,
                                        settings = settings,
                                        selectedInspectorVideo = inspectorVideo,
                                        isExpandedTabletWidth = isExpandedTabletWidth,
                                        fileRepository = container.fileExplorerRepository,
                                        onSearchQueryChange = { mainViewModel.setLibrarySearchQuery(it) },
                                        onSelectFolder = { mainViewModel.selectLibraryFolder(it) },
                                        onUpdateSort = { f, asc -> mainViewModel.updateSort(f, asc) },
                                        onToggleLayout = { mainViewModel.toggleLayoutMode() },
                                        onRefresh = { mainViewModel.refreshMediaLibrary() },
                                        onRequestMediaPermission = {
                                            mediaPermissionLauncher.launch(requiredMediaPermission)
                                        },
                                        onPickSingleVideo = {
                                            singleVideoLauncher.launch(arrayOf("video/*"))
                                        },
                                        onOpenStreamSamples = {
                                            mainViewModel.selectTab(MainNavTab.HISTORY_STREAM)
                                        },
                                        onPlayVideo = { video, fromStart ->
                                            mainViewModel.playVideo(video, forceFromBeginning = fromStart)
                                        },
                                        onInspectVideo = { mainViewModel.inspectVideo(it) }
                                    )
                                }

                                MainNavTab.FILES -> {
                                    val currentPathKey = activeSafUri ?: currentDir.absolutePath
                                    val isFav = favorites.any { it.path == currentPathKey }
                                    FileBrowserScreen(
                                        currentDir = currentDir,
                                        activeSafUri = activeSafUri,
                                        entries = fileEntries,
                                        breadcrumbs = breadcrumbs,
                                        quickAccessDirs = mainViewModel.getQuickAccessDirs(),
                                        favorites = favorites,
                                        safRoots = safRoots,
                                        isCurrentFavorite = isFav,
                                        hasAllFilesAccess = hasAllFilesAccess,
                                        searchQuery = fileSearch,
                                        onSearchQueryChange = { mainViewModel.setFileSearchQuery(it) },
                                        onOpenDirectory = { mainViewModel.openDirectory(it) },
                                        onNavigateUp = { mainViewModel.navigateUpInExplorer() },
                                        onToggleFavorite = { mainViewModel.toggleCurrentDirectoryFavorite() },
                                        onOpenSafBookmark = { mainViewModel.openSafRootBookmark(context, it) },
                                        onOpenFavorite = { fav ->
                                            if (fav.isSafUri) {
                                                mainViewModel.openSafRootBookmark(
                                                    context,
                                                    com.padvideo.data.model.SafRootBookmark(fav.name, fav.path)
                                                )
                                            } else {
                                                mainViewModel.openDirectory(File(fav.path))
                                            }
                                        },
                                        onRequestMountSafFolder = { safTreeLauncher.launch(null) },
                                        onPickVideoDocument = { singleVideoLauncher.launch(arrayOf("video/*")) },
                                        onRequestAllFilesAccess = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                runCatching {
                                                    val intent = Intent(
                                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                    context.startActivity(intent)
                                                }.onFailure {
                                                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                    runCatching { context.startActivity(fallback) }
                                                }
                                                hasAllFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
                                            } else {
                                                mediaPermissionLauncher.launch(requiredMediaPermission)
                                            }
                                        },
                                        onRefresh = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                hasAllFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
                                            }
                                            mainViewModel.refreshFileExplorer(context)
                                        },
                                        onPlayVideo = { mainViewModel.playVideo(it) }
                                    )
                                }

                                MainNavTab.HISTORY_STREAM -> {
                                    HistoryStreamScreen(
                                        history = history,
                                        presets = mainViewModel.builtInStreamPresets,
                                        isExpandedTabletWidth = isExpandedTabletWidth,
                                        onLaunchStream = { url, title, srt ->
                                            mainViewModel.playNetworkStream(url, title, srt)
                                        },
                                        onResumeHistory = { mainViewModel.playVideo(it) },
                                        onRemoveHistory = { mainViewModel.removeHistoryItem(it) },
                                        onClearAllHistory = { mainViewModel.clearHistory() }
                                    )
                                }

                                MainNavTab.SETTINGS -> {
                                    SettingsScreen(
                                        settings = settings,
                                        onUpdateSettings = { mainViewModel.updateSettings(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun navIcon(tab: MainNavTab, selected: Boolean) = when (tab) {
    MainNavTab.LIBRARY -> if (selected) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
    MainNavTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    MainNavTab.HISTORY_STREAM -> if (selected) Icons.Filled.History else Icons.Outlined.History
    MainNavTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
