package com.padvideo.player

import androidx.media3.common.util.UnstableApi
import com.padvideo.data.model.BatchSubtitleOutputFormat
import com.padvideo.data.model.BatchSubtitleTarget
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleWriteDestination
import com.padvideo.data.model.VideoItem
import com.padvideo.data.preferences.AppSettingsRepository
import com.padvideo.data.repository.SmbRepository
import com.padvideo.data.repository.WebDavRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(UnstableApi::class)
class BatchSubtitlePipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `vad segmenter splits two utterances separated by silence with accurate timestamps`() {
        val segmenter = SileroVadSegmenter(
            assetManager = null,
            useSileroVad = false,
            sampleRate = 16_000,
            minSpeechDurationSec = 0.25f,
            minSilenceDurationSec = 0.40f
        )

        // Build: 0.5s silence + 1.0s tone + 0.6s silence + 1.0s tone + 0.5s silence = 3.6s @ 16kHz
        val totalSamples = (3.6f * 16_000).toInt()
        val audio = FloatArray(totalSamples)
        fun fillTone(startSec: Float, durSec: Float) {
            val s = (startSec * 16_000).toInt()
            val len = (durSec * 16_000).toInt()
            for (i in 0 until len) {
                audio[s + i] = (sin(2.0 * PI * 350.0 * i / 16_000.0) * 0.4).toFloat()
            }
        }
        fillTone(0.5f, 1.0f)
        fillTone(2.1f, 1.0f)

        val emitted = mutableListOf<TimedSpeechSegment>()
        segmenter.use { vad ->
            emitted.addAll(vad.acceptSamples(audio))
            emitted.addAll(vad.flush())
        }

        assertEquals("Expected 2 distinct speech segments", 2, emitted.size)
        assertTrue("First start should be near 500ms, got ${emitted[0].startTimeMs}", emitted[0].startTimeMs in 400L..650L)
        assertTrue("First end should be near 1500ms, got ${emitted[0].endTimeMs}", emitted[0].endTimeMs in 1350L..1950L)
        assertTrue("Second start should be near 2100ms, got ${emitted[1].startTimeMs}", emitted[1].startTimeMs in 2000L..2250L)
        assertTrue("Second end should be > 2800ms, got ${emitted[1].endTimeMs}", emitted[1].endTimeMs > 2800L)
    }

    @Test
    fun `subtitle cue refiner splits long multi-sentence cue and fixes overlaps`() {
        val raw = listOf(
            SubtitleCue(
                index = 1,
                startTimeMs = 1_000L,
                endTimeMs = 9_000L,
                originalText = "We are approaching the outer atmosphere now. Please fasten your seatbelts immediately! Prepare for landing sequence."
            ),
            SubtitleCue(
                index = 2,
                startTimeMs = 8_500L, // Deliberate overlap with cue 1
                endTimeMs = 11_000L,
                originalText = "Copy that, control."
            )
        )

        val refined = SubtitleCueRefiner.refineCues(raw, maxCharsPerCue = 50)
        assertTrue("Expected long cue to split into multiple readable cues", refined.size >= 3)
        for (i in 0 until refined.lastIndex) {
            assertTrue(
                "Cues must be strictly non-overlapping: ${refined[i].endTimeMs} <= ${refined[i + 1].startTimeMs}",
                refined[i].endTimeMs <= refined[i + 1].startTimeMs
            )
        }
        assertEquals(1, refined.first().index)
        assertEquals(refined.size, refined.last().index)
    }

    @Test
    fun `streaming resampler downmixes 5_1 surround 48kHz PCM to 16kHz mono float`() {
        val resampler = OfflineVideoAudioExtractor.StreamingMono16kResampler()
        val frames48k = 4_800 // 100ms at 48 kHz
        val channels = 6 // 5.1 surround
        val bytes = ByteArray(frames48k * channels * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames48k) {
            val v = (sin(2.0 * PI * 440.0 * f / 48_000.0) * 16_000.0).toInt().toShort()
            repeat(channels) { bb.putShort(v) }
        }

        val out16k = resampler.convert(bytes, bytes.size, srcSampleRate = 48_000, srcChannels = channels, isFloat = false)
        assertEquals(1_600, out16k.size) // Exact 100ms @ 16kHz
        assertTrue(out16k.maxOf { kotlin.math.abs(it) } in 0.4f..0.6f)
    }

    @Test
    fun `output format builds matching sidecar filenames and renders bilingual or target-only SRT`() {
        assertEquals("Episode.01.bilingual.srt", BatchSubtitleOutputFormat.BILINGUAL.buildFileName("Episode.01.mkv", "zh"))
        assertEquals("Episode.01.zh.srt", BatchSubtitleOutputFormat.TARGET_ONLY.buildFileName("Episode.01.mkv", "zh-CN"))
        assertEquals("Episode.01.asr.srt", BatchSubtitleOutputFormat.ORIGINAL_ONLY.buildFileName("Episode.01.mkv", "zh"))

        val cues = listOf(
            SubtitleCue(1, 1000L, 3500L, "Hello world", translatedText = "你好世界")
        )
        val bilingual = BatchSubtitleGenerator.renderSrtForFormat(cues, BatchSubtitleOutputFormat.BILINGUAL)
        assertTrue(bilingual.contains("你好世界") && bilingual.contains("Hello world"))

        val targetOnly = BatchSubtitleGenerator.renderSrtForFormat(cues, BatchSubtitleOutputFormat.TARGET_ONLY)
        assertTrue(targetOnly.contains("你好世界"))
        assertTrue(!targetOnly.contains("Hello world"))

        val origOnly = BatchSubtitleGenerator.renderSrtForFormat(cues, BatchSubtitleOutputFormat.ORIGINAL_ONLY)
        assertTrue(origOnly.contains("Hello world"))
        assertTrue(!origOnly.contains("你好世界"))
    }

    @Test
    fun `generated subtitle store writes directly to local folder, WebDAV PUT, and falls back to indexed cache on read-only`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val settingsRepo = AppSettingsRepository(ctx)
        val smbRepo = SmbRepository()
        val webDavRepo = WebDavRepository()
        val cacheBase = tempFolder.newFolder("generated_cache")
        val store = GeneratedSubtitleStore(ctx, cacheBase, smbRepo, webDavRepo, settingsRepo)

        val sampleSrt = "1\n00:00:01,000 --> 00:00:03,000\n你好世界\nHello world\n"

        // 1. Local directory direct write-back
        val localDir = tempFolder.newFolder("LocalMovies")
        val localVideoFile = File(localDir, "Dune.2021.mkv").apply { writeBytes(ByteArray(32)) }
        val localTarget = BatchSubtitleTarget(
            video = VideoItem(
                id = "v1",
                title = localVideoFile.name,
                uriString = localVideoFile.toURI().toString(),
                filePath = localVideoFile.absolutePath
            )
        )
        val localRes = store.writeSubtitleForTarget(localTarget, "Dune.2021.bilingual.srt", sampleSrt)
        assertEquals(SubtitleWriteDestination.ORIGINAL_DIRECTORY, localRes.destination)
        val writtenLocalSrt = File(localDir, "Dune.2021.bilingual.srt")
        assertTrue(writtenLocalSrt.exists())
        assertEquals(sampleSrt, writtenLocalSrt.readText(Charsets.UTF_8))

        // 2. WebDAV writable (201 Created) & WebDAV read-only (403 -> indexed fallback)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val mount = MountedStorageSource(
                id = "dav_1",
                name = "TestNAS",
                type = StorageMountType.WEBDAV,
                address = server.url("/dav/").toString(),
                username = "admin",
                password = "secret"
            )
            settingsRepo.saveMountedSource(mount)

            val davVideoUrl = server.url("/dav/Movies/Ep01.mkv").toString()
            val davTarget = BatchSubtitleTarget(
                video = VideoItem(
                    id = davVideoUrl,
                    title = "Ep01.mkv",
                    uriString = davVideoUrl,
                    folderPath = server.url("/dav/Movies/").toString(),
                    mountSourceId = mount.id
                )
            )
            val davOk = store.writeSubtitleForTarget(davTarget, "Ep01.bilingual.srt", sampleSrt)
            assertEquals(SubtitleWriteDestination.ORIGINAL_DIRECTORY, davOk.destination)
            val putReq = server.takeRequest()
            assertEquals("PUT", putReq.method)
            assertEquals("/dav/Movies/Ep01.bilingual.srt", putReq.path)

            // Now test read-only (403) automatic fallback + local auto-load index
            val roUrl = server.url("/dav/ReadOnly/Ep02.mkv").toString()
            val roTarget = BatchSubtitleTarget(
                video = VideoItem(
                    id = roUrl,
                    title = "Ep02.mkv",
                    uriString = roUrl,
                    folderPath = server.url("/dav/ReadOnly/").toString(),
                    mountSourceId = mount.id
                )
            )
            val fallbackRes = store.writeSubtitleForTarget(roTarget, "Ep02.bilingual.srt", sampleSrt)
            assertEquals(SubtitleWriteDestination.LOCAL_CACHE_FALLBACK, fallbackRes.destination)
            val indexed = store.findIndexedSubtitle(roUrl)
            assertNotNull("Indexed fallback subtitle must be discoverable for next playback auto-load", indexed)
            assertEquals(sampleSrt, indexed!!.readText(Charsets.UTF_8))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buffered read-ahead data source collapses tiny demuxer reads and preserves exact byte fidelity`() {
        val payload = ByteArray(100_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        var upstreamReadCalls = 0
        val fakeUpstream = object : androidx.media3.datasource.BaseDataSource(false) {
            private var pos = 0
            private var uri: android.net.Uri? = null
            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                uri = dataSpec.uri
                pos = dataSpec.position.toInt()
                return (payload.size - pos).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                upstreamReadCalls++
                if (pos >= payload.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
                val n = minOf(length, payload.size - pos)
                System.arraycopy(payload, pos, buffer, offset, n)
                pos += n
                return n
            }
            override fun getUri(): android.net.Uri? = uri
            override fun close() = Unit
        }

        val buffered = OfflineVideoAudioExtractor.BufferedReadAheadDataSource(fakeUpstream, bufferSizeBytes = 32 * 1024)
        buffered.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("smb://192.168.1.10/Movies/test.mp4")))
        val reconstructed = java.io.ByteArrayOutputStream(payload.size)
        val tinyBuf = ByteArray(350) // Simulating 350-byte AAC packet reads
        while (true) {
            val n = buffered.read(tinyBuf, 0, tinyBuf.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            reconstructed.write(tinyBuf, 0, n)
        }
        buffered.close()

        assertTrue("Expected exact byte match", payload.contentEquals(reconstructed.toByteArray()))
        // 100 KB / 32 KB = 4 upstream reads instead of 286 tiny RPCs!
        assertTrue("Expected <= 6 upstream SMB reads, got $upstreamReadCalls", upstreamReadCalls <= 6)
    }
}
