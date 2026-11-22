package com.padvideo.player

import android.content.Context
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.BatchSubtitleJobConfig
import com.padvideo.data.model.BatchSubtitleOutputFormat
import com.padvideo.data.model.BatchSubtitleTarget
import com.padvideo.data.model.BatchSubtitleTaskProgress
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleWriteDestination
import com.padvideo.data.model.formatDurationMs
import com.padvideo.data.preferences.AppSettingsRepository
import com.padvideo.data.repository.QuarkDriveRepository
import com.padvideo.data.repository.RealtimeTranslationRepository
import com.padvideo.data.repository.SmbRepository
import com.padvideo.data.repository.WebDavRepository
import com.padvideo.subtitle.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * App-scoped Batch Video Subtitle Generator:
 * Extracts audio at full speed (skipping video decode), applies bundled Silero VAD precision segmentation,
 * transcribes via Sherpa-ONNX (SenseVoiceSmall / Whisper Tiny) or Cloud Whisper API, translates in batches,
 * and writes standard .srt subtitles directly back to the SMB / Local / SAF / WebDAV directory!
 */
internal class BatchSubtitleGenerator(
    private val appContext: Context,
    private val smbRepository: SmbRepository,
    private val webDavRepository: WebDavRepository,
    private val quarkDriveRepository: QuarkDriveRepository,
    private val settingsRepository: AppSettingsRepository,
    private val speechRecognizer: VideoSpeechRecognizerEngine,
    private val translationRepository: RealtimeTranslationRepository,
    val generatedSubtitleStore: GeneratedSubtitleStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioExtractor = OfflineVideoAudioExtractor(appContext, smbRepository)

    private val _progress = MutableStateFlow(BatchSubtitleTaskProgress())
    val progress: StateFlow<BatchSubtitleTaskProgress> = _progress.asStateFlow()

    private var activeJob: Job? = null

    fun isRunning(): Boolean = activeJob?.isActive == true

    fun cancel() {
        if (!isRunning()) return
        _progress.update { it.copy(isCancelling = true, currentStageText = "正在安全停止并保留已完成字幕…") }
        activeJob?.cancel()
    }

    fun dismissSummary() {
        if (!isRunning()) {
            _progress.value = BatchSubtitleTaskProgress()
        }
    }

    /**
     * Starts a background batch subtitle generation job for a directory or single video.
     */
    fun startBatch(
        directoryTitle: String,
        targetsProvider: suspend () -> List<BatchSubtitleTarget>,
        config: BatchSubtitleJobConfig,
        onVideoSubtitleWritten: () -> Unit = {}
    ) {
        if (isRunning()) return

        _progress.value = BatchSubtitleTaskProgress(
            isRunning = true,
            directoryTitle = directoryTitle,
            currentStageText = "正在扫描目录中的视频文件…",
            usingSileroVad = config.useSileroVad
        )

        BatchSubtitleService.start(appContext)

        activeJob = scope.launch {
            try {
                smbRepository.registerSavedMounts(settingsRepository.mountedSources.value)

                // 1. Ensure offline ASR model is downloaded before processing
                val asrEngine = config.translationConfig.asrEngine
                if (asrEngine != AsrEngineType.CLOUD_WHISPER_API && !speechRecognizer.isModelReady(asrEngine)) {
                    _progress.update {
                        it.copy(currentStageText = "首次使用，正在通过国内镜像下载「${asrEngine.label}」离线模型…")
                    }
                    val monitorJob = launch {
                        speechRecognizer.downloadProgress.collect { dl ->
                            if (dl != null && dl.engine == asrEngine) {
                                _progress.update { state ->
                                    state.copy(
                                        currentStageText = dl.statusText,
                                        currentVideoFraction = dl.progressFraction
                                    )
                                }
                            }
                        }
                    }
                    val dlResult = try {
                        speechRecognizer.downloadOfflineModel(asrEngine)
                    } finally {
                        monitorJob.cancel()
                    }
                    if (dlResult.isFailure) {
                        throw dlResult.exceptionOrNull()
                            ?: IllegalStateException("离线语音模型下载失败，请检查网络连接")
                    }
                }

                // 2. Gather targets (current folder or recursive subfolders)
                val allTargets = targetsProvider()
                if (allTargets.isEmpty()) {
                    _progress.update {
                        it.copy(
                            isRunning = false,
                            currentStageText = "当前目录未找到可处理的视频文件",
                            summaryMessage = "目录「$directoryTitle」中未发现视频文件"
                        )
                    }
                    return@launch
                }

                _progress.update {
                    it.copy(
                        totalVideos = allTargets.size,
                        currentVideoFraction = 0f,
                        currentStageText = "共发现 ${allTargets.size} 个视频，开始批量生成字幕…"
                    )
                }

                // 3. Process each video sequentially (preserving completed .srt files immediately on disk/SMB)
                for (target in allTargets) {
                    coroutineContext.ensureActive()
                    val video = target.video

                    if (config.skipExistingSubtitles &&
                        (video.sidecarSubtitlePaths.isNotEmpty() ||
                            generatedSubtitleStore.hasIndexedSubtitle(video.uriString))
                    ) {
                        _progress.update { state ->
                            state.copy(
                                skippedVideos = state.skippedVideos + 1,
                                currentVideoName = video.title,
                                currentVideoFraction = 0f,
                                logs = appendLog(state.logs, "⏭ 跳过（已有字幕）：${video.title}")
                            )
                        }
                        continue
                    }

                    _progress.update { state ->
                        state.copy(
                            currentVideoName = video.title,
                            currentVideoFraction = 0.02f,
                            currentStageText = "正在打开视频并解复用音轨（跳过画面）…"
                        )
                    }

                    val singleOutcome = runCatching {
                        processSingleVideo(target, config)
                    }

                    if (singleOutcome.isSuccess) {
                        val outcome = singleOutcome.getOrThrow()
                        val destTag = if (outcome.writeDestination == SubtitleWriteDestination.ORIGINAL_DIRECTORY) {
                            "已直接写回原目录"
                        } else {
                            "目录只读，已存入本地自动加载索引"
                        }
                        _progress.update { state ->
                            state.copy(
                                completedVideos = state.completedVideos + 1,
                                writtenBackCount = state.writtenBackCount +
                                    if (outcome.writeDestination == SubtitleWriteDestination.ORIGINAL_DIRECTORY) 1 else 0,
                                localFallbackCount = state.localFallbackCount +
                                    if (outcome.writeDestination == SubtitleWriteDestination.LOCAL_CACHE_FALLBACK) 1 else 0,
                                currentVideoFraction = 1f,
                                logs = appendLog(
                                    state.logs,
                                    "✅ ${video.title}（${outcome.cueCount} 句 · ${outcome.decoderName} · $destTag）"
                                )
                            )
                        }
                        onVideoSubtitleWritten()
                    } else {
                        val err = singleOutcome.exceptionOrNull()
                        if (err is CancellationException) throw err
                        _progress.update { state ->
                            state.copy(
                                failedVideos = state.failedVideos + 1,
                                currentVideoFraction = 0f,
                                logs = appendLog(
                                    state.logs,
                                    "❌ ${video.title}：${err?.message?.take(72) ?: "处理失败"}"
                                )
                            )
                        }
                    }
                }

                val finalState = _progress.value
                val summary = buildString {
                    append("批量字幕生成完成：成功 ${finalState.completedVideos} 个")
                    if (finalState.skippedVideos > 0) append("，跳过 ${finalState.skippedVideos} 个")
                    if (finalState.failedVideos > 0) append("，失败 ${finalState.failedVideos} 个")
                    if (finalState.writtenBackCount > 0) append("（${finalState.writtenBackCount} 个已直接写入原目录")
                    if (finalState.localFallbackCount > 0) {
                        append(if (finalState.writtenBackCount > 0) "，" else "（")
                        append("${finalState.localFallbackCount} 个已存入本地自动加载库")
                    }
                    if (finalState.writtenBackCount > 0 || finalState.localFallbackCount > 0) append("）")
                }

                _progress.update {
                    it.copy(
                        isRunning = false,
                        isCancelling = false,
                        currentVideoFraction = 1f,
                        currentStageText = summary,
                        summaryMessage = summary
                    )
                }
            } catch (_: CancellationException) {
                val cur = _progress.value
                val msg = "已取消批量任务（已完成并保存 ${cur.completedVideos} 个字幕，下次会自动跳过）"
                _progress.update {
                    it.copy(
                        isRunning = false,
                        isCancelling = false,
                        currentStageText = msg,
                        summaryMessage = msg
                    )
                }
            } catch (err: Exception) {
                val msg = "批量生成中止：${err.message ?: "未知错误"}"
                _progress.update {
                    it.copy(
                        isRunning = false,
                        isCancelling = false,
                        currentStageText = msg,
                        summaryMessage = msg
                    )
                }
            } finally {
                BatchSubtitleService.stop(appContext)
            }
        }
    }

    private data class SingleVideoOutcome(
        val cueCount: Int,
        val decoderName: String,
        val writeDestination: SubtitleWriteDestination
    )

    private suspend fun processSingleVideo(
        target: BatchSubtitleTarget,
        config: BatchSubtitleJobConfig
    ): SingleVideoOutcome {
        val video = target.video
        val (streamUri, headers) = resolveStreamSource(video)
        val rawCues = mutableListOf<SubtitleCue>()
        var recognizedCount = 0

        val vadSegmenter = SileroVadSegmenter(
            assetManager = appContext.assets,
            useSileroVad = config.useSileroVad
        )
        val vadLabel = if (vadSegmenter.isUsingSileroNative) "Silero VAD 精准断句" else "能量断句"
        _progress.update { it.copy(usingSileroVad = vadSegmenter.isUsingSileroNative) }

        val summary = try {
            val extractSummary = audioExtractor.extractAudioTo16kMono(
                uriString = streamUri,
                httpHeaders = headers,
                onProgress = { extractFrac, decodedMs ->
                    val scaled = (extractFrac * 0.78f).coerceIn(0.02f, 0.78f)
                    _progress.update { state ->
                        state.copy(
                            currentVideoFraction = scaled,
                            currentStageText = "$vadLabel + AI 识别中 · 已处理 ${formatDurationMs(decodedMs)}（已识别 $recognizedCount 句）"
                        )
                    }
                },
                onPcm16kChunk = { chunk, validLen ->
                    val segments = vadSegmenter.acceptSamples(chunk, validLen)
                    for (seg in segments) {
                        coroutineContext.ensureActive()
                        val text = speechRecognizer.transcribeSegment(seg.samples16k, config.translationConfig)
                            .getOrThrow()
                            .trim()
                        if (text.isNotEmpty()) {
                            recognizedCount++
                            rawCues.add(
                                SubtitleCue(
                                    index = recognizedCount,
                                    startTimeMs = seg.startTimeMs,
                                    endTimeMs = seg.endTimeMs,
                                    originalText = text
                                )
                            )
                        }
                    }
                }
            )

            // Flush final speech segment at EOF
            for (seg in vadSegmenter.flush()) {
                coroutineContext.ensureActive()
                val text = speechRecognizer.transcribeSegment(seg.samples16k, config.translationConfig)
                    .getOrThrow()
                    .trim()
                if (text.isNotEmpty()) {
                    recognizedCount++
                    rawCues.add(
                        SubtitleCue(
                            index = recognizedCount,
                            startTimeMs = seg.startTimeMs,
                            endTimeMs = seg.endTimeMs,
                            originalText = text
                        )
                    )
                }
            }
            extractSummary
        } finally {
            vadSegmenter.close()
        }

        if (rawCues.isEmpty()) {
            throw IllegalStateException("未在音轨中检测到有效人声对白")
        }

        // Refine cue lengths & ensure non-overlapping timestamps
        val refinedCues = SubtitleCueRefiner.refineCues(rawCues)

        // Batch translation if bilingual or target-only output requested
        val finalCues: List<SubtitleCue> = if (config.outputFormat == BatchSubtitleOutputFormat.ORIGINAL_ONLY) {
            refinedCues
        } else {
            translateAllCuesInBatches(refinedCues, config)
        }

        // Render standard UTF-8 .srt text
        val srtText = renderSrtForFormat(finalCues, config.outputFormat)
        val srtFileName = config.outputFormat.buildFileName(
            videoFileName = video.title,
            targetLangIso = config.translationConfig.targetLanguage.isoCode
        )

        _progress.update {
            it.copy(
                currentVideoFraction = 0.96f,
                currentStageText = "正在将 $srtFileName 写入视频目录…"
            )
        }

        val writeResult = generatedSubtitleStore.writeSubtitleForTarget(
            target = target,
            srtFileName = srtFileName,
            srtContentUtf8 = srtText
        )

        return SingleVideoOutcome(
            cueCount = finalCues.size,
            decoderName = summary.decoderName,
            writeDestination = writeResult.destination
        )
    }

    private suspend fun translateAllCuesInBatches(
        cues: List<SubtitleCue>,
        config: BatchSubtitleJobConfig
    ): List<SubtitleCue> {
        val mutableCues = cues.toMutableList()
        val batchSize = 14
        var startIdx = 0
        while (startIdx < mutableCues.size) {
            coroutineContext.ensureActive()
            val endIdx = (startIdx + batchSize).coerceAtMost(mutableCues.size)
            val progressFrac = 0.78f + (startIdx.toFloat() / mutableCues.size.coerceAtLeast(1)) * 0.17f
            _progress.update {
                it.copy(
                    currentVideoFraction = progressFrac.coerceIn(0.78f, 0.95f),
                    currentStageText = "正在批量翻译字幕 ($endIdx / ${mutableCues.size} 句)…"
                )
            }
            val transRes = translationRepository.translateCueWindow(
                cues = mutableCues,
                startIndex = startIdx,
                windowSize = batchSize,
                config = config.translationConfig,
                onUpdatedCues = { updates ->
                    for (i in startIdx until endIdx) {
                        val translated = updates[mutableCues[i].index]
                        if (!translated.isNullOrBlank()) {
                            mutableCues[i] = mutableCues[i].copy(
                                translatedText = translated,
                                runtimeTranslation = true
                            )
                        }
                    }
                }
            )
            // Keep going even if one translation batch transiently fails — original speech is never lost!
            transRes.exceptionOrNull()?.let { err ->
                if (err is CancellationException) throw err
            }
            startIdx = endIdx
        }
        return mutableCues
    }

    private suspend fun resolveStreamSource(video: com.padvideo.data.model.VideoItem): Pair<String, Map<String, String>> {
        if (video.uriString.startsWith("quark://", ignoreCase = true)) {
            val mountId = video.mountSourceId
            val fid = video.remoteFileId ?: video.uriString.substringAfterLast('/')
            val mount = mountId?.let { settingsRepository.getMountedSource(it) }
                ?: throw IllegalStateException("未找到对应夸克网盘挂载信息")
            return quarkDriveRepository.resolveStreamUrl(mount, fid)
        }
        val headers = video.httpHeaders.ifEmpty {
            video.mountSourceId
                ?.let { settingsRepository.getMountedSource(it) }
                ?.takeIf { it.type == StorageMountType.WEBDAV }
                ?.let { webDavRepository.buildAuthHeaders(it) }
                ?: emptyMap()
        }
        return video.uriString to headers
    }

    private fun appendLog(existing: List<String>, line: String): List<String> {
        return (existing + line).takeLast(60)
    }

    companion object {
        fun renderSrtForFormat(cues: List<SubtitleCue>, format: BatchSubtitleOutputFormat): String {
            return when (format) {
                BatchSubtitleOutputFormat.BILINGUAL -> {
                    SubtitleParser.exportToSrt(cues, includeTranslation = true)
                }
                BatchSubtitleOutputFormat.ORIGINAL_ONLY -> {
                    SubtitleParser.exportToSrt(cues, includeTranslation = false)
                }
                BatchSubtitleOutputFormat.TARGET_ONLY -> {
                    val targetOnly = cues.map { cue ->
                        val text = cue.translatedText?.takeIf { it.isNotBlank() } ?: cue.originalText
                        cue.copy(originalText = text, translatedText = null)
                    }
                    SubtitleParser.exportToSrt(targetOnly, includeTranslation = false)
                }
            }
        }
    }
}
