package com.padvideo

import android.app.Application
import android.content.Context
import com.padvideo.data.preferences.AppSettingsRepository
import com.padvideo.data.repository.FileExplorerRepository
import com.padvideo.data.repository.OnlineSubtitleRepository
import com.padvideo.data.repository.QuarkDriveRepository
import com.padvideo.data.repository.RealtimeTranslationRepository
import com.padvideo.data.repository.SmbRepository
import com.padvideo.data.repository.WebDavRepository
import com.padvideo.player.BatchSubtitleGenerator
import com.padvideo.player.GeneratedSubtitleStore
import com.padvideo.player.VideoSpeechRecognizerEngine
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = AppSettingsRepository(appContext)
    internal val smbRepository = SmbRepository().apply {
        registerSavedMounts(settingsRepository.mountedSources.value)
    }
    val webDavRepository = WebDavRepository()
    val quarkDriveRepository = QuarkDriveRepository()
    val fileExplorerRepository = FileExplorerRepository(appContext)
    val translationRepository = RealtimeTranslationRepository(
        cacheDirectory = File(appContext.cacheDir, "translations")
    )
    val videoSpeechRecognizer = VideoSpeechRecognizerEngine(
        modelsRootDir = File(appContext.filesDir, "asr_models")
    )
    internal val generatedSubtitleStore = GeneratedSubtitleStore(
        context = appContext,
        baseDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "GeneratedSubtitles"),
        smbRepository = smbRepository,
        webDavRepository = webDavRepository,
        settingsRepository = settingsRepository
    )
    internal val batchSubtitleGenerator = BatchSubtitleGenerator(
        appContext = appContext,
        smbRepository = smbRepository,
        webDavRepository = webDavRepository,
        quarkDriveRepository = quarkDriveRepository,
        settingsRepository = settingsRepository,
        speechRecognizer = videoSpeechRecognizer,
        translationRepository = translationRepository,
        generatedSubtitleStore = generatedSubtitleStore
    )
    val onlineSubtitleRepository = OnlineSubtitleRepository(
        subtitleStorageDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Subtitles"),
        translationRepository = translationRepository
    )
}

class PadVideoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
