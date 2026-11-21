package com.padvideo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.StreamPreset
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatDurationMs
import com.padvideo.ui.components.OverlayBadge

@Composable
fun HistoryStreamScreen(
    history: List<PlaybackHistoryItem>,
    presets: List<StreamPreset>,
    isExpandedTabletWidth: Boolean,
    onLaunchStream: (url: String, title: String?, inlineSrt: String?) -> Unit,
    onResumeHistory: (VideoItem) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var streamTitle by rememberSaveable { mutableStateOf("") }

    if (isExpandedTabletWidth) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Left Column: Network Stream & Cinema Demo Presets
            LazyColumn(
                modifier = Modifier.weight(0.48f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    StreamLauncherCard(
                        streamUrl = streamUrl,
                        streamTitle = streamTitle,
                        onUrlChange = { streamUrl = it },
                        onTitleChange = { streamTitle = it },
                        onPlay = {
                            onLaunchStream(streamUrl, streamTitle.ifBlank { null }, null)
                        }
                    )
                }
                item {
                    Text(
                        text = "精选开源高清样片 (一键体验双语同传)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(presets) { preset ->
                    StreamPresetCard(
                        preset = preset,
                        onPlay = { onLaunchStream(preset.streamUrl, preset.title, preset.sampleEnglishSrt) }
                    )
                }
            }

            // Right Column: Watch History
            Column(modifier = Modifier.weight(0.52f)) {
                HistoryHeaderRow(hasItems = history.isNotEmpty(), onClearAll = onClearAllHistory)
                Spacer(modifier = Modifier.height(10.dp))
                HistoryListContent(
                    history = history,
                    onResumeHistory = onResumeHistory,
                    onRemoveHistory = onRemoveHistory
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                StreamLauncherCard(
                    streamUrl = streamUrl,
                    streamTitle = streamTitle,
                    onUrlChange = { streamUrl = it },
                    onTitleChange = { streamTitle = it },
                    onPlay = { onLaunchStream(streamUrl, streamTitle.ifBlank { null }, null) }
                )
            }
            item {
                Text(
                    text = "精选开源高清样片 (一键体验双语同传)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(presets) { preset ->
                StreamPresetCard(
                    preset = preset,
                    onPlay = { onLaunchStream(preset.streamUrl, preset.title, preset.sampleEnglishSrt) }
                )
            }
            item {
                HistoryHeaderRow(hasItems = history.isNotEmpty(), onClearAll = onClearAllHistory)
            }
            items(history, key = { it.uriString }) { item ->
                HistoryRowItem(
                    item = item,
                    onResume = {
                        onResumeHistory(
                            VideoItem(
                                id = item.uriString.hashCode().toString(),
                                title = item.title,
                                uriString = item.uriString,
                                filePath = item.filePath,
                                durationMs = item.durationMs,
                                width = item.width,
                                height = item.height,
                                lastPositionMs = item.positionMs
                            )
                        )
                    },
                    onDelete = { onRemoveHistory(item.uriString) }
                )
            }
        }
    }
}

@Composable
private fun StreamLauncherCard(
    streamUrl: String,
    streamTitle: String,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onPlay: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "网络串流直连播放",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "支持 HTTP / HTTPS 直链、HLS (.m3u8)、DASH (.mpd)、RTSP 与局域网 NAS/WebDAV 视频地址",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = streamUrl,
                onValueChange = onUrlChange,
                label = { Text("输入视频串流 URL") },
                placeholder = { Text("https://example.com/movie.mp4 或 .m3u8") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = streamTitle,
                    onValueChange = onTitleChange,
                    label = { Text("自定义标题 (可选)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onPlay,
                    enabled = streamUrl.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(54.dp)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("立即串流")
                }
            }
        }
    }
}

@Composable
private fun StreamPresetCard(
    preset: StreamPreset,
    onPlay: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onPlay() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    OverlayBadge(
                        text = preset.badge,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = preset.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(onClick = onPlay, shape = RoundedCornerShape(12.dp)) {
                Text("试播")
            }
        }
    }
}

@Composable
private fun HistoryHeaderRow(
    hasItems: Boolean,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "最近播放记录",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (hasItems) {
            TextButton(onClick = onClearAll) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("清空记录")
            }
        }
    }
}

@Composable
private fun HistoryListContent(
    history: List<PlaybackHistoryItem>,
    onResumeHistory: (VideoItem) -> Unit,
    onRemoveHistory: (String) -> Unit
) {
    if (history.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无播放记录", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "观看过的本地影片与网络串流将自动保存精确进度、倍速与外挂字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(history, key = { it.uriString }) { item ->
                HistoryRowItem(
                    item = item,
                    onResume = {
                        onResumeHistory(
                            VideoItem(
                                id = item.uriString.hashCode().toString(),
                                title = item.title,
                                uriString = item.uriString,
                                filePath = item.filePath,
                                durationMs = item.durationMs,
                                width = item.width,
                                height = item.height,
                                lastPositionMs = item.positionMs
                            )
                        )
                    },
                    onDelete = { onRemoveHistory(item.uriString) }
                )
            }
        }
    }
}

@Composable
private fun HistoryRowItem(
    item: PlaybackHistoryItem,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onResume() },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("上次看到 ${formatDurationMs(item.positionMs)}")
                        if (item.durationMs > 0L) {
                            append(" / ${formatDurationMs(item.durationMs)}")
                        }
                        if (item.playbackSpeed != 1.0f) {
                            append(" · ${item.playbackSpeed}x")
                        }
                        if (!item.externalSubtitlePath.isNullOrBlank()) {
                            append(" · 已绑外挂字幕")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(3.5.dp)
                        .clip(CircleShape)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除记录")
            }
        }
    }
}
