package com.padvideo.repository

import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.SubtitleProviderSource
import com.padvideo.data.repository.OnlineSubtitleRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OnlineSubtitleRepositoryTest {

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
    fun `searchSubtitles aggregates multiple providers ranks hash matches and unpacks GBK ZIP downloads`() = runTest {
        val gbkSrtContent = """
            1
            00:00:02,000 --> 00:00:05,000
            欢迎来到梦境第一层。

            2
            00:00:06,000 --> 00:00:09,500
            时间在这里会变慢。
        """.trimIndent()

        val zippedSubtitleBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("Inception.2010.chs.srt"))
                zos.write(gbkSrtContent.toByteArray(Charset.forName("GB18030")))
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val baseUrl = server.url("/").toString().trimEnd('/')

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/shooter") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """[{"Delay":0,"Files":[{"Ext":"srt","Link":"$baseUrl/dl/shooter_sub.zip"}]}]"""
                            )
                    }
                    path.contains("/catalog/movie/top/search=") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"metas":[{"id":"tt1375666","imdb_id":"tt1375666","name":"Inception","releaseInfo":"2010"}]}""")
                    }
                    path.contains("/subtitles/movie/tt1375666.json") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"subtitles":[{"id":"991","url":"$baseUrl/dl/os_en.srt","lang":"eng","subtitleFileName":"Inception.2010.en.srt"}]}"""
                            )
                    }
                    path.startsWith("/subdl/subtitles") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"status":true,"subtitles":[{"url":"/dl/shooter_sub.zip","lang":"zh","release_name":"Inception.2010.BluRay.chs"}]}"""
                            )
                    }
                    path.startsWith("/dl/shooter_sub.zip") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(Buffer().write(zippedSubtitleBytes))
                    }
                    path.startsWith("/dl/os_en.srt") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("1\n00:00:01,000 --> 00:00:03,000\nDream within a dream.\n")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val dummyMovie = tempFolder.newFile("Inception.2010.1080p.BluRay.mkv").apply {
            writeBytes(ByteArray(32_768) { (it % 127).toByte() })
        }
        val saveDir = tempFolder.newFolder("SavedSubs")

        val repo = OnlineSubtitleRepository(
            subtitleStorageDir = saveDir,
            translationRepository = null,
            cinemetaBaseUrl = baseUrl,
            openSubPublicBaseUrl = baseUrl,
            shooterApiUrl = "$baseUrl/shooter",
            subDlBaseUrl = "$baseUrl/subdl",
            subDlDownloadHost = baseUrl
        )

        val results = repo.searchSubtitles(
            rawQuery = dummyMovie.name,
            videoFilePath = dummyMovie.absolutePath,
            settings = AppSettings(subDlApiKey = "test_subdl_key")
        )

        assertTrue(results.size >= 3)
        // Shooter exact hash match should be ranked #1
        assertEquals(SubtitleProviderSource.SHOOTER_HASH, results.first().source)
        assertTrue(results.first().isHashMatch)

        val savedBundle = repo.downloadAndSaveSubtitle(
            item = results.first(),
            targetVideoBaseName = dummyMovie.name
        ).getOrThrow()

        assertTrue(savedBundle.savedFile.exists())
        assertEquals(2, savedBundle.cues.size)
        assertEquals("欢迎来到梦境第一层。", savedBundle.cues[0].originalText)
    }
}
