package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class AudioTrackChoice(val label: String) {
    ORIGINAL("Original"),
    BANGLA_DUB("বাংলা AI Dub")
}

enum class SubtitleChoice(val label: String) {
    OFF("Off"),
    BANGLA("বাংলা")
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioChoice: AudioTrackChoice = AudioTrackChoice.BANGLA_DUB,
    val subtitleChoice: SubtitleChoice = SubtitleChoice.BANGLA,
    val playbackSpeed: Float = 1.0f,
    val isLocked: Boolean = false,
    val activeSubtitleText: String? = null,
    val hasDubbedAudio: Boolean = false
)

class MediaPlayerManager(private val context: Context) {

    private val TAG = "MediaPlayerManager"

    var player: ExoPlayer? = null
        private set

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var currentVideoUri: Uri? = null
    private var currentDubbedAudioFile: File? = null
    private var currentSrtFile: File? = null

    // Parsed subtitles for instant on-screen rendering
    private val subtitleEntries = mutableListOf<ParsedSubtitle>()

    data class ParsedSubtitle(val startMs: Long, val endMs: Long, val text: String)

    fun initializePlayer(): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        _playerState.value = _playerState.value.copy(playbackSpeed = playbackParameters.speed)
                    }
                })
            }
        }
        return player!!
    }

    fun setupMedia(
        videoUri: Uri,
        dubbedAudioFile: File?,
        srtFile: File?
    ) {
        currentVideoUri = videoUri
        currentDubbedAudioFile = dubbedAudioFile
        currentSrtFile = srtFile

        loadSubtitleFile(srtFile)

        val exo = initializePlayer()
        val dataSourceFactory = DefaultDataSource.Factory(context)

        // 1. Video source
        val videoItem = MediaItem.fromUri(videoUri)
        val videoSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoItem)

        // 2. Dubbed audio source if available
        val hasDubbed = dubbedAudioFile != null && dubbedAudioFile.exists() && dubbedAudioFile.length() > 0
        _playerState.value = _playerState.value.copy(hasDubbedAudio = hasDubbed)

        if (hasDubbed) {
            val audioUri = Uri.fromFile(dubbedAudioFile)
            val audioItem = MediaItem.Builder()
                .setUri(audioUri)
                .setMimeType(MimeTypes.AUDIO_AAC)
                .build()
            val audioSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(audioItem)

            // Merge video source and external dubbed audio source
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            exo.setMediaSource(mergedSource)
        } else {
            exo.setMediaSource(videoSource)
        }

        exo.prepare()
        applyTrackSelection(_playerState.value.audioChoice)
    }

    fun setAudioChoice(choice: AudioTrackChoice) {
        _playerState.value = _playerState.value.copy(audioChoice = choice)
        applyTrackSelection(choice)
    }

    private fun applyTrackSelection(choice: AudioTrackChoice) {
        val exo = player ?: return
        val tracks = exo.currentTracks

        // If audio choice is ORIGINAL, select the first audio track (track group 0)
        // If BANGLA_DUB and merged source is present, select track group 1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                // Media3 handles track selection
                val trackCount = group.length
                if (trackCount > 1) {
                    val trackIndex = if (choice == AudioTrackChoice.BANGLA_DUB) 1 else 0
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                        .build()
                    break
                }
            }
        }
    }

    fun setSubtitleChoice(choice: SubtitleChoice) {
        _playerState.value = _playerState.value.copy(subtitleChoice = choice)
        updateCurrentSubtitleText(_playerState.value.currentPositionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _playerState.value = _playerState.value.copy(playbackSpeed = speed)
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val current = exo.currentPosition
        val target = (current + deltaMs).coerceIn(0L, exo.duration.coerceAtLeast(0L))
        exo.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    fun toggleLock() {
        _playerState.value = _playerState.value.copy(isLocked = !_playerState.value.isLocked)
    }

    fun updateProgress() {
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(0L)
        _playerState.value = _playerState.value.copy(
            currentPositionMs = pos,
            durationMs = dur
        )
        updateCurrentSubtitleText(pos)
    }

    private fun updateCurrentSubtitleText(currentPositionMs: Long) {
        if (_playerState.value.subtitleChoice == SubtitleChoice.OFF || subtitleEntries.isEmpty()) {
            if (_playerState.value.activeSubtitleText != null) {
                _playerState.value = _playerState.value.copy(activeSubtitleText = null)
            }
            return
        }

        val active = subtitleEntries.firstOrNull { currentPositionMs in it.startMs..it.endMs }
        val text = active?.text
        if (_playerState.value.activeSubtitleText != text) {
            _playerState.value = _playerState.value.copy(activeSubtitleText = text)
        }
    }

    private fun loadSubtitleFile(srtFile: File?) {
        subtitleEntries.clear()
        if (srtFile == null || !srtFile.exists()) return

        try {
            val lines = srtFile.readLines(Charsets.UTF_8)
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.toIntOrNull() != null && i + 1 < lines.size) {
                    val timeLine = lines[i + 1].trim()
                    val timeParts = timeLine.split(" --> ")
                    if (timeParts.size == 2) {
                        val startMs = parseSrtTimestamp(timeParts[0])
                        val endMs = parseSrtTimestamp(timeParts[1])
                        var text = ""
                        var textLineIdx = i + 2
                        while (textLineIdx < lines.size && lines[textLineIdx].isNotBlank()) {
                            text += lines[textLineIdx] + " "
                            textLineIdx++
                        }
                        if (text.isNotBlank()) {
                            subtitleEntries.add(ParsedSubtitle(startMs, endMs, text.trim()))
                        }
                        i = textLineIdx
                    }
                }
                i++
            }
            Log.d(TAG, "Loaded ${subtitleEntries.size} subtitle entries from ${srtFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing SRT file", e)
        }
    }

    private fun parseSrtTimestamp(str: String): Long {
        return try {
            val parts = str.trim().split(":")
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secParts = parts[2].split(",")
            val seconds = secParts[0].toLong()
            val millis = secParts[1].toLong()
            (hours * 3600000) + (minutes * 60000) + (seconds * 1000) + millis
        } catch (e: Exception) {
            0L
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}
