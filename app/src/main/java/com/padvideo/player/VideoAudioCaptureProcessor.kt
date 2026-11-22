package com.padvideo.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class CapturedAudioSegment(
    val samples16k: FloatArray,
    val startPositionMs: Long,
    val endPositionMs: Long
)

/**
 * Non-destructive Media3 AudioProcessor that taps the playing video's own decoded PCM audio stream
 * (works with headphones, muted speakers, SMB, WebDAV, Quark, local files — ZERO microphone required!),
 * resamples to 16 kHz mono float samples, and uses RMS energy VAD to emit natural speech segments.
 */
@UnstableApi
class VideoAudioCaptureProcessor(
    private val currentPositionMsProvider: () -> Long
) : BaseAudioProcessor() {

    @Volatile
    private var capturingEnabled: Boolean = false

    private val segmentChannel = Channel<CapturedAudioSegment>(
        capacity = 6,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val segments: Flow<CapturedAudioSegment> = segmentChannel.receiveAsFlow()

    private val lock = Any()
    private var pcmBuffer = FloatArray(TARGET_SAMPLE_RATE * 8)
    private var bufferedCount = 0
    private var segmentStartPosMs = 0L
    private var trailingSilenceSamples = 0
    private var speechDetectedInBuffer = false
    private var resamplePhase = 0.0
    private var lastMonoSample = 0f

    fun setCapturing(enabled: Boolean) {
        synchronized(lock) {
            capturingEnabled = enabled
            if (!enabled) {
                resetAccumulatorLocked()
            }
        }
    }

    fun isCapturing(): Boolean = capturingEnabled

    fun clearPendingAudio() {
        synchronized(lock) {
            resetAccumulatorLocked()
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Accept standard PCM 16-bit and PCM Float; pass audio through completely unchanged!
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (capturingEnabled) {
            val readOnly = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            tapAudioBuffer(readOnly, inputAudioFormat)
        }

        // Pass original PCM straight through to speakers/headphones untouched
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        synchronized(lock) {
            resetAccumulatorLocked()
        }
    }

    override fun onReset() {
        synchronized(lock) {
            resetAccumulatorLocked()
        }
    }

    private fun tapAudioBuffer(buf: ByteBuffer, format: AudioProcessor.AudioFormat) {
        val channels = format.channelCount.coerceAtLeast(1)
        val srcRate = format.sampleRate.coerceAtLeast(8000)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = channels * bytesPerSample
        val frameCount = buf.remaining() / frameBytes
        if (frameCount <= 0) return

        // 1. Mix multi-channel PCM down to normalized mono float array (-1f..1f)
        val mono = FloatArray(frameCount)
        var energySum = 0.0
        for (i in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until channels) {
                val sample = if (isFloat) {
                    buf.float
                } else {
                    buf.short / 32768f
                }
                sum += sample
            }
            val avg = (sum / channels).coerceIn(-1f, 1f)
            mono[i] = avg
            energySum += (avg * avg).toDouble()
        }
        val frameRms = sqrt(energySum / frameCount).toFloat()

        // 2. Resample to 16 kHz mono and run VAD segmentation
        val step = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val nowPosMs = runCatching { currentPositionMsProvider() }.getOrDefault(0L).coerceAtLeast(0L)

        synchronized(lock) {
            if (!capturingEnabled) return
            val beforeCount = bufferedCount
            var pos = resamplePhase
            while (pos < frameCount) {
                val idx = pos.toInt()
                val frac = (pos - idx).toFloat()
                val s0 = if (idx == 0 && pos < 0.0) lastMonoSample else mono[idx.coerceIn(0, frameCount - 1)]
                val s1 = mono[(idx + 1).coerceAtMost(frameCount - 1)]
                val sample16k = s0 + (s1 - s0) * frac

                if (bufferedCount == 0 && frameRms >= SPEECH_RMS_THRESHOLD) {
                    segmentStartPosMs = nowPosMs
                }
                if (bufferedCount > 0 || frameRms >= SPEECH_RMS_THRESHOLD) {
                    if (bufferedCount >= pcmBuffer.size) {
                        pcmBuffer = pcmBuffer.copyOf(pcmBuffer.size * 2)
                    }
                    pcmBuffer[bufferedCount++] = sample16k
                }
                pos += step
            }
            resamplePhase = pos - frameCount
            lastMonoSample = mono[frameCount - 1]

            val addedSamples = bufferedCount - beforeCount
            if (frameRms >= SPEECH_RMS_THRESHOLD) {
                speechDetectedInBuffer = true
                trailingSilenceSamples = 0
            } else if (speechDetectedInBuffer) {
                trailingSilenceSamples += addedSamples.coerceAtLeast((frameCount / step).toInt())
            }

            // Emit segment when natural speech pause detected OR chunk reaches max length
            val hasEnoughForPauseSplit = speechDetectedInBuffer &&
                bufferedCount >= MIN_PAUSE_SPLIT_SAMPLES &&
                trailingSilenceSamples >= SILENCE_SPLIT_SAMPLES
            val reachedMaxLength = speechDetectedInBuffer && bufferedCount >= MAX_SEGMENT_SAMPLES

            if (hasEnoughForPauseSplit || reachedMaxLength) {
                val trimLen = if (hasEnoughForPauseSplit) {
                    (bufferedCount - (trailingSilenceSamples / 2)).coerceAtLeast(MIN_EMIT_SAMPLES)
                } else {
                    bufferedCount
                }.coerceAtMost(bufferedCount)

                val outSamples = pcmBuffer.copyOf(trimLen)
                val durationMs = (trimLen * 1000L) / TARGET_SAMPLE_RATE
                val endMs = nowPosMs.coerceAtLeast(segmentStartPosMs + durationMs)
                val startMs = (endMs - durationMs).coerceAtLeast(0L)

                segmentChannel.trySend(
                    CapturedAudioSegment(
                        samples16k = outSamples,
                        startPositionMs = startMs,
                        endPositionMs = endMs
                    )
                )

                // Keep a tiny 200ms overlap on max-length forced cuts so words at boundary aren't clipped
                if (reachedMaxLength) {
                    val overlap = (TARGET_SAMPLE_RATE / 5).coerceAtMost(bufferedCount)
                    System.arraycopy(pcmBuffer, bufferedCount - overlap, pcmBuffer, 0, overlap)
                    bufferedCount = overlap
                    segmentStartPosMs = (endMs - 200L).coerceAtLeast(0L)
                    trailingSilenceSamples = 0
                    speechDetectedInBuffer = true
                } else {
                    resetAccumulatorLocked()
                }
            }
        }
    }

    private fun resetAccumulatorLocked() {
        bufferedCount = 0
        trailingSilenceSamples = 0
        speechDetectedInBuffer = false
        resamplePhase = 0.0
        lastMonoSample = 0f
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val SPEECH_RMS_THRESHOLD = 0.012f
        private const val MIN_EMIT_SAMPLES = TARGET_SAMPLE_RATE / 2          // 0.5s
        private const val MIN_PAUSE_SPLIT_SAMPLES = (TARGET_SAMPLE_RATE * 1.4).toInt() // 1.4s
        private const val SILENCE_SPLIT_SAMPLES = (TARGET_SAMPLE_RATE * 0.45).toInt()  // 450ms pause
        private const val MAX_SEGMENT_SAMPLES = TARGET_SAMPLE_RATE * 6         // 6.0s max chunk

        /**
         * Encodes 16 kHz mono normalized float samples (-1f..1f) into a standard 16-bit PCM WAV file.
         */
        fun encodePcm16kToWav(samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
            val dataSize = samples.size * 2
            val out = ByteArrayOutputStream(44 + dataSize)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM fmt chunk size
            header.putShort(1) // AudioFormat = PCM
            header.putShort(1) // Mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // ByteRate
            header.putShort(2) // BlockAlign
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())

            val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val clamped = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                pcmBuf.putShort(clamped)
            }
            out.write(pcmBuf.array())
            return out.toByteArray()
        }
    }
}
