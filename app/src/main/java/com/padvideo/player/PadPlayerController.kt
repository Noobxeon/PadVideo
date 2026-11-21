package com.padvideo.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import com.padvideo.data.model.AudioTrackOption
import com.padvideo.data.model.SubtitleTrackOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class PadPlayerController(
    private val context: Context,
    preferHardwareDecoder: Boolean = true
) {

    val exoPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentBoostGainMb: Int = 0

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _videoSize = MutableStateFlow(0 to 0)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<AudioTrackOption>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackOption>> = _audioTracks.asStateFlow()

    private val _embeddedSubtitleTracks = MutableStateFlow<List<SubtitleTrackOption>>(emptyList())
    val embeddedSubtitleTracks: StateFlow<List<SubtitleTrackOption>> = _embeddedSubtitleTracks.asStateFlow()

    private val _liveEmbeddedSubtitleCue = MutableStateFlow("")
    val liveEmbeddedSubtitleCue: StateFlow<String> = _liveEmbeddedSubtitleCue.asStateFlow()

    private val _playbackEndedCount = MutableStateFlow(0)
    val playbackEndedCount: StateFlow<Int> = _playbackEndedCount.asStateFlow()

    init {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(
                if (preferHardwareDecoder) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                }
            )
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
                    _playbackError.value = null
                } else if (playbackState == Player.STATE_ENDED) {
                    _playbackEndedCount.value += 1
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoSize.value = videoSize.width to videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _playbackError.value = "播放出错 (${error.errorCodeName}): ${error.localizedMessage ?: "未知解码错误"}"
            }

            override fun onTracksChanged(tracks: Tracks) {
                parseTracks(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                val combined = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                _liveEmbeddedSubtitleCue.value = combined
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudnessEnhancer(audioSessionId)
            }
        })

        runCatching {
            mediaSession = MediaSession.Builder(context, exoPlayer)
                .setId("padvideo_session_${System.currentTimeMillis()}")
                .build()
        }
    }

    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
            runCatching {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(currentBoostGainMb)
                    enabled = currentBoostGainMb > 0
                }
            }
        }
    }

    private fun parseTracks(tracks: Tracks) {
        val audios = mutableListOf<AudioTrackOption>()
        val subs = mutableListOf<SubtitleTrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val lang = format.language ?: "und"
                        val labelBase = format.label
                            ?: Locale.forLanguageTag(lang).displayLanguage.takeIf { it.isNotBlank() && it != "und" }
                            ?: "音轨 ${audios.size + 1}"
                        val chText = when (format.channelCount) {
                            1 -> "单声道"
                            2 -> "立体声 2.0"
                            6 -> "环绕声 5.1"
                            8 -> "全景声 7.1"
                            in 3..16 -> "${format.channelCount} 声道"
                            else -> ""
                        }
                        val codec = format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
                        val fullLabel = listOf(labelBase, chText, codec)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        audios.add(
                            AudioTrackOption(
                                id = "audio_${groupIdx}_$trackIdx",
                                label = fullLabel,
                                language = lang,
                                channelCount = format.channelCount,
                                mimeType = format.sampleMimeType.orEmpty(),
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                isSelected = group.isTrackSelected(trackIdx)
                            )
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val lang = format.language ?: "und"
                        val labelBase = format.label
                            ?: Locale.forLanguageTag(lang).displayLanguage.takeIf { it.isNotBlank() && it != "und" }
                            ?: "内嵌字幕 ${subs.size + 1}"
                        subs.add(
                            SubtitleTrackOption(
                                id = "emb_sub_${groupIdx}_$trackIdx",
                                label = "$labelBase (内嵌)",
                                language = lang,
                                isExternal = false,
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                isSelected = group.isTrackSelected(trackIdx)
                            )
                        )
                    }
                }
            }
        }

        _audioTracks.value = audios
        _embeddedSubtitleTracks.value = subs
    }

    fun loadVideo(uriString: String, title: String, startPositionMs: Long = 0L) {
        _playbackError.value = null
        _liveEmbeddedSubtitleCue.value = ""
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(uriString))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()

        exoPlayer.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun play() {
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        exoPlayer.seekTo(positionMs.coerceIn(0L, dur))
    }

    fun seekBy(deltaMs: Long) {
        seekTo(exoPlayer.currentPosition + deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        exoPlayer.playbackParameters = PlaybackParameters(clamped, 1.0f)
    }

    /**
     * Supports 0%..200% volume:
     * 0..100 controls player volume; 101..200 engages LoudnessEnhancer up to +1400 mB.
     */
    fun setPlayerVolumeAndBoost(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        if (clamped <= 100) {
            exoPlayer.volume = clamped / 100f
            currentBoostGainMb = 0
            runCatching {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            }
        } else {
            exoPlayer.volume = 1.0f
            // Map 101..200 -> +14..+1400 millibels (+14dB max audio boost)
            currentBoostGainMb = (clamped - 100) * 14
            if (loudnessEnhancer == null) {
                attachLoudnessEnhancer(exoPlayer.audioSessionId)
            }
            runCatching {
                loudnessEnhancer?.setTargetGain(currentBoostGainMb)
                loudnessEnhancer?.enabled = true
            }
        }
    }

    fun selectAudioTrack(option: AudioTrackOption) {
        val groups = exoPlayer.currentTracks.groups
        if (option.groupIndex !in groups.indices) return
        val mediaGroup = groups[option.groupIndex].mediaTrackGroup
        val params = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(mediaGroup, option.trackIndex))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        exoPlayer.trackSelectionParameters = params
    }

    fun selectEmbeddedSubtitleTrack(option: SubtitleTrackOption?) {
        if (option == null) {
            val params = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            exoPlayer.trackSelectionParameters = params
            _liveEmbeddedSubtitleCue.value = ""
        } else {
            val groups = exoPlayer.currentTracks.groups
            if (option.groupIndex !in groups.indices) return
            val mediaGroup = groups[option.groupIndex].mediaTrackGroup
            val params = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(mediaGroup, option.trackIndex))
                .build()
            exoPlayer.trackSelectionParameters = params
        }
    }

    suspend fun captureCurrentFrame(
        uriString: String,
        filePath: String?,
        positionMs: Long,
        videoTitle: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            val bitmap: Bitmap = try {
                if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                    retriever.setDataSource(filePath)
                } else {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        retriever.setDataSource(uriString, HashMap())
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                }
                retriever.getFrameAtTime(
                    positionMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: retriever.getFrameAtTime(
                    positionMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: throw IllegalStateException("当前视频流不支持静帧抓取")
            } finally {
                runCatching { retriever.release() }
            }

            val safeName = videoTitle.substringBeforeLast('.')
                .replace(Regex("""[^a-zA-Z0-9\u4E00-\u9FA5_-]"""), "_")
                .take(40)
                .ifBlank { "PadVideo" }
            val fileName = "${safeName}_${positionMs / 1000}s_${System.currentTimeMillis() % 10000}.png"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PadVideo")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("无法写入系统相册")

            resolver.openOutputStream(insertedUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 96, out)
            } ?: throw IllegalStateException("无法保存截图文件")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(insertedUri, contentValues, null, null)
            }

            "已保存截图至相册 Pictures/PadVideo/$fileName"
        }
    }

    fun release() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { mediaSession?.release() }
        mediaSession = null
        exoPlayer.release()
    }
}
