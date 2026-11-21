package com.padvideo.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val uriString: String,
    val filePath: String? = null,
    val folderName: String = "未分类",
    val folderPath: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val dateModifiedSec: Long = 0L,
    val mimeType: String = "video/*",
    val sidecarSubtitlePaths: List<String> = emptyList(),
    val lastPositionMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L
) {
    val resolutionBadge: String?
        get() {
            val maxDim = maxOf(width, height)
            val minDim = minOf(width, height)
            return when {
                maxDim >= 3800 || minDim >= 2100 -> "4K UHD"
                maxDim >= 2500 || minDim >= 1400 -> "2K QHD"
                maxDim >= 1900 || minDim >= 1060 -> "1080P"
                maxDim >= 1260 || minDim >= 700 -> "720P"
                maxDim > 0 -> "${minDim}P"
                else -> null
            }
        }

    val watchProgress: Float
        get() = if (durationMs > 0L && lastPositionMs > 0L) {
            (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val formattedDuration: String
        get() = formatDurationMs(durationMs)

    val formattedSize: String
        get() = formatFileSize(sizeBytes)
}

data class FileEntry(
    val name: String,
    val path: String,
    val uriString: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val childCount: Int = 0,
    val isVideoFile: Boolean = false,
    val isSubtitleFile: Boolean = false,
    val sidecarSubtitles: List<String> = emptyList(),
    val videoItem: VideoItem? = null
)

data class BreadcrumbSegment(
    val label: String,
    val path: String,
    val isSafUri: Boolean = false
)

@Serializable
enum class SortField(val label: String) {
    NAME("按名称"),
    DATE_MODIFIED("按修改时间"),
    SIZE("按文件大小"),
    DURATION("按视频时长")
}

@Serializable
enum class ViewLayoutMode {
    GRID,
    LIST
}

@Serializable
data class SafRootBookmark(
    val name: String,
    val treeUriString: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class FavoriteFolder(
    val name: String,
    val path: String,
    val isSafUri: Boolean = false,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class PlaybackHistoryItem(
    val uriString: String,
    val title: String,
    val filePath: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val lastPlayedAtMs: Long = System.currentTimeMillis(),
    val width: Int = 0,
    val height: Int = 0,
    val externalSubtitlePath: String? = null,
    val playbackSpeed: Float = 1.0f
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Serializable
data class VideoBookmark(
    val id: String,
    val videoUri: String,
    val label: String,
    val positionMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class StreamPreset(
    val title: String,
    val subtitle: String,
    val streamUrl: String,
    val sampleEnglishSrt: String? = null,
    val badge: String = "1080P CC-BY"
)

fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatSignedMs(deltaMs: Long): String {
    val sign = if (deltaMs >= 0) "+" else "-"
    return "$sign${formatDurationMs(kotlin.math.abs(deltaMs))}"
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
