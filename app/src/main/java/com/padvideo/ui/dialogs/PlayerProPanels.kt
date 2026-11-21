package com.padvideo.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.AspectRatioMode
import com.padvideo.data.model.AudioTrackOption
import com.padvideo.data.model.SleepTimerOption
import com.padvideo.data.model.SubtitleStyleConfig
import com.padvideo.data.model.SubtitleTrackOption
import com.padvideo.data.model.VideoBookmark
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatDurationMs
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedAndTimerSheet(
    currentSpeed: Float,
    sleepTimerOption: SleepTimerOption,
    sleepRemainingSec: Long,
    aspectRatioMode: AspectRatioMode,
    rotationDegrees: Int,
    isMirrored: Boolean,
    onSpeedChange: (Float) -> Unit,
    onSleepTimerChange: (SleepTimerOption) -> Unit,
    onSelectAspect: (AspectRatioMode) -> Unit,
    onRotate90: () -> Unit,
    onToggleMirror: () -> Unit,
    onResetTransform: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "播放倍速 · 画面变换 · 睡眠定时",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "当前倍速: %.2fx", currentSpeed),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onSpeedChange((currentSpeed - 0.05f).coerceAtLeast(0.25f)) }) {
                    Icon(Icons.Outlined.Remove, contentDescription = "减速")
                }
                IconButton(onClick = { onSpeedChange((currentSpeed + 0.05f).coerceAtMost(4.0f)) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "加速")
                }
            }
            Slider(
                value = currentSpeed,
                onValueChange = onSpeedChange,
                valueRange = 0.25f..4.0f
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(currentSpeed - preset) < 0.03f,
                        onClick = { onSpeedChange(preset) },
                        label = { Text("${preset}x") }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("画面比例、旋转与镜像翻转", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatioMode.entries.forEach { mode ->
                    FilterChip(
                        selected = aspectRatioMode == mode,
                        onClick = { onSelectAspect(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onRotate90, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("旋转 90° (当前 ${rotationDegrees}°)")
                }
                FilledTonalButton(onClick = onToggleMirror, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Flip, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isMirrored) "已开启镜像" else "水平镜像翻转")
                }
                FilledTonalButton(onClick = onResetTransform, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重置画面")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = buildString {
                    append("睡眠定时器")
                    if (sleepRemainingSec > 0L) {
                        append(" (剩余 ${formatDurationMs(sleepRemainingSec * 1000L)})")
                    }
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SleepTimerOption.entries.forEach { opt ->
                    FilterChip(
                        selected = sleepTimerOption == opt,
                        onClick = { onSleepTimerChange(opt) },
                        label = { Text(opt.label) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioAndSubtitleStyleSheet(
    volumePercent: Int,
    audioTracks: List<AudioTrackOption>,
    embeddedSubtitleTracks: List<SubtitleTrackOption>,
    activeExternalSubPath: String?,
    subtitlesEnabled: Boolean,
    subtitleStyle: SubtitleStyleConfig,
    onVolumePercentChange: (Int) -> Unit,
    onSelectAudioTrack: (AudioTrackOption) -> Unit,
    onSelectEmbeddedSubTrack: (SubtitleTrackOption?) -> Unit,
    onToggleSubtitlesEnabled: () -> Unit,
    onImportLocalSubtitle: () -> Unit,
    onUpdateSubtitleStyle: ((SubtitleStyleConfig) -> SubtitleStyleConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "多音轨 · 200% 音量增益 · 字幕同步与样式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (volumePercent > 100) {
                    "音频响度增强 (LoudnessEnhancer): $volumePercent%"
                } else {
                    "播放器音量: $volumePercent%"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (volumePercent > 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = volumePercent.toFloat(),
                onValueChange = { onVolumePercentChange(it.toInt()) },
                valueRange = 0f..200f
            )

            if (audioTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("切换内嵌音轨", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                audioTracks.forEach { track ->
                    FilterChip(
                        selected = track.isSelected,
                        onClick = { onSelectAudioTrack(track) },
                        label = { Text(track.label) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = subtitlesEnabled,
                    onClick = onToggleSubtitlesEnabled,
                    label = { Text(if (subtitlesEnabled) "字幕显示已开启" else "字幕已隐藏") }
                )
                FilledTonalButton(onClick = onImportLocalSubtitle, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导入本地字幕 (.srt/.ass/.vtt)")
                }
            }

            if (!activeExternalSubPath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "当前外挂字幕: ${activeExternalSubPath.substringAfterLast('/').substringAfterLast('\\')}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (embeddedSubtitleTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("内嵌字幕轨道", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    embeddedSubtitleTracks.forEach { sub ->
                        FilterChip(
                            selected = sub.isSelected && activeExternalSubPath == null,
                            onClick = { onSelectEmbeddedSubTrack(sub) },
                            label = { Text(sub.label) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subtitle Sync Offset (-30s..+30s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val secOffset = subtitleStyle.syncOffsetMs / 1000.0
                Text(
                    text = String.format(Locale.US, "字幕时间轴同步偏移: %+.1f 秒", secOffset),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        onUpdateSubtitleStyle { it.copy(syncOffsetMs = (it.syncOffsetMs - 200L).coerceAtLeast(-30_000L)) }
                    }
                ) { Text("-0.2s") }
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = {
                        onUpdateSubtitleStyle { it.copy(syncOffsetMs = 0L) }
                    }
                ) { Text("归零") }
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = {
                        onUpdateSubtitleStyle { it.copy(syncOffsetMs = (it.syncOffsetMs + 200L).coerceAtMost(30_000L)) }
                    }
                ) { Text("+0.2s") }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "字幕字号: ${subtitleStyle.fontSizeSp.toInt()} sp",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = subtitleStyle.fontSizeSp,
                onValueChange = { sz -> onUpdateSubtitleStyle { it.copy(fontSizeSp = sz) } },
                valueRange = 14f..42f
            )

            Text(
                text = "底部边距高度: ${subtitleStyle.bottomPaddingDp.toInt()} dp",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = subtitleStyle.bottomPaddingDp,
                onValueChange = { pad -> onUpdateSubtitleStyle { it.copy(bottomPaddingDp = pad) } },
                valueRange = 8f..120f
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAndBookmarksSheet(
    playlist: List<VideoItem>,
    currentIndex: Int,
    bookmarks: List<VideoBookmark>,
    onSelectPlaylistIndex: (Int) -> Unit,
    onAddBookmarkNow: () -> Unit,
    onJumpToBookmark: (Long) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "视频书签与同目录选集列表",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onAddBookmarkNow, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("标记当前时间点")
                }
            }

            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("当前影片书签 (${bookmarks.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onJumpToBookmark(bm.positionMs)
                                    onDismiss()
                                },
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatDurationMs(bm.positionMs),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = bm.label, modifier = Modifier.weight(1f), maxLines = 1)
                                IconButton(
                                    onClick = { onDeleteBookmark(bm.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除书签", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("同目录播放列表 (${playlist.size} 集)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(playlist) { idx, item ->
                    val active = idx == currentIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectPlaylistIndex(idx)
                                onDismiss()
                            },
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}. ${item.title}",
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            if (active) {
                                Icon(Icons.Outlined.Check, contentDescription = "正在播放")
                            }
                        }
                    }
                }
            }
        }
    }
}
