package com.padvideo.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatDurationMs
import com.padvideo.data.repository.FileExplorerRepository
import java.io.File

@Composable
fun VideoGridCard(
    video: VideoItem,
    fileRepository: FileExplorerRepository,
    isSelected: Boolean = false,
    onPlay: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var thumbnail by remember(video.uriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.uriString) {
        thumbnail = fileRepository.loadVideoThumbnail(video)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onPlay() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9.4f)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF0F172A),
                                Color(0xFF1E3A5F),
                                Color(0xFF134E4A)
                            )
                        )
                    )
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                            .background(Color.White.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Top-left resolution & subtitle badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    video.resolutionBadge?.let { res ->
                        OverlayBadge(text = res, containerColor = Color(0xAA0F172A))
                    }
                    if (video.sidecarSubtitlePaths.isNotEmpty()) {
                        OverlayBadge(
                            text = "字幕×${video.sidecarSubtitlePaths.size}",
                            containerColor = Color(0xCC0F766E)
                        )
                    }
                }

                // Bottom-right duration badge
                if (video.durationMs > 0L) {
                    OverlayBadge(
                        text = video.formattedDuration,
                        containerColor = Color(0xBF000000),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }

                // Watch progress bar
                if (video.watchProgress > 0.01f) {
                    LinearProgressIndicator(
                        progress = { video.watchProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(video.folderName)
                            if (video.sizeBytes > 0L) {
                                append(" · ")
                                append(video.formattedSize)
                            }
                            if (video.lastPositionMs > 2000L) {
                                append(" · 已看 ${(video.watchProgress * 100).toInt()}%")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onInspect, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "视频详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VideoListRow(
    video: VideoItem,
    fileRepository: FileExplorerRepository,
    isSelected: Boolean = false,
    onPlay: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var thumbnail by remember(video.uriString) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.uriString) {
        thumbnail = fileRepository.loadVideoThumbnail(video, widthPx = 240, heightPx = 135)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onPlay() },
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(136.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                if (video.durationMs > 0L) {
                    OverlayBadge(
                        text = video.formattedDuration,
                        containerColor = Color(0xBF000000),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${video.folderName} · ${video.formattedSize}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    video.resolutionBadge?.let { badge ->
                        OverlayBadge(
                            text = badge,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (video.sidecarSubtitlePaths.isNotEmpty()) {
                        OverlayBadge(
                            text = "含外挂字幕",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                if (video.watchProgress > 0.01f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { video.watchProgress },
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(3.dp)
                            .clip(CircleShape)
                    )
                }
            }

            IconButton(onClick = onInspect) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "视频详情"
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletVideoInspectorPane(
    video: VideoItem,
    onPlayResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "影片详情预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                video.resolutionBadge?.let {
                    AssistChip(onClick = {}, label = { Text(it) })
                }
                if (video.durationMs > 0L) {
                    AssistChip(onClick = {}, label = { Text("时长 ${video.formattedDuration}") })
                }
                if (video.sizeBytes > 0L) {
                    AssistChip(onClick = {}, label = { Text(video.formattedSize) })
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            InspectorField(
                icon = Icons.Outlined.Folder,
                title = "所在目录",
                value = video.filePath?.let { File(it).parent } ?: video.folderName
            )

            Spacer(modifier = Modifier.height(10.dp))

            InspectorField(
                icon = Icons.Outlined.Subtitles,
                title = "关联外挂字幕",
                value = if (video.sidecarSubtitlePaths.isEmpty()) {
                    "未检测到同名本地字幕（播放时可一键在线搜字幕或开启实时翻译）"
                } else {
                    video.sidecarSubtitlePaths.joinToString("\n") { File(it).name }
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onPlayResume,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (video.lastPositionMs > 2_000L) {
                        "继续播放 (${formatDurationMs(video.lastPositionMs)})"
                    } else {
                        "立即播放"
                    }
                )
            }

            if (video.lastPositionMs > 2_000L) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onPlayFromStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Replay, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("从头开始播放")
                }
            }
        }
    }
}

@Composable
private fun InspectorField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun OverlayBadge(
    text: String,
    containerColor: Color,
    textColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
