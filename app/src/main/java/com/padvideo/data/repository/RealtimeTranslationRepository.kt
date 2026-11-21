package com.padvideo.data.repository

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class RealtimeTranslationRepository(
    private val cacheDirectory: File? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val googleGtxUrl: String = "https://translate.googleapis.com/translate_a/single",
    private val myMemoryUrl: String = "https://api.mymemory.translated.net/get"
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryCache = LruCache<String, String>(2048)
    private val diskCacheMap = mutableMapOf<String, String>()
    private val cacheLock = Any()
    private val diskCacheFile: File? = cacheDirectory?.let { File(it, "translation_lru_cache.json") }

    init {
        loadDiskCache()
    }

    private fun loadDiskCache() {
        val file = diskCacheFile ?: return
        if (!file.exists()) return
        runCatching {
            val map = json.decodeFromString<Map<String, String>>(file.readText())
            synchronized(cacheLock) {
                diskCacheMap.putAll(map)
                map.forEach { (k, v) -> memoryCache.put(k, v) }
            }
        }
    }

    private fun persistDiskCacheSnapshot() {
        val file = diskCacheFile ?: return
        val snapshot = synchronized(cacheLock) {
            if (diskCacheMap.size > 1500) {
                val keysToDrop = diskCacheMap.keys.take(diskCacheMap.size - 1200)
                keysToDrop.forEach { diskCacheMap.remove(it) }
            }
            HashMap(diskCacheMap)
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(snapshot))
        }
    }

    private fun buildCacheKey(
        engine: TranslationEngineType,
        sourceLang: String,
        targetLang: String,
        text: String
    ): String = "${engine.name}|$sourceLang|$targetLang|${text.trim()}"

    fun getCachedTranslation(
        text: String,
        config: TranslationConfig
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val key = buildCacheKey(config.engine, config.sourceLanguageCode, config.targetLanguage.isoCode, trimmed)
        return synchronized(cacheLock) { memoryCache.get(key) ?: diskCacheMap[key] }
    }

    private fun putCache(
        engine: TranslationEngineType,
        sourceLang: String,
        targetLang: String,
        originalText: String,
        translatedText: String
    ) {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty() || translatedText.isBlank()) return
        val key = buildCacheKey(engine, sourceLang, targetLang, trimmed)
        synchronized(cacheLock) {
            memoryCache.put(key, translatedText)
            diskCacheMap[key] = translatedText
        }
    }

    suspend fun translateSingle(
        text: String,
        config: TranslationConfig
    ): Result<String> = translateBatch(listOf(text), config).map { it.firstOrNull().orEmpty() }

    suspend fun translateBatch(
        texts: List<String>,
        config: TranslationConfig
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext Result.success(emptyList())

        val targetIso = config.targetLanguage.isoCode
        val sourceCode = config.sourceLanguageCode
        val results = MutableList(texts.size) { "" }
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()

        texts.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                results[idx] = ""
            } else {
                val cached = getCachedTranslation(trimmed, config)
                if (cached != null) {
                    results[idx] = cached
                } else {
                    uncachedIndices.add(idx)
                    uncachedTexts.add(trimmed)
                }
            }
        }

        if (uncachedTexts.isEmpty()) {
            return@withContext Result.success(results)
        }

        val fetchedResult = runCatching {
            when (config.engine) {
                TranslationEngineType.AUTO_CLOUD -> {
                    runCatching { translateViaGoogleGtx(uncachedTexts, sourceCode, targetIso) }
                        .recoverCatching { translateViaMyMemory(uncachedTexts, sourceCode, targetIso) }
                        .getOrThrow()
                }
                TranslationEngineType.GOOGLE_GTX -> {
                    translateViaGoogleGtx(uncachedTexts, sourceCode, targetIso)
                }
                TranslationEngineType.MYMEMORY -> {
                    translateViaMyMemory(uncachedTexts, sourceCode, targetIso)
                }
                TranslationEngineType.MLKIT_OFFLINE -> {
                    translateViaMlKitOffline(uncachedTexts, sourceCode, config.targetLanguage)
                }
                TranslationEngineType.OPENAI_COMPATIBLE -> {
                    translateViaOpenAiCompatible(uncachedTexts, config)
                }
            }
        }

        fetchedResult.map { translatedList ->
            uncachedIndices.forEachIndexed { localIdx, originalIdx ->
                val trans = translatedList.getOrNull(localIdx)?.trim().orEmpty()
                results[originalIdx] = trans.ifEmpty { texts[originalIdx] }
                if (trans.isNotEmpty()) {
                    putCache(config.engine, sourceCode, targetIso, uncachedTexts[localIdx], trans)
                }
            }
            persistDiskCacheSnapshot()
            results
        }
    }

    /**
     * Translates a window of upcoming cues ahead of the playhead and invokes [onProgress] per chunk.
     */
    suspend fun translateCueWindow(
        cues: List<SubtitleCue>,
        startIndex: Int,
        windowSize: Int = 20,
        config: TranslationConfig,
        onUpdatedCues: (Map<Int, String>) -> Unit
    ) {
        if (cues.isEmpty() || startIndex !in cues.indices) return
        val endExclusive = (startIndex + windowSize).coerceAtMost(cues.size)
        val slice = cues.subList(startIndex, endExclusive)
            .filter { it.translatedText.isNullOrBlank() && it.originalText.isNotBlank() }

        if (slice.isEmpty()) return

        // Translate in compact chunks of 8 cues for fast progressive UI updates
        slice.chunked(8).forEach { chunk ->
            val texts = chunk.map { it.originalText.replace('\n', ' ') }
            val batchResult = translateBatch(texts, config)
            batchResult.onSuccess { translated ->
                val updates = mutableMapOf<Int, String>()
                chunk.forEachIndexed { i, cue ->
                    val tr = translated.getOrNull(i)?.trim()
                    if (!tr.isNullOrBlank()) {
                        updates[cue.index] = tr
                    }
                }
                if (updates.isNotEmpty()) {
                    onUpdatedCues(updates)
                }
            }
        }
    }

    private fun translateViaGoogleGtx(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        // Delimiter token that survives sentence translation cleanly for batching up to 8 lines
        if (texts.size == 1) {
            return listOf(requestSingleGoogleGtx(texts[0], sourceLang, targetLang))
        }
        val delimiter = "\n[[PV_SEP]]\n"
        val joined = texts.joinToString(delimiter)
        val combinedTranslated = requestSingleGoogleGtx(joined, sourceLang, targetLang)
        val splitParts = combinedTranslated.split(Regex("""\s*\[\[PV_SEP]]\s*""", RegexOption.IGNORE_CASE))
        if (splitParts.size == texts.size) {
            return splitParts.map { it.trim() }
        }
        // Fallback to per-line if separator was merged
        return texts.map { requestSingleGoogleGtx(it, sourceLang, targetLang) }
    }

    private fun requestSingleGoogleGtx(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val sl = sourceLang.ifBlank { "auto" }
        val url = googleGtxUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", sl)
            .addQueryParameter("tl", targetLang)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google GTX HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val outer = JSONArray(body)
            val sentences = outer.optJSONArray(0) ?: throw IllegalStateException("Malformed GTX payload")
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i)
                val part = seg?.optString(0).orEmpty()
                sb.append(part)
            }
            val result = sb.toString().trim()
            if (result.isEmpty()) throw IllegalStateException("Empty GTX translation")
            return result
        }
    }

    private fun translateViaMyMemory(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        return texts.map { line ->
            val effectiveSource = if (sourceLang.isBlank() || sourceLang.equals("auto", ignoreCase = true)) {
                detectHeuristicLanguage(line, fallbackWhenTarget = targetLang)
            } else {
                sourceLang
            }
            if (effectiveSource.equals(targetLang, ignoreCase = true)) {
                line
            } else {
                val url = myMemoryUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("q", line)
                    .addQueryParameter("langpair", "$effectiveSource|$targetLang")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("MyMemory HTTP ${response.code}")
                    }
                    val root = JSONObject(response.body?.string().orEmpty())
                    val translated = root.optJSONObject("responseData")
                        ?.optString("translatedText")
                        ?.trim()
                        .orEmpty()
                    if (translated.isEmpty() || translated.startsWith("MYMEMORY WARNING", ignoreCase = true)) {
                        throw IllegalStateException("MyMemory quota or empty response")
                    }
                    translated
                }
            }
        }
    }

    private suspend fun translateViaMlKitOffline(
        texts: List<String>,
        sourceLang: String,
        targetLang: TranslationLanguage
    ): List<String> {
        val sampleText = texts.joinToString(" ")
        val resolvedSource = if (sourceLang == "auto" || sourceLang.isBlank()) {
            detectHeuristicLanguage(sampleText, targetLang.mlKitCode).substringBefore('-')
        } else {
            sourceLang.substringBefore('-')
        }
        val mlSource = TranslateLanguage.fromLanguageTag(resolvedSource) ?: TranslateLanguage.ENGLISH
        val mlTarget = TranslateLanguage.fromLanguageTag(targetLang.mlKitCode) ?: TranslateLanguage.CHINESE

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlSource)
            .setTargetLanguage(mlTarget)
            .build()
        val translator = Translation.getClient(options)
        return try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            texts.map { line -> translator.translate(line).await() }
        } finally {
            translator.close()
        }
    }

    suspend fun isOfflineModelDownloaded(lang: TranslationLanguage): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val code = TranslateLanguage.fromLanguageTag(lang.mlKitCode) ?: return@runCatching false
            val model = TranslateRemoteModel.Builder(code).build()
            RemoteModelManager.getInstance().isModelDownloaded(model).await()
        }.getOrDefault(false)
    }

    suspend fun downloadOfflineModel(lang: TranslationLanguage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val code = TranslateLanguage.fromLanguageTag(lang.mlKitCode)
                ?: throw IllegalArgumentException("Unsupported ML Kit language: ${lang.mlKitCode}")
            val model = TranslateRemoteModel.Builder(code).build()
            val conditions = DownloadConditions.Builder().build()
            RemoteModelManager.getInstance().download(model, conditions).await()
            Unit
        }
    }

    private fun translateViaOpenAiCompatible(
        texts: List<String>,
        config: TranslationConfig
    ): List<String> {
        if (config.openAiApiKey.isBlank() && !config.openAiBaseUrl.contains("localhost") && !config.openAiBaseUrl.contains("127.0.0.1")) {
            throw IllegalStateException("请先在设置中填写 AI 大模型 API Key")
        }
        val endpoint = config.openAiBaseUrl.trimEnd('/') + "/chat/completions"
        val inputItems = JSONArray()
        texts.forEachIndexed { idx, line ->
            inputItems.put(JSONObject().put("id", idx).put("text", line))
        }

        val systemPrompt = """
            You are a professional movie and TV subtitle translator.
            Translate each subtitle line naturally and concisely into ${config.targetLanguage.label} (${config.targetLanguage.isoCode}).
            Return ONLY a valid JSON array of strings in the exact same order and length as the input array. No markdown fences.
        """.trimIndent()

        val payload = JSONObject()
            .put("model", config.openAiModel.ifBlank { "gpt-4o-mini" })
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", inputItems.toString()))
            )

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .apply {
                if (config.openAiApiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.openAiApiKey.trim()}")
                }
            }
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("AI API HTTP ${response.code}")
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsedArray = JSONArray(content)
            return List(texts.size) { idx ->
                parsedArray.optString(idx, texts[idx]).trim()
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 PadVideo/1.0"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        /**
         * Fast script-based source language detection for endpoints requiring explicit source codes.
         */
        fun detectHeuristicLanguage(text: String, fallbackWhenTarget: String = "zh-CN"): String {
            var cjkCount = 0
            var kanaCount = 0
            var hangulCount = 0
            var cyrillicCount = 0
            for (ch in text) {
                when (ch.code) {
                    in 0x3040..0x30FF -> kanaCount++
                    in 0xAC00..0xD7AF -> hangulCount++
                    in 0x4E00..0x9FFF -> cjkCount++
                    in 0x0400..0x04FF -> cyrillicCount++
                }
            }
            return when {
                kanaCount > 0 -> "ja"
                hangulCount > 0 -> "ko"
                cjkCount > 0 -> "zh-CN"
                cyrillicCount > 1 -> "ru"
                else -> if (fallbackWhenTarget.startsWith("en", ignoreCase = true)) "zh-CN" else "en"
            }
        }
    }
}
