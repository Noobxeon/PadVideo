package com.padvideo.data.repository

import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.OnlineSubtitleResult
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleProviderSource
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage
import com.padvideo.subtitle.EncodingDetector
import com.padvideo.subtitle.SubtitleParser
import com.padvideo.subtitle.VideoFilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

data class SavedSubtitleBundle(
    val savedFile: File,
    val cues: List<SubtitleCue>
)

class OnlineSubtitleRepository(
    private val subtitleStorageDir: File,
    private val translationRepository: RealtimeTranslationRepository? = null,
    private val httpClient: OkHttpClient = RealtimeTranslationRepository.defaultHttpClient(),
    private val cinemetaBaseUrl: String = "https://v3-cinemeta.strem.io",
    private val openSubPublicBaseUrl: String = "https://opensubtitles-v3.strem.io",
    private val shooterApiUrl: String = "https://www.shooter.cn/api/subapi.php",
    private val openSubRestBaseUrl: String = "https://api.opensubtitles.com/api/v1",
    private val assrtBaseUrl: String = "https://api.assrt.net/v1",
    private val subDlBaseUrl: String = "https://api.subdl.com/api/v1",
    private val subDlDownloadHost: String = "https://dl.subdl.com"
) {

    suspend fun searchSubtitles(
        rawQuery: String,
        videoFilePath: String? = null,
        preferredLanguageFilter: String = "all",
        settings: AppSettings = AppSettings()
    ): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val parsed = VideoFilenameParser.parse(rawQuery)
        val localFile = videoFilePath?.let { File(it) }?.takeIf { it.exists() && it.isFile }

        val allResults = coroutineScope {
            val tasks = buildList {
                // 1. Shooter.cn Exact File Hash Match (Zero API key, Chinese & English fansubs)
                if (localFile != null && localFile.length() >= 8192L) {
                    add(async { runCatching { searchShooterByHash(localFile) }.getOrDefault(emptyList()) })
                }

                // 2. OpenSubtitles Global Public Index via Cinemeta IMDB resolver (Zero API key)
                if (parsed.cleanTitle.isNotBlank()) {
                    add(async { runCatching { searchOpenSubtitlesPublic(parsed) }.getOrDefault(emptyList()) })
                }

                // 3. Official OpenSubtitles REST API v1 (if API key configured)
                if (settings.openSubtitlesApiKey.isNotBlank()) {
                    add(async {
                        runCatching { searchOpenSubtitlesRestV1(parsed, localFile, settings) }
                            .getOrDefault(emptyList())
                    })
                }

                // 4. Assrt 伪射手网 API v1 (if token configured)
                if (settings.assrtApiToken.isNotBlank() && parsed.cleanTitle.isNotBlank()) {
                    add(async {
                        runCatching { searchAssrt(parsed.cleanTitle, settings.assrtApiToken.trim()) }
                            .getOrDefault(emptyList())
                    })
                }

                // 5. SubDL REST API v1 (if API key configured)
                if (settings.subDlApiKey.isNotBlank() && parsed.cleanTitle.isNotBlank()) {
                    add(async {
                        runCatching { searchSubDl(parsed, settings.subDlApiKey.trim()) }
                            .getOrDefault(emptyList())
                    })
                }
            }
            tasks.awaitAll().flatten()
        }

        val filtered = if (preferredLanguageFilter == "all" || preferredLanguageFilter.isBlank()) {
            allResults
        } else {
            allResults.filter { item ->
                matchesLanguageFilter(item.languageCode, preferredLanguageFilter)
            }
        }

        // Deduplicate & rank: exact hash match first -> Chinese -> English -> others -> download count
        filtered
            .distinctBy { "${it.source}_${it.downloadUrl}_${it.openSubtitlesFileId}_${it.assrtSubId}_${it.fileName}" }
            .sortedWith(
                compareByDescending<OnlineSubtitleResult> { it.isHashMatch }
                    .thenByDescending { languagePriorityScore(it.languageCode) }
                    .thenByDescending { it.downloadCount }
            )
    }

    private fun searchShooterByHash(videoFile: File): List<OnlineSubtitleResult> {
        val shooterHash = VideoFilenameParser.computeShooterHash(videoFile) ?: return emptyList()
        val results = mutableListOf<OnlineSubtitleResult>()

        listOf("Chn" to "zh-CN", "Eng" to "en").forEach { (shooterLang, isoLang) ->
            val formBody = FormBody.Builder()
                .add("filehash", shooterHash)
                .add("pathinfo", videoFile.absolutePath)
                .add("format", "json")
                .add("lang", shooterLang)
                .build()

            val request = Request.Builder()
                .url(shooterApiUrl)
                .header("User-Agent", USER_AGENT)
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val bodyBytes = response.body?.bytes() ?: return@use
                if (bodyBytes.isEmpty() || (bodyBytes.size == 1 && bodyBytes[0] == 0xFF.toByte())) {
                    return@use
                }
                val text = String(bodyBytes, Charsets.UTF_8).trim()
                if (!text.startsWith("[")) return@use
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val delay = item.optInt("Delay", 0)
                    val files = item.optJSONArray("Files") ?: continue
                    for (j in 0 until files.length()) {
                        val fileObj = files.optJSONObject(j) ?: continue
                        val ext = fileObj.optString("Ext", "srt").lowercase(Locale.ROOT)
                        val rawLink = fileObj.optString("Link", "")
                        val link = if (rawLink.startsWith("http://") && rawLink.contains("shooter.cn", ignoreCase = true)) {
                            rawLink.replaceFirst("http://", "https://")
                        } else {
                            rawLink
                        }
                        if (link.isNotBlank()) {
                            val baseTitle = videoFile.nameWithoutExtension
                            results.add(
                                OnlineSubtitleResult(
                                    id = "shooter_${isoLang}_${i}_$j",
                                    title = "$baseTitle (精确帧同步匹配${if (delay != 0) " · 偏移 ${delay}ms" else ""})",
                                    fileName = "$baseTitle.$isoLang.$ext",
                                    languageCode = isoLang,
                                    languageLabel = mapLanguageLabel(isoLang),
                                    format = ext,
                                    source = SubtitleProviderSource.SHOOTER_HASH,
                                    downloadUrl = link,
                                    isHashMatch = true,
                                    downloadCount = 9999 - i
                                )
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    private suspend fun searchOpenSubtitlesPublic(parsed: com.padvideo.subtitle.CleanedVideoQuery): List<OnlineSubtitleResult> {
        val candidateQueries = mutableListOf(parsed.cleanTitle)
        // If user searched in Chinese (e.g. "盗梦空间"), also translate query to English ("Inception") for global IMDB match
        if (parsed.cleanTitle.any { it.code in 0x4E00..0x9FFF } && translationRepository != null) {
            val translatedTitle = translationRepository.translateSingle(
                parsed.cleanTitle,
                TranslationConfig(
                    engine = TranslationEngineType.AUTO_CLOUD,
                    sourceLanguageCode = "zh-CN",
                    targetLanguage = TranslationLanguage.EN
                )
            ).getOrNull()?.trim()
            if (!translatedTitle.isNullOrBlank() && !translatedTitle.equals(parsed.cleanTitle, ignoreCase = true)) {
                candidateQueries.add(0, translatedTitle)
            }
        }

        val catalogType = if (parsed.isTvEpisode) "series" else "movie"
        var imdbId: String? = null
        var matchedMediaName: String = parsed.cleanTitle

        for (q in candidateQueries) {
            val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            val searchUrl = "${cinemetaBaseUrl.trimEnd('/')}/catalog/$catalogType/top/search=$encoded.json"
            val req = Request.Builder().url(searchUrl).header("User-Agent", USER_AGENT).get().build()
            runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val metas = JSONObject(resp.body?.string().orEmpty()).optJSONArray("metas")
                        if (metas != null && metas.length() > 0) {
                            // Prefer year match if year is known
                            var chosen = metas.optJSONObject(0)
                            if (parsed.year != null) {
                                val yearStr = parsed.year.toString()
                                for (k in 0 until metas.length()) {
                                    val candidate = metas.optJSONObject(k) ?: continue
                                    if (candidate.optString("releaseInfo").contains(yearStr)) {
                                        chosen = candidate
                                        break
                                    }
                                }
                            }
                            imdbId = chosen?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
                                ?: chosen?.optString("id")?.takeIf { it.startsWith("tt") }
                            matchedMediaName = chosen?.optString("name")?.ifBlank { q } ?: q
                        }
                    }
                }
            }
            if (imdbId != null) break
        }

        val resolvedImdb = imdbId ?: return emptyList()
        val resourcePath = if (parsed.isTvEpisode) {
            "$resolvedImdb:${parsed.season ?: 1}:${parsed.episode ?: 1}"
        } else {
            resolvedImdb
        }
        val subUrl = "${openSubPublicBaseUrl.trimEnd('/')}/subtitles/$catalogType/$resourcePath.json"
        val subReq = Request.Builder().url(subUrl).header("User-Agent", USER_AGENT).get().build()

        val results = mutableListOf<OnlineSubtitleResult>()
        httpClient.newCall(subReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val subs = JSONObject(resp.body?.string().orEmpty()).optJSONArray("subtitles") ?: return emptyList()
            for (i in 0 until subs.length()) {
                val obj = subs.optJSONObject(i) ?: continue
                val dlUrl = obj.optString("url")
                if (dlUrl.isBlank()) continue
                val rawLang = obj.optString("lang", "eng")
                val normLang = normalizeLangCode(rawLang)
                val subId = obj.optString("id", "$i")
                val fileName = obj.optString("subtitleFileName")
                    .ifBlank { "$matchedMediaName.$normLang.$subId.srt" }
                results.add(
                    OnlineSubtitleResult(
                        id = "os_pub_$subId",
                        title = "$matchedMediaName (${mapLanguageLabel(normLang)})",
                        fileName = fileName,
                        languageCode = normLang,
                        languageLabel = mapLanguageLabel(normLang),
                        format = fileName.substringAfterLast('.', "srt").lowercase(Locale.ROOT),
                        source = SubtitleProviderSource.OPENSUBTITLES_PUBLIC,
                        downloadUrl = dlUrl,
                        downloadCount = (subs.length() - i) * 15
                    )
                )
            }
        }
        return results
    }

    private fun searchOpenSubtitlesRestV1(
        parsed: com.padvideo.subtitle.CleanedVideoQuery,
        localFile: File?,
        settings: AppSettings
    ): List<OnlineSubtitleResult> {
        val urlBuilder = "${openSubRestBaseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", parsed.cleanTitle)
        localFile?.let { f ->
            VideoFilenameParser.computeOpenSubtitlesHash(f)?.let { hash ->
                urlBuilder.addQueryParameter("moviehash", hash)
            }
        }
        parsed.year?.let { urlBuilder.addQueryParameter("year", it.toString()) }
        parsed.season?.let { urlBuilder.addQueryParameter("season_number", it.toString()) }
        parsed.episode?.let { urlBuilder.addQueryParameter("episode_number", it.toString()) }

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("Api-Key", settings.openSubtitlesApiKey.trim())
            .header("User-Agent", "PadVideo v1.0.0")
            .header("Accept", "application/json")
            .get()
            .build()

        val results = mutableListOf<OnlineSubtitleResult>()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val attrs = item.optJSONObject("attributes") ?: continue
                val lang = normalizeLangCode(attrs.optString("language", "en"))
                val dlCount = attrs.optInt("download_count", 0)
                val hashMatch = attrs.optBoolean("moviehash_match", false)
                val rating = attrs.optDouble("ratings", 0.0)
                val files = attrs.optJSONArray("files") ?: continue
                val firstFile = files.optJSONObject(0) ?: continue
                val fileId = firstFile.optInt("file_id", -1)
                if (fileId <= 0) continue
                val fileName = firstFile.optString("file_name", "${parsed.cleanTitle}.$lang.srt")
                results.add(
                    OnlineSubtitleResult(
                        id = "os_rest_$fileId",
                        title = attrs.optString("release").ifBlank { fileName },
                        fileName = if (fileName.contains('.')) fileName else "$fileName.srt",
                        languageCode = lang,
                        languageLabel = mapLanguageLabel(lang),
                        format = fileName.substringAfterLast('.', "srt").lowercase(Locale.ROOT),
                        source = SubtitleProviderSource.OPENSUBTITLES_REST,
                        openSubtitlesFileId = fileId,
                        downloadCount = dlCount,
                        isHashMatch = hashMatch,
                        rating = rating
                    )
                )
            }
        }
        return results
    }

    private fun searchAssrt(query: String, token: String): List<OnlineSubtitleResult> {
        val url = "${assrtBaseUrl.trimEnd('/')}/sub/search".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("q", query)
            .addQueryParameter("cnt", "15")
            .build()

        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val results = mutableListOf<OnlineSubtitleResult>()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val subs = JSONObject(resp.body?.string().orEmpty())
                .optJSONObject("sub")
                ?.optJSONArray("subs") ?: return emptyList()
            for (i in 0 until subs.length()) {
                val item = subs.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id <= 0) continue
                val nativeName = item.optString("native_name").ifBlank { item.optString("videoname", query) }
                val subtype = item.optString("subtype", "srt").lowercase(Locale.ROOT)
                val langDesc = item.optJSONObject("lang")?.optString("desc").orEmpty()
                val langCode = when {
                    langDesc.contains("双语") || langDesc.contains("简") -> "zh-CN"
                    langDesc.contains("繁") -> "zh-TW"
                    langDesc.contains("英") -> "en"
                    else -> "zh-CN"
                }
                results.add(
                    OnlineSubtitleResult(
                        id = "assrt_$id",
                        title = if (langDesc.isNotBlank()) "$nativeName [$langDesc]" else nativeName,
                        fileName = "$nativeName.$subtype",
                        languageCode = langCode,
                        languageLabel = langDesc.ifBlank { mapLanguageLabel(langCode) },
                        format = subtype,
                        source = SubtitleProviderSource.ASSRT,
                        assrtSubId = id,
                        downloadCount = item.optInt("down_count", 100)
                    )
                )
            }
        }
        return results
    }

    private fun searchSubDl(
        parsed: com.padvideo.subtitle.CleanedVideoQuery,
        apiKey: String
    ): List<OnlineSubtitleResult> {
        val urlBuilder = "${subDlBaseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("film_name", parsed.cleanTitle)
            .addQueryParameter("subs_per_page", "20")
        parsed.year?.let { urlBuilder.addQueryParameter("year", it.toString()) }
        parsed.season?.let { urlBuilder.addQueryParameter("season_number", it.toString()) }
        parsed.episode?.let { urlBuilder.addQueryParameter("episode_number", it.toString()) }

        val req = Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT).get().build()
        val results = mutableListOf<OnlineSubtitleResult>()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(resp.body?.string().orEmpty())
            val subtitlesArr = root.optJSONArray("subtitles")
                ?: root.optJSONArray("result")?.optJSONObject(0)?.optJSONArray("subtitles")
                ?: return emptyList()

            for (i in 0 until subtitlesArr.length()) {
                val sub = subtitlesArr.optJSONObject(i) ?: continue
                val relUrl = sub.optString("url")
                if (relUrl.isBlank()) continue
                val fullUrl = if (relUrl.startsWith("http")) relUrl else "${subDlDownloadHost.trimEnd('/')}/${relUrl.trimStart('/')}"
                val lang = normalizeLangCode(sub.optString("lang", sub.optString("language", "en")))
                val releaseName = sub.optString("release_name").ifBlank { sub.optString("name", "${parsed.cleanTitle}.srt") }
                results.add(
                    OnlineSubtitleResult(
                        id = "subdl_$i",
                        title = releaseName,
                        fileName = if (releaseName.endsWith(".srt") || releaseName.endsWith(".ass")) releaseName else "$releaseName.srt",
                        languageCode = lang,
                        languageLabel = mapLanguageLabel(lang),
                        format = "srt",
                        source = SubtitleProviderSource.SUBDL,
                        downloadUrl = fullUrl,
                        downloadCount = 200 - i
                    )
                )
            }
        }
        return results
    }

    suspend fun downloadAndSaveSubtitle(
        item: OnlineSubtitleResult,
        targetVideoBaseName: String,
        settings: AppSettings = AppSettings(),
        autoGenerateBilingual: Boolean = false
    ): Result<SavedSubtitleBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedUrl = resolveDirectDownloadUrl(item, settings)
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val rawBytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("字幕下载失败 (HTTP ${resp.code})")
                }
                resp.body?.bytes() ?: throw IllegalStateException("字幕内容为空")
            }

            val (unpackedBytes, unpackedName) = SubtitleParser.unpackArchiveIfNeeded(rawBytes, item.fileName)
            val decodedText = EncodingDetector.decodeBytes(unpackedBytes)
            var cues = SubtitleParser.parseText(decodedText, unpackedName)
            if (cues.isEmpty()) {
                throw IllegalStateException("未能从下载文件中解析出有效字幕时间轴")
            }

            if (autoGenerateBilingual && translationRepository != null) {
                val config = settings.translationConfig
                val translatedMap = mutableMapOf<Int, String>()
                cues.chunked(12).forEach { chunk ->
                    val batch = translationRepository.translateBatch(
                        chunk.map { it.originalText.replace('\n', ' ') },
                        config
                    ).getOrNull()
                    if (batch != null) {
                        chunk.forEachIndexed { idx, cue ->
                            batch.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { tr ->
                                translatedMap[cue.index] = tr
                            }
                        }
                    }
                }
                cues = cues.map { c -> c.copy(translatedText = translatedMap[c.index] ?: c.translatedText) }
            }

            subtitleStorageDir.mkdirs()
            val safeBase = targetVideoBaseName
                .substringBeforeLast('.')
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .ifBlank { "padvideo_sub" }

            val savedFile = if (autoGenerateBilingual) {
                File(subtitleStorageDir, "$safeBase.bilingual.${settings.translationConfig.targetLanguage.isoCode}.srt").also {
                    it.writeText(SubtitleParser.exportToSrt(cues, includeTranslation = true), Charsets.UTF_8)
                }
            } else {
                val ext = unpackedName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
                    .takeIf { it in setOf("srt", "ass", "ssa", "vtt") } ?: "srt"
                File(subtitleStorageDir, "$safeBase.${item.languageCode}.$ext").also {
                    it.writeText(decodedText, Charsets.UTF_8)
                }
            }

            SavedSubtitleBundle(savedFile = savedFile, cues = cues)
        }
    }

    private fun resolveDirectDownloadUrl(item: OnlineSubtitleResult, settings: AppSettings): String {
        if (item.downloadUrl.isNotBlank()) return item.downloadUrl

        if (item.source == SubtitleProviderSource.OPENSUBTITLES_REST && item.openSubtitlesFileId != null) {
            val payload = JSONObject().put("file_id", item.openSubtitlesFileId).toString()
            val reqBuilder = Request.Builder()
                .url("${openSubRestBaseUrl.trimEnd('/')}/download")
                .header("Api-Key", settings.openSubtitlesApiKey.trim())
                .header("User-Agent", "PadVideo v1.0.0")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
            if (settings.openSubtitlesJwtToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${settings.openSubtitlesJwtToken.trim()}")
            }
            val req = reqBuilder.post(payload.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("OpenSubtitles 下载授权失败 (${resp.code})")
                val link = JSONObject(resp.body?.string().orEmpty()).optString("link")
                if (link.isBlank()) throw IllegalStateException("OpenSubtitles 未返回下载链接")
                return link
            }
        }

        if (item.source == SubtitleProviderSource.ASSRT && item.assrtSubId != null) {
            val url = "${assrtBaseUrl.trimEnd('/')}/sub/detail".toHttpUrl().newBuilder()
                .addQueryParameter("token", settings.assrtApiToken.trim())
                .addQueryParameter("id", item.assrtSubId.toString())
                .build()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Assrt 详情获取失败 (${resp.code})")
                val subObj = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("sub")
                    ?.optJSONArray("subs")
                    ?.optJSONObject(0)
                val directFile = subObj?.optJSONArray("filelist")?.optJSONObject(0)?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                    ?: subObj?.optString("url")
                if (directFile.isNullOrBlank()) throw IllegalStateException("Assrt 未返回下载链接")
                return directFile
            }
        }

        throw IllegalStateException("无效的字幕下载地址")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 PadVideo/1.0"

        fun normalizeLangCode(raw: String): String {
            return when (raw.lowercase(Locale.ROOT).trim()) {
                "zho", "chi", "chs", "zh-cn", "zh-hans", "zh", "chinese" -> "zh-CN"
                "zht", "cht", "zh-tw", "zh-hant", "zh-hk" -> "zh-TW"
                "eng", "en", "english" -> "en"
                "jpn", "ja", "japanese" -> "ja"
                "kor", "ko", "korean" -> "ko"
                "fra", "fre", "fr", "french" -> "fr"
                "deu", "ger", "de", "german" -> "de"
                "spa", "es", "spanish" -> "es"
                "rus", "ru", "russian" -> "ru"
                else -> raw.lowercase(Locale.ROOT)
            }
        }

        fun mapLanguageLabel(code: String): String {
            return when (normalizeLangCode(code)) {
                "zh-CN" -> "简体中文"
                "zh-TW" -> "繁體中文"
                "en" -> "English"
                "ja" -> "日本語"
                "ko" -> "한국어"
                "fr" -> "Français"
                "de" -> "Deutsch"
                "es" -> "Español"
                "ru" -> "Русский"
                else -> code.uppercase(Locale.ROOT)
            }
        }

        private fun matchesLanguageFilter(itemLang: String, filterLang: String): Boolean {
            val normItem = normalizeLangCode(itemLang)
            val normFilter = normalizeLangCode(filterLang)
            if (normFilter == "zh-CN" && (normItem == "zh-CN" || normItem == "zh-TW")) return true
            return normItem.equals(normFilter, ignoreCase = true)
        }

        private fun languagePriorityScore(code: String): Int {
            return when (normalizeLangCode(code)) {
                "zh-CN" -> 100
                "zh-TW" -> 90
                "en" -> 75
                "ja" -> 60
                "ko" -> 55
                else -> 20
            }
        }
    }
}
