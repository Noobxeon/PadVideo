package androidx.media3.decoder.ffmpeg

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Type-safe bridge to Media3's package-private [FfmpegAudioDecoder] so PadVideo's offline batch
 * audio extractor can decode AC3, EAC3, DTS, TrueHD, AAC, MP3, Opus, Vorbis, FLAC, ALAC, and WMA
 * directly from SMB/WebDAV/Local containers without vendor MediaCodec limitations.
 */
@UnstableApi
class FfmpegAudioDecoderBridge private constructor(
    private val decoder: FfmpegAudioDecoder
) : Closeable {

    val channelCount: Int
        get() = decoder.channelCount

    val sampleRate: Int
        get() = decoder.sampleRate

    val encoding: Int
        get() = decoder.encoding

    fun decodePacket(
        packetBytes: ByteArray,
        length: Int,
        timeUs: Long,
        onPcmOutput: (pcmBytes: ByteArray, byteCount: Int, sampleRate: Int, channels: Int, isFloat: Boolean, ptsUs: Long) -> Unit
    ) {
        if (length <= 0) return
        val inputBuffer = dequeueInputWithRetry(onPcmOutput) ?: return
        inputBuffer.clear()
        inputBuffer.ensureSpaceForWrite(length)
        inputBuffer.data?.put(packetBytes, 0, length)
        // CRITICAL: FfmpegAudioDecoder.decode() reads inputBuffer.data.limit() as packet length!
        inputBuffer.flip()
        inputBuffer.timeUs = timeUs
        decoder.queueInputBuffer(inputBuffer)
        drainOutputs(onPcmOutput, waitForEos = false)
    }

    fun finishAndDrain(
        onPcmOutput: (pcmBytes: ByteArray, byteCount: Int, sampleRate: Int, channels: Int, isFloat: Boolean, ptsUs: Long) -> Unit
    ) {
        val eosBuffer = dequeueInputWithRetry(onPcmOutput)
        if (eosBuffer != null) {
            eosBuffer.clear()
            eosBuffer.flip()
            eosBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            decoder.queueInputBuffer(eosBuffer)
            drainOutputs(onPcmOutput, waitForEos = true)
        } else {
            drainOutputs(onPcmOutput, waitForEos = false)
        }
    }

    private fun dequeueInputWithRetry(
        onPcmOutput: (pcmBytes: ByteArray, byteCount: Int, sampleRate: Int, channels: Int, isFloat: Boolean, ptsUs: Long) -> Unit
    ): DecoderInputBuffer? {
        repeat(120) {
            // Always drain pending outputs first so we never drop decoded PCM or stall the decoder thread
            drainOutputs(onPcmOutput, waitForEos = false)
            val buf = decoder.dequeueInputBuffer()
            if (buf != null) return buf
            Thread.sleep(1L)
        }
        return null
    }

    private fun drainOutputs(
        onPcmOutput: (pcmBytes: ByteArray, byteCount: Int, sampleRate: Int, channels: Int, isFloat: Boolean, ptsUs: Long) -> Unit,
        waitForEos: Boolean
    ) {
        var idleRounds = 0
        while (true) {
            val outBuf: SimpleDecoderOutputBuffer? = decoder.dequeueOutputBuffer()
            if (outBuf == null) {
                if (!waitForEos || idleRounds >= 100) break
                idleRounds++
                Thread.sleep(2L)
                continue
            }
            idleRounds = 0
            try {
                if (outBuf.isEndOfStream) break
                if (outBuf.shouldBeSkipped) continue
                val byteBuf = outBuf.data
                if (byteBuf != null && byteBuf.remaining() > 0) {
                    val dup = byteBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val count = dup.remaining()
                    val arr = ByteArray(count)
                    dup.get(arr)
                    val sr = decoder.sampleRate.coerceAtLeast(8_000)
                    val ch = decoder.channelCount.coerceAtLeast(1)
                    val isFloat = decoder.encoding == C.ENCODING_PCM_FLOAT
                    onPcmOutput(arr, count, sr, ch, isFloat, outBuf.timeUs)
                }
            } finally {
                outBuf.release()
            }
        }
    }

    override fun close() {
        runCatching { decoder.release() }
    }

    companion object {
        fun isAvailableFor(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            return runCatching {
                FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType)
            }.getOrDefault(false)
        }

        fun createOrNull(format: Format): FfmpegAudioDecoderBridge? {
            if (!isAvailableFor(format.sampleMimeType)) return null
            return runCatching {
                val decoder = FfmpegAudioDecoder(
                    format,
                    /* numInputBuffers = */ 4,
                    /* numOutputBuffers = */ 4,
                    /* initialInputBufferSize = */ 64 * 1024,
                    /* outputFloat = */ false
                )
                FfmpegAudioDecoderBridge(decoder)
            }.getOrNull()
        }
    }
}
