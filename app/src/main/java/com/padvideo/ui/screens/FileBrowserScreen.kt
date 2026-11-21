package com.padvideo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.BreadcrumbSegment
import com.padvideo.data.model.FavoriteFolder
import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.SafRootBookmark
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatFileSize
import com.padvideo.ui.components.OverlayBadge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(
    currentDir: File,
    activeSafUri: String?,
    entries: List<FileEntry>,
    breadcrumbs: List<BreadcrumbSegment>,
    quickAccessDirs: List<Pair<String, File>>,
    favorites: List<FavoriteFolder>,
    safRoots: List<SafRootBookmark>,
    isCurrentFavorite: Boolean,
    hasAllFilesAccess: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenDirectory: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSafBookmark: (SafRootBookmark) -> Unit,
    onOpenFavorite: (FavoriteFolder) -> Unit,
    onRequestMountSafFolder: () -> Unit,
    onPickVideoDocument: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRefresh: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "平板文件浏览器",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "支持本地目录层级跳转、USB/OTG 外接盘 SAF 挂载与外挂字幕自动关联",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("过滤当前目录文件…") },
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
                modifier = Modifier.width(260.dp)
            )

            FilledTonalButton(onClick = onRequestMountSafFolder, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("挂载目录 / U盘")
            }

            Button(onClick = onPickVideoDocument, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("打开文件")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Storage Quick-Access & Favorites Strip
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickAccessDirs) { (label, dir) ->
                val selected = activeSafUri == null && currentDir.absolutePath == dir.absolutePath
                FilterChip(
                    selected = selected,
                    onClick = { onOpenDirectory(dir) },
                    leadingIcon = {
                        Icon(Icons.Outlined.SdStorage, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(label) }
                )
            }
            items(safRoots) { saf ->
                FilterChip(
                    selected = activeSafUri == saf.treeUriString,
                    onClick = { onOpenSafBookmark(saf) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(saf.name) }
                )
            }
            items(favorites) { fav ->
                AssistChip(
                    onClick = { onOpenFavorite(fav) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("★ ${fav.name}") }
                )
            }
            if (!hasAllFilesAccess) {
                item {
                    AssistChip(
                        onClick = onRequestAllFilesAccess,
                        leadingIcon = {
                            Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("开启全盘目录访问 (支持 MKV/ASS 深度扫描)") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Breadcrumb Bar
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上级目录")
                }

                LazyRow(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(breadcrumbs.indices.toList()) { idx ->
                        val seg = breadcrumbs[idx]
                        val isLast = idx == breadcrumbs.lastIndex
                        TextButton(
                            onClick = {
                                if (!seg.isSafUri) {
                                    onOpenDirectory(File(seg.path))
                                }
                            }
                        ) {
                            Text(
                                text = seg.label,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isCurrentFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "收藏当前目录",
                        tint = if (isCurrentFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新目录")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "当前目录没有匹配的视频或子文件夹",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "可使用右上角「挂载目录 / U盘」授权任意文件夹，或在设置中开启显示非媒体文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        formattedDate = if (entry.lastModifiedMs > 0L) dateFormat.format(Date(entry.lastModifiedMs)) else "",
                        onClick = {
                            if (entry.isDirectory) {
                                if (!entry.path.startsWith("content://")) {
                                    onOpenDirectory(File(entry.path))
                                }
                            } else if (entry.videoItem != null) {
                                onPlayVideo(entry.videoItem)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    formattedDate: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                entry.isDirectory -> Icons.Outlined.Folder
                entry.isVideoFile -> Icons.Outlined.Movie
                entry.isSubtitleFile -> Icons.Outlined.Subtitles
                else -> Icons.Outlined.Description
            }
            val iconTint = when {
                entry.isDirectory -> MaterialTheme.colorScheme.primary
                entry.isVideoFile -> MaterialTheme.colorScheme.secondary
                entry.isSubtitleFile -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(30.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val subtitleInfo = if (entry.isDirectory) {
                        "${entry.childCount} 项媒体/子目录"
                    } else {
                        formatFileSize(entry.sizeBytes)
                    }
                    Text(
                        text = listOf(subtitleInfo, formattedDate).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.sidecarSubtitles.isNotEmpty()) {
                        OverlayBadge(
                            text = "外挂字幕×${entry.sidecarSubtitles.size}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (entry.isSubtitleFile) {
                        OverlayBadge(
                            text = entry.name.substringAfterLast('.', "SUB").uppercase(Locale.ROOT),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (entry.isDirectory) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
