package com.padvideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.ThemeMode
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var openAiBaseUrl by remember(settings.translationConfig.openAiBaseUrl) {
        mutableStateOf(settings.translationConfig.openAiBaseUrl)
    }
    var openAiApiKey by remember(settings.translationConfig.openAiApiKey) {
        mutableStateOf(settings.translationConfig.openAiApiKey)
    }
    var openAiModel by remember(settings.translationConfig.openAiModel) {
        mutableStateOf(settings.translationConfig.openAiModel)
    }

    var assrtToken by remember(settings.assrtApiToken) { mutableStateOf(settings.assrtApiToken) }
    var openSubKey by remember(settings.openSubtitlesApiKey) { mutableStateOf(settings.openSubtitlesApiKey) }
    var subDlKey by remember(settings.subDlApiKey) { mutableStateOf(settings.subDlApiKey) }
    var savedBanner by remember { mutableStateOf<String?>(null) }
    val isCompact = LocalConfiguration.current.screenWidthDp < 600

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "设置中心",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "定制平板 Material You 外观、商业播放操控、实时翻译引擎与扩展在线字幕密钥",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Appearance & Material Design 3
        item {
            SettingsCard(
                icon = Icons.Outlined.Palette,
                title = "外观与 Material Design 3 主题"
            ) {
                Text("色彩模式", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsToggleRow(
                    title = "Material You 动态壁纸取色 (Android 12+)",
                    subtitle = "根据平板系统壁纸自动生成和谐的主辅色调",
                    checked = settings.dynamicColor,
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(dynamicColor = checked) } }
                )
            }
        }

        // 2. Commercial Playback Preferences
        item {
            SettingsCard(
                icon = Icons.Outlined.Tune,
                title = "商业级播放控制与文件浏览偏好"
            ) {
                Text("双击快进 / 快退跨度", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { sec ->
                        FilterChip(
                            selected = settings.defaultJumpSeconds == sec,
                            onClick = { onUpdateSettings { it.copy(defaultJumpSeconds = sec) } },
                            label = { Text("$sec 秒") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("长按极速倍速档位", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2.0f, 2.5f, 3.0f, 4.0f).forEach { spd ->
                        FilterChip(
                            selected = settings.longPressSpeed == spd,
                            onClick = { onUpdateSettings { it.copy(longPressSpeed = spd) } },
                            label = { Text("${spd}x") }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingsToggleRow(
                    title = "智能断点续播",
                    subtitle = "重新打开看过的影片时自动恢复上次播放时间点与倍速",
                    checked = settings.autoResumePlayback,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoResumePlayback = v) } }
                )
                SettingsToggleRow(
                    title = "返回桌面自动进入画中画小窗 (PiP)",
                    subtitle = "播放中切换多任务时自动悬浮小窗继续播放",
                    checked = settings.autoEnterPip,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoEnterPip = v) } }
                )
                SettingsToggleRow(
                    title = "硬件解码器优先 (含解码容灾回退)",
                    subtitle = "优先启用平板 GPU 硬解 4K HEVC/AV1，遇异常编码自动无缝回退软解",
                    checked = settings.hardwareDecoderPreferred,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(hardwareDecoderPreferred = v) } }
                )
                SettingsToggleRow(
                    title = "文件浏览器显示隐藏与全部类型文件",
                    subtitle = "开启后在目录浏览中一并展示非视频/字幕文件与点开头隐藏目录",
                    checked = settings.showHiddenFiles,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showHiddenFiles = v) } }
                )
            }
        }

        // 3. Real-Time Translation Engine
        item {
            SettingsCard(
                icon = Icons.Outlined.AutoAwesome,
                title = "实时双语翻译引擎配置"
            ) {
                Text("翻译后端引擎", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TranslationEngineType.entries.forEach { engine ->
                        FilterChip(
                            selected = settings.translationConfig.engine == engine,
                            onClick = {
                                onUpdateSettings { s ->
                                    s.copy(translationConfig = s.translationConfig.copy(engine = engine))
                                }
                            },
                            label = { Text(engine.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = settings.translationConfig.engine.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("默认目标译文语言", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TranslationLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.translationConfig.targetLanguage == lang,
                            onClick = {
                                onUpdateSettings { s ->
                                    s.copy(translationConfig = s.translationConfig.copy(targetLanguage = lang))
                                }
                            },
                            label = { Text(lang.label) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "自定义 AI 大模型接口 (OpenAI / DeepSeek / 通义千问 / 本地 Ollama)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = openAiBaseUrl,
                    onValueChange = { openAiBaseUrl = it },
                    label = { Text("API Base URL (如 https://api.deepseek.com/v1)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isCompact) {
                    OutlinedTextField(
                        value = openAiApiKey,
                        onValueChange = { openAiApiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = openAiModel,
                        onValueChange = { openAiModel = it },
                        label = { Text("模型名 (如 deepseek-chat)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = openAiApiKey,
                            onValueChange = { openAiApiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = openAiModel,
                            onValueChange = { openAiModel = it },
                            label = { Text("模型名 (如 deepseek-chat)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                }
            }
        }

        // 4. Online Subtitle Providers
        item {
            SettingsCard(
                icon = Icons.Outlined.Subtitles,
                title = "在线字幕搜索多源密钥 (可选扩展)"
            ) {
                Text(
                    text = "内置「射手影音文件哈希匹配」与「OpenSubtitles 全球开放索引」免密钥默认生效；如需扩展更多精校双语字幕库，可填写以下免费个人密钥：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = assrtToken,
                    onValueChange = { assrtToken = it },
                    label = { Text("伪射手网 (assrt.net) API Token (可选)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isCompact) {
                    OutlinedTextField(
                        value = openSubKey,
                        onValueChange = { openSubKey = it },
                        label = { Text("OpenSubtitles REST v1 Api-Key (可选)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = subDlKey,
                        onValueChange = { subDlKey = it },
                        label = { Text("SubDL API Key (可选)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = openSubKey,
                            onValueChange = { openSubKey = it },
                            label = { Text("OpenSubtitles REST v1 Api-Key (可选)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = subDlKey,
                            onValueChange = { subDlKey = it },
                            label = { Text("SubDL API Key (可选)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            onUpdateSettings { current ->
                                current.copy(
                                    assrtApiToken = assrtToken.trim(),
                                    openSubtitlesApiKey = openSubKey.trim(),
                                    subDlApiKey = subDlKey.trim(),
                                    translationConfig = current.translationConfig.copy(
                                        openAiBaseUrl = openAiBaseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                                        openAiApiKey = openAiApiKey.trim(),
                                        openAiModel = openAiModel.trim().ifBlank { "gpt-4o-mini" }
                                    )
                                )
                            }
                            savedBanner = "已保存全部接口密钥与大模型配置"
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("保存接口与密钥配置")
                    }
                    savedBanner?.let { msg ->
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
