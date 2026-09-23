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

                    override fun onTracksChanged(tracks: Tracks) {
                        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        Log.i(TAG, "onTracksChanged: found ${audioGroups.size} audio track group(s)")
                        for (i in audioGroups.indices) {
                            val g = audioGroups[i]
                            Log.i(TAG, "Track group $i: isSelected=${g.isSelected}, isSupported=${g.isSupported}, format=${g.getTrackFormat(0)}")
                        }
                        applyTrackSelection(_playerState.value.audioChoice)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} - ${error.message}", error)
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

        // 2. Resolve effective dubbed audio file (m4a or fallback wav)
        val effectiveDubbedFile = when {
            dubbedAudioFile != null && dubbedAudioFile.exists() && dubbedAudioFile.length() > 44 -> dubbedAudioFile
            dubbedAudioFile != null -> {
                val parent = dubbedAudioFile.parentFile
                val wavFile = if (parent != null) File(parent, "dubbed_bn.wav") else null
                if (wavFile != null && wavFile.exists() && wavFile.length() > 44) wavFile else null
            }
            else -> null
        }

        val hasDubbed = effectiveDubbedFile != null
        val initialChoice = if (hasDubbed) AudioTrackChoice.BANGLA_DUB else AudioTrackChoice.ORIGINAL
        _playerState.value = _playerState.value.copy(hasDubbedAudio = hasDubbed, audioChoice = initialChoice)

        if (effectiveDubbedFile != null) {
            val audioUri = Uri.fromFile(effectiveDubbedFile)
            val audioItem = MediaItem.Builder()
                .setUri(audioUri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("বাংলা AI Dub")
                        .build()
                )
                .build()
            val audioSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(audioItem)

            // Merge video source and external dubbed audio source without clipping video duration
            val mergedSource = MergingMediaSource(true, false, videoSource, audioSource)
            exo.setMediaSource(mergedSource)
            Log.i(TAG, "Merged video source with dubbed audio file: ${effectiveDubbedFile.absolutePath} (${effectiveDubbedFile.length()} bytes)")
        } else {
            exo.setMediaSource(videoSource)
            Log.i(TAG, "Loaded video source without dubbed audio (dubbed file unavailable)")
        }

        exo.prepare()
        exo.volume = 1.0f
        applyTrackSelection(initialChoice)
    }

    fun setAudioChoice(choice: AudioTrackChoice) {
        _playerState.value = _playerState.value.copy(audioChoice = choice)
        applyTrackSelection(choice)
    }

    private fun applyTrackSelection(choice: AudioTrackChoice) {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        Log.i(TAG, "applyTrackSelection: requested=$choice, available audio groups=${audioGroups.size}")
        if (audioGroups.isEmpty()) return

        // Case 1: Multiple audio groups (MergingMediaSource: video audio + external dubbed audio)
        // audioGroups[0] is original video audio; audioGroups.last() is merged dubbed audio
        if (audioGroups.size > 1) {
            val targetGroup = if (choice == AudioTrackChoice.BANGLA_DUB) {
                audioGroups.last()
            } else {
                audioGroups.first()
            }
            Log.i(TAG, "Applying track override for ${choice.name}: using audio group with format ${targetGroup.getTrackFormat(0)}")
            val override = TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(override)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
            return
        }

        // Case 2: Single audio group with multiple tracks (container has multi-channel/multi-track audio)
        val singleGroup = audioGroups[0]
        if (singleGroup.length > 1) {
            val trackIdx = if (choice == AudioTrackChoice.BANGLA_DUB) 1 else 0
            val safeTrackIdx = trackIdx.coerceAtMost(singleGroup.length - 1)
            Log.i(TAG, "Applying multi-track override in single group: track index $safeTrackIdx")
            val override = TrackSelectionOverride(singleGroup.mediaTrackGroup, safeTrackIdx)
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(override)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
            return
        }

        // Case 3: Only 1 single track available
        val override = TrackSelectionOverride(singleGroup.mediaTrackGroup, 0)
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(override)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
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
