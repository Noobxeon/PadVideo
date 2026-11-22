package com.padvideo.data.repository

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import com.padvideo.data.model.BreadcrumbSegment
import com.padvideo.data.model.FileEntry
import com.padvideo.data.model.PlaybackHistoryItem
import com.padvideo.data.model.SortField
import com.padvideo.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class FileExplorerRepository(private val context: Context?) {

    private val thumbnailCache = object : LruCache<String, Bitmap>(64) {}

    val defaultStorageRoot: File
        get() = runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.takeIf { it.exists() }
            ?: context?.filesDir
            ?: File(".")

    fun getQuickAccessDirectories(): List<Pair<String, File>> {
        val root = defaultStorageRoot
        val candidates = listOf(
            "内部存储" to root,
            "Movies (电影)" to File(root, "Movies"),
            "Download (下载)" to File(root, "Download"),
            "DCIM (相册视频)" to File(root, "DCIM")
        )
        return candidates.filter { (_, dir) -> dir.exists() || dir == root }
    }

    suspend fun scanMediaLibrary(
        historyByUri: Map<String, PlaybackHistoryItem> = emptyMap()
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext emptyList()
        val results = mutableListOf<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE
        )

        runCatching {
            ctx.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val name = cursor.getString(nameCol) ?: "未命名视频_$id"
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val parentFile = filePath?.let { File(it).parentFile }
                    val folderName = (if (bucketCol >= 0) cursor.getString(bucketCol) else null)
                        ?: parentFile?.name
                        ?: "未分类"
                    val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val dateMod = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                    val mime = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "video/*"

                    val sidecars = filePath?.let { findSidecarSubtitles(File(it)) } ?: emptyList()
                    val hist = historyByUri[contentUri] ?: filePath?.let { fp ->
                        historyByUri[fileToUriString(File(fp))]
                    }

                    results.add(
                        VideoItem(
                            id = id.toString(),
                            title = name,
                            uriString = contentUri,
                            filePath = filePath,
                            folderName = folderName,
                            folderPath = parentFile?.absolutePath.orEmpty(),
                            durationMs = durationMs.coerceAtLeast(hist?.durationMs ?: 0L),
                            sizeBytes = sizeBytes,
                            width = width,
                            height = height,
                            dateModifiedSec = dateMod,
                            mimeType = mime,
                            sidecarSubtitlePaths = sidecars,
                            lastPositionMs = hist?.positionMs ?: 0L,
                            lastPlayedAtMs = hist?.lastPlayedAtMs ?: 0L
                        )
                    )
                }
            }
        }
        results
    }

    fun listDirectory(
        directory: File,
        showHidden: Boolean = false,
        searchQuery: String = "",
        sortField: SortField = SortField.NAME,
        sortAscending: Boolean = true,
        historyByUri: Map<String, PlaybackHistoryItem> = emptyMap()
    ): List<FileEntry> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        val children = directory.listFiles() ?: return emptyList()

        // Pre-index subtitle files in this directory for fast sidecar matching
        val subtitleFilesInDir = children.filter { it.isFile && isSubtitleFileName(it.name) }

        val entries = children.mapNotNull { file ->
            if (!showHidden && file.name.startsWith(".")) return@mapNotNull null
            if (searchQuery.isNotBlank() && !file.name.contains(searchQuery.trim(), ignoreCase = true)) {
                return@mapNotNull null
            }

            if (file.isDirectory) {
                val subChildren = file.listFiles()
                val mediaCount = subChildren?.count { child ->
                    (!child.name.startsWith(".") || showHidden) &&
                        (child.isDirectory || isVideoFileName(child.name))
                } ?: 0
                FileEntry(
                    name = file.name,
                    path = file.absolutePath,
                    uriString = fileToUriString(file),
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModifiedMs = file.lastModified(),
                    childCount = mediaCount
                )
            } else {
                val isVid = isVideoFileName(file.name)
                val isSub = isSubtitleFileName(file.name)
                if (!isVid && !isSub && !showHidden) {
                    // In video explorer mode, focus on directories, videos, and subtitle files unless showHidden is enabled
                    return@mapNotNull null
                }
                val fileUri = fileToUriString(file)
                val sidecars = if (isVid) {
                    matchSidecarSubtitlesInList(file.name, subtitleFilesInDir)
                } else {
                    emptyList()
                }
                val hist = historyByUri[fileUri]
                val videoItem = if (isVid) {
                    VideoItem(
                        id = file.absolutePath.hashCode().toString(),
                        title = file.name,
                        uriString = fileUri,
                        filePath = file.absolutePath,
                        folderName = directory.name.ifBlank { "存储" },
                        folderPath = directory.absolutePath,
                        durationMs = hist?.durationMs ?: 0L,
                        sizeBytes = file.length(),
                        width = hist?.width ?: 0,
                        height = hist?.height ?: 0,
                        dateModifiedSec = file.lastModified() / 1000L,
                        mimeType = guessVideoMimeType(file.name),
                        sidecarSubtitlePaths = sidecars,
                        lastPositionMs = hist?.positionMs ?: 0L,
                        lastPlayedAtMs = hist?.lastPlayedAtMs ?: 0L
                    )
                } else {
                    null
                }
                FileEntry(
                    name = file.name,
                    path = file.absolutePath,
                    uriString = fileUri,
                    isDirectory = false,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    isVideoFile = isVid,
                    isSubtitleFile = isSub,
                    sidecarSubtitles = sidecars,
                    videoItem = videoItem
                )
            }
        }

        return sortFileEntries(entries, sortField, sortAscending)
    }

    suspend fun listSafDirectory(
        treeOrDirDoc: DocumentFile,
        showHidden: Boolean = false,
        searchQuery: String = "",
        sortField: SortField = SortField.NAME,
        sortAscending: Boolean = true,
        historyByUri: Map<String, PlaybackHistoryItem> = emptyMap()
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val children = treeOrDirDoc.listFiles()
        val subtitleDocs = children.filter { doc ->
            !doc.isDirectory && doc.name?.let { isSubtitleFileName(it) } == true
        }
        val entries = children.mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            if (searchQuery.isNotBlank() && !name.contains(searchQuery.trim(), ignoreCase = true)) {
                return@mapNotNull null
            }
            val uriStr = doc.uri.toString()
            if (doc.isDirectory) {
                FileEntry(
                    name = name,
                    path = uriStr,
                    uriString = uriStr,
                    isDirectory = true,
                    lastModifiedMs = doc.lastModified()
                )
            } else {
                val isVid = isVideoFileName(name) || doc.type?.startsWith("video/") == true
                val isSub = isSubtitleFileName(name)
                if (!isVid && !isSub && !showHidden) return@mapNotNull null
                val sidecars = if (isVid) {
                    val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
                    subtitleDocs.filter { sub ->
                        val stem = (sub.name ?: "").substringBeforeLast('.').lowercase(Locale.ROOT)
                        stem == base || stem.startsWith("$base.") || stem.startsWith("${base}_")
                    }.map { it.uri.toString() }
                } else {
                    emptyList()
                }
                val hist = historyByUri[uriStr]
                val videoItem = if (isVid) {
                    VideoItem(
                        id = uriStr.hashCode().toString(),
                        title = name,
                        uriString = uriStr,
                        filePath = null,
                        folderName = treeOrDirDoc.name ?: "外接目录",
                        folderPath = treeOrDirDoc.uri.toString(),
                        durationMs = hist?.durationMs ?: 0L,
                        sizeBytes = doc.length(),
                        dateModifiedSec = doc.lastModified() / 1000L,
                        mimeType = doc.type ?: guessVideoMimeType(name),
                        sidecarSubtitlePaths = sidecars,
                        lastPositionMs = hist?.positionMs ?: 0L,
                        lastPlayedAtMs = hist?.lastPlayedAtMs ?: 0L
                    )
                } else {
                    null
                }
                FileEntry(
                    name = name,
                    path = uriStr,
                    uriString = uriStr,
                    isDirectory = false,
                    sizeBytes = doc.length(),
                    lastModifiedMs = doc.lastModified(),
                    isVideoFile = isVid,
                    isSubtitleFile = isSub,
                    sidecarSubtitles = sidecars,
                    videoItem = videoItem
                )
            }
        }
        sortFileEntries(entries, sortField, sortAscending)
    }

    fun sortFileEntries(
        entries: List<FileEntry>,
        sortField: SortField,
        ascending: Boolean
    ): List<FileEntry> {
        val comparator = Comparator<FileEntry> { a, b ->
            // Directories always grouped at top
            if (a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }
            val rawCmp = when (sortField) {
                SortField.NAME -> compareNaturalStrings(a.name, b.name)
                SortField.DATE_MODIFIED -> a.lastModifiedMs.compareTo(b.lastModifiedMs)
                SortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                SortField.DURATION -> (a.videoItem?.durationMs ?: 0L).compareTo(b.videoItem?.durationMs ?: 0L)
            }
            if (ascending) rawCmp else -rawCmp
        }
        return entries.sortedWith(comparator)
    }

    fun sortVideos(
        videos: List<VideoItem>,
        sortField: SortField,
        ascending: Boolean
    ): List<VideoItem> {
        val comparator = Comparator<VideoItem> { a, b ->
            val rawCmp = when (sortField) {
                SortField.NAME -> compareNaturalStrings(a.title, b.title)
                SortField.DATE_MODIFIED -> a.dateModifiedSec.compareTo(b.dateModifiedSec)
                SortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                SortField.DURATION -> a.durationMs.compareTo(b.durationMs)
            }
            if (ascending) rawCmp else -rawCmp
        }
        return videos.sortedWith(comparator)
    }

    fun buildBreadcrumbs(currentDir: File, baseRoot: File = defaultStorageRoot): List<BreadcrumbSegment> {
        val currentAbs = currentDir.absoluteFile
        val rootAbs = baseRoot.absoluteFile
        if (!currentAbs.path.startsWith(rootAbs.path)) {
            val chain = mutableListOf<File>()
            var ptr: File? = currentAbs
            while (ptr != null) {
                chain.add(0, ptr)
                ptr = ptr.parentFile
            }
            return chain.map { f ->
                BreadcrumbSegment(
                    label = f.name.ifEmpty { f.path },
                    path = f.absolutePath
                )
            }
        }

        val segments = mutableListOf(
            BreadcrumbSegment(label = "内部存储", path = rootAbs.absolutePath)
        )
        if (currentAbs.path == rootAbs.path) return segments

        val relative = currentAbs.path.removePrefix(rootAbs.path).trimStart('/', '\\')
        var running = rootAbs
        relative.split('/', '\\').filter { it.isNotEmpty() }.forEach { part ->
            running = File(running, part)
            segments.add(BreadcrumbSegment(label = part, path = running.absolutePath))
        }
        return segments
    }

    fun findSidecarSubtitles(videoFile: File): List<String> {
        val parent = videoFile.parentFile ?: return emptyList()
        val siblings = parent.listFiles()?.filter { it.isFile && isSubtitleFileName(it.name) } ?: return emptyList()
        return matchSidecarSubtitlesInList(videoFile.name, siblings)
    }

    private fun matchSidecarSubtitlesInList(videoName: String, subtitleFiles: List<File>): List<String> {
        val base = videoName.substringBeforeLast('.').lowercase(Locale.ROOT)
        if (base.isEmpty()) return emptyList()
        return subtitleFiles
            .filter { sub ->
                val subLower = sub.name.lowercase(Locale.ROOT)
                val subStem = subLower.substringBeforeLast('.')
                subStem == base || subStem.startsWith("$base.") || subStem.startsWith("${base}_")
            }
            .sortedBy { it.name }
            .map { it.absolutePath }
    }

    fun findSiblingEpisodePlaylist(
        currentVideo: VideoItem,
        libraryFallback: List<VideoItem> = emptyList()
    ): List<VideoItem> {
        val filePath = currentVideo.filePath
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            val parent = file.parentFile
            if (parent != null && parent.exists() && parent.isDirectory) {
                val entries = listDirectory(
                    directory = parent,
                    sortField = SortField.NAME,
                    sortAscending = true
                )
                val videos = entries.mapNotNull { it.videoItem }
                if (videos.isNotEmpty()) return videos
            }
        }
        if (currentVideo.folderPath.isNotBlank()) {
            val sameFolder = libraryFallback
                .filter { it.folderPath == currentVideo.folderPath }
                .sortedWith { a, b -> compareNaturalStrings(a.title, b.title) }
            if (sameFolder.isNotEmpty()) return sameFolder
        }
        return listOf(currentVideo)
    }

    suspend fun loadVideoThumbnail(
        video: VideoItem,
        widthPx: Int = 360,
        heightPx: Int = 202
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${video.uriString}_${widthPx}x$heightPx"
        thumbnailCache.get(cacheKey)?.let { return@withContext it }

        val ctx = context
        val bitmap: Bitmap? = runCatching {
            val parsedUri = Uri.parse(video.uriString)
            if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && parsedUri.scheme == "content") {
                ctx.contentResolver.loadThumbnail(parsedUri, Size(widthPx, heightPx), null)
            } else if (!video.filePath.isNullOrBlank() && File(video.filePath).exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(
                        File(video.filePath),
                        Size(widthPx, heightPx),
                        null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        video.filePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
            } else if (ctx != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, parsedUri)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                null
            }
        }.getOrNull()

        if (bitmap != null) {
            thumbnailCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    private fun fileToUriString(file: File): String {
        return runCatching { Uri.fromFile(file)?.toString() }.getOrNull() ?: file.toURI().toString()
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m2ts", "mts",
            "wmv", "3gp", "m4v", "mpg", "mpeg", "rmvb", "rm", "ogv", "vob"
        )

        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

        fun isVideoFileName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in VIDEO_EXTENSIONS
        }

        fun isSubtitleFileName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in SUBTITLE_EXTENSIONS
        }

        fun guessVideoMimeType(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "flv" -> "video/x-flv"
                "3gp" -> "video/3gpp"
                else -> "video/*"
            }
        }

        /**
         * Natural alphanumeric comparison so "Episode 2" sorts before "Episode 10".
         */
        fun compareNaturalStrings(a: String, b: String): Int {
            var ia = 0
            var ib = 0
            val na = a.length
            val nb = b.length
            while (ia < na && ib < nb) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    var startA = ia
                    while (ia < na && a[ia].isDigit()) ia++
                    var startB = ib
                    while (ib < nb && b[ib].isDigit()) ib++
                    val numA = a.substring(startA, ia).trimStart('0').ifEmpty { "0" }
                    val numB = b.substring(startB, ib).trimStart('0').ifEmpty { "0" }
                    if (numA.length != numB.length) {
                        return numA.length.compareTo(numB.length)
                    }
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return cmp
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return cmp
                    ia++
                    ib++
                }
            }
            return (na - ia).compareTo(nb - ib)
        }
    }
}
