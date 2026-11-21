package com.padvideo.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.padvideo.AppContainer
import com.padvideo.data.model.AbLoopState
import com.padvideo.data.model.AppSettings
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
import com.padvideo.data.model.TranslationLanguage
import com.padvideo.data.model.VideoBookmark
import com.padvideo.data.model.VideoItem
import com.padvideo.data.model.formatDurationMs
import com.padvideo.data.model.formatSignedMs
import com.padvideo.player.LiveSpeechCaptionEngine
import com.padvideo.player.PadPlayerController
import com.padvideo.subtitle.SubtitleParser
import com.padvideo.subtitle.VideoFilenameParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    val playerController = PadPlayerController(
        context = appContext,
        preferHardwareDecoder = settings.value.hardwareDecoderPreferred
    )

    private val liveSpeechEngine = LiveSpeechCaptionEngine(
        context = appContext,
        onSegmentRecognized = { spokenText -> onLiveSpeechSegment(spokenText) }
    )

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var hudDismissJob: Job? = null
    private var lookaheadTranslationJob: Job? = null
    private var embeddedCueTranslationJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastTranslatedWindowStart: Int = -999
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
            liveSpeechEngine.partialTranscript.collect { partial ->
                if (settings.value.translationConfig.displayMode == TranslationDisplayMode.LIVE_SPEECH_BILINGUAL &&
                    partial.isNotBlank()
                ) {
                    _uiState.update {
                        it.copy(
                            displayPrimaryLine = "🎙️ $partial",
                            displaySecondaryLine = it.displaySecondaryLine
                        )
                    }
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
        lastTranslatedWindowStart = -999

        playerController.loadVideo(video.uriString, video.title, resumeMs)
        playerController.setPlaybackSpeed(initialSpeed)

        // Auto-load saved external subtitle, sidecar subtitle, or inline demo subtitle
        val preferredSubPath = savedHistory?.externalSubtitlePath?.takeIf { File(it).exists() }
            ?: video.sidecarSubtitlePaths.firstOrNull { File(it).exists() }

        if (preferredSubPath != null) {
            loadExternalSubtitleFile(File(preferredSubPath), silent = true)
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
        if (!state.subtitlesEnabled) {
            if (state.displayPrimaryLine.isNotEmpty() || state.displaySecondaryLine.isNotEmpty()) {
                _uiState.update { it.copy(displayPrimaryLine = "", displaySecondaryLine = "", activeCueIndex = -1) }
            }
            return
        }

        val cues = state.externalCues
        if (cues.isEmpty()) return

        val offsetMs = settings.value.subtitleStyle.syncOffsetMs
        val activeCues = SubtitleParser.findActiveCues(cues, positionMs, offsetMs)
        val nearestIdx = SubtitleParser.findCueIndexAtOrAfter(cues, positionMs, offsetMs)

        val transConfig = settings.value.translationConfig
        if (transConfig.displayMode == TranslationDisplayMode.BILINGUAL ||
            transConfig.displayMode == TranslationDisplayMode.TARGET_ONLY
        ) {
            if (nearestIdx >= 0 && (nearestIdx < lastTranslatedWindowStart || nearestIdx >= lastTranslatedWindowStart + 6)) {
                triggerLookaheadTranslation(nearestIdx)
            }
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
        if (!_uiState.value.subtitlesEnabled) return
        val cleaned = SubtitleParser.cleanSubtitleText(embeddedText)
        if (cleaned.isEmpty()) {
            _uiState.update { it.copy(displayPrimaryLine = "", displaySecondaryLine = "") }
            return
        }

        val transConfig = settings.value.translationConfig
        if (transConfig.displayMode == TranslationDisplayMode.OFF) {
            _uiState.update { it.copy(displayPrimaryLine = cleaned, displaySecondaryLine = "") }
            return
        }

        val cached = container.translationRepository.getCachedTranslation(cleaned, transConfig)
        if (cached != null) {
            val (primary, secondary) = formatDisplayLines(cleaned, cached, transConfig.displayMode)
            _uiState.update { it.copy(displayPrimaryLine = primary, displaySecondaryLine = secondary) }
            return
        }

        // Show original immediately while fetching live translation
        _uiState.update { it.copy(displayPrimaryLine = cleaned, displaySecondaryLine = "正在实时翻译…", isTranslatingNow = true) }
        embeddedCueTranslationJob?.cancel()
        embeddedCueTranslationJob = viewModelScope.launch {
            val result = container.translationRepository.translateSingle(cleaned, transConfig)
            val translated = result.getOrDefault("")
            val (primary, secondary) = formatDisplayLines(cleaned, translated, transConfig.displayMode)
            _uiState.update {
                it.copy(
                    displayPrimaryLine = primary,
                    displaySecondaryLine = secondary,
                    isTranslatingNow = false
                )
            }
        }
    }

    private fun onLiveSpeechSegment(spokenText: String) {
        val pos = playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
        val transConfig = settings.value.translationConfig
        viewModelScope.launch {
            _uiState.update { it.copy(isTranslatingNow = true) }
            val translated = container.translationRepository.translateSingle(spokenText, transConfig).getOrDefault("")
            val newCue = SubtitleCue(
                index = _uiState.value.externalCues.size + 1,
                startTimeMs = (pos - 2800L).coerceAtLeast(0L),
                endTimeMs = pos + 2500L,
                originalText = spokenText,
                translatedText = translated.ifBlank { null }
            )
            _uiState.update { state ->
                val updatedList = state.externalCues + newCue
                state.copy(
                    externalCues = updatedList,
                    displayPrimaryLine = translated.ifBlank { spokenText },
                    displaySecondaryLine = if (translated.isNotBlank()) spokenText else "",
                    isTranslatingNow = false
                )
            }
        }
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

    private fun triggerLookaheadTranslation(startIndex: Int, force: Boolean = false) {
        val cues = _uiState.value.externalCues
        if (cues.isEmpty() || startIndex !in cues.indices) return
        if (!force && startIndex == lastTranslatedWindowStart) return
        lastTranslatedWindowStart = startIndex

        val config = settings.value.translationConfig
        if (config.displayMode == TranslationDisplayMode.OFF) return

        lookaheadTranslationJob?.cancel()
        lookaheadTranslationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslatingNow = true) }
            container.translationRepository.translateCueWindow(
                cues = _uiState.value.externalCues,
                startIndex = startIndex,
                windowSize = config.lookaheadCueCount.coerceIn(8, 40),
                config = config,
                onUpdatedCues = { updatesByIndex ->
                    _uiState.update { current ->
                        val merged = current.externalCues.map { c ->
                            val newTr = updatesByIndex[c.index]
                            if (newTr != null) c.copy(translatedText = newTr) else c
                        }
                        current.copy(externalCues = merged)
                    }
                }
            )
            _uiState.update { it.copy(isTranslatingNow = false) }
        }
    }

    fun loadExternalSubtitleFile(file: File, silent: Boolean = false) {
        viewModelScope.launch {
            val cues = SubtitleParser.parseFile(file)
            if (cues.isEmpty()) {
                if (!silent) postMessage("字幕文件解析为空: ${file.name}")
                return@launch
            }
            playerController.selectEmbeddedSubtitleTrack(null)
            lastTranslatedWindowStart = -999
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
                lastTranslatedWindowStart = -999
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
            res.onSuccess { bundle ->
                playerController.selectEmbeddedSubtitleTrack(null)
                lastTranslatedWindowStart = -999
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
            liveSpeechEngine.start(settings.value.translationConfig.sourceLanguageCode)
            postMessage("已开启实时语音识别双语同传")
        } else {
            liveSpeechEngine.stop()
            if (mode != TranslationDisplayMode.OFF) {
                lastTranslatedWindowStart = -999
                val idx = SubtitleParser.findCueIndexAtOrAfter(
                    _uiState.value.externalCues,
                    _uiState.value.positionMs
                ).coerceAtLeast(0)
                triggerLookaheadTranslation(idx, force = true)
                postMessage("已切换为: ${mode.label}")
            } else {
                postMessage("已关闭实时翻译")
            }
        }
    }

    fun updateTranslationConfig(transform: (TranslationConfig) -> TranslationConfig) {
        container.settingsRepository.updateSettings { s ->
            s.copy(translationConfig = transform(s.translationConfig))
        }
        lastTranslatedWindowStart = -999
        val idx = SubtitleParser.findCueIndexAtOrAfter(_uiState.value.externalCues, _uiState.value.positionMs)
        if (idx >= 0) {
            triggerLookaheadTranslation(idx, force = true)
        }
    }

    fun downloadMlKitOfflinePack(lang: TranslationLanguage, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("正在下载 ${lang.label} 离线神经翻译模型…")
            val res = container.translationRepository.downloadOfflineModel(lang)
            res.onSuccess {
                onResult("${lang.label} 离线翻译包已就绪，可完全断网实时翻译")
            }.onFailure { e ->
                onResult("离线模型下载失败: ${e.message}")
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
        _uiState.update { it.copy(subtitlesEnabled = next) }
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
        liveSpeechEngine.stop()
        playerController.pause()
    }

    override fun onCleared() {
        saveCurrentPlaybackHistory()
        liveSpeechEngine.stop()
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
