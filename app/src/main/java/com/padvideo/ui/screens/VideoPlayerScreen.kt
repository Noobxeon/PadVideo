package com.padvideo.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.padvideo.data.model.AspectRatioMode
import com.padvideo.data.model.GestureHudState
import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleStyleConfig
import com.padvideo.data.model.TranslationDisplayMode
import com.padvideo.data.model.formatDurationMs
import com.padvideo.ui.PlayerViewModel
import com.padvideo.ui.dialogs.AudioAndSubtitleStyleSheet
import com.padvideo.ui.dialogs.OnlineSubtitleSheet
import com.padvideo.ui.dialogs.PlaylistAndBookmarksSheet
import com.padvideo.ui.dialogs.SpeedAndTimerSheet
import com.padvideo.ui.dialogs.TranslationControlSheet
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class ActiveDragAxis {
    NONE,
    HORIZONTAL_SEEK,
    LEFT_BRIGHTNESS,
    RIGHT_VOLUME,
    PINCH_ZOOM
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    playerViewModel: PlayerViewModel,
    isInPipMode: Boolean,
    onEnterPip: () -> Unit,
    onExitPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val uiState by playerViewModel.uiState.collectAsState()
    val settings by playerViewModel.settings.collectAsState()
    val isPlaying by playerViewModel.playerController.isPlaying.collectAsState()
    val isBuffering by playerViewModel.playerController.isBuffering.collectAsState()
    val playbackError by playerViewModel.playerController.playbackError.collectAsState()
    val audioTracks by playerViewModel.playerController.audioTracks.collectAsState()
    val embeddedSubTracks by playerViewModel.playerController.embeddedSubtitleTracks.collectAsState()
    val bookmarks by playerViewModel.settings.collectAsState() // triggers recompose with repo updates
    val currentBookmarks = remember(uiState.currentVideo?.uriString, uiState.snackbarMessage, bookmarks) {
        playerViewModel.getBookmarksForCurrentVideo()
    }

    var showOnlineSubSheet by remember { mutableStateOf(false) }
    var showTranslationSheet by remember { mutableStateOf(false) }
    var showSpeedTimerSheet by remember { mutableStateOf(false) }
    var showAudioSubStyleSheet by remember { mutableStateOf(false) }
    var showPlaylistBookmarkSheet by remember { mutableStateOf(false) }

    var isUserDraggingSeekSlider by remember { mutableStateOf(false) }
    var seekSliderValue by remember { mutableFloatStateOf(0f) }

    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            playerViewModel.loadExternalSubtitleUri(uri)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            playerViewModel.setTranslationDisplayMode(TranslationDisplayMode.LIVE_SPEECH_BILINGUAL)
        } else {
            playerViewModel.postMessage("需要麦克风权限才能开启无字幕视频实时语音识别同传")
        }
    }

    BackHandler {
        if (uiState.showTranscriptDrawer) {
            playerViewModel.toggleTranscriptDrawer()
        } else {
            playerViewModel.stopAndSave()
            onExitPlayer()
        }
    }

    // Request keyboard focus for external tablet keyboard shortcuts
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // Auto-hide controls after 4.5s during uninterrupted playback
    LaunchedEffect(uiState.controlsVisible, isPlaying, showOnlineSubSheet, showTranslationSheet, showSpeedTimerSheet, showAudioSubStyleSheet) {
        val anySheetOpen = showOnlineSubSheet || showTranslationSheet || showSpeedTimerSheet ||
            showAudioSubStyleSheet || showPlaylistBookmarkSheet || uiState.showTranscriptDrawer
        if (uiState.controlsVisible && isPlaying && !anySheetOpen && !isUserDraggingSeekSlider) {
            delay(4500L)
            playerViewModel.setControlsVisible(false)
        }
    }

    // Snackbar notifications
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        playerViewModel.clearMessage()
    }

    // Window brightness, Keep Screen On & Immersive System Bars
    DisposableEffect(view) {
        val activity = context as? Activity
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(uiState.brightnessFraction) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val lp = activity.window.attributes
        lp.screenBrightness = uiState.brightnessFraction.coerceIn(0.02f, 1.0f)
        activity.window.attributes = lp
    }

    LaunchedEffect(uiState.controlsVisible, isInPipMode) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!uiState.controlsVisible || isInPipMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val shift = keyEvent.nativeKeyEvent.isShiftPressed
                when (keyEvent.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_SPACE, AndroidKeyEvent.KEYCODE_K, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        playerViewModel.playerController.togglePlayPause()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        playerViewModel.playerController.seekBy(if (shift) -30_000L else -5_000L)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        playerViewModel.playerController.seekBy(if (shift) 30_000L else 5_000L)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        playerViewModel.adjustVolumeByDelta(5)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        playerViewModel.adjustVolumeByDelta(-5)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_C -> {
                        playerViewModel.toggleSubtitlesEnabled()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_T -> {
                        playerViewModel.cycleTranslationModeQuick()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> {
                        playerViewModel.setPlaybackSpeed(uiState.playbackSpeed - 0.25f)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> {
                        playerViewModel.setPlaybackSpeed(uiState.playbackSpeed + 0.25f)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        playerViewModel.stopAndSave()
                        onExitPlayer()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // 1. ExoPlayer Video Surface with Aspect Ratio, Pinch Zoom, Rotation & Mirroring
        val aspectModifier = when (uiState.aspectRatioMode) {
            AspectRatioMode.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
            AspectRatioMode.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
            AspectRatioMode.RATIO_21_9 -> Modifier.aspectRatio(21f / 9f)
            else -> Modifier.fillMaxSize()
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        subtitleView?.visibility = View.GONE
                        player = playerViewModel.playerController.exoPlayer
                    }
                },
                update = { pv ->
                    pv.player = playerViewModel.playerController.exoPlayer
                    pv.resizeMode = when (uiState.aspectRatioMode) {
                        AspectRatioMode.FIT,
                        AspectRatioMode.RATIO_16_9,
                        AspectRatioMode.RATIO_4_3,
                        AspectRatioMode.RATIO_21_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRatioMode.ZOOM_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                modifier = aspectModifier.graphicsLayer {
                    val mirrorFactor = if (uiState.isMirrored) -1f else 1f
                    scaleX = uiState.zoomScale * mirrorFactor
                    scaleY = uiState.zoomScale
                    translationX = uiState.panOffsetX
                    translationY = uiState.panOffsetY
                    rotationZ = uiState.rotationDegrees.toFloat()
                }
            )
        }

        // 2. Multi-Touch & Commercial Gesture Detection Layer (disabled in PiP)
        if (!isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.isScreenLocked) {
                        detectTapGestures(
                            onTap = {
                                playerViewModel.toggleControlsVisibility()
                            },
                            onDoubleTap = { offset ->
                                val zone = ((offset.x / size.width.coerceAtLeast(1)) * 3f).toInt().coerceIn(0, 2)
                                playerViewModel.onDoubleTapZone(zone)
                            },
                            onLongPress = {
                                playerViewModel.beginLongPressSpeedBoost()
                            },
                            onPress = {
                                tryAwaitRelease()
                                playerViewModel.endLongPressSpeedBoost()
                            }
                        )
                    }
                    .pointerInput(uiState.isScreenLocked, uiState.durationMs) {
                        if (uiState.isScreenLocked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            var dragMode = ActiveDragAxis.NONE
                            val startSeekPos = playerViewModel.playerController.exoPlayer.currentPosition.coerceAtLeast(0L)
                            var accumulatedDx = 0f
                            var seekTargetMs = startSeekPos

                            do {
                                val event = awaitPointerEvent()
                                val activeCount = event.changes.count { it.pressed }
                                if (activeCount >= 2) {
                                    dragMode = ActiveDragAxis.PINCH_ZOOM
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (abs(zoom - 1f) > 0.004f || abs(pan.x) > 0.5f || abs(pan.y) > 0.5f) {
                                        playerViewModel.updateZoomAndPan(zoom, pan.x, pan.y)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (activeCount == 1 && dragMode != ActiveDragAxis.PINCH_ZOOM) {
                                    val change = event.changes.first()
                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y

                                    if (dragMode == ActiveDragAxis.NONE) {
                                        val totalDx = change.position.x - down.position.x
                                        val totalDy = change.position.y - down.position.y
                                        val slop = viewConfiguration.touchSlop * 1.4f
                                        if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                            dragMode = if (abs(totalDx) > abs(totalDy) * 1.15f) {
                                                ActiveDragAxis.HORIZONTAL_SEEK
                                            } else if (startX < size.width / 2f) {
                                                ActiveDragAxis.LEFT_BRIGHTNESS
                                            } else {
                                                ActiveDragAxis.RIGHT_VOLUME
                                            }
                                        }
                                    }

                                    when (dragMode) {
                                        ActiveDragAxis.HORIZONTAL_SEEK -> {
                                            accumulatedDx += dx
                                            // Full tablet width drag = ~180 seconds seek span
                                            val deltaMs = ((accumulatedDx / size.width.coerceAtLeast(1)) * 180_000f).toLong()
                                            val maxDur = uiState.durationMs.coerceAtLeast(60_000L)
                                            seekTargetMs = (startSeekPos + deltaMs).coerceIn(0L, maxDur)
                                            playerViewModel.previewHorizontalSeek(seekTargetMs, seekTargetMs - startSeekPos)
                                            change.consume()
                                        }
                                        ActiveDragAxis.LEFT_BRIGHTNESS -> {
                                            val deltaFrac = -dy / (size.height.coerceAtLeast(1) * 0.75f)
                                            playerViewModel.adjustBrightnessByDelta(deltaFrac)
                                            change.consume()
                                        }
                                        ActiveDragAxis.RIGHT_VOLUME -> {
                                            val deltaVol = ((-dy / (size.height.coerceAtLeast(1) * 0.75f)) * 160f).toInt()
                                            if (deltaVol != 0) {
                                                playerViewModel.adjustVolumeByDelta(deltaVol)
                                            }
                                            change.consume()
                                        }
                                        else -> Unit
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (dragMode == ActiveDragAxis.HORIZONTAL_SEEK) {
                                playerViewModel.commitSeek(seekTargetMs)
                            }
                            playerViewModel.endLongPressSpeedBoost()
                        }
                    }
            )
        }

        // 3. Buffering & Error Indicators
        if (isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        playbackError?.let { errText ->
            Surface(
                color = Color(0xCC7F1D1D),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Text(
                    text = errText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }

        // 4. Crisp Bilingual Subtitle Overlay
        if (uiState.subtitlesEnabled &&
            (uiState.displayPrimaryLine.isNotBlank() || uiState.displaySecondaryLine.isNotBlank())
        ) {
            val extraBottomForControls = if (uiState.controlsVisible && !isInPipMode) 96.dp else 0.dp
            BilingualSubtitleOverlay(
                primaryLine = uiState.displayPrimaryLine,
                secondaryLine = uiState.displaySecondaryLine,
                styleConfig = settings.subtitleStyle,
                isCompactPip = isInPipMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (isInPipMode) {
                            6.dp
                        } else {
                            settings.subtitleStyle.bottomPaddingDp.dp + extraBottomForControls
                        },
                        start = 32.dp,
                        end = 32.dp
                    )
            )
        }

        // 5. Center Gesture HUD Pill
        AnimatedVisibility(
            visible = uiState.gestureHud != null && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            uiState.gestureHud?.let { hud ->
                GestureHudCard(hud = hud)
            }
        }

        // 6. Screen Lock Pill when Locked
        if (uiState.isScreenLocked && uiState.controlsVisible && !isInPipMode) {
            FilledTonalIconButton(
                onClick = { playerViewModel.toggleScreenLock() },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(54.dp)
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = "点击解锁屏幕")
            }
        }

        // 7. Full Commercial Top & Bottom Frosted Controls
        AnimatedVisibility(
            visible = uiState.controlsVisible && !uiState.isScreenLocked && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Scrim & Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            playerViewModel.stopAndSave()
                            onExitPlayer()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.currentVideo?.title.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append("${uiState.playbackSpeed}x")
                                    if (uiState.volumePercent > 100) {
                                        append(" · 增益 ${uiState.volumePercent}%")
                                    }
                                    if (uiState.abLoop.pointAMs != null) {
                                        append(" · A-B复读")
                                    }
                                    if (uiState.isTranslatingNow) {
                                        append(" · AI预译中…")
                                    }
                                },
                                color = Color(0xFF5EEAD4),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // Top Pro Action Icons
                    IconButton(onClick = { playerViewModel.stepAbLoopMarker() }) {
                        Icon(
                            imageVector = Icons.Outlined.Repeat,
                            contentDescription = "A-B 区间复读",
                            tint = if (uiState.abLoop.pointAMs != null) Color(0xFF5EEAD4) else Color.White
                        )
                    }

                    IconButton(onClick = { playerViewModel.addBookmarkAtCurrentPosition() }) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdd,
                            contentDescription = "添加视频书签",
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = { playerViewModel.captureScreenshot() }) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = "视频帧截图",
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = { playerViewModel.toggleTranscriptDrawer() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = "双语台词本分栏",
                            tint = if (uiState.showTranscriptDrawer) Color(0xFF5EEAD4) else Color.White
                        )
                    }

                    IconButton(onClick = onEnterPip) {
                        Icon(
                            imageVector = Icons.Outlined.PictureInPictureAlt,
                            contentDescription = "画中画小窗",
                            tint = Color.White
                        )
                    }
                }

                // Bottom Scrim, Seekbar & Pro Feature Strip
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xDD000000))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // Seekbar Row
                    val effectiveDuration = uiState.durationMs.coerceAtLeast(1L)
                    val sliderPos = if (isUserDraggingSeekSlider) {
                        seekSliderValue
                    } else {
                        (uiState.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDurationMs(
                                if (isUserDraggingSeekSlider) (seekSliderValue * effectiveDuration).toLong() else uiState.positionMs
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = sliderPos,
                            onValueChange = { frac ->
                                isUserDraggingSeekSlider = true
                                seekSliderValue = frac
                            },
                            onValueChangeFinished = {
                                val target = (seekSliderValue * effectiveDuration).toLong()
                                playerViewModel.commitSeek(target)
                                isUserDraggingSeekSlider = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF5EEAD4),
                                activeTrackColor = Color(0xFF5EEAD4),
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )
                        Text(
                            text = formatDurationMs(uiState.durationMs),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Transport & Pro Feature Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left group: Lock + Playback Transport
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(onClick = { playerViewModel.toggleScreenLock() }) {
                                Icon(Icons.Outlined.LockOpen, contentDescription = "锁定屏幕防误触", tint = Color.White)
                            }
                            IconButton(onClick = { playerViewModel.playPreviousEpisode() }) {
                                Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一集", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    playerViewModel.playerController.seekBy(-settings.defaultJumpSeconds * 1000L)
                                }
                            ) {
                                Icon(Icons.Outlined.Replay10, contentDescription = "快退", tint = Color.White)
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF5EEAD4),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable { playerViewModel.playerController.togglePlayPause() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = if (isPlaying) "暂停" else "播放",
                                        tint = Color(0xFF042F2E),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    playerViewModel.playerController.seekBy(settings.defaultJumpSeconds * 1000L)
                                }
                            ) {
                                Icon(Icons.Outlined.Forward10, contentDescription = "快进", tint = Color.White)
                            }
                            IconButton(onClick = { playerViewModel.playNextEpisode() }) {
                                Icon(Icons.Outlined.SkipNext, contentDescription = "下一集", tint = Color.White)
                            }
                        }

                        // Right group: Commercial Feature Pills (Translation, Online Subtitles, Audio/Sub, Speed, Playlist, Aspect)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val transMode = settings.translationConfig.displayMode
                            AssistChip(
                                onClick = { showTranslationSheet = true },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Translate,
                                        contentDescription = null,
                                        tint = if (transMode != TranslationDisplayMode.OFF) Color(0xFF042F2E) else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = "实时翻译 · ${transMode.shortBadge}",
                                        color = if (transMode != TranslationDisplayMode.OFF) Color(0xFF042F2E) else Color.White
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (transMode != TranslationDisplayMode.OFF) {
                                        Color(0xFF5EEAD4)
                                    } else {
                                        Color.White.copy(alpha = 0.14f)
                                    }
                                ),
                                border = null
                            )

                            AssistChip(
                                onClick = { showOnlineSubSheet = true },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("在线字幕", color = Color.White) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color.White.copy(alpha = 0.14f)
                                ),
                                border = null
                            )

                            AssistChip(
                                onClick = { showAudioSubStyleSheet = true },
                                leadingIcon = {
                                    Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("音轨/字幕", color = Color.White) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color.White.copy(alpha = 0.14f)
                                ),
                                border = null
                            )

                            AssistChip(
                                onClick = { showSpeedTimerSheet = true },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("${uiState.playbackSpeed}x", color = Color.White) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color.White.copy(alpha = 0.14f)
                                ),
                                border = null
                            )

                            IconButton(onClick = { showPlaylistBookmarkSheet = true }) {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = "选集与书签", tint = Color.White)
                            }

                            IconButton(onClick = { playerViewModel.cycleAspectRatio() }) {
                                Icon(Icons.Outlined.AspectRatio, contentDescription = "切换画面比例", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // 8. Tablet Right-Side Interactive Bilingual Transcript Drawer
        AnimatedVisibility(
            visible = uiState.showTranscriptDrawer && !isInPipMode,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            TranscriptSideDrawer(
                cues = uiState.externalCues,
                activeIndex = uiState.activeCueIndex,
                onSeekToCue = { cue -> playerViewModel.commitSeek(cue.startTimeMs) },
                onExportBilingualSrt = { playerViewModel.exportBilingualSrtFile() },
                onOpenOnlineSearch = { showOnlineSubSheet = true },
                onClose = { playerViewModel.toggleTranscriptDrawer() }
            )
        }

        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
        )
    }

    // Modal Bottom Sheets
    if (showOnlineSubSheet) {
        OnlineSubtitleSheet(
            query = uiState.onlineQuery,
            languageFilter = uiState.onlineLanguageFilter,
            isSearching = uiState.isSearchingOnline,
            isDownloading = uiState.isDownloadingOnlineSub,
            results = uiState.onlineResults,
            statusMessage = uiState.onlineStatusMessage,
            onQueryChange = { playerViewModel.updateOnlineSearchQuery(it) },
            onLanguageFilterChange = { playerViewModel.updateOnlineLanguageFilter(it) },
            onSearch = { playerViewModel.searchOnlineSubtitles() },
            onDownloadAndApply = { item, genBilingual ->
                playerViewModel.downloadAndApplySubtitle(item, genBilingual)
            },
            onDismiss = { showOnlineSubSheet = false }
        )
    }

    if (showTranslationSheet) {
        TranslationControlSheet(
            config = settings.translationConfig,
            hasLoadedSubtitleCues = uiState.externalCues.isNotEmpty(),
            onSelectDisplayMode = { playerViewModel.setTranslationDisplayMode(it) },
            onSelectEngine = { eng -> playerViewModel.updateTranslationConfig { it.copy(engine = eng) } },
            onSelectTargetLanguage = { lang -> playerViewModel.updateTranslationConfig { it.copy(targetLanguage = lang) } },
            onSelectSourceLanguage = { src -> playerViewModel.updateTranslationConfig { it.copy(sourceLanguageCode = src) } },
            onDownloadOfflinePack = { lang, cb -> playerViewModel.downloadMlKitOfflinePack(lang, cb) },
            onRequestMicPermissionAndStartLiveSpeech = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    playerViewModel.setTranslationDisplayMode(TranslationDisplayMode.LIVE_SPEECH_BILINGUAL)
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onExportBilingualSrt = { playerViewModel.exportBilingualSrtFile() },
            onDismiss = { showTranslationSheet = false }
        )
    }

    if (showSpeedTimerSheet) {
        SpeedAndTimerSheet(
            currentSpeed = uiState.playbackSpeed,
            sleepTimerOption = uiState.sleepTimerOption,
            sleepRemainingSec = uiState.sleepTimerRemainingSec,
            aspectRatioMode = uiState.aspectRatioMode,
            rotationDegrees = uiState.rotationDegrees,
            isMirrored = uiState.isMirrored,
            onSpeedChange = { playerViewModel.setPlaybackSpeed(it) },
            onSleepTimerChange = { playerViewModel.setSleepTimer(it) },
            onSelectAspect = { mode ->
                while (playerViewModel.uiState.value.aspectRatioMode != mode) {
                    playerViewModel.cycleAspectRatio()
                }
            },
            onRotate90 = { playerViewModel.rotateVideo90() },
            onToggleMirror = { playerViewModel.toggleMirror() },
            onResetTransform = { playerViewModel.resetVideoTransform() },
            onDismiss = { showSpeedTimerSheet = false }
        )
    }

    if (showAudioSubStyleSheet) {
        AudioAndSubtitleStyleSheet(
            volumePercent = uiState.volumePercent,
            audioTracks = audioTracks,
            embeddedSubtitleTracks = embeddedSubTracks,
            activeExternalSubPath = uiState.activeExternalSubtitlePath,
            subtitlesEnabled = uiState.subtitlesEnabled,
            subtitleStyle = settings.subtitleStyle,
            onVolumePercentChange = { playerViewModel.setVolumePercent(it) },
            onSelectAudioTrack = { playerViewModel.playerController.selectAudioTrack(it) },
            onSelectEmbeddedSubTrack = { opt ->
                playerViewModel.playerController.selectEmbeddedSubtitleTrack(opt)
            },
            onToggleSubtitlesEnabled = { playerViewModel.toggleSubtitlesEnabled() },
            onImportLocalSubtitle = {
                subtitlePickerLauncher.launch(arrayOf("*/*"))
            },
            onUpdateSubtitleStyle = { playerViewModel.updateSubtitleStyle(it) },
            onDismiss = { showAudioSubStyleSheet = false }
        )
    }

    if (showPlaylistBookmarkSheet) {
        PlaylistAndBookmarksSheet(
            playlist = uiState.playlist,
            currentIndex = uiState.currentPlaylistIndex,
            bookmarks = currentBookmarks,
            onSelectPlaylistIndex = { playerViewModel.playPlaylistIndex(it) },
            onAddBookmarkNow = { playerViewModel.addBookmarkAtCurrentPosition() },
            onJumpToBookmark = { playerViewModel.commitSeek(it) },
            onDeleteBookmark = { playerViewModel.removeBookmark(it) },
            onDismiss = { showPlaylistBookmarkSheet = false }
        )
    }
}

@Composable
private fun BilingualSubtitleOverlay(
    primaryLine: String,
    secondaryLine: String,
    styleConfig: SubtitleStyleConfig,
    isCompactPip: Boolean,
    modifier: Modifier = Modifier
) {
    val primarySize = if (isCompactPip) 12f else styleConfig.fontSizeSp
    val secondarySize = primarySize * styleConfig.secondaryFontScale

    Surface(
        modifier = modifier.widthIn(max = 900.dp),
        color = Color.Black.copy(alpha = styleConfig.backgroundAlpha.coerceIn(0f, 0.9f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (isCompactPip) 4.dp else 8.dp)
        ) {
            if (primaryLine.isNotBlank()) {
                Text(
                    text = primaryLine,
                    color = if (secondaryLine.isNotBlank()) Color(styleConfig.translatedColorHex) else Color(styleConfig.primaryColorHex),
                    fontSize = primarySize.sp,
                    fontWeight = if (styleConfig.boldPrimary) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(color = Color.Black, blurRadius = 6f)
                    )
                )
            }
            if (secondaryLine.isNotBlank() && !isCompactPip) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = secondaryLine,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = secondarySize.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(color = Color.Black, blurRadius = 4f)
                    )
                )
            }
        }
    }
}

@Composable
private fun GestureHudCard(hud: GestureHudState) {
    Surface(
        color = Color(0xCC0F172A),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(min = 164.dp, max = 240.dp)
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            Text(
                text = hud.title,
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hud.valueText,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            hud.progressFraction?.let { progress ->
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = Color(0xFF5EEAD4),
                    trackColor = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun TranscriptSideDrawer(
    cues: List<SubtitleCue>,
    activeIndex: Int,
    onSeekToCue: (SubtitleCue) -> Unit,
    onExportBilingualSrt: () -> Unit,
    onOpenOnlineSearch: () -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex in cues.indices) {
            runCatching { listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0)) }
        }
    }

    Surface(
        color = Color(0xEE0F172A),
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color(0xFF5EEAD4))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "平板双语台词本 (${cues.size})",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (cues.isNotEmpty()) {
                    IconButton(onClick = onExportBilingualSrt) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = "导出双语字幕", tint = Color.White)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭台词本", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (cues.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ClosedCaption, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("当前暂未挂载外挂字幕", color = Color.LightGray)
                        Spacer(modifier = Modifier.height(10.dp))
                        AssistChip(
                            onClick = onOpenOnlineSearch,
                            label = { Text("立即在线搜索字幕", color = Color.White) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(cues, key = { _, c -> c.index }) { idx, cue ->
                        val isCurrent = idx == activeIndex
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSeekToCue(cue) },
                            color = if (isCurrent) Color(0xFF134E4A) else Color(0xFF1E293B)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = formatDurationMs(cue.startTimeMs),
                                    color = Color(0xFF5EEAD4),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                cue.translatedText?.takeIf { it.isNotBlank() }?.let { tr ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tr,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cue.originalText,
                                    color = Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
