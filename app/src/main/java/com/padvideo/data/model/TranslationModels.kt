package com.padvideo.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationEngineType(val label: String, val description: String) {
    AUTO_CLOUD(
        label = "智能云端双引擎 (推荐)",
        description = "自动调度 Google GTX 与 MyMemory 翻译引擎，免 Key 即开即用、国内海外自动容灾"
    ),
    GOOGLE_GTX(
        label = "Google 神经机器翻译",
        description = "低延迟云端翻译，适合外语影视对白快速直译"
    ),
    MYMEMORY(
        label = "MyMemory 全球开放翻译",
        description = "无需代理与密钥，全球直连翻译记忆库"
    ),
    MLKIT_OFFLINE(
        label = "ML Kit 端侧离线翻译",
        description = "下载所选语言模型后，可在设备上断网翻译字幕"
    ),
    OPENAI_COMPATIBLE(
        label = "AI 大模型精翻 (OpenAI / DeepSeek)",
        description = "结合前后剧情上下文批量意译影视台词，支持任意 OpenAI 兼容接口与本地 Ollama"
    )
}

@Serializable
enum class TranslationDisplayMode(val label: String, val shortBadge: String) {
    OFF("关闭实时翻译", "原字"),
    BILINGUAL("双语对照 (译文 + 原文)", "双语"),
    TARGET_ONLY("仅显示目标译文", "译文"),
    LIVE_SPEECH_BILINGUAL("麦克风语音同传", "同传")
}

@Serializable
enum class TranslationLanguage(
    val label: String,
    val isoCode: String,
    val mlKitCode: String,
    val openSubCode: String
) {
    ZH_CN("简体中文", "zh-CN", "zh", "zh-cn"),
    ZH_TW("繁體中文", "zh-TW", "zh", "zh-tw"),
    EN("English (英语)", "en", "en", "en"),
    JA("日本語 (日语)", "ja", "ja", "ja"),
    KO("한국어 (韩语)", "ko", "ko", "ko"),
    FR("Français (法语)", "fr", "fr", "fr"),
    DE("Deutsch (德语)", "de", "de", "de"),
    ES("Español (西班牙语)", "es", "es", "es"),
    RU("Русский (俄语)", "ru", "ru", "ru");

    companion object {
        fun fromIso(code: String): TranslationLanguage {
            val normalized = code.lowercase().trim()
            return entries.firstOrNull {
                it.isoCode.lowercase() == normalized ||
                    it.mlKitCode.lowercase() == normalized ||
                    it.openSubCode.lowercase() == normalized
            } ?: ZH_CN
        }
    }
}

@Serializable
data class TranslationConfig(
    val engine: TranslationEngineType = TranslationEngineType.AUTO_CLOUD,
    val sourceLanguageCode: String = "auto",
    val targetLanguage: TranslationLanguage = TranslationLanguage.ZH_CN,
    val displayMode: TranslationDisplayMode = TranslationDisplayMode.OFF,
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val lookaheadCueCount: Int = 20
)
