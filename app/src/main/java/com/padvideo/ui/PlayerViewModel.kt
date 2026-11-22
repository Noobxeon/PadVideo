package com.padvideo.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.padvideo.AppContainer
import com.padvideo.data.model.AbLoopState
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.AsrEngineType
import com.padvideo.data.model.AspectRatioMode
import com.padvideo.data.model.GestureHudState
import com.padvideo.data.model.GestureHudType
import com.padvideo.data.model.OnlineSubtitleResult
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.SleepTimerOption
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleStyleConfig
import com.padvideo.data.model.TranslationConfig
import com.padvideo.data.model.TranslationDisplayMode
import com.padvideo.data.model.TranslationEngineType
import com.padvideo.data.model.VideoBookmark
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatDurationMs
import com.padvideo.data.model.formatSignedMs
import com.padvideo.player.AsrModelDownloadProgress
import com.padvideo.player.CapturedAudioSegment
import com.padvideo.player.PadPlayerController
import com.padvideo.subtitle.SubtitleParser
import com.padvideo.subtitle.VideoFilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PlayerUiState(
    val currentVideo: VideoItem? = null,
    val playlist: List<VideoItem> = emptyList(),
    val currentPlaylistIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val controlsVisible: Boolean = true,
    val isScreenLocked: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isLongPressBoostActive: Boolean = false,
    val volumePercent: Int = 100,
    val brightnessFraction: Float = 0.65f,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val rotationDegrees: Int = 0,
    val isMirrored: Boolean = false,
    val zoomScale: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val abLoop: AbLoopState = AbLoopState(),
    val sleepTimerOption: SleepTimerOption = SleepTimerOption.OFF,
    val sleepTimerRemainingSec: Long = 0L,
    val gestureHud: GestureHudState? = null,
    val subtitlesEnabled: Boolean = true,
    val activeExternalSubtitlePath: String? = null,
    val externalCues: List<SubtitleCue> = emptyList(),
    val activeCueIndex: Int = -1,
    val displayPrimaryLine: String = "",
    val displaySecondaryLine: String = "",
    val isTranslatingNow: Boolean = false,
    val translationError: String? = null,
    val showTranscriptDrawer: Boolean = false,
    val snackbarMessage: String? = null,
    // Online Subtitle Search Sheet State
    val onlineQuery: String = "",
    val onlineLanguageFilter: String = "all",
    val isSearchingOnline: Boolean = false,
    val isDownloadingOnlineSub: Boolean = false,
    val onlineResults: List<OnlineSubtitleResult> = emptyList(),
    val onlineStatusMessage: String? = null
)

class PlayerViewModel(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val asrDownloadProgress: StateFlow<AsrModelDownloadProgress?> = container.videoSpeechRecognizer.downloadProgress
    private val _readyAsrEngines = MutableStateFlow(computeReadyAsrEngines())
    val readyAsrEngines: StateFlow<Set<AsrEngineType>> = _readyAsrEngines.asStateFlow()

    val playerController = PadPlayerController(
        context = appContext,
        smbRepository = container.smbRepository,
        preferHardwareDecoder = settings.value.hardwareDecoderPreferred
    )

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var hudDismissJob: Job? = null
    private var lookaheadTranslationJob: Job? = null
    private var embeddedCueTranslationJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var asrLiveCaptionJob: Job? = null
    private var asrDownloadJob: Job? = null
    private var liveAsrPrimaryText: String = ""
    private var liveAsrSecondaryText: String = ""
    private var liveAsrExpiresAtMs: Long = 0L
    private var lastTranslatedWindowStart = -1
    private var lookaheadRetryAtMs = 0L
    private var playbackGeneration = 0
    private var preLongPressSpeed: Float = 1.0f
    private var cumulativeDoubleTapMs: Long = 0L
    private var lastDoubleTapTimestamp: Long = 0L

    init {
        startPlaybackTickerLoop()
        observePlayerEvents()
    }

    private fun startPlaybackTickerLoop() {
        viewModelScope.launch {
            var tickCounter = 0
            while (isActive) {
                val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
                playerController.updateCachedPlaybackPosition(pos)
                val dur = playerController.exoPlayer.duration.takeIf { it > 0L }
                    ?: _uiState.value.durationMs

                // Enforce A-B Loop
                val ab = _uiState.value.abLoop
                if (ab.isActive && ab.pointAMs != null && ab.pointBMs != null && pos >= ab.pointBMs) {
                    playerController.seekTo(ab.pointAMs)
                }

                updateActiveSubtitleAtPosition(pos)

                _uiState.update { state ->
                    state.copy(positionMs = pos, durationMs = dur)
                }

                // Persist progress every ~3 seconds while playing
                tickCounter++
                if (tickCounter % 25 == 0 && playerController.isPlaying.value) {
                    saveCurrentPlaybackHistory()
                }

                delay(120L)
            }
        }
    }

    private fun observePlayerEvents() {
        viewModelScope.launch {
            playerController.liveEmbeddedSubtitleCue.collect { embeddedText ->
                if (_uiState.value.externalCues.isEmpty()) {
                    handleLiveEmbeddedCueUpdate(embeddedText)
                }
            }
        }

        viewModelScope.launch {
            playerController.playbackEndedCount.collect { endedCount ->
                if (endedCount > 0) {
                    saveCurrentPlaybackHistory()
                    if (_uiState.value.sleepTimerOption == SleepTimerOption.END_OF_VIDEO) {
                        setSleepTimer(SleepTimerOption.OFF)
                        postMessage("已按睡眠定时在当前视频结束时暂停")
                    }
                }
            }
        }
    }

    fun startLaunch(launch: ActivePlaybackLaunch) {
        playbackGeneration++
        stopVideoAudioAsr()
        resetTranslationWork()
        val video = launch.video
        val playlist = launch.playlist.ifEmpty { listOf(video) }
        val index = playlist.indexOfFirst { it.uriString == video.uriString }.coerceAtLeast(0)

        val savedHistory = container.settingsRepository.getHistoryItem(video.uriString)
        val resumeMs = if (!launch.forceStartFromBeginning && settings.value.autoResumePlayback) {
            savedHistory?.positionMs?.takeIf { pos ->
                val total = savedHistory.durationMs
                pos > 2_000L && (total <= 0L || pos < total - 5_000L)
            } ?: 0L
        } else {
            0L
        }

        val cleanedQuery = VideoFilenameParser.parse(video.title).toDisplayQuery()
        val initialSpeed = savedHistory?.playbackSpeed ?: 1.0f

        _uiState.update {
            PlayerUiState(
                currentVideo = video,
                playlist = playlist,
                currentPlaylistIndex = index,
                playbackSpeed = initialSpeed,
                brightnessFraction = it.brightnessFraction,
                volumePercent = it.volumePercent,
                onlineQuery = cleanedQuery
            )
        }
        playerController.setPlaybackSpeed(initialSpeed)

        // Resolve Quark Drive stream URL dynamically or load Local / SAF / SMB / WebDAV / HTTP directly
        if (video.uriString.startsWith("quark://")) {
            val mountId = video.mountSourceId
            val fid = video.remoteFileId ?: video.uriString.substringAfterLast('/')
            val mount = mountId?.let { container.settingsRepository.getMountedSource(it) }
            if (mount != null && fid.isNotBlank()) {
                val gen = playbackGeneration
                postMessage("正在解析夸克网盘高速直链…")
                viewModelScope.launch {
                    runCatching { container.quarkDriveRepository.resolveStreamUrl(mount, fid) }
                        .onSuccess { (streamUrl, headers) ->
                            if (gen == playbackGeneration) {
                                playerController.loadVideo(streamUrl, video.title, resumeMs, headers)
                            }
                        }
                        .onFailure { err ->
                            if (gen == playbackGeneration) {
                                postMessage(err.message ?: "夸克网盘直链解析失败，请重新登录")
                            }
                        }
                }
            } else {
                postMessage("找不到对应的夸克网盘挂载源，请重新挂载")
            }
        } else {
            val headers = video.httpHeaders.ifEmpty {
                video.mountSourceId
                    ?.let { container.settingsRepository.getMountedSource(it) }
                    ?.takeIf { it.type == com.padvideo.data.model.StorageMountType.WEBDAV }
                    ?.let { container.webDavRepository.buildAuthHeaders(it) }
                    ?: emptyMap()
            }
            if (video.uriString.startsWith("smb://")) {
                container.smbRepository.registerSavedMounts(container.settingsRepository.mountedSources.value)
            }
            playerController.loadVideo(video.uriString, video.title, resumeMs, headers)
        }

        // Auto-load saved external subtitle, sidecar subtitle (local/SAF/SMB/WebDAV/Quark), or inline demo subtitle
        val preferredSubPath = savedHistory?.externalSubtitlePath?.takeIf { File(it).exists() }
            ?: video.sidecarSubtitlePaths.firstOrNull()

        if (preferredSubPath != null) {
            loadSidecarSubtitlePath(preferredSubPath, video)
        } else if (!launch.inlineInitialSrt.isNullOrBlank()) {
            val demoCues = SubtitleParser.parseText(launch.inlineInitialSrt, "demo.srt")
            _uiState.update {
                it.copy(
                    externalCues = demoCues,
                    activeExternalSubtitlePath = null,
                    subtitlesEnabled = true
                )
            }
            // Enable bilingual translation automatically on the Sintel demo if user hasn't chosen a mode yet
            if (settings.value.translationConfig.displayMode == TranslationDisplayMode.OFF) {
                updateTranslationConfig { cfg -> cfg.copy(displayMode = TranslationDisplayMode.BILINGUAL) }
            }
            triggerLookaheadTranslation(0, force = true)
        }

        if (resumeMs > 0L) {
            postMessage("已自动恢复上次播放进度 ${formatDurationMs(resumeMs)}")
        }

        // Automatically resume direct video-audio AI speech recognition if user kept it enabled
        if (settings.value.translationConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) {
            startVideoAudioAsr(settings.value.translationConfig)
        }
    }

    private fun loadSidecarSubtitlePath(pathOrUri: String, video: VideoItem) {
        val gen = playbackGeneration
        when {
            pathOrUri.startsWith("content://") -> {
                loadExternalSubtitleUri(Uri.parse(pathOrUri))
            }
            pathOrUri.startsWith("smb://") -> {
                viewModelScope.launch {
                    runCatching {
                        val loc = com.padvideo.data.repository.SmbLocation.parse(pathOrUri)
                        val bytes = container.smbRepository.readSmallFileBytes(loc)
                        val cues = SubtitleParser.parseBytes(bytes, loc.segments.lastOrNull() ?: "smb.srt")
                        if (gen == playbackGeneration && cues.isNotEmpty()) {
                            applyLoadedExternalCues(cues, null)
                        }
                    }
                }
            }
            pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://") -> {
                viewModelScope.launch {
                    runCatching {
                        val headers = video.httpHeaders.ifEmpty {
                            video.mountSourceId
                                ?.let { container.settingsRepository.getMountedSource(it) }
                                ?.let { container.webDavRepository.buildAuthHeaders(it) }
                                ?: emptyMap()
                        }
                        val bytes = container.webDavRepository.downloadBytes(pathOrUri, headers)
                        val cues = SubtitleParser.parseBytes(bytes, pathOrUri.substringAfterLast('/'))
                        if (gen == playbackGeneration && cues.isNotEmpty()) {
                            applyLoadedExternalCues(cues, null)
                        }
                    }
                }
            }
            pathOrUri.startsWith("quark://") -> {
                val mount = video.mountSourceId?.let { container.settingsRepository.getMountedSource(it) } ?: return
                val parts = pathOrUri.removePrefix("quark://").split('/')
                val subFid = parts.getOrNull(1) ?: return
                val subName = parts.getOrNull(2) ?: "quark.srt"
                viewModelScope.launch {
                    runCatching {
                        val bytes = container.quarkDriveRepository.downloadSubtitleBytes(mount, subFid)
                        val cues = SubtitleParser.parseBytes(bytes, subName)
                        if (gen == playbackGeneration && cues.isNotEmpty()) {
                            applyLoadedExternalCues(cues, null)
                        }
                    }
                }
            }
            else -> {
                val f = File(pathOrUri)
                if (f.exists()) loadExternalSubtitleFile(f, silent = true)
            }
        }
    }

    private fun applyLoadedExternalCues(cues: List<SubtitleCue>, savedPath: String?) {
        playerController.selectEmbeddedSubtitleTrack(null)
        resetTranslationWork()
        _uiState.update {
            it.copy(
                externalCues = cues,
                activeExternalSubtitlePath = savedPath,
                subtitlesEnabled = true
            )
        }
        val mode = settings.value.translationConfig.displayMode
        if (mode == TranslationDisplayMode.BILINGUAL || mode == TranslationDisplayMode.TARGET_ONLY) {
            val idx = SubtitleParser.findCueIndexAtOrAfter(cues, _uiState.value.positionMs).coerceAtLeast(0)
            triggerLookaheadTranslation(idx, force = true)
        }
    }

    fun playPlaylistIndex(index: Int) {
        val list = _uiState.value.playlist
        if (index !in list.indices) return
        saveCurrentPlaybackHistory()
        startLaunch(ActivePlaybackLaunch(video = list[index], playlist = list))
    }

    fun playPreviousEpisode() {
        val prev = _uiState.value.currentPlaylistIndex - 1
        if (prev >= 0) playPlaylistIndex(prev) else postMessage("已经是第一集")
    }

    fun playNextEpisode() {
        val next = _uiState.value.currentPlaylistIndex + 1
        if (next < _uiState.value.playlist.size) playPlaylistIndex(next) else postMessage("已经是最后一集")
    }

    private fun updateActiveSubtitleAtPosition(positionMs: Long) {
        val state = _uiState.value
        if (state.currentVideo == null) return
        if (!state.subtitlesEnabled) {
            if (state.displayPrimaryLine.isNotEmpty() || state.displaySecondaryLine.isNotEmpty()) {
                _uiState.update { it.copy(displayPrimaryLine = "", displaySecondaryLine = "", activeCueIndex = -1) }
            }
            return
        }

        val cues = state.externalCues
        val transConfig = settings.value.translationConfig

        // In Live Video-Audio ASR mode, show the latest live recognized/translated caption smoothly while playing
        if (transConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) {
            val nowMs = SystemClock.elapsedRealtime()
            if (liveAsrPrimaryText.isNotBlank() && nowMs <= liveAsrExpiresAtMs) {
                if (state.displayPrimaryLine != liveAsrPrimaryText || state.displaySecondaryLine != liveAsrSecondaryText) {
                    _uiState.update {
                        it.copy(
                            displayPrimaryLine = liveAsrPrimaryText,
                            displaySecondaryLine = liveAsrSecondaryText,
                            activeCueIndex = (cues.lastIndex).coerceAtLeast(-1)
                        )
                    }
                }
                return
            } else if (cues.isEmpty()) {
                if (state.displayPrimaryLine.isNotEmpty() || state.displaySecondaryLine.isNotEmpty()) {
                    _uiState.update { it.copy(displayPrimaryLine = "", displaySecondaryLine = "") }
                }
                return
            }
        }

        if (cues.isEmpty()) return

        val offsetMs = settings.value.subtitleStyle.syncOffsetMs
        val activeCues = SubtitleParser.findActiveCues(cues, positionMs, offsetMs)
        val nearestIdx = SubtitleParser.findCueIndexAtOrAfter(cues, positionMs, offsetMs)

        if (transConfig.displayMode == TranslationDisplayMode.BILINGUAL ||
            transConfig.displayMode == TranslationDisplayMode.TARGET_ONLY
        ) {
            if (nearestIdx >= 0) triggerLookaheadTranslation(nearestIdx)
        }

        if (activeCues.isEmpty()) {
            if (state.displayPrimaryLine.isNotEmpty() || state.displaySecondaryLine.isNotEmpty()) {
                _uiState.update {
                    it.copy(displayPrimaryLine = "", displaySecondaryLine = "", activeCueIndex = nearestIdx)
                }
            }
            return
        }

        val origCombined = activeCues.joinToString("\n") { it.originalText.trim() }
        val transCombined = activeCues.mapNotNull { it.translatedText?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .joinToString("\n")

        val (primary, secondary) = formatDisplayLines(
            originalText = origCombined,
            translatedText = transCombined,
            mode = transConfig.displayMode
        )

        _uiState.update {
            it.copy(
                activeCueIndex = activeCues.first().index - 1,
                displayPrimaryLine = primary,
                displaySecondaryLine = secondary
            )
        }
    }

    private fun handleLiveEmbeddedCueUpdate(embeddedText: String) {
        embeddedCueTranslationJob?.cancel()
        val state = _uiState.value
        if (state.currentVideo == null || !state.subtitlesEnabled || state.externalCues.isNotEmpty()) return
        val cleaned = SubtitleParser.cleanSubtitleText(embeddedText)
        val transConfig = settings.value.translationConfig
        if (transConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) return
        if (cleaned.isEmpty() || transConfig.displayMode == TranslationDisplayMode.OFF) {
            _uiState.update {
                it.copy(displayPrimaryLine = cleaned, displaySecondaryLine = "", isTranslatingNow = false)
            }
            return
        }

        val cached = container.translationRepository.getCachedTranslation(cleaned, transConfig)
        if (cached != null) {
            val (primary, secondary) = formatDisplayLines(cleaned, cached, transConfig.displayMode)
            _uiState.update {
                it.copy(
                    displayPrimaryLine = primary, displaySecondaryLine = secondary,
                    isTranslatingNow = false, translationError = null
                )
            }
            return
        }

        // Show original immediately while fetching live translation
        _uiState.update {
            it.copy(displayPrimaryLine = cleaned, displaySecondaryLine = "", isTranslatingNow = true)
        }
        embeddedCueTranslationJob = viewModelScope.launch {
            val result = container.translationRepository.translateSingle(cleaned, transConfig)
            val (primary, secondary) = formatDisplayLines(cleaned, result.getOrDefault(""), transConfig.displayMode)
            _uiState.update {
                it.copy(
                    displayPrimaryLine = primary,
                    displaySecondaryLine = secondary,
                    isTranslatingNow = false,
                    translationError = result.exceptionOrNull()?.let { error -> translationError(transConfig, error) }
                )
            }
        }
    }

    private suspend fun processCapturedVideoAudioSegment(segment: CapturedAudioSegment) {
        val state = _uiState.value
        val config = settings.value.translationConfig
        if (state.currentVideo == null || config.displayMode != TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) return

        val generation = playbackGeneration
        _uiState.update { it.copy(isTranslatingNow = true) }

        val asrResult = container.videoSpeechRecognizer.transcribeSegment(segment.samples16k, config)
        if (generation != playbackGeneration) return

        val spokenText = asrResult.getOrElse { err ->
            _uiState.update {
                it.copy(
                    isTranslatingNow = false,
                    translationError = err.message ?: "视频原声识别失败"
                )
            }
            return
        }.trim()

        if (spokenText.isEmpty()) {
            _uiState.update { it.copy(isTranslatingNow = false) }
            return
        }

        // Show recognized original speech immediately while translating
        liveAsrPrimaryText = spokenText
        liveAsrSecondaryText = ""
        liveAsrExpiresAtMs = SystemClock.elapsedRealtime() + 5_500L
        _uiState.update {
            it.copy(
                displayPrimaryLine = spokenText,
                displaySecondaryLine = "",
                translationError = null
            )
        }

        val transResult = container.translationRepository.translateSingle(spokenText, config)
        if (generation != playbackGeneration) return

        val translated = transResult.getOrNull()?.trim().orEmpty()
        val (primary, secondary) = formatDisplayLines(spokenText, translated, TranslationDisplayMode.BILINGUAL)
        liveAsrPrimaryText = primary
        liveAsrSecondaryText = secondary
        liveAsrExpiresAtMs = SystemClock.elapsedRealtime() + 6_500L

        val newCue = SubtitleCue(
            index = (_uiState.value.externalCues.maxOfOrNull { it.index } ?: 0) + 1,
            startTimeMs = segment.startPositionMs,
            endTimeMs = segment.endPositionMs.coerceAtLeast(segment.startPositionMs + 1_500L),
            originalText = spokenText,
            translatedText = translated.ifBlank { null },
            runtimeTranslation = true
        )

        _uiState.update { current ->
            val updatedCues = (current.externalCues + newCue).sortedBy { it.startTimeMs }
            current.copy(
                externalCues = updatedCues,
                activeCueIndex = updatedCues.lastIndex,
                displayPrimaryLine = primary,
                displaySecondaryLine = secondary,
                isTranslatingNow = false,
                translationError = transResult.exceptionOrNull()?.let { err -> translationError(config, err) }
            )
        }
    }

    private fun computeReadyAsrEngines(): Set<AsrEngineType> =
        AsrEngineType.entries.filter { container.videoSpeechRecognizer.isModelReady(it) }.toSet()

    fun isAsrModelReady(engine: AsrEngineType): Boolean =
        container.videoSpeechRecognizer.isModelReady(engine)

    fun refreshReadyAsrEngines() {
        _readyAsrEngines.value = computeReadyAsrEngines()
    }

    fun downloadAsrModel(
        engine: AsrEngineType,
        autoEnableLiveSpeech: Boolean = false,
        onFinished: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        asrDownloadJob?.cancel()
        asrDownloadJob = viewModelScope.launch {
            val res = container.videoSpeechRecognizer.downloadOfflineModel(engine)
            refreshReadyAsrEngines()
            res.onSuccess {
                val msg = if (autoEnableLiveSpeech || settings.value.translationConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) {
                    "离线模型已就绪，正在开启视频原声 AI 实时识别 + 翻译"
                } else {
                    "离线模型已下载就绪，可随时开启无字幕视频原声识别"
                }
                onFinished(true, msg)
                postMessage(msg)
                if (autoEnableLiveSpeech) {
                    updateTranslationConfig { cfg ->
                        cfg.copy(
                            asrEngine = engine,
                            displayMode = TranslationDisplayMode.LIVE_SPEECH_BILINGUAL
                        )
                    }
                    startVideoAudioAsr(settings.value.translationConfig)
                } else if (settings.value.translationConfig.asrEngine == engine &&
                    settings.value.translationConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL
                ) {
                    startVideoAudioAsr(settings.value.translationConfig)
                }
            }.onFailure { err ->
                val msg = err.message ?: "模型下载失败，请检查网络连接"
                onFinished(false, msg)
                postMessage(msg)
            }
        }
    }

    fun deleteAsrModel(engine: AsrEngineType) {
        if (settings.value.translationConfig.asrEngine == engine) {
            stopVideoAudioAsr()
        }
        container.videoSpeechRecognizer.deleteModel(engine)
        refreshReadyAsrEngines()
        postMessage("已清理 ${engine.label} 离线模型缓存")
    }

    private fun translationError(config: TranslationConfig, error: Throwable): String =
        if (config.engine == TranslationEngineType.MLKIT_OFFLINE) {
            error.message?.takeIf { it.startsWith("请") || it.startsWith("端侧") || it.startsWith("尚未") }
                ?: "离线翻译失败：${error.message ?: "请检查源语言和离线模型"}"
        } else {
            "实时翻译失败：${error.message?.take(60) ?: "请检查网络或切换翻译引擎"}"
        }

    private fun formatDisplayLines(
        originalText: String,
        translatedText: String,
        mode: TranslationDisplayMode
    ): Pair<String, String> {
        return when (mode) {
            TranslationDisplayMode.OFF -> originalText to ""
            TranslationDisplayMode.TARGET_ONLY -> {
                if (translatedText.isNotBlank()) translatedText to "" else originalText to ""
            }
            TranslationDisplayMode.BILINGUAL, TranslationDisplayMode.LIVE_SPEECH_BILINGUAL -> {
                if (translatedText.isNotBlank() && !translatedText.equals(originalText, ignoreCase = true)) {
                    translatedText to originalText
                } else {
                    originalText to ""
                }
            }
        }
    }

    private fun resetTranslationWork() {
        lookaheadTranslationJob?.cancel()
        embeddedCueTranslationJob?.cancel()
        lastTranslatedWindowStart = -1
        lookaheadRetryAtMs = 0L
        _uiState.update { it.copy(isTranslatingNow = false, translationError = null) }
    }

    private fun triggerLookaheadTranslation(startIndex: Int, force: Boolean = false) {
        val state = _uiState.value
        val cues = state.externalCues
        if (!state.subtitlesEnabled || startIndex !in cues.indices) return

        val config = settings.value.translationConfig
        if (config.displayMode !in listOf(TranslationDisplayMode.BILINGUAL, TranslationDisplayMode.TARGET_ONLY)) return

        val windowSize = config.lookaheadCueCount.coerceIn(8, 40)
        val inProgress = startIndex in lastTranslatedWindowStart until lastTranslatedWindowStart + windowSize
        if (!force && inProgress && lookaheadTranslationJob?.isActive == true) return
        if (!force && SystemClock.elapsedRealtime() < lookaheadRetryAtMs) return
        val end = (startIndex + windowSize).coerceAtMost(cues.size)
        if (cues.subList(startIndex, end).none { it.translatedText.isNullOrBlank() && it.originalText.isNotBlank() }) return

        lastTranslatedWindowStart = startIndex
        lookaheadTranslationJob?.cancel()
        lookaheadTranslationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslatingNow = true) }
            val result = container.translationRepository.translateCueWindow(
                cues = cues,
                startIndex = startIndex,
                windowSize = windowSize,
                config = config,
                onUpdatedCues = { updatesByIndex ->
                    _uiState.update { current ->
                        current.copy(externalCues = current.externalCues.map { cue ->
                            val translated = updatesByIndex[cue.index]
                            if (translated == null) cue
                            else cue.copy(translatedText = translated, runtimeTranslation = true)
                        })
                    }
                }
            )
            if (result.isFailure) lookaheadRetryAtMs = SystemClock.elapsedRealtime() + 4_000
            _uiState.update {
                it.copy(
                    isTranslatingNow = false,
                    translationError = result.exceptionOrNull()?.let { error -> translationError(config, error) }
                )
            }
        }
    }

    fun loadExternalSubtitleFile(file: File, silent: Boolean = false) {
        viewModelScope.launch {
            val generation = playbackGeneration
            val cues = withContext(Dispatchers.IO) { SubtitleParser.parseFile(file) }
            if (generation != playbackGeneration) return@launch
            if (cues.isEmpty()) {
                if (!silent) postMessage("字幕文件解析为空: ${file.name}")
                return@launch
            }
            playerController.selectEmbeddedSubtitleTrack(null)
            resetTranslationWork()
            _uiState.update {
                it.copy(
                    externalCues = cues,
                    activeExternalSubtitlePath = file.absolutePath,
                    subtitlesEnabled = true
                )
            }
            if (!silent) {
                postMessage("已挂载字幕: ${file.name} (${cues.size} 条台词)")
            }
            val mode = settings.value.translationConfig.displayMode
            if (mode == TranslationDisplayMode.BILINGUAL || mode == TranslationDisplayMode.TARGET_ONLY) {
                val startIdx = SubtitleParser.findCueIndexAtOrAfter(cues, _uiState.value.positionMs).coerceAtLeast(0)
                triggerLookaheadTranslation(startIdx, force = true)
            }
        }
    }

    fun loadExternalSubtitleUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选字幕文件")
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_subtitle.srt"
                val cues = SubtitleParser.parseBytes(bytes, fileName)
                if (cues.isEmpty()) throw IllegalStateException("未能识别有效字幕格式")
                val cacheDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Subtitles").apply { mkdirs() }
                val saved = File(cacheDir, fileName)
                saved.writeText(SubtitleParser.exportToSrt(cues, includeTranslation = true), Charsets.UTF_8)
                playerController.selectEmbeddedSubtitleTrack(null)
                resetTranslationWork()
                _uiState.update {
                    it.copy(
                        externalCues = cues,
                        activeExternalSubtitlePath = saved.absolutePath,
                        subtitlesEnabled = true
                    )
                }
                postMessage("成功导入外挂字幕 (${cues.size} 句)")
                val startIdx = SubtitleParser.findCueIndexAtOrAfter(cues, _uiState.value.positionMs).coerceAtLeast(0)
                triggerLookaheadTranslation(startIdx, force = true)
            }.onFailure { e ->
                postMessage("导入字幕失败: ${e.message}")
            }
        }
    }

    fun exportBilingualSrtFile() {
        val cues = _uiState.value.externalCues
        if (cues.isEmpty()) {
            postMessage("当前没有可导出的字幕台词")
            return
        }
        viewModelScope.launch {
            runCatching {
                val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Subtitles").apply { mkdirs() }
                val baseName = (_uiState.value.currentVideo?.title ?: "PadVideo")
                    .substringBeforeLast('.')
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                val outFile = File(dir, "${baseName}.bilingual.srt")
                outFile.writeText(SubtitleParser.exportToSrt(cues, includeTranslation = true), Charsets.UTF_8)
                postMessage("已导出双语 SRT 字幕: ${outFile.name}")
            }.onFailure {
                postMessage("导出失败: ${it.message}")
            }
        }
    }

    // --- Online Subtitle Search & Apply ---
    fun updateOnlineSearchQuery(query: String) {
        _uiState.update { it.copy(onlineQuery = query) }
    }

    fun updateOnlineLanguageFilter(langFilter: String) {
        _uiState.update { it.copy(onlineLanguageFilter = langFilter) }
        if (_uiState.value.onlineQuery.isNotBlank()) {
            searchOnlineSubtitles()
        }
    }

    fun searchOnlineSubtitles() {
        val query = _uiState.value.onlineQuery.trim()
        val filePath = _uiState.value.currentVideo?.filePath
        if (query.isEmpty() && filePath.isNullOrBlank()) {
            _uiState.update { it.copy(onlineStatusMessage = "请输入影片名称或剧集关键词") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearchingOnline = true,
                    onlineStatusMessage = "正在并行检索全球字幕索引与哈希库…"
                )
            }
            val results = container.onlineSubtitleRepository.searchSubtitles(
                rawQuery = query.ifEmpty { _uiState.value.currentVideo?.title.orEmpty() },
                videoFilePath = filePath,
                preferredLanguageFilter = _uiState.value.onlineLanguageFilter,
                settings = settings.value
            )
            _uiState.update {
                it.copy(
                    isSearchingOnline = false,
                    onlineResults = results,
                    onlineStatusMessage = if (results.isEmpty()) {
                        "未找到匹配字幕，请尝试使用英文原名搜索或在设置中配置扩展字幕源密钥"
                    } else {
                        "共找到 ${results.size} 条可用字幕（支持外语字幕一键实时双语翻译）"
                    }
                )
            }
        }
    }

    fun downloadAndApplySubtitle(item: OnlineSubtitleResult, autoGenerateBilingual: Boolean = false) {
        val videoTitle = _uiState.value.currentVideo?.title ?: item.title
        val generation = playbackGeneration
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingOnlineSub = true,
                    onlineStatusMessage = if (autoGenerateBilingual) {
                        "正在下载并批量智能翻译生成双语字幕…"
                    } else {
                        "正在下载并自动识别字符编码…"
                    }
                )
            }
            val res = container.onlineSubtitleRepository.downloadAndSaveSubtitle(
                item = item,
                targetVideoBaseName = videoTitle,
                settings = settings.value,
                autoGenerateBilingual = autoGenerateBilingual
            )
            if (generation != playbackGeneration) return@launch
            res.onSuccess { bundle ->
                playerController.selectEmbeddedSubtitleTrack(null)
                resetTranslationWork()
                _uiState.update {
                    it.copy(
                        isDownloadingOnlineSub = false,
                        externalCues = bundle.cues,
                        activeExternalSubtitlePath = bundle.savedFile.absolutePath,
                        subtitlesEnabled = true,
                        onlineStatusMessage = "已成功加载: ${bundle.savedFile.name} (${bundle.cues.size} 句)"
                    )
                }
                if (autoGenerateBilingual && settings.value.translationConfig.displayMode == TranslationDisplayMode.OFF) {
                    updateTranslationConfig { cfg -> cfg.copy(displayMode = TranslationDisplayMode.BILINGUAL) }
                } else {
                    val idx = SubtitleParser.findCueIndexAtOrAfter(bundle.cues, _uiState.value.positionMs).coerceAtLeast(0)
                    triggerLookaheadTranslation(idx, force = true)
                }
                postMessage("在线字幕挂载成功 (${bundle.cues.size} 句)")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingOnlineSub = false,
                        onlineStatusMessage = "下载失败: ${err.message}"
                    )
                }
            }
        }
    }

    // --- Real-Time Translation Controls ---
    fun cycleTranslationModeQuick() {
        val current = settings.value.translationConfig.displayMode
        val next = when (current) {
            TranslationDisplayMode.OFF -> TranslationDisplayMode.BILINGUAL
            TranslationDisplayMode.BILINGUAL -> TranslationDisplayMode.TARGET_ONLY
            TranslationDisplayMode.TARGET_ONLY -> TranslationDisplayMode.OFF
            TranslationDisplayMode.LIVE_SPEECH_BILINGUAL -> TranslationDisplayMode.OFF
        }
        setTranslationDisplayMode(next)
    }

    fun setTranslationDisplayMode(mode: TranslationDisplayMode) {
        updateTranslationConfig { it.copy(displayMode = mode) }
        if (mode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL) {
            startVideoAudioAsr(settings.value.translationConfig)
        } else {
            stopVideoAudioAsr()
            if (mode == TranslationDisplayMode.BILINGUAL || mode == TranslationDisplayMode.TARGET_ONLY) {
                val hasExternal = _uiState.value.externalCues.isNotEmpty()
                val hasEmbedded = playerController.embeddedSubtitleTracks.value.isNotEmpty()
                if (!hasExternal && !hasEmbedded) {
                    postMessage("当前视频无字幕，请切换「视频原声 AI 识别 + 翻译」或点击「在线字幕」")
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startVideoAudioAsr(config: TranslationConfig) {
        stopVideoAudioAsr()
        if (config.asrEngine != AsrEngineType.CLOUD_WHISPER_API && !isAsrModelReady(config.asrEngine)) {
            val msg = "请先在翻译面板一键下载「${config.asrEngine.label}」离线模型（国内镜像直连）"
            _uiState.update { it.copy(translationError = msg) }
            postMessage(msg)
            return
        }
        _uiState.update { it.copy(subtitlesEnabled = true, translationError = null) }
        playerController.audioCaptureProcessor.setCapturing(true)
        postMessage("已开启视频原声 AI 实时识别 + 翻译（直采视频音轨，戴耳机也可用）")
        asrLiveCaptionJob = viewModelScope.launch {
            playerController.audioCaptureProcessor.segments.collect { segment ->
                processCapturedVideoAudioSegment(segment)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun stopVideoAudioAsr() {
        playerController.audioCaptureProcessor.setCapturing(false)
        asrLiveCaptionJob?.cancel()
        asrLiveCaptionJob = null
        liveAsrPrimaryText = ""
        liveAsrSecondaryText = ""
        liveAsrExpiresAtMs = 0L
    }

    fun updateTranslationConfig(transform: (TranslationConfig) -> TranslationConfig) {
        val old = settings.value.translationConfig
        val next = transform(old)
        if (old == next) return
        container.settingsRepository.updateSettings { it.copy(translationConfig = next) }

        resetTranslationWork()
        if (old.copy(displayMode = next.displayMode, lookaheadCueCount = next.lookaheadCueCount) != next) {
            _uiState.update { state ->
                state.copy(externalCues = state.externalCues.map { cue ->
                    if (cue.runtimeTranslation) cue.copy(translatedText = null, runtimeTranslation = false) else cue
                })
            }
            if (old.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL &&
                next.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL
            ) {
                startVideoAudioAsr(next)
            }
        }
        if (_uiState.value.externalCues.isEmpty()) {
            handleLiveEmbeddedCueUpdate(playerController.liveEmbeddedSubtitleCue.value)
        } else {
            updateActiveSubtitleAtPosition(playerController.exoPlayer.currentPosition.coerceAtLeast(0L))
        }
    }

    fun loadDownloadedOfflineLanguages(onLoaded: (Set<String>) -> Unit) {
        viewModelScope.launch {
            onLoaded(container.translationRepository.getDownloadedOfflineModelCodes())
        }
    }

    fun downloadOfflineLanguagePack(langTag: String, onResult: (String, Set<String>) -> Unit) {
        viewModelScope.launch {
            container.translationRepository.downloadOfflineLanguagePack(langTag).onSuccess {
                val codes = container.translationRepository.getDownloadedOfflineModelCodes()
                onResult("离线语言包 ($langTag) 已下载就绪", codes)
                resetTranslationWork()
                if (_uiState.value.externalCues.isEmpty()) {
                    handleLiveEmbeddedCueUpdate(playerController.liveEmbeddedSubtitleCue.value)
                } else {
                    updateActiveSubtitleAtPosition(playerController.exoPlayer.currentPosition.coerceAtLeast(0L))
                }
            }.onFailure { err ->
                val codes = container.translationRepository.getDownloadedOfflineModelCodes()
                onResult("模型下载失败（需可访问 Google ML Kit 服务）：${err.message ?: "请检查网络"}", codes)
            }
        }
    }

    fun downloadMlKitOfflinePack(onResult: (String) -> Unit) {
        val config = settings.value.translationConfig
        viewModelScope.launch {
            container.translationRepository.downloadOfflinePair(config).onSuccess {
                onResult("离线模型已就绪，可断网实时翻译")
                if (settings.value.translationConfig == config) {
                    resetTranslationWork()
                    if (_uiState.value.externalCues.isEmpty()) {
                        handleLiveEmbeddedCueUpdate(playerController.liveEmbeddedSubtitleCue.value)
                    } else {
                        updateActiveSubtitleAtPosition(playerController.exoPlayer.currentPosition.coerceAtLeast(0L))
                    }
                }
            }.onFailure { error ->
                onResult("模型下载失败（需访问 Google ML Kit；无代理建议用「智能云端多引擎」）：${error.message ?: ""}")
            }
        }
    }

    fun updateSubtitleStyle(transform: (SubtitleStyleConfig) -> SubtitleStyleConfig) {
        container.settingsRepository.updateSettings { s ->
            s.copy(subtitleStyle = transform(s.subtitleStyle))
        }
    }

    // --- Commercial Playback Controls & Gestures ---
    fun toggleControlsVisibility() {
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(controlsVisible = visible) }
    }

    fun toggleScreenLock() {
        _uiState.update {
            val nextLocked = !it.isScreenLocked
            it.copy(isScreenLocked = nextLocked, controlsVisible = true)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        playerController.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
    }

    fun beginLongPressSpeedBoost() {
        if (_uiState.value.isScreenLocked || _uiState.value.isLongPressBoostActive) return
        preLongPressSpeed = _uiState.value.playbackSpeed
        val boostSpeed = settings.value.longPressSpeed.coerceIn(1.5f, 4.0f)
        playerController.setPlaybackSpeed(boostSpeed)
        _uiState.update {
            it.copy(
                isLongPressBoostActive = true,
                gestureHud = GestureHudState(
                    type = GestureHudType.LONG_PRESS_SPEED,
                    title = "倍速快进中",
                    valueText = "${boostSpeed}x"
                )
            )
        }
    }

    fun endLongPressSpeedBoost() {
        if (!_uiState.value.isLongPressBoostActive) return
        playerController.setPlaybackSpeed(preLongPressSpeed)
        _uiState.update {
            it.copy(
                isLongPressBoostActive = false,
                playbackSpeed = preLongPressSpeed,
                gestureHud = null
            )
        }
    }

    fun adjustBrightnessByDelta(deltaFraction: Float) {
        if (_uiState.value.isScreenLocked) return
        val updated = (_uiState.value.brightnessFraction + deltaFraction).coerceIn(0.02f, 1.0f)
        val pct = (updated * 100).toInt()
        _uiState.update { it.copy(brightnessFraction = updated) }
        showTemporaryHud(
            GestureHudState(
                type = GestureHudType.BRIGHTNESS,
                title = "屏幕亮度",
                valueText = "$pct%",
                progressFraction = updated
            )
        )
    }

    fun adjustVolumeByDelta(deltaPercent: Int) {
        if (_uiState.value.isScreenLocked) return
        val updated = (_uiState.value.volumePercent + deltaPercent).coerceIn(0, 200)
        playerController.setPlayerVolumeAndBoost(updated)
        _uiState.update { it.copy(volumePercent = updated) }
        showTemporaryHud(
            GestureHudState(
                type = GestureHudType.VOLUME,
                title = if (updated > 100) "音量增益 Boost" else "媒体音量",
                valueText = "$updated%",
                progressFraction = (updated / 200f).coerceIn(0f, 1f)
            )
        )
    }

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        playerController.setPlayerVolumeAndBoost(clamped)
        _uiState.update { it.copy(volumePercent = clamped) }
    }

    fun onDoubleTapZone(zoneIndex: Int) {
        if (_uiState.value.isScreenLocked) return
        val jumpMs = settings.value.defaultJumpSeconds * 1000L
        val now = System.currentTimeMillis()
        when (zoneIndex) {
            0 -> { // Left third -> Rewind
                cumulativeDoubleTapMs = if (now - lastDoubleTapTimestamp < 900L && cumulativeDoubleTapMs < 0) {
                    cumulativeDoubleTapMs - jumpMs
                } else {
                    -jumpMs
                }
                lastDoubleTapTimestamp = now
                playerController.seekBy(-jumpMs)
                showTemporaryHud(
                    GestureHudState(
                        type = GestureHudType.DOUBLE_TAP_REWIND,
                        title = "快退",
                        valueText = "${cumulativeDoubleTapMs / 1000} 秒"
                    )
                )
            }
            1 -> { // Center third -> Play / Pause
                playerController.togglePlayPause()
                _uiState.update { it.copy(controlsVisible = true) }
            }
            else -> { // Right third -> Fast-Forward
                cumulativeDoubleTapMs = if (now - lastDoubleTapTimestamp < 900L && cumulativeDoubleTapMs > 0) {
                    cumulativeDoubleTapMs + jumpMs
                } else {
                    jumpMs
                }
                lastDoubleTapTimestamp = now
                playerController.seekBy(jumpMs)
                showTemporaryHud(
                    GestureHudState(
                        type = GestureHudType.DOUBLE_TAP_FORWARD,
                        title = "快进",
                        valueText = "+${cumulativeDoubleTapMs / 1000} 秒"
                    )
                )
            }
        }
    }

    fun previewHorizontalSeek(targetMs: Long, deltaMs: Long) {
        if (_uiState.value.isScreenLocked) return
        val dur = _uiState.value.durationMs.coerceAtLeast(1L)
        val clamped = targetMs.coerceIn(0L, dur)
        showTemporaryHud(
            GestureHudState(
                type = GestureHudType.SEEK,
                title = formatSignedMs(deltaMs),
                valueText = "${formatDurationMs(clamped)} / ${formatDurationMs(dur)}",
                progressFraction = (clamped.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            )
        )
    }

    fun commitSeek(targetMs: Long) {
        playerController.seekTo(targetMs)
    }

    fun updateZoomAndPan(scaleMultiplier: Float, panXDelta: Float, panYDelta: Float) {
        if (_uiState.value.isScreenLocked) return
        _uiState.update { state ->
            val newScale = (state.zoomScale * scaleMultiplier).coerceIn(0.5f, 4.0f)
            val newPanX = if (newScale <= 1.02f) 0f else (state.panOffsetX + panXDelta).coerceIn(-900f, 900f)
            val newPanY = if (newScale <= 1.02f) 0f else (state.panOffsetY + panYDelta).coerceIn(-600f, 600f)
            state.copy(zoomScale = newScale, panOffsetX = newPanX, panOffsetY = newPanY)
        }
        showTemporaryHud(
            GestureHudState(
                type = GestureHudType.ZOOM,
                title = "画面缩放",
                valueText = String.format(java.util.Locale.US, "%.2fx", _uiState.value.zoomScale)
            )
        )
    }

    fun resetVideoTransform() {
        _uiState.update {
            it.copy(
                zoomScale = 1.0f,
                panOffsetX = 0f,
                panOffsetY = 0f,
                rotationDegrees = 0,
                isMirrored = false,
                aspectRatioMode = AspectRatioMode.FIT
            )
        }
        postMessage("已重置画面比例、缩放与旋转")
    }

    fun cycleAspectRatio() {
        val entries = AspectRatioMode.entries
        val next = entries[(_uiState.value.aspectRatioMode.ordinal + 1) % entries.size]
        _uiState.update { it.copy(aspectRatioMode = next) }
        postMessage("画面比例: ${next.label}")
    }

    fun rotateVideo90() {
        val next = (_uiState.value.rotationDegrees + 90) % 360
        _uiState.update { it.copy(rotationDegrees = next) }
        postMessage("画面旋转: $next°")
    }

    fun toggleMirror() {
        val next = !_uiState.value.isMirrored
        _uiState.update { it.copy(isMirrored = next) }
        postMessage(if (next) "已开启水平镜像翻转" else "已关闭镜像翻转")
    }

    // --- A-B Repeat Loop ---
    fun stepAbLoopMarker() {
        val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
        val cur = _uiState.value.abLoop
        when {
            cur.pointAMs == null -> {
                _uiState.update { it.copy(abLoop = AbLoopState(pointAMs = pos, pointBMs = null, enabled = false)) }
                postMessage("已标记 A 点 (${formatDurationMs(pos)})，再次点击标记 B 点")
            }
            cur.pointBMs == null -> {
                if (pos <= cur.pointAMs + 500L) {
                    postMessage("B 点需晚于 A 点至少 0.5 秒")
                } else {
                    _uiState.update { it.copy(abLoop = cur.copy(pointBMs = pos, enabled = true)) }
                    postMessage("已开启 A-B 区间复读: ${formatDurationMs(cur.pointAMs)} → ${formatDurationMs(pos)}")
                }
            }
            else -> {
                _uiState.update { it.copy(abLoop = AbLoopState()) }
                postMessage("已清除 A-B 区间循环")
            }
        }
    }

    // --- Bookmarks & Frame Screenshot ---
    fun addBookmarkAtCurrentPosition(customLabel: String? = null) {
        val video = _uiState.value.currentVideo ?: return
        val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
        val defaultLabel = _uiState.value.displayPrimaryLine.lines().firstOrNull()?.take(28)?.takeIf { it.isNotBlank() }
            ?: "书签 @ ${formatDurationMs(pos)}"
        val bookmark = VideoBookmark(
            id = UUID.randomUUID().toString(),
            videoUri = video.uriString,
            label = customLabel?.takeIf { it.isNotBlank() } ?: defaultLabel,
            positionMs = pos
        )
        container.settingsRepository.addVideoBookmark(bookmark)
        postMessage("已添加视频书签: ${bookmark.label}")
    }

    fun removeBookmark(id: String) {
        container.settingsRepository.removeVideoBookmark(id)
    }

    fun getBookmarksForCurrentVideo(): List<VideoBookmark> {
        val uri = _uiState.value.currentVideo?.uriString ?: return emptyList()
        return container.settingsRepository.getBookmarksForVideo(uri)
    }

    fun captureScreenshot() {
        val video = _uiState.value.currentVideo ?: return
        val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
        viewModelScope.launch {
            val result = playerController.captureCurrentFrame(
                uriString = video.uriString,
                filePath = video.filePath,
                positionMs = pos,
                videoTitle = video.title
            )
            result.onSuccess { msg -> postMessage(msg) }
                .onFailure { err -> postMessage("截图失败: ${err.message}") }
        }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        if (option == SleepTimerOption.OFF) {
            _uiState.update { it.copy(sleepTimerOption = SleepTimerOption.OFF, sleepTimerRemainingSec = 0L) }
            return
        }
        if (option == SleepTimerOption.END_OF_VIDEO) {
            _uiState.update { it.copy(sleepTimerOption = option, sleepTimerRemainingSec = -1L) }
            postMessage("睡眠定时: 播完当前视频后自动暂停")
            return
        }
        val totalSeconds = option.minutes * 60L
        _uiState.update { it.copy(sleepTimerOption = option, sleepTimerRemainingSec = totalSeconds) }
        postMessage("睡眠定时已开启: ${option.label}")
        sleepTimerJob = viewModelScope.launch {
            var rem = totalSeconds
            while (rem > 0 && isActive) {
                delay(1000L)
                rem--
                _uiState.update { it.copy(sleepTimerRemainingSec = rem) }
            }
            if (rem <= 0L) {
                playerController.pause()
                _uiState.update { it.copy(sleepTimerOption = SleepTimerOption.OFF, sleepTimerRemainingSec = 0L) }
                postMessage("睡眠定时时间到，已自动暂停播放")
            }
        }
    }

    fun toggleSubtitlesEnabled() {
        val next = !_uiState.value.subtitlesEnabled
        resetTranslationWork()
        _uiState.update {
            it.copy(subtitlesEnabled = next, displayPrimaryLine = "", displaySecondaryLine = "")
        }
        if (next && _uiState.value.externalCues.isEmpty()) {
            handleLiveEmbeddedCueUpdate(playerController.liveEmbeddedSubtitleCue.value)
        }
        postMessage(if (next) "已开启字幕显示" else "已隐藏字幕")
    }

    fun toggleTranscriptDrawer() {
        _uiState.update { it.copy(showTranscriptDrawer = !it.showTranscriptDrawer) }
    }

    private fun showTemporaryHud(hud: GestureHudState) {
        _uiState.update { it.copy(gestureHud = hud) }
        hudDismissJob?.cancel()
        hudDismissJob = viewModelScope.launch {
            delay(900L)
            _uiState.update { it.copy(gestureHud = null) }
        }
    }

    fun postMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun saveCurrentPlaybackHistory() {
        val video = _uiState.value.currentVideo ?: return
        val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = playerController.exoPlayer.duration.takeIf { it > 0L } ?: video.durationMs
        val size = playerController.videoSize.value
        container.settingsRepository.recordPlaybackProgress(
            PlaybackHistoryItem(
                uriString = video.uriString,
                title = video.title,
                filePath = video.filePath,
                durationMs = dur,
                positionMs = pos,
                width = size.first.takeIf { it > 0 } ?: video.width,
                height = size.second.takeIf { it > 0 } ?: video.height,
                externalSubtitlePath = _uiState.value.activeExternalSubtitlePath,
                playbackSpeed = _uiState.value.playbackSpeed
            )
        )
    }

    fun stopAndSave() {
        saveCurrentPlaybackHistory()
        stopVideoAudioAsr()
        playbackGeneration++
        resetTranslationWork()
        _uiState.update { it.copy(currentVideo = null) }
        playerController.exoPlayer.stop()
        playerController.exoPlayer.clearMediaItems()
    }

    override fun onCleared() {
        saveCurrentPlaybackHistory()
        stopVideoAudioAsr()
        playerController.release()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerViewModel(context.applicationContext, container) as T
                }
            }
    }
}
