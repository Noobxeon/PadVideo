package com.padvideo.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AspectRatioMode(val label: String) {
    FIT("自适应"),
    ZOOM_CROP("铺满裁剪"),
    STRETCH("拉伸满屏"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_21_9("21:9 影院")
}

enum class GestureHudType {
    BRIGHTNESS,
    VOLUME,
    SEEK,
    DOUBLE_TAP_REWIND,
    DOUBLE_TAP_FORWARD,
    LONG_PRESS_SPEED,
    ZOOM
}

data class GestureHudState(
    val type: GestureHudType,
    val title: String,
    val valueText: String,
    val progressFraction: Float? = null
)

data class AbLoopState(
    val pointAMs: Long? = null,
    val pointBMs: Long? = null,
    val enabled: Boolean = false
) {
    val isActive: Boolean
        get() = enabled && pointAMs != null && pointBMs != null && pointBMs > pointAMs
}

data class AudioTrackOption(
    val id: String,
    val label: String,
    val language: String = "",
    val channelCount: Int = 0,
    val mimeType: String = "",
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val isSelected: Boolean = false
)

enum class SleepTimerOption(val label: String, val minutes: Int) {
    OFF("不开启", 0),
    MIN_15("15 分钟后暂停", 15),
    MIN_30("30 分钟后暂停", 30),
    MIN_45("45 分钟后暂停", 45),
    MIN_60("60 分钟后暂停", 60),
    MIN_90("90 分钟后暂停", 90),
    END_OF_VIDEO("播完当前视频暂停", -1)
}

@Serializable
enum class ThemeMode(val label: String) {
    DARK("深色影院 (默认)"),
    AMOLED_BLACK("OLED 纯黑省电"),
    SYSTEM("跟随平板系统"),
    LIGHT("浅色明亮")
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    val defaultJumpSeconds: Int = 10,
    val longPressSpeed: Float = 2.0f,
    val autoResumePlayback: Boolean = true,
    val autoEnterPip: Boolean = true,
    val backgroundAudioEnabled: Boolean = false,
    val hardwareDecoderPreferred: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val sortField: SortField = SortField.DATE_MODIFIED,
    val sortAscending: Boolean = false,
    val viewLayoutMode: ViewLayoutMode = ViewLayoutMode.GRID,
    val openSubtitlesApiKey: String = "",
    val openSubtitlesJwtToken: String = "",
    val assrtApiToken: String = "",
    val subDlApiKey: String = "",
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val translationConfig: TranslationConfig = TranslationConfig()
)
