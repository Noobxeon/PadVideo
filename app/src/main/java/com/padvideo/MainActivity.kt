package com.padvideo

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.padvideo.ui.MainViewModel
import com.padvideo.ui.PadVideoApp
import com.padvideo.ui.PlayerViewModel

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        val app = application
        if (app is PadVideoApplication) {
            app.container
        } else {
            AppContainer(applicationContext)
        }
    }

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.factory(appContainer)
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.factory(applicationContext, appContainer)
    }

    private var inPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleExternalVideoIntent(intent)

        setContent {
            PadVideoApp(
                container = appContainer,
                mainViewModel = mainViewModel,
                playerViewModel = playerViewModel,
                isInPipMode = inPipModeState,
                onEnterPip = { enterTabletPipMode() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalVideoIntent(intent)
    }

    private fun handleExternalVideoIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            mainViewModel.playExternalIntentUri(uri)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isPlayerActive = mainViewModel.activeLaunch.value != null
        val isPlaying = playerViewModel.playerController.isPlaying.value
        val autoPip = appContainer.settingsRepository.settings.value.autoEnterPip
        if (isPlayerActive && isPlaying && autoPip) {
            enterTabletPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipModeState = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            val bgAudio = appContainer.settingsRepository.settings.value.backgroundAudioEnabled
            if (!bgAudio && mainViewModel.activeLaunch.value != null) {
                playerViewModel.playerController.pause()
            }
            playerViewModel.saveCurrentPlaybackHistory()
        }
    }

    private fun enterTabletPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val (vw, vh) = playerViewModel.playerController.videoSize.value
                val rational = if (vw > 0 && vh > 0) {
                    val ratio = vw.toFloat() / vh.toFloat()
                    if (ratio in 0.42f..2.38f) Rational(vw, vh) else Rational(16, 9)
                } else {
                    Rational(16, 9)
                }
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }
}
