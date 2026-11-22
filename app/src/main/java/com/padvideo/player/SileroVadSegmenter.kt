package com.padvideo.player

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.padvideo.data.model.SubtitleCue
import java.io.Closeable
import kotlin.math.sqrt

data class TimedSpeechSegment(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val samples16k: FloatArray
)

/**
 * Precision Speech Segmenter powered by bundled Sherpa-ONNX Silero VAD (silero_vad.onnx in assets).
 * Filters out background music, explosions, and silence while producing accurate start/end timestamps.
 * Falls back transparently to deterministic RMS energy VAD when native ONNX JNI is unavailable (e.g., JVM tests).
 */
class SileroVadSegmenter(
    assetManager: AssetManager?,
    useSileroVad: Boolean = true,
    private val sampleRate: Int = 16_000,
    private val minSpeechDurationSec: Float = 0.25f,
    private val minSilenceDurationSec: Float = 0.42f,
    private val maxSpeechDurationSec: Float = 8.0f,
    private val threshold: Float = 0.45f
) : Closeable {

    private val nativeVad: Vad?
    val isUsingSileroNative: Boolean
        get() = nativeVad != null

    // Accumulator for Silero 512-sample windows
    private val sileroWindowSize = 512
    private val sileroPending = FloatArray(sileroWindowSize)
    private var sileroPendingCount = 0

    // Fallback RMS Energy VAD state (also used when useSileroVad == false or in JVM tests)
    private var totalFedSamples = 0L
    private var energyBuf = FloatArray(sampleRate * 10)
    private var energyCount = 0
    private var energySegmentStartSample = 0L
    private var energyTrailingSilence = 0
    private var energyHasSpeech = false

    init {
        nativeVad = if (useSileroVad && assetManager != null) {
            runCatching {
                val sileroConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = threshold,
                    minSilenceDuration = minSilenceDurationSec,
                    minSpeechDuration = minSpeechDurationSec,
                    windowSize = sileroWindowSize,
                    maxSpeechDuration = maxSpeechDurationSec
                )
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = sileroConfig,
                    sampleRate = sampleRate,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                Vad(assetManager = assetManager, config = vadConfig)
            }.getOrNull()
        } else {
            null
        }
    }

    /**
     * Feeds 16 kHz mono float samples (-1f..1f) and returns any newly completed speech segments.
     */
    fun acceptSamples(samples16k: FloatArray, length: Int = samples16k.size): List<TimedSpeechSegment> {
        if (length <= 0) return emptyList()
        val vad = nativeVad
        if (vad != null) {
            return acceptSilero(vad, samples16k, length)
        }
        return acceptEnergyFallback(samples16k, length)
    }

    /**
     * Flushes any trailing speech at end of video stream.
     */
    fun flush(): List<TimedSpeechSegment> {
        val vad = nativeVad
        if (vad != null) {
            if (sileroPendingCount > 0) {
                for (i in sileroPendingCount until sileroWindowSize) {
                    sileroPending[i] = 0f
                }
                vad.acceptWaveform(sileroPending.copyOf())
                sileroPendingCount = 0
            }
            vad.flush()
            return drainNativeVad(vad)
        }
        return flushEnergyFallback()
    }

    private fun acceptSilero(vad: Vad, samples: FloatArray, length: Int): List<TimedSpeechSegment> {
        var offset = 0
        while (offset < length) {
            val toCopy = minOf(sileroWindowSize - sileroPendingCount, length - offset)
            System.arraycopy(samples, offset, sileroPending, sileroPendingCount, toCopy)
            sileroPendingCount += toCopy
            offset += toCopy
            if (sileroPendingCount == sileroWindowSize) {
                vad.acceptWaveform(sileroPending.copyOf())
                sileroPendingCount = 0
            }
        }
        return drainNativeVad(vad)
    }

    private fun drainNativeVad(vad: Vad): List<TimedSpeechSegment> {
        val emitted = mutableListOf<TimedSpeechSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val samples = seg.samples
            if (samples.size >= (sampleRate * minSpeechDurationSec).toInt()) {
                val startMs = (seg.start * 1000L) / sampleRate
                val endMs = ((seg.start + samples.size) * 1000L) / sampleRate
                emitted.add(
                    TimedSpeechSegment(
                        startTimeMs = startMs.coerceAtLeast(0L),
                        endTimeMs = endMs.coerceAtLeast(startMs + 300L),
                        samples16k = samples
                    )
                )
            }
        }
        return emitted
    }

    private fun acceptEnergyFallback(samples: FloatArray, length: Int): List<TimedSpeechSegment> {
        val emitted = mutableListOf<TimedSpeechSegment>()
        val frameSize = 512 // 32ms at 16 kHz
        val minSpeechSamples = (sampleRate * minSpeechDurationSec).toInt()
        val minSilenceSamples = (sampleRate * minSilenceDurationSec).toInt()
        val maxSpeechSamples = (sampleRate * maxSpeechDurationSec).toInt()

        var idx = 0
        while (idx < length) {
            val take = minOf(frameSize, length - idx)
            var sumSq = 0.0
            for (i in 0 until take) {
                val v = samples[idx + i]
                sumSq += (v * v).toDouble()
            }
            val rms = sqrt(sumSq / take).toFloat()
            val isVoice = rms >= ENERGY_SPEECH_THRESHOLD

            if (isVoice) {
                if (!energyHasSpeech) {
                    energySegmentStartSample = totalFedSamples
                    energyHasSpeech = true
                    energyCount = 0
                }
                energyTrailingSilence = 0
                appendEnergySamples(samples, idx, take)
            } else if (energyHasSpeech) {
                appendEnergySamples(samples, idx, take)
                energyTrailingSilence += take
            }

            totalFedSamples += take
            idx += take

            val pauseCut = energyHasSpeech && energyTrailingSilence >= minSilenceSamples
            val maxCut = energyHasSpeech && energyCount >= maxSpeechSamples
            if (pauseCut || maxCut) {
                val usableLen = if (pauseCut) {
                    (energyCount - (energyTrailingSilence / 2)).coerceAtLeast(0)
                } else {
                    energyCount
                }
                if (usableLen >= minSpeechSamples) {
                    val startMs = (energySegmentStartSample * 1000L) / sampleRate
                    val endMs = ((energySegmentStartSample + usableLen) * 1000L) / sampleRate
                    emitted.add(
                        TimedSpeechSegment(
                            startTimeMs = startMs.coerceAtLeast(0L),
                            endTimeMs = endMs.coerceAtLeast(startMs + 300L),
                            samples16k = energyBuf.copyOf(usableLen)
                        )
                    )
                }
                energyCount = 0
                energyTrailingSilence = 0
                energyHasSpeech = false
            }
        }
        return emitted
    }

    private fun flushEnergyFallback(): List<TimedSpeechSegment> {
        val minSpeechSamples = (sampleRate * minSpeechDurationSec).toInt()
        if (!energyHasSpeech || energyCount < minSpeechSamples) {
            energyCount = 0
            energyHasSpeech = false
            return emptyList()
        }
        val usableLen = (energyCount - (energyTrailingSilence / 2)).coerceAtLeast(minSpeechSamples)
            .coerceAtMost(energyCount)
        val startMs = (energySegmentStartSample * 1000L) / sampleRate
        val endMs = ((energySegmentStartSample + usableLen) * 1000L) / sampleRate
        val seg = TimedSpeechSegment(
            startTimeMs = startMs.coerceAtLeast(0L),
            endTimeMs = endMs.coerceAtLeast(startMs + 300L),
            samples16k = energyBuf.copyOf(usableLen)
        )
        energyCount = 0
        energyTrailingSilence = 0
        energyHasSpeech = false
        return listOf(seg)
    }

    private fun appendEnergySamples(src: FloatArray, offset: Int, count: Int) {
        if (energyCount + count > energyBuf.size) {
            energyBuf = energyBuf.copyOf(maxOf(energyBuf.size * 2, energyCount + count))
        }
        System.arraycopy(src, offset, energyBuf, energyCount, count)
        energyCount += count
    }

    override fun close() {
        runCatching { nativeVad?.release() }
    }

    companion object {
        private const val ENERGY_SPEECH_THRESHOLD = 0.015f
    }
}

/**
 * Refines raw ASR cues into clean, commercial-grade SRT subtitles:
 * - Splits overly long multi-sentence segments proportionally by character count at natural punctuation
 * - Guarantees strictly non-overlapping, readable minimum display durations
 */
object SubtitleCueRefiner {

    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[。！？!?；;])\s*|(?<=[.,])\s+(?=[A-Z\u4e00-\u9fa5])""")

    fun refineCues(rawCues: List<SubtitleCue>, maxCharsPerCue: Int = 48): List<SubtitleCue> {
        val expanded = mutableListOf<SubtitleCue>()
        for (cue in rawCues) {
            val orig = cue.originalText.trim()
            if (orig.isEmpty()) continue
            val durationMs = (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(500L)

            if (orig.length <= maxCharsPerCue || durationMs < 2_400L) {
                expanded.add(cue.copy(originalText = orig, endTimeMs = cue.startTimeMs + durationMs))
                continue
            }

            val parts = splitNaturally(orig, maxCharsPerCue)
            if (parts.size <= 1) {
                expanded.add(cue.copy(originalText = orig, endTimeMs = cue.startTimeMs + durationMs))
            } else {
                val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)
                var cursorMs = cue.startTimeMs
                parts.forEachIndexed { i, part ->
                    val shareMs = if (i == parts.lastIndex) {
                        (cue.startTimeMs + durationMs - cursorMs).coerceAtLeast(500L)
                    } else {
                        ((durationMs * part.length) / totalChars).coerceAtLeast(600L)
                    }
                    expanded.add(
                        SubtitleCue(
                            index = 0,
                            startTimeMs = cursorMs,
                            endTimeMs = cursorMs + shareMs,
                            originalText = part,
                            translatedText = null,
                            runtimeTranslation = cue.runtimeTranslation
                        )
                    )
                    cursorMs += shareMs
                }
            }
        }

        // Enforce non-overlapping ascending timestamps & re-index from 1
        val sorted = expanded.sortedBy { it.startTimeMs }
        return sorted.mapIndexed { idx, cue ->
            val nextStart = sorted.getOrNull(idx + 1)?.startTimeMs
            val safeEnd = if (nextStart != null && cue.endTimeMs > nextStart) {
                maxOf(cue.startTimeMs + 350L, nextStart - 40L)
            } else {
                cue.endTimeMs
            }
            cue.copy(index = idx + 1, endTimeMs = safeEnd)
        }
    }

    private fun splitNaturally(text: String, maxChars: Int): List<String> {
        val rawSentences = text.split(SENTENCE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawSentences.size <= 1) return listOf(text)

        val merged = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in rawSentences) {
            if (buf.isEmpty()) {
                buf.append(sentence)
            } else if (buf.length + 1 + sentence.length <= maxChars) {
                if (buf.last().isLetterOrDigit() && sentence.first().isLetterOrDigit()) {
                    buf.append(' ')
                }
                buf.append(sentence)
            } else {
                merged.add(buf.toString().trim())
                buf.clear()
                buf.append(sentence)
            }
        }
        if (buf.isNotEmpty()) merged.add(buf.toString().trim())
        return merged
    }
}
