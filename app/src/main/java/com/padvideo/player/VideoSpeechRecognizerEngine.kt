package com.padvideo.player

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.TranslationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AsrModelDownloadProgress(
    val engine: AsrEngineType,
    val progressFraction: Float,
    val statusText: String,
    val isDownloading: Boolean = false
)

/**
 * Built-in Neural Video Audio Speech Recognition Engine:
 * - On-device offline recognition via Sherpa-ONNX (SenseVoiceSmall int8 & Whisper Tiny int8) — ZERO GMS / ZERO system SpeechRecognizer required!
 * - Cloud Whisper-compatible API (/audio/transcriptions) supporting SiliconFlow, Groq, OpenAI, and local faster-whisper.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSpeechRecognizerEngine(
    private val modelsRootDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    private data class RemoteFileSpec(
        val fileName: String,
        val primaryMirrorUrl: String,
        val fallbackUrl: String
    )

    private val recognizerMutex = Mutex()
    private var activeRecognizer: OfflineRecognizer? = null
    private var activeRecognizerKey: String? = null

    private val _downloadProgress = MutableStateFlow<AsrModelDownloadProgress?>(null)
    val downloadProgress: StateFlow<AsrModelDownloadProgress?> = _downloadProgress.asStateFlow()

    fun getModelDirectory(engine: AsrEngineType): File = when (engine) {
        AsrEngineType.SENSE_VOICE_OFFLINE -> File(modelsRootDir, "sense_voice_int8")
        AsrEngineType.WHISPER_TINY_OFFLINE -> File(modelsRootDir, "whisper_tiny_int8")
        AsrEngineType.CLOUD_WHISPER_API -> modelsRootDir
    }

    fun isModelReady(engine: AsrEngineType): Boolean {
        if (engine == AsrEngineType.CLOUD_WHISPER_API) return true
        val dir = getModelDirectory(engine)
        val specs = modelSpecsFor(engine)
        return specs.all { spec ->
            val f = File(dir, spec.fileName)
            f.exists() && f.length() > 10_000L
        }
    }

    fun deleteModel(engine: AsrEngineType) {
        if (engine == AsrEngineType.CLOUD_WHISPER_API) return
        if (activeRecognizerKey?.startsWith(engine.name) == true) {
            runCatching { activeRecognizer?.release() }
            activeRecognizer = null
            activeRecognizerKey = null
        }
        getModelDirectory(engine).deleteRecursively()
    }

    suspend fun downloadOfflineModel(engine: AsrEngineType): Result<Unit> = withContext(Dispatchers.IO) {
        if (engine == AsrEngineType.CLOUD_WHISPER_API) return@withContext Result.success(Unit)
        val dir = getModelDirectory(engine).apply { mkdirs() }
        val specs = modelSpecsFor(engine)

        runCatching {
            specs.forEachIndexed { idx, spec ->
                val targetFile = File(dir, spec.fileName)
                if (targetFile.exists() && targetFile.length() > 10_000L) {
                    return@forEachIndexed
                }
                val partFile = File(dir, "${spec.fileName}.part")
                val label = "正在下载 ${spec.fileName} (${idx + 1}/${specs.size})"
                _downloadProgress.value = AsrModelDownloadProgress(engine, 0f, label, isDownloading = true)

                val urls = listOf(spec.primaryMirrorUrl, spec.fallbackUrl)
                var downloaded = false
                var lastErr: Throwable? = null
                for (url in urls) {
                    val attempt = runCatching {
                        downloadFileWithProgress(url, partFile) { bytesRead, totalBytes ->
                            val frac = if (totalBytes > 0L) {
                                (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            val readMb = bytesRead / (1024f * 1024f)
                            val totalMb = totalBytes / (1024f * 1024f)
                            val detail = if (totalBytes > 0L) {
                                String.format(
                                    java.util.Locale.US,
                                    "$label · %.1f / %.1f MB (%.0f%%)",
                                    readMb,
                                    totalMb,
                                    frac * 100f
                                )
                            } else {
                                String.format(java.util.Locale.US, "$label · %.1f MB", readMb)
                            }
                            _downloadProgress.value = AsrModelDownloadProgress(engine, frac, detail, isDownloading = true)
                        }
                    }
                    if (attempt.isSuccess) {
                        if (targetFile.exists()) targetFile.delete()
                        if (!partFile.renameTo(targetFile)) {
                            partFile.copyTo(targetFile, overwrite = true)
                            partFile.delete()
                        }
                        downloaded = true
                        break
                    } else {
                        lastErr = attempt.exceptionOrNull()
                        partFile.delete()
                    }
                }
                if (!downloaded) {
                    throw lastErr ?: IOException("下载模型文件失败：${spec.fileName}")
                }
            }
            _downloadProgress.value = AsrModelDownloadProgress(engine, 1f, "语音识别模型已就绪，可直接识别无字幕视频！", isDownloading = false)
            Unit
        }.onFailure { err ->
            if (err is CancellationException) throw err
            _downloadProgress.value = AsrModelDownloadProgress(
                engine = engine,
                progressFraction = 0f,
                statusText = "模型下载失败：${err.message ?: "请检查网络"}",
                isDownloading = false
            )
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 PadVideo/1.3")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ($url)")
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                throw IOException("镜像返回网页而非模型文件，切换备用线路 ($url)")
            }
            val body = resp.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    var readTotal = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 160L || readTotal == total) {
                            lastEmit = now
                            onProgress(readTotal, total)
                        }
                    }
                }
            }
        }
    }

    /**
     * Transcribes a 16 kHz mono float audio chunk directly captured from the playing video.
     */
    suspend fun transcribeSegment(
        samples16k: FloatArray,
        config: TranslationConfig
    ): Result<String> = withContext(Dispatchers.Default) {
        if (samples16k.size < 3200) return@withContext Result.success("")
        runCatching {
            when (config.asrEngine) {
                AsrEngineType.SENSE_VOICE_OFFLINE, AsrEngineType.WHISPER_TINY_OFFLINE -> {
                    transcribeOfflineSherpa(samples16k, config.asrEngine, config.sourceLanguageCode)
                }
                AsrEngineType.CLOUD_WHISPER_API -> {
                    withContext(Dispatchers.IO) {
                        transcribeCloudWhisper(samples16k, config)
                    }
                }
            }
        }.also { res ->
            res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private suspend fun transcribeOfflineSherpa(
        samples16k: FloatArray,
        engine: AsrEngineType,
        sourceLanguageCode: String
    ): String {
        if (!isModelReady(engine)) {
            throw IllegalStateException("请先下载「${engine.label}」离线语音识别模型")
        }
        val langParam = normalizeAsrLanguage(sourceLanguageCode, engine)
        val recognizerKey = "${engine.name}|$langParam"

        return recognizerMutex.withLock {
            val recognizer = if (activeRecognizerKey == recognizerKey && activeRecognizer != null) {
                activeRecognizer!!
            } else {
                runCatching { activeRecognizer?.release() }
                val built = buildOfflineRecognizer(engine, langParam)
                activeRecognizer = built
                activeRecognizerKey = recognizerKey
                built
            }

            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples16k, VideoAudioCaptureProcessor.TARGET_SAMPLE_RATE)
                recognizer.decode(stream)
                val rawText = recognizer.getResult(stream).text
                cleanAsrText(rawText)
            } finally {
                stream.release()
            }
        }
    }

    private fun buildOfflineRecognizer(engine: AsrEngineType, langParam: String): OfflineRecognizer {
        val dir = getModelDirectory(engine)
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        val modelConfig = when (engine) {
            AsrEngineType.SENSE_VOICE_OFFLINE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    language = langParam,
                    useInverseTextNormalization = true
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            )
            AsrEngineType.WHISPER_TINY_OFFLINE -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "tiny-encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "tiny-decoder.int8.onnx").absolutePath,
                    language = langParam,
                    task = "transcribe"
                ),
                tokens = File(dir, "tiny-tokens.txt").absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            )
            AsrEngineType.CLOUD_WHISPER_API -> error("Cloud ASR does not use OfflineRecognizer")
        }

        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = VideoAudioCaptureProcessor.TARGET_SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(assetManager = null, config = recognizerConfig)
    }

    private fun transcribeCloudWhisper(
        samples16k: FloatArray,
        config: TranslationConfig
    ): String {
        val baseUrl = config.cloudAsrBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) throw IllegalStateException("请在设置中填写云端语音识别 API 地址")
        val effectiveApiKey = config.cloudAsrApiKey.trim().ifBlank { config.openAiApiKey.trim() }
        if (effectiveApiKey.isBlank() &&
            !baseUrl.contains("localhost") &&
            !baseUrl.contains("127.0.0.1") &&
            !baseUrl.contains("192.168.")
        ) {
            throw IllegalStateException("请先在设置中填写云端语音识别 API Key（推荐免费硅基流动 SenseVoiceSmall），或切换内置离线模型")
        }

        val wavBytes = VideoAudioCaptureProcessor.encodePcm16kToWav(samples16k)
        val mpBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", config.cloudAsrModel.ifBlank { "FunAudioLLM/SenseVoiceSmall" })

        if (config.sourceLanguageCode.isNotBlank() && config.sourceLanguageCode != "auto") {
            mpBuilder.addFormDataPart("language", config.sourceLanguageCode.substringBefore('-'))
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .post(mpBuilder.build())
        if (effectiveApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $effectiveApiKey")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("云端语音识别失败 (HTTP ${resp.code})")
            }
            val text = runCatching { JSONObject(body).optString("text") }.getOrDefault(body)
            return cleanAsrText(text)
        }
    }

    fun release() {
        runCatching { activeRecognizer?.release() }
        activeRecognizer = null
        activeRecognizerKey = null
    }

    private fun modelSpecsFor(engine: AsrEngineType): List<RemoteFileSpec> = when (engine) {
        AsrEngineType.SENSE_VOICE_OFFLINE -> listOf(
            RemoteFileSpec(
                fileName = "model.int8.onnx",
                primaryMirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                fallbackUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
            ),
            RemoteFileSpec(
                fileName = "tokens.txt",
                primaryMirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                fallbackUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
            )
        )
        AsrEngineType.WHISPER_TINY_OFFLINE -> listOf(
            RemoteFileSpec(
                fileName = "tiny-encoder.int8.onnx",
                primaryMirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                fallbackUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx"
            ),
            RemoteFileSpec(
                fileName = "tiny-decoder.int8.onnx",
                primaryMirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                fallbackUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx"
            ),
            RemoteFileSpec(
                fileName = "tiny-tokens.txt",
                primaryMirrorUrl = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                fallbackUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt"
            )
        )
        AsrEngineType.CLOUD_WHISPER_API -> emptyList()
    }

    companion object {
        private val META_TAG_REGEX = Regex("""<\|[^|]+?\|>""")

        fun cleanAsrText(raw: String): String {
            return raw
                .replace(META_TAG_REGEX, "")
                .replace(Regex("""\[BLANK_AUDIO]|\[MUSIC]|\[APPLAUSE]|\(music\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
        }

        fun normalizeAsrLanguage(sourceCode: String, engine: AsrEngineType): String {
            val base = sourceCode.lowercase().substringBefore('-').trim()
            return when (engine) {
                AsrEngineType.SENSE_VOICE_OFFLINE -> {
                    if (base in setOf("zh", "en", "ja", "ko", "yue")) base else "auto"
                }
                AsrEngineType.WHISPER_TINY_OFFLINE -> {
                    if (base.isBlank() || base == "auto") "" else base
                }
                AsrEngineType.CLOUD_WHISPER_API -> base
            }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
