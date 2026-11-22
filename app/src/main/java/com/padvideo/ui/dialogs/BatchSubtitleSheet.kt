package com.padvideo.ui.dialogs

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.BatchSubtitleJobConfig
import com.padvideo.data.model.BatchSubtitleOutputFormat
import com.padvideo.data.model.BatchSubtitleTaskProgress
import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage
import com.padvideo.player.AsrModelDownloadProgress

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchSubtitleSheet(
    directoryTitle: String,
    singleTargetEntry: FileEntry?,
    currentDirTotalVideos: Int,
    currentDirMissingSubVideos: Int,
    initialTranslationConfig: TranslationConfig,
    readyAsrEngines: Set<AsrEngineType>,
    asrDownloadProgress: AsrModelDownloadProgress?,
    taskProgress: BatchSubtitleTaskProgress,
    onDownloadAsrModel: (AsrEngineType) -> Unit,
    onStartDirectoryBatch: (BatchSubtitleJobConfig) -> Unit,
    onStartSingleVideo: (FileEntry, BatchSubtitleJobConfig) -> Unit,
    onCancelBatch: () -> Unit,
    onDismissSummary: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var outputFormat by remember { mutableStateOf(BatchSubtitleOutputFormat.BILINGUAL) }
    var skipExisting by remember { mutableStateOf(true) }
    var includeSubfolders by remember { mutableStateOf(false) }
    var useSileroVad by remember { mutableStateOf(true) }
    var asrEngine by remember(initialTranslationConfig.asrEngine) {
        mutableStateOf(initialTranslationConfig.asrEngine)
    }
    var translationEngine by remember(initialTranslationConfig.engine) {
        mutableStateOf(initialTranslationConfig.engine)
    }
    var sourceLanguageCode by remember(initialTranslationConfig.sourceLanguageCode) {
        mutableStateOf(initialTranslationConfig.sourceLanguageCode)
    }
    var targetLanguage by remember(initialTranslationConfig.targetLanguage) {
        mutableStateOf(initialTranslationConfig.targetLanguage)
    }

    val showLiveMonitor = taskProgress.isRunning || taskProgress.summaryMessage != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (singleTargetEntry != null && !showLiveMonitor) {
                            "为单个视频生成外挂字幕"
                        } else {
                            "目录批量生成字幕（自动写回目录）"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = singleTargetEntry?.name ?: "目标目录：$directoryTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭批量字幕面板")
                }
            }

            if (showLiveMonitor) {
                BatchProgressMonitorView(
                    progress = taskProgress,
                    onCancel = onCancelBatch,
                    onResetAndConfigure = onDismissSummary,
                    onClose = {
                        if (!taskProgress.isRunning) onDismissSummary()
                        onDismiss()
                    }
                )
            } else {
                // Info card explaining write-back & auto-load
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val headline = if (singleTargetEntry != null) {
                            "高速提取音轨（跳过画面，支持 MKV AC3/DTS 软解）+ Silero VAD 精准断句"
                        } else {
                            "当前目录发现 $currentDirTotalVideos 个视频（其中 $currentDirMissingSubVideos 个暂无外挂字幕）"
                        }
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "生成完成后将直接写入视频所在目录（支持 SMB 共享、本地、U盘、WebDAV）；若目录为只读权限，将自动存入本地字幕索引，下次播放依然自动加载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 1. Output Format
                Text("生成字幕格式", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BatchSubtitleOutputFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = outputFormat == fmt,
                            onClick = { outputFormat = fmt },
                            label = { Text(fmt.label) }
                        )
                    }
                }
                Text(
                    text = outputFormat.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // 2. Speech Recognition Engine (ASR)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("语音识别引擎（离线免系统服务 / 云端可选）", style = MaterialTheme.typography.titleMedium)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AsrEngineType.entries.forEach { eng ->
                        val ready = eng in readyAsrEngines
                        FilterChip(
                            selected = asrEngine == eng,
                            onClick = { asrEngine = eng },
                            leadingIcon = if (ready && eng != AsrEngineType.CLOUD_WHISPER_API) {
                                {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            label = {
                                Text(
                                    if (eng != AsrEngineType.CLOUD_WHISPER_API && ready) {
                                        "${eng.label} · 已就绪"
                                    } else {
                                        eng.label
                                    }
                                )
                            }
                        )
                    }
                }

                if (asrEngine != AsrEngineType.CLOUD_WHISPER_API && asrEngine !in readyAsrEngines) {
                    val activeDownload = asrDownloadProgress?.takeIf { it.isDownloading && it.engine == asrEngine }
                    if (activeDownload != null) {
                        LinearProgressIndicator(
                            progress = { activeDownload.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = activeDownload.statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "离线模型尚未下载；点击下方「开始生成」时会自动通过国内镜像下载并接着处理。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(
                                onClick = { onDownloadAsrModel(asrEngine) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("提前下载") }
                            )
                        }
                    }
                }

                // 3. Language & Translation Engine (when bilingual or target-only)
                if (outputFormat != BatchSubtitleOutputFormat.ORIGINAL_ONLY) {
                    HorizontalDivider()
                    val sourceOptions = listOf("auto" to "自动识别") +
                        TranslationLanguage.entries.map { it.isoCode to it.label }
                    Text("视频原声语言", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        sourceOptions.forEach { (code, label) ->
                            FilterChip(
                                selected = sourceLanguageCode == code,
                                onClick = { sourceLanguageCode = code },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text("译文目标语言", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TranslationLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = targetLanguage == lang,
                                onClick = { targetLanguage = lang },
                                label = { Text(lang.label) }
                            )
                        }
                    }

                    Text("翻译引擎", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TranslationEngineType.entries.forEach { eng ->
                            FilterChip(
                                selected = translationEngine == eng,
                                onClick = { translationEngine = eng },
                                label = { Text(eng.label) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 4. Precision VAD & Batch Options
                BatchOptionToggle(
                    title = "Silero VAD 神经网络精准断句（推荐）",
                    subtitle = "内置端侧 Silero VAD 模型，过滤背景音乐/音效并按自然语调精准切分时间轴",
                    checked = useSileroVad,
                    onCheckedChange = { useSileroVad = it }
                )

                if (singleTargetEntry == null) {
                    BatchOptionToggle(
                        title = "自动跳过已有外挂字幕的视频",
                        subtitle = "若中途取消或重启，再次启动会自动跳过已完成视频实现断点续跑",
                        checked = skipExisting,
                        onCheckedChange = { skipExisting = it }
                    )
                    BatchOptionToggle(
                        title = "递归包含子文件夹中的所有视频",
                        subtitle = "一并扫描当前目录下各层子文件夹并各自写回对应子目录",
                        checked = includeSubfolders,
                        onCheckedChange = { includeSubfolders = it }
                    )
                }

                // 5. Start Action
                val effectiveTransConfig = initialTranslationConfig.copy(
                    asrEngine = asrEngine,
                    engine = translationEngine,
                    sourceLanguageCode = sourceLanguageCode,
                    targetLanguage = targetLanguage
                )
                val jobConfig = BatchSubtitleJobConfig(
                    outputFormat = outputFormat,
                    skipExistingSubtitles = skipExisting,
                    includeSubfolders = includeSubfolders,
                    useSileroVad = useSileroVad,
                    translationConfig = effectiveTransConfig
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(0.4f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        enabled = singleTargetEntry != null || currentDirTotalVideos > 0 || includeSubfolders,
                        onClick = {
                            if (singleTargetEntry != null) {
                                onStartSingleVideo(singleTargetEntry, jobConfig)
                            } else {
                                onStartDirectoryBatch(jobConfig)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(0.6f)
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (singleTargetEntry != null) {
                                "立即生成并写回目录"
                            } else {
                                "开始批量生成字幕"
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchProgressMonitorView(
    progress: BatchSubtitleTaskProgress,
    onCancel: () -> Unit,
    onResetAndConfigure: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (progress.isRunning) {
                        "正在后台生成 (${progress.processedVideos}/${progress.totalVideos.coerceAtLeast(1)})"
                    } else {
                        "批量字幕任务已结束"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(progress.overallFraction * 100f).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { progress.overallFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            if (progress.currentVideoName.isNotBlank() && progress.isRunning) {
                Text(
                    text = "当前视频：${progress.currentVideoName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress.currentVideoFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }

            Text(
                text = progress.currentStageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(onClick = {}, label = { Text("成功 ${progress.completedVideos}") })
                if (progress.writtenBackCount > 0) {
                    AssistChip(onClick = {}, label = { Text("写回原目录 ${progress.writtenBackCount}") })
                }
                if (progress.localFallbackCount > 0) {
                    AssistChip(onClick = {}, label = { Text("本地索引 ${progress.localFallbackCount}") })
                }
                if (progress.skippedVideos > 0) {
                    AssistChip(onClick = {}, label = { Text("跳过 ${progress.skippedVideos}") })
                }
                if (progress.failedVideos > 0) {
                    AssistChip(onClick = {}, label = { Text("失败 ${progress.failedVideos}") })
                }
            }
        }
    }

    if (progress.logs.isNotEmpty()) {
        Text("处理明细日志", style = MaterialTheme.typography.titleSmall)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                progress.logs.asReversed().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (progress.isRunning) {
            OutlinedButton(
                enabled = !progress.isCancelling,
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(0.45f)
            ) {
                Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (progress.isCancelling) "正在停止…" else "停止任务")
            }
            FilledTonalButton(
                onClick = onClose,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(0.55f)
            ) {
                Text("转入后台继续运行")
            }
        } else {
            OutlinedButton(
                onClick = onResetAndConfigure,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(0.45f)
            ) {
                Text("再次配置")
            }
            Button(
                onClick = onClose,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(0.55f)
            ) {
                Text("完成并关闭")
            }
        }
    }
}

@Composable
private fun BatchOptionToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
