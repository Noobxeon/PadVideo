package com.padvideo.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationDisplayMode
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranslationControlSheet(
    config: TranslationConfig,
    hasLoadedSubtitleCues: Boolean,
    onSelectDisplayMode: (TranslationDisplayMode) -> Unit,
    onSelectEngine: (TranslationEngineType) -> Unit,
    onSelectTargetLanguage: (TranslationLanguage) -> Unit,
    onSelectSourceLanguage: (String) -> Unit,
    translationError: String?,
    onQueryDownloadedOfflineModels: ((Set<String>) -> Unit) -> Unit,
    onDownloadOfflineLanguagePack: (String, (String, Set<String>) -> Unit) -> Unit,
    onDownloadOfflinePack: ((String) -> Unit) -> Unit,
    onRequestMicPermissionAndStartLiveSpeech: () -> Unit,
    onExportBilingualSrt: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isCompact = LocalConfiguration.current.screenWidthDp < 600
    var offlineStatus by remember(config.sourceLanguageCode, config.targetLanguage) { mutableStateOf<String?>(null) }
    var downloadInProgress by remember { mutableStateOf(false) }
    var downloadedCodes by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(config.engine) {
        if (config.engine == TranslationEngineType.MLKIT_OFFLINE) {
            onQueryDownloadedOfflineModels { codes -> downloadedCodes = codes }
        }
    }

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
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "实时双语翻译与语音同传中心",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "支持内嵌/外挂字幕前瞻预译，无字幕视频可点「在线字幕」或开启麦克风同传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("显示与同传模式", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TranslationDisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.displayMode == mode,
                        onClick = {
                            if (mode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) {
                                onRequestMicPermissionAndStartLiveSpeech()
                            } else {
                                onSelectDisplayMode(mode)
                            }
                        },
                        label = { Text(mode.label) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            val sourceOptions = listOf("auto" to "自动识别") +
                TranslationLanguage.entries.map { it.isoCode to it.label }

            if (isCompact) {
                Text("视频源语言", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    sourceOptions.forEach { (code, label) ->
                        FilterChip(
                            selected = config.sourceLanguageCode == code,
                            onClick = { onSelectSourceLanguage(code) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("翻译目标语言", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TranslationLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = config.targetLanguage == lang,
                            onClick = { onSelectTargetLanguage(lang) },
                            label = { Text(lang.label) }
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("视频源语言", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sourceOptions.forEach { (code, label) ->
                                FilterChip(
                                    selected = config.sourceLanguageCode == code,
                                    onClick = { onSelectSourceLanguage(code) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("翻译目标语言", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TranslationLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = config.targetLanguage == lang,
                                    onClick = { onSelectTargetLanguage(lang) },
                                    label = { Text(lang.label) }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("实时翻译引擎", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TranslationEngineType.entries.forEach { engine ->
                    FilterChip(
                        selected = config.engine == engine,
                        onClick = { onSelectEngine(engine) },
                        label = { Text(engine.label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = config.engine.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            // Offline Language Pack Manager (always clickable, live status!)
            if (config.engine == TranslationEngineType.MLKIT_OFFLINE) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "离线语言模型包管理（点按下载，下载后可完全断网使用）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        val commonPacks = listOf(
                            "en" to "英语",
                            "zh" to "中文",
                            "ja" to "日语",
                            "ko" to "韩语",
                            "fr" to "法语",
                            "ru" to "俄语"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            commonPacks.forEach { (code, label) ->
                                val isInstalled = code in downloadedCodes
                                AssistChip(
                                    enabled = !downloadInProgress,
                                    onClick = {
                                        downloadInProgress = true
                                        offlineStatus = "正在下载 $label 离线模型（约 30MB）…"
                                        onDownloadOfflineLanguagePack(code) { msg, updated ->
                                            offlineStatus = msg
                                            downloadedCodes = updated
                                            downloadInProgress = false
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isInstalled) Icons.Outlined.CheckCircle else Icons.Outlined.CloudDownload,
                                            contentDescription = null,
                                            tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text(if (isInstalled) "$label ✓" else "下载 $label") }
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                enabled = !downloadInProgress,
                                onClick = {
                                    downloadInProgress = true
                                    offlineStatus = "正在下载当前语言组合离线模型…"
                                    onDownloadOfflinePack { msg ->
                                        offlineStatus = msg
                                        downloadInProgress = false
                                        onQueryDownloadedOfflineModels { codes -> downloadedCodes = codes }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("一键下载当前语种对")
                            }

                            AssistChip(
                                onClick = { onSelectEngine(TranslationEngineType.AUTO_CLOUD) },
                                label = { Text("国内无代理？切回智能云端直连") }
                            )
                        }
                    }
                }
            }

            if (hasLoadedSubtitleCues) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onExportBilingualSrt,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导出当前双语 SRT 字幕")
                }
            }

            translationError?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            offlineStatus?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
