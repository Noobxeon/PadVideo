package com.padvideo.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.TranslationConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@androidx.annotation.OptIn(UnstableApi::class)
class VideoAudioAsrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `audio capture processor passes PCM untouched and emits 16kHz mono speech segment after pause`() = runBlocking {
        var fakePositionMs = 10_000L
        val processor = VideoAudioCaptureProcessor { fakePositionMs }
        val inputFormat = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val outFormat = processor.configure(inputFormat)
        assertEquals(inputFormat, outFormat)
        processor.flush()
        processor.setCapturing(true)

        val segmentDeferred = async {
            withTimeout(5_000L) { processor.segments.first() }
        }

        // Feed 1.6 seconds of 440 Hz stereo sine wave in 200ms chunks (loud speech energy)
        val chunkFrames = 48_000 / 5 // 9600 frames = 200ms
        val speechChunkBytes = ByteArray(chunkFrames * 4)
        val speechBuf = ByteBuffer.wrap(speechChunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until chunkFrames) {
            val s = (sin(2.0 * PI * 440.0 * i / 48_000.0) * 12_000.0).toInt().toShort()
            speechBuf.putShort(s) // L
            speechBuf.putShort(s) // R
        }

        repeat(8) { idx ->
            fakePositionMs = 10_000L + idx * 200L
            val input = ByteBuffer.wrap(speechChunkBytes.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
            processor.queueInput(input)
            val passthrough = processor.output
            val outBytes = ByteArray(passthrough.remaining())
            passthrough.get(outBytes)
            // Verify non-destructive passthrough to speakers/headphones
            assertArrayEquals(speechChunkBytes, outBytes)
        }

        // Feed 600ms of silence to trigger natural pause split
        val silenceChunk = ByteArray(chunkFrames * 4)
        repeat(3) { idx ->
            fakePositionMs = 11_600L + idx * 200L
            processor.queueInput(ByteBuffer.wrap(silenceChunk.copyOf()).order(ByteOrder.LITTLE_ENDIAN))
            val out = processor.output
            out.position(out.limit())
        }

        val segment = segmentDeferred.await()
        // ~1.6s at 16 kHz = ~25,600 samples (minus half trailing silence)
        assertTrue("Expected > 20000 samples at 16kHz, got ${segment.samples16k.size}", segment.samples16k.size > 20_000)
        assertTrue("Expected < 35000 samples at 16kHz, got ${segment.samples16k.size}", segment.samples16k.size < 35_000)
        assertTrue(segment.endPositionMs > segment.startPositionMs)
    }

    @Test
    fun `encodePcm16kToWav produces valid RIFF WAVE header and 16-bit PCM payload`() {
        val samples = FloatArray(1600) { i -> sin(2.0 * PI * 440.0 * i / 16_000.0).toFloat() * 0.5f }
        val wav = VideoAudioCaptureProcessor.encodePcm16kToWav(samples)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val headerBuf = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.toShort(), headerBuf.getShort(20)) // PCM
        assertEquals(1.toShort(), headerBuf.getShort(22)) // Mono
        assertEquals(16_000, headerBuf.getInt(24))        // 16 kHz
        assertEquals(16.toShort(), headerBuf.getShort(34)) // 16-bit
        assertEquals(samples.size * 2, headerBuf.getInt(40))
    }

    @Test
    fun `cleanAsrText strips SenseVoice meta tags and Whisper noise tokens`() {
        val cleaned = VideoSpeechRecognizerEngine.cleanAsrText(
            "<|en|><|NEUTRAL|><|Speech|><|woitn|>  Hello   world! [MUSIC] (music) "
        )
        assertEquals("Hello world!", cleaned)
        assertEquals("", VideoSpeechRecognizerEngine.cleanAsrText("<|nospeech|>[BLANK_AUDIO]"))
    }

    @Test
    fun `normalizeAsrLanguage maps language codes cleanly for SenseVoice and Whisper`() {
        assertEquals("en", VideoSpeechRecognizerEngine.normalizeAsrLanguage("en-US", AsrEngineType.SENSE_VOICE_OFFLINE))
        assertEquals("ja", VideoSpeechRecognizerEngine.normalizeAsrLanguage("ja", AsrEngineType.SENSE_VOICE_OFFLINE))
        assertEquals("auto", VideoSpeechRecognizerEngine.normalizeAsrLanguage("fr", AsrEngineType.SENSE_VOICE_OFFLINE))
        assertEquals("", VideoSpeechRecognizerEngine.normalizeAsrLanguage("auto", AsrEngineType.WHISPER_TINY_OFFLINE))
        assertEquals("fr", VideoSpeechRecognizerEngine.normalizeAsrLanguage("fr-FR", AsrEngineType.WHISPER_TINY_OFFLINE))
    }

    @Test
    fun `offline model readiness check and cache deletion work cleanly`() {
        val modelsRoot = tempFolder.newFolder("asr_models")
        val engine = VideoSpeechRecognizerEngine(modelsRoot)

        assertFalse(engine.isModelReady(AsrEngineType.SENSE_VOICE_OFFLINE))
        assertTrue(engine.isModelReady(AsrEngineType.CLOUD_WHISPER_API))

        val svDir = engine.getModelDirectory(AsrEngineType.SENSE_VOICE_OFFLINE).apply { mkdirs() }
        File(svDir, "model.int8.onnx").writeBytes(ByteArray(16_384))
        File(svDir, "tokens.txt").writeBytes(ByteArray(16_384))
        assertTrue(engine.isModelReady(AsrEngineType.SENSE_VOICE_OFFLINE))

        engine.deleteModel(AsrEngineType.SENSE_VOICE_OFFLINE)
        assertFalse(engine.isModelReady(AsrEngineType.SENSE_VOICE_OFFLINE))
    }

    @Test
    fun `cloud whisper API uploads WAV multipart and cleans returned text`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"<|en|><|NEUTRAL|><|Speech|>We are ready for takeoff."}""")
        )
        server.start()
        try {
            val engine = VideoSpeechRecognizerEngine(tempFolder.newFolder("cloud_asr"))
            val config = TranslationConfig(
                asrEngine = AsrEngineType.CLOUD_WHISPER_API,
                sourceLanguageCode = "en",
                cloudAsrBaseUrl = server.url("/v1").toString(),
                cloudAsrApiKey = "sk-test-key",
                cloudAsrModel = "FunAudioLLM/SenseVoiceSmall"
            )
            val samples = FloatArray(8_000) { 0.1f }
            val result = engine.transcribeSegment(samples, config)
            assertTrue(result.isSuccess)
            assertEquals("We are ready for takeoff.", result.getOrThrow())

            val req = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", req.path)
            assertEquals("Bearer sk-test-key", req.getHeader("Authorization"))
            val bodyStr = req.body.readUtf8()
            assertTrue(bodyStr.contains("FunAudioLLM/SenseVoiceSmall"))
            assertTrue(bodyStr.contains("RIFF"))
        } finally {
            server.shutdown()
        }
    }
}
