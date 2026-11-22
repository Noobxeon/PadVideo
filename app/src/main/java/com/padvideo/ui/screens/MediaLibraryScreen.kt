package com.padvideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.SortField
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.ViewLayoutMode
import com.padvideo.data.repository.FileExplorerRepository
import com.padvideo.ui.components.TabletVideoInspectorPane
import com.padvideo.ui.components.VideoGridCard
import com.padvideo.ui.components.VideoListRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaLibraryScreen(
    videos: List<VideoItem>,
    folders: List<String>,
    selectedFolder: String?,
    searchQuery: String,
    isScanning: Boolean,
    hasMediaPermission: Boolean,
    settings: AppSettings,
    selectedInspectorVideo: VideoItem?,
    isExpandedTabletWidth: Boolean,
    fileRepository: FileExplorerRepository,
    onSearchQueryChange: (String) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onUpdateSort: (SortField, Boolean) -> Unit,
    onToggleLayout: () -> Unit,
    onRefresh: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onPickSingleVideo: () -> Unit,
    onOpenStreamSamples: () -> Unit,
    onPlayVideo: (VideoItem, Boolean) -> Unit,
    onInspectVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isCompact = screenWidthDp < 720

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (isCompact) 14.dp else 20.dp, vertical = 12.dp)
    ) {
        // Responsive Header: 2 clean rows on portrait / compact, 1 row on landscape tablet
        if (isCompact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "平板影院媒体库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "共 ${videos.size} 部本地视频",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LibraryActionIcons(
                    settings = settings,
                    showSortMenu = showSortMenu,
                    onShowSortMenuChange = { showSortMenu = it },
                    onUpdateSort = onUpdateSort,
                    onToggleLayout = onToggleLayout,
                    onRefresh = onRefresh
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("搜索影片或目录…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除")
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "平板影院媒体库",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "共发现 ${videos.size} 部本地视频 · 支持实时双语字幕与在线搜字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("搜索影片或目录…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.width(if (isExpandedTabletWidth) 300.dp else 220.dp)
                )

                LibraryActionIcons(
                    settings = settings,
                    showSortMenu = showSortMenu,
                    onShowSortMenuChange = { showSortMenu = it },
                    onUpdateSort = onUpdateSort,
                    onToggleLayout = onToggleLayout,
                    onRefresh = onRefresh
                )
            }
        }

        // Folder Filter Chips
        if (folders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedFolder == null,
                        onClick = { onSelectFolder(null) },
                        label = { Text("全部视频") }
                    )
                }
                items(folders) { folder ->
                    FilterChip(
                        selected = selectedFolder == folder,
                        onClick = { onSelectFolder(if (selectedFolder == folder) null else folder) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(folder) }
                    )
                }
            }
        }

        // Permission Banner if not yet granted
        if (!hasMediaPermission) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCompact) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "授权读取本地视频媒体库",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "授权后自动聚合本地影片；也可前往「文件浏览」挂载 Windows 共享、NAS 与夸克网盘。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        Button(onClick = onRequestMediaPermission, shape = RoundedCornerShape(12.dp)) {
                            Text("立即授权")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "授权读取平板视频媒体库",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "授予视频读取权限后可自动聚合平板上的电影与剧集；也可免权限打开单文件或挂载网盘/NAS。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        Button(onClick = onRequestMediaPermission) {
                            Text("立即授权")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isScanning && videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (videos.isEmpty()) {
            EmptyLibraryState(
                onPickSingleVideo = onPickSingleVideo,
                onOpenStreamSamples = onOpenStreamSamples
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(modifier = Modifier.weight(if (isExpandedTabletWidth && selectedInspectorVideo != null) 0.68f else 1f)) {
                    if (settings.viewLayoutMode == ViewLayoutMode.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = if (screenWidthDp < 600) 156.dp else 240.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(videos, key = { it.uriString }) { video ->
                                VideoGridCard(
                                    video = video,
                                    fileRepository = fileRepository,
                                    isSelected = selectedInspectorVideo?.uriString == video.uriString && isExpandedTabletWidth,
                                    onPlay = { onPlayVideo(video, false) },
                                    onInspect = { onInspectVideo(video) }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(videos, key = { it.uriString }) { video ->
                                VideoListRow(
                                    video = video,
                                    fileRepository = fileRepository,
                                    isSelected = selectedInspectorVideo?.uriString == video.uriString && isExpandedTabletWidth,
                                    onPlay = { onPlayVideo(video, false) },
                                    onInspect = { onInspectVideo(video) }
                                )
                            }
                        }
                    }
                }

                if (isExpandedTabletWidth && selectedInspectorVideo != null) {
                    TabletVideoInspectorPane(
                        video = selectedInspectorVideo,
                        onPlayResume = { onPlayVideo(selectedInspectorVideo, false) },
                        onPlayFromStart = { onPlayVideo(selectedInspectorVideo, true) },
                        modifier = Modifier
                            .weight(0.32f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryActionIcons(
    settings: AppSettings,
    showSortMenu: Boolean,
    onShowSortMenuChange: (Boolean) -> Unit,
    onUpdateSort: (SortField, Boolean) -> Unit,
    onToggleLayout: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { onShowSortMenuChange(true) }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "排序方式")
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { onShowSortMenuChange(false) }
            ) {
                SortField.entries.forEach { field ->
                    val isCurrent = settings.sortField == field
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append(field.label)
                                    if (isCurrent) {
                                        append(if (settings.sortAscending) " (升序 ↑)" else " (降序 ↓)")
                                    }
                                }
                            )
                        },
                        onClick = {
                            val nextAsc = if (isCurrent) !settings.sortAscending else (field == SortField.NAME)
                            onUpdateSort(field, nextAsc)
                            onShowSortMenuChange(false)
                        }
                    )
                }
            }
        }

        IconButton(onClick = onToggleLayout) {
            Icon(
                imageVector = if (settings.viewLayoutMode == ViewLayoutMode.GRID) {
                    Icons.AutoMirrored.Outlined.ViewList
                } else {
                    Icons.Outlined.GridView
                },
                contentDescription = "切换网格或列表视图"
            )
        }

        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新媒体库")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyLibraryState(
    onPickSingleVideo: () -> Unit,
    onOpenStreamSamples: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(60.dp)
            )
            Text(
                text = "暂未发现本地视频文件",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "可在「文件浏览」挂载本地目录、Windows 共享、局域网 NAS 或夸克网盘，或先试播开源样片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onPickSingleVideo, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("选择本地视频播放")
                }
                FilledTonalButton(onClick = onOpenStreamSamples, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.CloudQueue, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("体验高清双语测试片源")
                }
            }
        }
    }
}
