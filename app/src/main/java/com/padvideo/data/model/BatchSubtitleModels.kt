package com.padvideo.data.model

enum class BatchSubtitleOutputFormat(
    val label: String,
    val description: String
) {
    BILINGUAL(
        label = "双语对照字幕 (.bilingual.srt)",
        description = "上方译文 + 下方原声识别文本，最适合学习与双语观影"
    ),
    TARGET_ONLY(
        label = "仅目标语言译文 (.zh.srt)",
        description = "只写入翻译后的目标语言字幕，画面更简洁"
    ),
    ORIGINAL_ONLY(
        label = "仅原声识别字幕 (.asr.srt)",
        description = "不经网络翻译，纯离线生成视频原声语言字幕"
    );

    fun buildFileName(videoFileName: String, targetLangIso: String): String {
        val stem = videoFileName.substringBeforeLast('.').ifBlank { videoFileName }
        val suffix = when (this) {
            BILINGUAL -> ".bilingual.srt"
            TARGET_ONLY -> ".${targetLangIso.lowercase().substringBefore('-').ifBlank { "zh" }}.srt"
            ORIGINAL_ONLY -> ".asr.srt"
        }
        return "$stem$suffix"
    }
}

data class BatchSubtitleJobConfig(
    val outputFormat: BatchSubtitleOutputFormat = BatchSubtitleOutputFormat.BILINGUAL,
    val skipExistingSubtitles: Boolean = true,
    val includeSubfolders: Boolean = false,
    val useSileroVad: Boolean = true,
    val translationConfig: TranslationConfig = TranslationConfig()
)

enum class SubtitleWriteDestination {
    ORIGINAL_DIRECTORY,
    LOCAL_CACHE_FALLBACK
}

data class SubtitleWriteResult(
    val destination: SubtitleWriteDestination,
    val savedLocationDisplay: String,
    val fallbackReason: String? = null
)

data class BatchSubtitleTarget(
    val video: VideoItem,
    val safParentTreeUri: String? = null,
    val safParentDirUri: String? = null
)

data class BatchSubtitleTaskProgress(
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val directoryTitle: String = "",
    val totalVideos: Int = 0,
    val completedVideos: Int = 0,
    val skippedVideos: Int = 0,
    val failedVideos: Int = 0,
    val currentVideoName: String = "",
    val currentStageText: String = "",
    val currentVideoFraction: Float = 0f,
    val writtenBackCount: Int = 0,
    val localFallbackCount: Int = 0,
    val usingSileroVad: Boolean = true,
    val logs: List<String> = emptyList(),
    val summaryMessage: String? = null
) {
    val processedVideos: Int
        get() = completedVideos + skippedVideos + failedVideos

    val overallFraction: Float
        get() {
            if (totalVideos <= 0) return 0f
            val base = processedVideos.toFloat()
            val cur = if (isRunning) currentVideoFraction.coerceIn(0f, 1f) else 0f
            return ((base + cur) / totalVideos.toFloat()).coerceIn(0f, 1f)
        }
}
