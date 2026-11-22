package com.padvideo.data.repository

import android.util.AtomicFile
import android.util.LruCache
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RealtimeTranslationRepository(
    private val cacheDirectory: File? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val googleGtxUrl: String = "https://translate.googleapis.com/translate_a/single",
    private val myMemoryUrl: String = "https://api.mymemory.translated.net/get",
    private val bingPageUrl: String = "https://cn.bing.com/translator",
    private val bingTranslateBaseUrl: String = "https://cn.bing.com/ttranslatev3",
    private val youdaoUrl: String = "https://m.youdao.com/translate"
) {

    private data class BingSessionToken(
        val ig: String,
        val key: String,
        val token: String,
        val fetchedAtMs: Long
    )

    private val languageIdentifier by lazy { runCatching { LanguageIdentification.getClient() }.getOrNull() }
    private val offlineLock = Mutex()
    private var offlineClient: Translator? = null
    private var offlineLanguagePair: Pair<String, String>? = null
    @Volatile
    private var lastDetectedMlKitSource: String? = null

    private val bingTokenLock = Any()
    @Volatile
    private var cachedBingToken: BingSessionToken? = null
    private val providerCooldownUntilMs = ConcurrentHashMap<String, Long>()

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryCache = LruCache<String, String>(2048)
    private val diskCacheMap = linkedMapOf<String, String>()
    private val cacheLock = Any()
    private val diskWriteLock = Any()
    private val diskCacheFile = cacheDirectory?.let { AtomicFile(File(it, "translation_lru_cache.json")) }

    init {
        loadDiskCache()
    }

    private fun loadDiskCache() {
        val file = diskCacheFile ?: return
        if (!file.baseFile.exists()) return
        runCatching {
            val map = json.decodeFromString<Map<String, String>>(file.readFully().toString(Charsets.UTF_8))
            synchronized(cacheLock) {
                diskCacheMap.putAll(map)
                map.forEach { (k, v) -> memoryCache.put(k, v) }
            }
        }
    }

    private fun persistDiskCacheSnapshot() {
        val file = diskCacheFile ?: return
        synchronized(diskWriteLock) {
            val snapshot = synchronized(cacheLock) {
                if (diskCacheMap.size > 1500) {
                    val keysToDrop = diskCacheMap.keys.take(diskCacheMap.size - 1200)
                    keysToDrop.forEach { diskCacheMap.remove(it) }
                }
                LinkedHashMap(diskCacheMap)
            }
            runCatching {
                file.baseFile.parentFile?.mkdirs()
                val output = file.startWrite()
                try {
                    output.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
        }
    }

    private fun buildCacheKey(config: TranslationConfig, text: String): String {
        val engineKey = when (config.engine) {
            TranslationEngineType.MLKIT_OFFLINE -> "MLKIT_OFFLINE_V3"
            TranslationEngineType.OPENAI_COMPATIBLE -> "${config.engine.name}|${config.openAiBaseUrl}|${config.openAiModel}"
            else -> config.engine.name
        }
        return "$engineKey|${config.sourceLanguageCode}|${config.targetLanguage.isoCode}|${text.trim()}"
    }

    fun getCachedTranslation(
        text: String,
        config: TranslationConfig
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val key = buildCacheKey(config, trimmed)
        return synchronized(cacheLock) { memoryCache.get(key) ?: diskCacheMap[key] }
    }

    private fun putCache(
        config: TranslationConfig,
        originalText: String,
        translatedText: String
    ) {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty() || translatedText.isBlank()) return
        val key = buildCacheKey(config, trimmed)
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
                    translateViaAutoCloudCascade(uncachedTexts, sourceCode, config.targetLanguage)
                }
                TranslationEngineType.BING_NEURAL -> {
                    translateViaBingNeural(uncachedTexts, sourceCode, config.targetLanguage)
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

        coroutineContext.ensureActive()
        fetchedResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        fetchedResult.map { translatedList ->
            uncachedIndices.forEachIndexed { localIdx, originalIdx ->
                val trans = translatedList.getOrNull(localIdx)?.trim().orEmpty()
                results[originalIdx] = trans.ifEmpty { texts[originalIdx] }
                if (trans.isNotEmpty()) {
                    putCache(config, uncachedTexts[localIdx], trans)
                }
            }
            persistDiskCacheSnapshot()
            results
        }
    }

    suspend fun translateCueWindow(
        cues: List<SubtitleCue>,
        startIndex: Int,
        windowSize: Int = 20,
        config: TranslationConfig,
        onUpdatedCues: (Map<Int, String>) -> Unit
    ): Result<Unit> {
        if (startIndex !in cues.indices) return Result.success(Unit)
        val endExclusive = (startIndex + windowSize).coerceAtMost(cues.size)
        val pending = cues.subList(startIndex, endExclusive)
            .filter { it.translatedText.isNullOrBlank() && it.originalText.isNotBlank() }

        val chunkSize = when (config.engine) {
            TranslationEngineType.MLKIT_OFFLINE -> 3
            TranslationEngineType.MYMEMORY -> 4
            else -> 8
        }
        for (chunk in pending.chunked(chunkSize)) {
            val batch = translateBatch(chunk.map { it.originalText.replace('\n', ' ') }, config)
            val translations = batch.getOrElse { return Result.failure(it) }
            val updates = chunk.zip(translations).mapNotNull { (cue, text) ->
                text.trim().takeIf { it.isNotEmpty() }?.let { cue.index to it }
            }.toMap()
            if (updates.isNotEmpty()) onUpdatedCues(updates)
        }
        return Result.success(Unit)
    }

    /**
     * Multi-provider cascade designed to work reliably BOTH in mainland China (no VPN) AND globally:
     * 1. Bing Neural (cn.bing.com, China-direct + global, batch LLM translation)
     * 2. Google GTX (fast global)
     * 3. Youdao Mobile Web (China-direct fallback for ZH/EN/JA/KO)
     * 4. MyMemory (global fallback)
     * Providers that fail enter a 2-minute cooldown so subsequent subtitles never hang.
     */
    private fun translateViaAutoCloudCascade(
        texts: List<String>,
        sourceLang: String,
        targetLang: TranslationLanguage
    ): List<String> {
        val now = System.currentTimeMillis()
        val providers = buildList<Pair<String, () -> List<String>>> {
            add("bing" to { translateViaBingNeural(texts, sourceLang, targetLang) })
            add("gtx" to { translateViaGoogleGtx(texts, sourceLang, targetLang.isoCode) })
            if (targetLang == TranslationLanguage.ZH_CN || targetLang == TranslationLanguage.EN) {
                add("youdao" to { translateViaYoudaoWeb(texts, sourceLang, targetLang) })
            }
            add("mymemory" to { translateViaMyMemory(texts, sourceLang, targetLang.isoCode) })
        }

        // Try healthy providers first; if all are in cooldown, retry them all cleanly
        val healthy = providers.filter { (id, _) -> (providerCooldownUntilMs[id] ?: 0L) <= now }
        val ordered = if (healthy.isNotEmpty()) healthy + providers.filterNot { it in healthy } else providers

        var lastError: Throwable? = null
        for ((id, runner) in ordered) {
            val attempt = runCatching { runner() }
            if (attempt.isSuccess) {
                providerCooldownUntilMs.remove(id)
                return attempt.getOrThrow()
            } else {
                lastError = attempt.exceptionOrNull()
                providerCooldownUntilMs[id] = System.currentTimeMillis() + 120_000L
            }
        }
        throw lastError ?: IllegalStateException("所有在线翻译通道均暂不可用，请检查网络连接")
    }

    private fun fetchBingToken(forceRefresh: Boolean = false): BingSessionToken {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedBingToken?.takeIf { now - it.fetchedAtMs < 20 * 60_000L }?.let { return it }
        }
        synchronized(bingTokenLock) {
            if (!forceRefresh) {
                cachedBingToken?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < 20 * 60_000L }?.let { return it }
            }
            val req = Request.Builder()
                .url(bingPageUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Bing translator page HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                val ig = BING_IG_REGEX.find(html)?.groupValues?.getOrNull(1)
                    ?: throw IllegalStateException("Bing IG token missing")
                val abuse = BING_ABUSE_REGEX.find(html)
                    ?: throw IllegalStateException("Bing auth token missing")
                val token = BingSessionToken(
                    ig = ig,
                    key = abuse.groupValues[1],
                    token = abuse.groupValues[2],
                    fetchedAtMs = System.currentTimeMillis()
                )
                cachedBingToken = token
                return token
            }
        }
    }

    private fun translateViaBingNeural(
        texts: List<String>,
        sourceLang: String,
        targetLang: TranslationLanguage
    ): List<String> {
        val bingTo = mapBingLangCode(targetLang.isoCode)
        val bingFrom = if (sourceLang.isBlank() || sourceLang.equals("auto", true)) {
            "auto-detect"
        } else {
            mapBingLangCode(sourceLang)
        }
        if (texts.size == 1) {
            return listOf(requestBingSingle(texts[0], bingFrom, bingTo))
        }
        val delimiter = "\n[[PV_SEP]]\n"
        val combined = requestBingSingle(texts.joinToString(delimiter), bingFrom, bingTo)
        val parts = combined.split(Regex("""\s*\[\[PV_SEP]]\s*""", RegexOption.IGNORE_CASE))
        if (parts.size == texts.size) {
            return parts.map { it.trim() }
        }
        return texts.map { requestBingSingle(it, bingFrom, bingTo) }
    }

    private fun requestBingSingle(text: String, fromLang: String, toLang: String): String {
        for (attempt in 0..1) {
            val sess = fetchBingToken(forceRefresh = attempt == 1)
            val url = bingTranslateBaseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("isVertical", "1")
                .addQueryParameter("IG", sess.ig)
                .addQueryParameter("IID", "translator.5024")
                .build()
            val form = FormBody.Builder()
                .add("fromLang", fromLang)
                .add("text", text)
                .add("to", toLang)
                .add("token", sess.token)
                .add("key", sess.key)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Referer", bingPageUrl)
                .post(form)
                .build()
            val translatedOrNull: String? = httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (attempt == 0) return@use null
                    throw IllegalStateException("Bing HTTP ${resp.code}")
                }
                if (body.trimStart().startsWith("[")) {
                    val arr = JSONArray(body)
                    val translated = arr.optJSONObject(0)
                        ?.optJSONArray("translations")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.trim()
                        .orEmpty()
                    if (translated.isNotEmpty()) return@use translated
                }
                if (attempt == 0) {
                    cachedBingToken = null
                    return@use null
                }
                throw IllegalStateException("Bing empty or rejected translation payload")
            }
            if (translatedOrNull != null) return translatedOrNull
        }
        throw IllegalStateException("Bing translation failed after retry")
    }

    private fun translateViaYoudaoWeb(
        texts: List<String>,
        sourceLang: String,
        targetLang: TranslationLanguage
    ): List<String> {
        val typeParam = when {
            sourceLang.startsWith("en", true) && targetLang == TranslationLanguage.ZH_CN -> "EN2ZH_CN"
            sourceLang.startsWith("ja", true) && targetLang == TranslationLanguage.ZH_CN -> "JA2ZH_CN"
            sourceLang.startsWith("ko", true) && targetLang == TranslationLanguage.ZH_CN -> "KR2ZH_CN"
            sourceLang.startsWith("zh", true) && targetLang == TranslationLanguage.EN -> "ZH_CN2EN"
            else -> "AUTO"
        }
        return texts.map { line ->
            val form = FormBody.Builder()
                .add("inputtext", line)
                .add("type", typeParam)
                .build()
            val req = Request.Builder()
                .url(youdaoUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Youdao HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                val matched = YOUDAO_RESULT_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val decoded = matched
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace(Regex("<[^>]+>"), "")
                    .trim()
                if (decoded.isEmpty()) throw IllegalStateException("Youdao empty translation")
                decoded
            }
        }
    }

    private fun translateViaGoogleGtx(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
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
        val resolvedSource = if (sourceLang == "auto" || sourceLang.isBlank()) {
            val sample = texts.joinToString(" ")
            val detected = runCatching { languageIdentifier?.identifyLanguage(sample)?.await() }.getOrNull()
            val chosen = if (!detected.isNullOrBlank() && detected != "und") {
                lastDetectedMlKitSource = detected
                detected
            } else {
                lastDetectedMlKitSource ?: detectHeuristicLanguage(sample, targetLang.mlKitCode)
            }
            chosen
        } else {
            sourceLang
        }

        val source = mlKitLanguage(resolvedSource)
        val target = targetLang.mlKitCode
        if (source == target) return texts

        return offlineLock.withLock {
            val translator = offlineTranslator(source, target)
            try {
                texts.map { translator.translate(it).await() }
            } catch (error: MlKitException) {
                if (error.errorCode != MlKitException.NOT_FOUND) throw error
                // Auto-attempt model download once before surfacing actionable error
                val dl = runCatching {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                }
                if (dl.isSuccess) {
                    texts.map { translator.translate(it).await() }
                } else {
                    throw IllegalStateException(
                        "尚未下载 $source → $target 离线模型（下载需访问 Google ML Kit 服务；无代理时建议切换「智能云端多引擎」）",
                        dl.exceptionOrNull() ?: error
                    )
                }
            }
        }
    }

    private fun offlineTranslator(source: String, target: String): Translator {
        val pair = source to target
        if (offlineLanguagePair == pair) offlineClient?.let { return it }

        val translator = createTranslator(source, target)
        offlineClient?.close()
        offlineClient = translator
        offlineLanguagePair = pair
        return translator
    }

    suspend fun getDownloadedOfflineModelCodes(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun downloadOfflineLanguagePack(langTag: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val code = mlKitLanguage(langTag)
            val model = TranslateRemoteModel.Builder(code).build()
            RemoteModelManager.getInstance()
                .download(model, DownloadConditions.Builder().build())
                .await()
            Unit
        }.also { res ->
            res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    suspend fun downloadOfflinePair(config: TranslationConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveSource = if (config.sourceLanguageCode == "auto" || config.sourceLanguageCode.isBlank()) {
                if (config.targetLanguage == TranslationLanguage.EN) "zh" else "en"
            } else {
                config.sourceLanguageCode
            }
            val source = mlKitLanguage(effectiveSource)
            val target = config.targetLanguage.mlKitCode
            if (source != target) {
                offlineLock.withLock {
                    offlineTranslator(source, target)
                        .downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                }
            }
            Unit
        }.also { result ->
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private fun mlKitLanguage(tag: String): String =
        TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
            ?: throw IllegalArgumentException("端侧翻译暂不支持该语言（$tag），请手动选择")

    private fun createTranslator(source: String, target: String): Translator =
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        )

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
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 PadVideo/1.2"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private val BING_IG_REGEX = Regex("""IG:"([A-F0-9]+)"""")
        private val BING_ABUSE_REGEX = Regex("""params_AbusePreventionHelper\s*=\s*\[(\d+),"([^"]+)"""")
        private val YOUDAO_RESULT_REGEX = Regex("""<ul id="translateResult">\s*<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()

        fun mapBingLangCode(iso: String): String = when (iso.lowercase().trim()) {
            "zh-cn", "zh", "zh-hans" -> "zh-Hans"
            "zh-tw", "zh-hant", "zh-hk" -> "zh-Hant"
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "fr" -> "fr"
            "de" -> "de"
            "es" -> "es"
            "ru" -> "ru"
            else -> iso.substringBefore('-')
        }

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
