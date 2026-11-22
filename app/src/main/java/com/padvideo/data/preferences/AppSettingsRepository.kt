package com.padvideo.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.padvideo.data.model.AppSettings
import com.padvideo.data.model.FavoriteFolder
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.SafRootBookmark
import com.padvideo.data.model.VideoBookmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("padvideo_preferences", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<PlaybackHistoryItem>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<List<FavoriteFolder>> = _favorites.asStateFlow()

    private val _safRoots = MutableStateFlow(loadSafRoots())
    val safRoots: StateFlow<List<SafRootBookmark>> = _safRoots.asStateFlow()

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<VideoBookmark>> = _bookmarks.asStateFlow()

    private val _mountedSources = MutableStateFlow(loadMountedSources())
    val mountedSources: StateFlow<List<MountedStorageSource>> = _mountedSources.asStateFlow()

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    private fun loadHistory(): List<PlaybackHistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PlaybackHistoryItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadFavorites(): List<FavoriteFolder> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FavoriteFolder>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSafRoots(): List<SafRootBookmark> {
        val raw = prefs.getString(KEY_SAF_ROOTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SafRootBookmark>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadBookmarks(): List<VideoBookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<VideoBookmark>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadMountedSources(): List<MountedStorageSource> {
        val raw = prefs.getString(KEY_MOUNTED_SOURCES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<MountedStorageSource>>(raw) }.getOrDefault(emptyList())
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(updated)).apply()
    }

    fun recordPlaybackProgress(item: PlaybackHistoryItem) {
        val existing = _history.value.toMutableList()
        existing.removeAll { it.uriString == item.uriString }
        existing.add(0, item.copy(lastPlayedAtMs = System.currentTimeMillis()))
        val trimmed = existing.take(120)
        _history.value = trimmed
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(trimmed)).apply()
    }

    fun getHistoryItem(uriString: String): PlaybackHistoryItem? {
        return _history.value.firstOrNull { it.uriString == uriString }
    }

    fun removeHistoryItem(uriString: String) {
        val updated = _history.value.filterNot { it.uriString == uriString }
        _history.value = updated
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun toggleFavoriteFolder(folder: FavoriteFolder) {
        val current = _favorites.value.toMutableList()
        val removed = current.removeAll { it.path == folder.path }
        if (!removed) {
            current.add(0, folder)
        }
        _favorites.value = current
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(current)).apply()
    }

    fun isFavoriteFolder(path: String): Boolean {
        return _favorites.value.any { it.path == path }
    }

    fun addSafRoot(root: SafRootBookmark) {
        val current = _safRoots.value.toMutableList()
        current.removeAll { it.treeUriString == root.treeUriString }
        current.add(0, root)
        _safRoots.value = current
        prefs.edit().putString(KEY_SAF_ROOTS, json.encodeToString(current)).apply()
    }

    fun removeSafRoot(treeUriString: String) {
        val updated = _safRoots.value.filterNot { it.treeUriString == treeUriString }
        _safRoots.value = updated
        prefs.edit().putString(KEY_SAF_ROOTS, json.encodeToString(updated)).apply()
    }

    fun addVideoBookmark(bookmark: VideoBookmark) {
        val current = _bookmarks.value.toMutableList()
        current.add(bookmark)
        val sorted = current.sortedBy { it.positionMs }
        _bookmarks.value = sorted
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(sorted)).apply()
    }

    fun removeVideoBookmark(bookmarkId: String) {
        val updated = _bookmarks.value.filterNot { it.id == bookmarkId }
        _bookmarks.value = updated
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(updated)).apply()
    }

    fun getBookmarksForVideo(videoUri: String): List<VideoBookmark> {
        return _bookmarks.value.filter { it.videoUri == videoUri }.sortedBy { it.positionMs }
    }

    fun saveMountedSource(source: MountedStorageSource) {
        val current = _mountedSources.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == source.id }
        if (existingIdx >= 0) {
            current[existingIdx] = source
        } else {
            current.add(0, source)
        }
        _mountedSources.value = current
        prefs.edit().putString(KEY_MOUNTED_SOURCES, json.encodeToString(current)).apply()
    }

    fun removeMountedSource(sourceId: String) {
        val updated = _mountedSources.value.filterNot { it.id == sourceId }
        _mountedSources.value = updated
        prefs.edit().putString(KEY_MOUNTED_SOURCES, json.encodeToString(updated)).apply()
    }

    fun getMountedSource(sourceId: String): MountedStorageSource? {
        return _mountedSources.value.firstOrNull { it.id == sourceId }
    }

    companion object {
        private const val KEY_SETTINGS = "app_settings_v1"
        private const val KEY_HISTORY = "playback_history_v1"
        private const val KEY_FAVORITES = "favorite_folders_v1"
        private const val KEY_SAF_ROOTS = "saf_roots_v1"
        private const val KEY_BOOKMARKS = "video_bookmarks_v1"
        private const val KEY_MOUNTED_SOURCES = "mounted_storage_sources_v1"
    }
}
