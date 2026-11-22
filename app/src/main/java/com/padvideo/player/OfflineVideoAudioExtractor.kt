package com.padvideo.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoderBridge
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.padvideo.data.repository.SmbRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-speed, video-free offline audio extractor built directly on Media3 Extractors + DataSource.
 *
 * Key advantages:
 * - Works uniformly across SMB (`smb://`), Local files, SAF/USB (`content://`), WebDAV & HTTP/HTTPS!
 * - Discards all video packets (zero GPU/CPU video decoding!) so extraction runs at maximum I/O + audio speed.
 * - Uses Jellyfin Media3 FFmpeg audio soft-decoder first (handles MKV AC3, EAC3, DTS, TrueHD, AAC, MP3, Opus,
 *   Vorbis, FLAC, ALAC, WMA without device codec restrictions) with automatic Android MediaCodec fallback.
 * - Streams 16 kHz mono float PCM incrementally so memory stays flat even on 3-hour 4K MKV movies.
 */
@OptIn(UnstableApi::class)
internal class OfflineVideoAudioExtractor(
    private val context: Context,
    private val smbRepository: SmbRepository
) {

    data class ExtractionSummary(
        val totalSamples16k: Long,
        val durationMs: Long,
        val audioMimeType: String,
        val decoderName: String
    )

    suspend fun extractAudioTo16kMono(
        uriString: String,
        httpHeaders: Map<String, String> = emptyMap(),
        onProgress: (fraction: Float, decodedAudioMs: Long) -> Unit = { _, _ -> },
        onPcm16kChunk: suspend (samples16k: FloatArray, validLength: Int) -> Unit
    ): ExtractionSummary = withContext(Dispatchers.IO) {
        val dataSource = buildDataSource(uriString, httpHeaders)
        val uri = Uri.parse(uriString)
        val totalLength = dataSource.open(DataSpec(uri))

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .createExtractors(uri, emptyMap())

        var selectedExtractor: Extractor? = null
        var extractorInput = DefaultExtractorInput(dataSource, 0L, totalLength)

        try {
            for (candidate in extractors) {
                val sniffed = try {
                    candidate.sniff(extractorInput)
                } catch (_: EOFException) {
                    false
                } finally {
                    extractorInput.resetPeekPosition()
                }
                if (sniffed) {
                    selectedExtractor = candidate
                    break
                }
            }
            val extractor = selectedExtractor
                ?: throw IOException("无法识别该视频容器格式（不支持的封装格式或文件损坏）")

            var audioTrackId = C.INDEX_UNSET
            var audioFormat: Format? = null
            var containerDurationUs = C.TIME_UNSET
            var tracksEnded = false

            // Pending audio packets decoded inside the coroutine loop so we can call suspend onPcm16kChunk safely
            val pendingPackets = ArrayDeque<AudioPacket>()

            val audioTrackOutput = object : TrackOutput {
                private val packetBuf = ByteArrayOutputStream(16 * 1024)

                override fun format(format: Format) {
                    if (audioFormat == null) {
                        audioFormat = format
                    }
                }

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int
                ): Int {
                    val tmp = ByteArray(minOf(length, 16 * 1024))
                    val read = input.read(tmp, 0, tmp.size)
                    if (read == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw EOFException()
                    }
                    packetBuf.write(tmp, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val tmp = ByteArray(length)
                    data.readBytes(tmp, 0, length)
                    packetBuf.write(tmp)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?
                ) {
                    val allBytes = packetBuf.toByteArray()
                    packetBuf.reset()
                    if (allBytes.isEmpty()) return
                    val validSize = size.coerceIn(0, allBytes.size)
                    val startOffset = (allBytes.size - offset - validSize).coerceAtLeast(0)
                    val packetBytes = if (startOffset == 0 && validSize == allBytes.size) {
                        allBytes
                    } else {
                        allBytes.copyOfRange(startOffset, (startOffset + validSize).coerceAtMost(allBytes.size))
                    }
                    if (packetBytes.isNotEmpty()) {
                        pendingPackets.addLast(AudioPacket(packetBytes, timeUs.coerceAtLeast(0L)))
                    }
                }
            }

            val discardingOutput = DiscardingTrackOutput()
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    return if (type == C.TRACK_TYPE_AUDIO && audioTrackId == C.INDEX_UNSET) {
                        audioTrackId = id
                        audioTrackOutput
                    } else {
                        discardingOutput
                    }
                }

                override fun endTracks() {
                    tracksEnded = true
                }

                override fun seekMap(seekMap: SeekMap) {
                    if (seekMap.durationUs != C.TIME_UNSET) {
                        containerDurationUs = seekMap.durationUs
                    }
                }
            })

            val positionHolder = PositionHolder()
            var ffmpegBridge: FfmpegAudioDecoderBridge? = null
            var mediaCodecHelper: MediaCodecAudioDecoderHelper? = null
            var decoderName = "PCM"
            var decoderInitialized = false
            val resampler = StreamingMono16kResampler()
            var totalSamples16k = 0L
            var lastProgressEmitMs = 0L

            suspend fun emitResampled(
                pcmBytes: ByteArray,
                byteCount: Int,
                srcSampleRate: Int,
                srcChannels: Int,
                isFloat: Boolean
            ) {
                val out16k = resampler.convert(pcmBytes, byteCount, srcSampleRate, srcChannels, isFloat)
                if (out16k.isNotEmpty()) {
                    totalSamples16k += out16k.size
                    onPcm16kChunk(out16k, out16k.size)
                }
            }

            try {
                while (true) {
                    currentCoroutineContext().ensureActive()

                    val readResult = try {
                        extractor.read(extractorInput, positionHolder)
                    } catch (_: EOFException) {
                        Extractor.RESULT_END_OF_INPUT
                    }

                    // Initialize audio decoder once audio format is known
                    if (!decoderInitialized && audioFormat != null) {
                        val fmt = audioFormat!!
                        val mime = fmt.sampleMimeType.orEmpty()
                        if (mime == MimeTypes.AUDIO_RAW) {
                            decoderName = "RawPCM"
                        } else {
                            ffmpegBridge = FfmpegAudioDecoderBridge.createOrNull(fmt)
                            if (ffmpegBridge != null) {
                                decoderName = "FFmpeg($mime)"
                            } else {
                                mediaCodecHelper = MediaCodecAudioDecoderHelper.createOrThrow(fmt)
                                decoderName = "MediaCodec($mime)"
                            }
                        }
                        decoderInitialized = true
                    }

                    // Decode all queued audio packets from this extractor read step
                    while (pendingPackets.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val pkt = pendingPackets.removeFirst()
                        val fmt = audioFormat ?: continue
                        when {
                            ffmpegBridge != null -> {
                                val pcmChunks = mutableListOf<DecodedPcmChunk>()
                                ffmpegBridge.decodePacket(pkt.bytes, pkt.bytes.size, pkt.timeUs) { arr, len, sr, ch, flt, _ ->
                                    pcmChunks.add(DecodedPcmChunk(arr, len, sr, ch, flt))
                                }
                                for (c in pcmChunks) {
                                    emitResampled(c.bytes, c.length, c.sampleRate, c.channels, c.isFloat)
                                }
                            }
                            mediaCodecHelper != null -> {
                                val pcmChunks = mediaCodecHelper.decodePacket(pkt.bytes, pkt.timeUs)
                                for (c in pcmChunks) {
                                    emitResampled(c.bytes, c.length, c.sampleRate, c.channels, c.isFloat)
                                }
                            }
                            fmt.sampleMimeType == MimeTypes.AUDIO_RAW -> {
                                val sr = fmt.sampleRate.coerceAtLeast(8_000)
                                val ch = fmt.channelCount.coerceAtLeast(1)
                                val isFloat = fmt.pcmEncoding == C.ENCODING_PCM_FLOAT
                                emitResampled(pkt.bytes, pkt.bytes.size, sr, ch, isFloat)
                            }
                        }
                    }

                    // Throttled progress update
                    val now = System.currentTimeMillis()
                    if (now - lastProgressEmitMs >= 220L) {
                        lastProgressEmitMs = now
                        val decodedMs = (totalSamples16k * 1000L) / TARGET_SAMPLE_RATE
                        val frac = when {
                            containerDurationUs > 0L && decodedMs > 0L ->
                                (decodedMs.toFloat() / (containerDurationUs / 1000f)).coerceIn(0f, 0.99f)
                            totalLength > 0L ->
                                (extractorInput.position.toFloat() / totalLength.toFloat()).coerceIn(0f, 0.99f)
                            else -> 0f
                        }
                        onProgress(frac, decodedMs)
                    }

                    if (readResult == Extractor.RESULT_SEEK) {
                        val seekPos = positionHolder.position
                        dataSource.close()
                        val reopenedLen = dataSource.open(DataSpec(uri, seekPos, C.LENGTH_UNSET.toLong()))
                        val newTotal = if (reopenedLen == C.LENGTH_UNSET.toLong()) totalLength else seekPos + reopenedLen
                        extractorInput = DefaultExtractorInput(dataSource, seekPos, newTotal)
                    } else if (readResult == Extractor.RESULT_END_OF_INPUT) {
                        break
                    } else if (tracksEnded && audioTrackId == C.INDEX_UNSET) {
                        throw IOException("该视频未检测到可用音轨")
                    }
                }

                // Flush trailing decoded audio frames
                if (ffmpegBridge != null) {
                    val tailChunks = mutableListOf<DecodedPcmChunk>()
                    ffmpegBridge.finishAndDrain { arr, len, sr, ch, flt, _ ->
                        tailChunks.add(DecodedPcmChunk(arr, len, sr, ch, flt))
                    }
                    for (c in tailChunks) {
                        emitResampled(c.bytes, c.length, c.sampleRate, c.channels, c.isFloat)
                    }
                } else if (mediaCodecHelper != null) {
                    val tailChunks = mediaCodecHelper.finishAndDrain()
                    for (c in tailChunks) {
                        emitResampled(c.bytes, c.length, c.sampleRate, c.channels, c.isFloat)
                    }
                }

                val finalMs = (totalSamples16k * 1000L) / TARGET_SAMPLE_RATE
                onProgress(1f, finalMs)
                ExtractionSummary(
                    totalSamples16k = totalSamples16k,
                    durationMs = finalMs,
                    audioMimeType = audioFormat?.sampleMimeType ?: "unknown",
                    decoderName = decoderName
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatching { ffmpegBridge?.close() }
                runCatching { mediaCodecHelper?.close() }
            }
        } finally {
            runCatching { selectedExtractor?.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun buildDataSource(uriString: String, httpHeaders: Map<String, String>): DataSource {
        return when {
            uriString.startsWith("smb://", ignoreCase = true) -> {
                SmbDataSource(smbRepository)
            }
            uriString.startsWith("http://", ignoreCase = true) ||
                uriString.startsWith("https://", ignoreCase = true) -> {
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .apply {
                        if (httpHeaders.isNotEmpty()) {
                            setDefaultRequestProperties(httpHeaders)
                        }
                    }
                    .createDataSource()
            }
            else -> {
                DefaultDataSource.Factory(context).createDataSource()
            }
        }
    }

    private data class AudioPacket(val bytes: ByteArray, val timeUs: Long)
    private data class DecodedPcmChunk(
        val bytes: ByteArray,
        val length: Int,
        val sampleRate: Int,
        val channels: Int,
        val isFloat: Boolean
    )

    /**
     * Stateful linear resampler & multi-channel downmixer producing 16 kHz mono float samples (-1f..1f).
     */
    internal class StreamingMono16kResampler {
        private var phase = 0.0
        private var lastMonoSample = 0f

        fun convert(
            pcmBytes: ByteArray,
            byteCount: Int,
            srcSampleRate: Int,
            srcChannels: Int,
            isFloat: Boolean
        ): FloatArray {
            val channels = srcChannels.coerceAtLeast(1)
            val rate = srcSampleRate.coerceAtLeast(8_000)
            val bytesPerSample = if (isFloat) 4 else 2
            val frameBytes = channels * bytesPerSample
            val frameCount = byteCount / frameBytes
            if (frameCount <= 0) return FloatArray(0)

            val buf = ByteBuffer.wrap(pcmBytes, 0, frameCount * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            val mono = FloatArray(frameCount)
            for (i in 0 until frameCount) {
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += if (isFloat) buf.float else buf.short / 32768f
                }
                mono[i] = (sum / channels).coerceIn(-1f, 1f)
            }

            if (rate == TARGET_SAMPLE_RATE) {
                lastMonoSample = mono[frameCount - 1]
                return mono
            }

            val step = rate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            val estOut = ((frameCount / step) + 4).toInt().coerceAtLeast(1)
            val out = FloatArray(estOut)
            var outCount = 0
            var pos = phase
            while (pos < frameCount) {
                val idx = pos.toInt()
                val frac = (pos - idx).toFloat()
                val s0 = if (idx == 0 && pos < 0.0) lastMonoSample else mono[idx.coerceIn(0, frameCount - 1)]
                val s1 = mono[(idx + 1).coerceAtMost(frameCount - 1)]
                if (outCount >= out.size) {
                    // Safety growth
                    break
                }
                out[outCount++] = s0 + (s1 - s0) * frac
                pos += step
            }
            phase = pos - frameCount
            lastMonoSample = mono[frameCount - 1]
            return if (outCount == out.size) out else out.copyOf(outCount)
        }
    }

    /**
     * Fallback Android MediaCodec audio packet decoder when FFmpeg isn't available for a specific codec.
     */
    private class MediaCodecAudioDecoderHelper(
        private val codec: MediaCodec,
        private var currentSampleRate: Int,
        private var currentChannels: Int
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()

        fun decodePacket(packetBytes: ByteArray, timeUs: Long): List<DecodedPcmChunk> {
            val outputs = mutableListOf<DecodedPcmChunk>()
            val inIndex = codec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    val copyLen = minOf(packetBytes.size, inBuf.capacity())
                    inBuf.put(packetBytes, 0, copyLen)
                    codec.queueInputBuffer(inIndex, 0, copyLen, timeUs, 0)
                }
            }
            drain(outputs, waitForEos = false)
            return outputs
        }

        fun finishAndDrain(): List<DecodedPcmChunk> {
            val outputs = mutableListOf<DecodedPcmChunk>()
            val inIndex = codec.dequeueInputBuffer(20_000L)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(outputs, waitForEos = true)
            return outputs
        }

        private fun drain(outputs: MutableList<DecodedPcmChunk>, waitForEos: Boolean) {
            var rounds = 0
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, if (waitForEos) 10_000L else 0L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            currentSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            currentChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.get(bytes)
                            outputs.add(DecodedPcmChunk(bytes, bytes.size, currentSampleRate, currentChannels, false))
                        }
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIdx, false)
                        if (isEos) break
                    }
                    else -> {
                        if (!waitForEos || ++rounds >= 30) break
                    }
                }
            }
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        companion object {
            fun createOrThrow(format: Format): MediaCodecAudioDecoderHelper {
                val mime = format.sampleMimeType
                    ?: throw IOException("无法识别视频音轨编码格式")
                val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48_000
                val channels = format.channelCount.takeIf { it > 0 } ?: 2
                val mediaFormat = MediaFormat.createAudioFormat(mime, sampleRate, channels)
                format.initializationData.forEachIndexed { idx, csd ->
                    mediaFormat.setByteBuffer("csd-$idx", ByteBuffer.wrap(csd))
                }
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
                return MediaCodecAudioDecoderHelper(codec, sampleRate, channels)
            }
        }
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
    }
}
