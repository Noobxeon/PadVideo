package com.padvideo

import android.app.Application
import android.content.Context
import com.padvideo.data.preferences.AppSettingsRepository
import com.padvideo.data.repository.FileExplorerRepository
import com.padvideo.data.repository.OnlineSubtitleRepository
import com.padvideo.data.repository.RealtimeTranslationRepository
import com.padvideo.data.repository.SmbRepository
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = AppSettingsRepository(appContext)
    internal val smbRepository = SmbRepository()
    val fileExplorerRepository = FileExplorerRepository(appContext)
    val translationRepository = RealtimeTranslationRepository(
        cacheDirectory = File(appContext.cacheDir, "translations")
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
