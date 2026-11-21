package com.padvideo.repository

import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationDisplayMode
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.TranslationLanguage
import com.padvideo.data.repository.RealtimeTranslationRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeTranslationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `AUTO_CLOUD automatically falls back to MyMemory when Google GTX fails and caches on disk`() = runTest {
        val myMemoryCalls = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/gtx") -> {
                        // Simulate network block / failure on Google GTX
                        MockResponse().setResponseCode(503)
                    }
                    path.startsWith("/mymemory") -> {
                        myMemoryCalls.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"responseData":{"translatedText":"欢迎来到未来世界","match":0.99}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val cacheDir = tempFolder.newFolder("trans_cache")
        val baseUrl = server.url("/").toString().trimEnd('/')

        val repo = RealtimeTranslationRepository(
            cacheDirectory = cacheDir,
            googleGtxUrl = "$baseUrl/gtx",
            myMemoryUrl = "$baseUrl/mymemory"
        )

        val config = TranslationConfig(
            engine = TranslationEngineType.AUTO_CLOUD,
            sourceLanguageCode = "en",
            targetLanguage = TranslationLanguage.ZH_CN,
            displayMode = TranslationDisplayMode.BILINGUAL
        )

        val firstRes = repo.translateSingle("Welcome to the future world", config).getOrThrow()
        assertEquals("欢迎来到未来世界", firstRes)
        assertEquals(1, myMemoryCalls.get())

        // Second translation of same line hits LRU cache with zero extra network calls
        val secondRes = repo.translateSingle("Welcome to the future world", config).getOrThrow()
        assertEquals("欢迎来到未来世界", secondRes)
        assertEquals(1, myMemoryCalls.get())

        // Re-instantiating repository reads persisted disk cache
        val reloadedRepo = RealtimeTranslationRepository(
            cacheDirectory = cacheDir,
            googleGtxUrl = "$baseUrl/gtx",
            myMemoryUrl = "$baseUrl/mymemory"
        )
        assertEquals("欢迎来到未来世界", reloadedRepo.getCachedTranslation("Welcome to the future world", config))
    }

    @Test
    fun `OPENAI_COMPATIBLE translates batch of movie dialogue cues ahead of playhead`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "[\"抓紧时间，科布先生。\", \"梦境正在崩塌！\"]"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/v1").toString().trimEnd('/')
        val repo = RealtimeTranslationRepository(cacheDirectory = tempFolder.newFolder("ai_cache"))

        val config = TranslationConfig(
            engine = TranslationEngineType.OPENAI_COMPATIBLE,
            targetLanguage = TranslationLanguage.ZH_CN,
            displayMode = TranslationDisplayMode.BILINGUAL,
            openAiBaseUrl = baseUrl,
            openAiApiKey = "sk-test-key",
            openAiModel = "deepseek-chat"
        )

        val cues = listOf(
            SubtitleCue(1, 1000L, 3000L, "Hurry up, Mr. Cobb."),
            SubtitleCue(2, 3500L, 6000L, "The dream is collapsing!")
        )

        val updates = mutableMapOf<Int, String>()
        repo.translateCueWindow(
            cues = cues,
            startIndex = 0,
            windowSize = 10,
            config = config,
            onUpdatedCues = { batch -> updates.putAll(batch) }
        )

        assertTrue(updates.containsKey(1))
        assertEquals("抓紧时间，科布先生。", updates[1])
        assertEquals("梦境正在崩塌！", updates[2])
    }
    @Test
    fun `offline same language completes without a model or cloud request`() = runTest {
        val repo = RealtimeTranslationRepository()
        val config = TranslationConfig(
            engine = TranslationEngineType.MLKIT_OFFLINE,
            sourceLanguageCode = "en",
            targetLanguage = TranslationLanguage.EN
        )

        repo.downloadOfflinePair(config).getOrThrow()
        assertEquals("Hello", repo.translateSingle("Hello", config).getOrThrow())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `prepare offline models requires an explicit source language`() = runTest {
        val repo = RealtimeTranslationRepository()
        val config = TranslationConfig(engine = TranslationEngineType.MLKIT_OFFLINE)

        assertTrue(repo.downloadOfflinePair(config).exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `failed lookahead reports the failure without publishing translations`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val repo = RealtimeTranslationRepository(googleGtxUrl = server.url("/gtx").toString())
        val config = TranslationConfig(engine = TranslationEngineType.GOOGLE_GTX)
        val updates = mutableMapOf<Int, String>()

        val result = repo.translateCueWindow(
            cues = listOf(SubtitleCue(1, 0, 1000, "First line")),
            startIndex = 0,
            config = config,
            onUpdatedCues = updates::putAll
        )

        assertTrue(result.isFailure)
        assertTrue(updates.isEmpty())
    }

}
