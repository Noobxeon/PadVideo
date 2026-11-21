package com.padvideo.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleCue(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    val translatedText: String? = null
)

enum class SubtitleFormat(val extension: String) {
    SRT("srt"),
    VTT("vtt"),
    ASS("ass"),
    UNKNOWN("srt")
}

data class SubtitleTrackOption(
    val id: String,
    val label: String,
    val language: String = "",
    val isExternal: Boolean = false,
    val externalPath: String? = null,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val isSelected: Boolean = false
)

enum class SubtitleProviderSource(val displayName: String) {
    SHOOTER_HASH("射手影音哈希匹配"),
    OPENSUBTITLES_PUBLIC("OpenSubtitles 全球开放索引"),
    OPENSUBTITLES_REST("OpenSubtitles 官方 API"),
    ASSRT("伪射手网 (Assrt) 字幕库"),
    SUBDL("SubDL 字幕库")
}

data class OnlineSubtitleResult(
    val id: String,
    val title: String,
    val fileName: String,
    val languageCode: String,
    val languageLabel: String,
    val format: String = "srt",
    val source: SubtitleProviderSource,
    val downloadUrl: String = "",
    val openSubtitlesFileId: Int? = null,
    val assrtSubId: Int? = null,
    val downloadCount: Int = 0,
    val isHashMatch: Boolean = false,
    val rating: Double = 0.0
)

@Serializable
data class SubtitleStyleConfig(
    val fontSizeSp: Float = 22f,
    val secondaryFontScale: Float = 0.78f,
    val bottomPaddingDp: Float = 32f,
    val backgroundAlpha: Float = 0.52f,
    val syncOffsetMs: Long = 0L,
    val primaryColorHex: Long = 0xFFFFFFFF,
    val translatedColorHex: Long = 0xFF5EEAD4,
    val boldPrimary: Boolean = true
)
