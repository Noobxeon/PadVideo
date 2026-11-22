package com.padvideo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.SafRootBookmark
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.formatFileSize
import com.padvideo.ui.ExplorerBackend
import com.padvideo.ui.components.OverlayBadge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(
    explorerBackend: ExplorerBackend,
    currentDir: File,
    activeSafUri: String?,
    activeMountId: String?,
    entries: List<FileEntry>,
    breadcrumbs: List<BreadcrumbSegment>,
    quickAccessDirs: List<Pair<String, File>>,
    favorites: List<FavoriteFolder>,
    safRoots: List<SafRootBookmark>,
    mountedSources: List<MountedStorageSource>,
    isCurrentFavorite: Boolean,
    hasAllFilesAccess: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenDirectory: (File) -> Unit,
    onOpenEntry: (FileEntry) -> Unit,
    onOpenBreadcrumb: (BreadcrumbSegment) -> Unit,
    onNavigateUp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSafBookmark: (SafRootBookmark) -> Unit,
    onRemoveSafBookmark: (String) -> Unit,
    onOpenMountedSource: (MountedStorageSource) -> Unit,
    onEditMountedSource: (MountedStorageSource) -> Unit,
    onOpenFavorite: (FavoriteFolder) -> Unit,
    onOpenAddMountSheet: () -> Unit,
    onPickVideoDocument: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val activeMount = mountedSources.firstOrNull { it.id == activeMountId }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Measure actual available width (after NavigationRail) so portrait tablets never squeeze the title!
        val isCompact = maxWidth < 860.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isCompact) 14.dp else 20.dp, vertical = 12.dp)
        ) {
        // Responsive Header (clean 2-row on portrait, 1-row on landscape tablet)
        if (isCompact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "平板文件浏览器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = onOpenAddMountSheet,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加挂载")
                }
                IconButton(onClick = onPickVideoDocument) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = "打开单个文件")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                        text = "平板文件浏览器",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "本地存储 · U盘 · Windows 共享 · 局域网 NAS · WebDAV · 夸克网盘一站式挂载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    modifier = Modifier.width(240.dp)
                )

                Button(onClick = onOpenAddMountSheet, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添加挂载")
                }

                FilledTonalButton(onClick = onPickVideoDocument, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("打开文件")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Unified Storage Sources Strip (Local + SAF + SMB + WebDAV + Quark + Favorites all in ONE tab!)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickAccessDirs) { (label, dir) ->
                val selected = explorerBackend == ExplorerBackend.LOCAL && currentDir.absolutePath == dir.absolutePath
                FilterChip(
                    selected = selected,
                    onClick = { onOpenDirectory(dir) },
                    leadingIcon = {
                        Icon(Icons.Outlined.SdStorage, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(label) }
                )
            }

            items(mountedSources, key = { it.id }) { source ->
                val selected = activeMountId == source.id
                FilterChip(
                    selected = selected,
                    onClick = { onOpenMountedSource(source) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (source.type) {
                                StorageMountType.SMB -> Icons.Outlined.Computer
                                StorageMountType.WEBDAV -> Icons.Outlined.Language
                                StorageMountType.QUARK -> Icons.Outlined.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(source.name) }
                )
            }

            items(safRoots, key = { it.treeUriString }) { saf ->
                val selected = explorerBackend == ExplorerBackend.SAF && activeSafUri == saf.treeUriString
                FilterChip(
                    selected = selected,
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

            if (!hasAllFilesAccess && explorerBackend == ExplorerBackend.LOCAL) {
                item {
                    AssistChip(
                        onClick = onRequestAllFilesAccess,
                        leadingIcon = {
                            Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("开启全盘访问") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Breadcrumb Bar (works identically for Local, SAF, SMB, WebDAV, and Quark!)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
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
                            onClick = { onOpenBreadcrumb(seg) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
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
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (activeMount != null) {
                    IconButton(onClick = { onEditMountedSource(activeMount) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑当前挂载源")
                    }
                } else if (explorerBackend == ExplorerBackend.SAF && activeSafUri != null) {
                    IconButton(onClick = { onRemoveSafBookmark(activeSafUri) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除该挂载目录")
                    }
                } else {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isCurrentFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "收藏当前目录",
                            tint = if (isCurrentFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新目录")
                }
            }
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (activeMount != null) {
                        TextButton(onClick = { onEditMountedSource(activeMount) }) {
                            Text("修改配置")
                        }
                    }
                    TextButton(onClick = onRefresh) {
                        Text("重试")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!isLoading && entries.isEmpty() && errorMessage == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = "当前目录没有匹配的视频或子文件夹",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "点击「添加挂载」可挂载 Windows 共享、局域网 NAS、WebDAV、夸克网盘或 U盘目录",
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
                        onClick = { onOpenEntry(entry) }
                    )
                }
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

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
                        if (entry.childCount > 0) "${entry.childCount} 项" else "文件夹"
                    } else {
                        formatFileSize(entry.sizeBytes)
                    }
                    Text(
                        text = listOf(subtitleInfo, formattedDate).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
