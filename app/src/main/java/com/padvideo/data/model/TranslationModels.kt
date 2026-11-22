package com.padvideo.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationEngineType(val label: String, val description: String) {
    AUTO_CLOUD(
        label = "智能云端多引擎 (推荐·国内直连)",
        description = "自动调度微软必应神经翻译、Google、有道与 MyMemory，免 Key 免代理、国内海外自动容灾"
    ),
    BING_NEURAL(
        label = "微软必应神经翻译 (国内直连)",
        description = "微软必应神经大模型翻译，国内网络免代理极速批量响应"
    ),
    GOOGLE_GTX(
        label = "Google 神经机器翻译",
        description = "低延迟云端翻译，适合外语影视对白快速直译"
    ),
    MYMEMORY(
        label = "MyMemory 全球开放翻译",
        description = "全球开放翻译记忆库，适合作为轻量备用通道"
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
enum class AsrEngineType(val label: String, val sizeLabel: String, val description: String) {
    SENSE_VOICE_OFFLINE(
        label = "内置离线 SenseVoice (中英日韩粤·推荐)",
        sizeLabel = "约 228 MB",
        description = "阿里达摩院端侧离线语音大模型，免谷歌/免系统语音服务，戴耳机也能直接识别视频音轨，带标点、极速低延迟"
    ),
    WHISPER_TINY_OFFLINE(
        label = "内置离线 Whisper Tiny (99语轻量版)",
        sizeLabel = "约 98 MB",
        description = "OpenAI Whisper 端侧量化模型，体积更小，支持英/日/韩/法/德/西/俄等 99 种语言离线识别视频原声"
    ),
    CLOUD_WHISPER_API(
        label = "云端 Whisper / 硅基流动 API",
        sizeLabel = "免下载模型",
        description = "直接将视频音轨片段发送至 SiliconFlow (SenseVoiceSmall)、Groq、OpenAI 或局域网 faster-whisper 识别"
    )
}

@Serializable
enum class TranslationDisplayMode(val label: String, val shortBadge: String) {
    OFF("关闭实时翻译", "原字"),
    BILINGUAL("双语对照 (译文 + 原文)", "双语"),
    TARGET_ONLY("仅显示目标译文", "译文"),
    LIVE_SPEECH_BILINGUAL("视频原声 AI 识别 + 翻译 (无字幕专用)", "原声同传")
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
    val lookaheadCueCount: Int = 20,
    val asrEngine: AsrEngineType = AsrEngineType.SENSE_VOICE_OFFLINE,
    val cloudAsrBaseUrl: String = "https://api.siliconflow.cn/v1",
    val cloudAsrApiKey: String = "",
    val cloudAsrModel: String = "FunAudioLLM/SenseVoiceSmall"
)
