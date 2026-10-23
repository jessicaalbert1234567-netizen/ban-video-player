package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs

enum class AudioTrackChoice(val label: String, val description: String) {
    BANGLA_DUB("স্মার্ট অটো ডাব", "কথার সময় ডাবিং, কথার বাইরে অরিজিনাল অডিও (অটোমেটিক)"),
    ORIGINAL("Original Video", "শুধুমাত্র মূল ভিডিওর অডিও"),
    BANGLA_ONLY("শুধু বাংলা ডাবিং", "শুধুমাত্র অনূদিত বাংলা কণ্ঠ (মূল অডিও মিউট)")
}

enum class SubtitleChoice(val label: String) {
    OFF("Off (বন্ধ)"),
    BANGLA("বাংলা (Bangla)"),
    ENGLISH("English (ইংরেজি)")
}

enum class ResizeModeChoice(val label: String, val mode: Int) {
    FIT("Fit (ফিট)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM_CROP("Full Zoom (ফুউল জুম)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch (টেনে পূর্ণ)", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioChoice: AudioTrackChoice = AudioTrackChoice.BANGLA_DUB,
    val subtitleChoice: SubtitleChoice = SubtitleChoice.BANGLA,
    val resizeMode: ResizeModeChoice = ResizeModeChoice.FIT,
    val playbackSpeed: Float = 1.0f,
    val isLocked: Boolean = false,
    val activeSubtitleText: String? = null,
    val hasDubbedAudio: Boolean = false,
    val hasBanglaSubtitles: Boolean = false,
    val hasEnglishSubtitles: Boolean = false,
    val dubbedAudioFile: File? = null,
    val isSpeechActive: Boolean = false,
    val isLiveDubbing: Boolean = false,
    val isAudioOnlyFallback: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(UnstableApi::class)
class MediaPlayerManager(private val context: Context) {

    private val TAG = "MediaPlayerManager"

    // Primary player: plays video and original video soundtrack natively
    var player: ExoPlayer? = null
        private set

    // Secondary player: plays synchronized dubbed speech audio track
    private var dubbedAudioPlayer: ExoPlayer? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var currentVideoUri: Uri? = null
    private var currentDubbedAudioFile: File? = null
    private var currentSrtFile: File? = null
    private var currentSourceSrtFile: File? = null

    // Parsed subtitles for instant on-screen rendering and dialogue presence detection
    private val bnSubtitleEntries = mutableListOf<ParsedSubtitle>()
    private val enSubtitleEntries = mutableListOf<ParsedSubtitle>()
    private var embeddedCueText: String? = null

    data class ParsedSubtitle(val startMs: Long, val endMs: Long, val text: String)

    private var liveTts: android.speech.tts.TextToSpeech? = null
    private var isLiveTtsReady = false
    private var lastSpokenCueStartMs: Long = -1L

    private var errorRecoveryAttempts = 0
    private var forceSoftwareVideoDecoder = false
    private var zeroFramePlayingChecks = 0

    private fun initLiveTts() {
        if (liveTts == null) {
            liveTts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    val res = liveTts?.setLanguage(java.util.Locale("bn", "BD"))
                    if (res == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || res == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        liveTts?.setLanguage(java.util.Locale("bn", "IN"))
                    }
                    isLiveTtsReady = true
                    Log.i(TAG, "Live Real-Time Bengali TTS Synthesizer initialized successfully")
                }
            }
        }
    }

    private fun createExtractorsFactory(): DefaultExtractorsFactory {
        return DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
    }

    fun initializePlayer(): ExoPlayer {
        if (player == null) {
            val renderersFactory = VlcGradeRenderersFactory(context) { forceSoftwareVideoDecoder }

            val trackSelector = DefaultTrackSelector(context).apply {
                parameters = buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedSampleRateAdaptiveness(true)
                    .setAllowAudioMixedChannelCountAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val mediaSourceFactory = DefaultMediaSourceFactory(context, createExtractorsFactory())

            player = ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) {
                            dubbedAudioPlayer?.play()
                        } else {
                            dubbedAudioPlayer?.pause()
                            liveTts?.stop()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            errorRecoveryAttempts = 0
                        }
                        val isPlaying = player?.isPlaying ?: false
                        val playWhenReady = player?.playWhenReady ?: false
                        val active = isPlaying || (playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING))
                        _playerState.value = _playerState.value.copy(isPlaying = active)
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        val state = player?.playbackState ?: Player.STATE_IDLE
                        val active = (player?.isPlaying ?: false) || (playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE)
                        _playerState.value = _playerState.value.copy(isPlaying = active)
                        if (playWhenReady) {
                            dubbedAudioPlayer?.playWhenReady = true
                            dubbedAudioPlayer?.play()
                        } else {
                            dubbedAudioPlayer?.playWhenReady = false
                            dubbedAudioPlayer?.pause()
                            liveTts?.stop()
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        _playerState.value = _playerState.value.copy(playbackSpeed = playbackParameters.speed)
                        dubbedAudioPlayer?.setPlaybackSpeed(playbackParameters.speed)
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        val cueText = cueGroup.cues
                            .mapNotNull { it.text?.toString()?.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                            .ifBlank { null }
                        embeddedCueText = cueText
                        if (bnSubtitleEntries.isEmpty() && enSubtitleEntries.isEmpty()) {
                            updateCurrentSubtitleText(player?.currentPosition ?: 0L)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} (code=${error.errorCode}) - ${error.message}", error)

                        val isAudioOnlyFile = currentVideoUri?.path?.let { p ->
                            p.endsWith(".wav") || p.endsWith(".m4a") || p.endsWith(".mp3") || p.endsWith(".aac")
                        } ?: false

                        val isVideoCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

                        // 1. If hardware video decoder failed (e.g., 10-bit HEVC Main 10 on 8-bit hardware),
                        // immediately switch to Android software video decoder (c2.android.hevc.decoder)
                        if (!isAudioOnlyFile && isVideoCodecError && !forceSoftwareVideoDecoder) {
                            Log.w(TAG, "Video decoder error detected; switching to software video decoder (10-bit HEVC fallback)")
                            forceSoftwareVideoDecoder = true
                            rebuildPrimaryPlayerWithSoftwareFallback()
                            return
                        }

                        // 2. If media is a video (MKV/MP4), retry with stereo channel cap if audio failed
                        if (!isAudioOnlyFile && player != null && errorRecoveryAttempts < 3) {
                            errorRecoveryAttempts++
                            Log.i(TAG, "Playback retry attempt ($errorRecoveryAttempts/3) with stereo downmix parameters.")
                            try {
                                val savedPos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                player?.trackSelectionParameters = player?.trackSelectionParameters
                                    ?.buildUpon()
                                    ?.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                                    ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    ?.setMaxAudioChannelCount(2)
                                    ?.build() ?: androidx.media3.common.TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
                                player?.prepare()
                                if (savedPos > 0L) player?.seekTo(savedPos)
                                player?.playWhenReady = true
                                player?.play()
                                return
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not recover video playback: ${e.message}")
                            }
                        }

                        // 3. Only for genuine audio-only playback files
                        if (isAudioOnlyFile) {
                            val dubbed = _playerState.value.dubbedAudioFile ?: currentDubbedAudioFile
                            if (dubbed != null && dubbed.exists() && dubbed.length() > 44) {
                                Log.i(TAG, "Audio file fallback to dubbed audio track")
                                _playerState.value = _playerState.value.copy(
                                    isAudioOnlyFallback = true,
                                    hasError = false,
                                    errorMessage = null
                                )
                                val audioItem = MediaItem.fromUri(Uri.fromFile(dubbed))
                                val audioSource = DefaultMediaSourceFactory(context, createExtractorsFactory()).createMediaSource(audioItem)
                                dubbedAudioPlayer?.stop()
                                dubbedAudioPlayer?.release()
                                dubbedAudioPlayer = null
                                player?.setMediaSource(audioSource)
                                player?.prepare()
                                player?.play()
                                return
                            }
                        }

                        _playerState.value = _playerState.value.copy(
                            hasError = false,
                            errorMessage = null,
                            isPlaying = false
                        )
                    }
                })
            }
        }
        return player!!
    }

    private fun rebuildPrimaryPlayerWithSoftwareFallback() {
        val targetUri = currentVideoUri ?: return
        val savedPos = player?.currentPosition?.coerceAtLeast(0L) ?: _playerState.value.currentPositionMs
        val savedSpeed = _playerState.value.playbackSpeed

        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null

        val exo = initializePlayer()
        val mediaSourceFactory = DefaultMediaSourceFactory(context, createExtractorsFactory())
        val videoItem = MediaItem.fromUri(targetUri)
        val videoSource = mediaSourceFactory.createMediaSource(videoItem)
        exo.setMediaSource(videoSource)
        exo.setPlaybackSpeed(savedSpeed)
        exo.prepare()
        if (savedPos > 0L) {
            exo.seekTo(savedPos)
            dubbedAudioPlayer?.seekTo(savedPos)
        }
        exo.playWhenReady = true
        exo.play()
        updateAudioVolumes(savedPos)
    }

    private fun initializeDubbedPlayer(): ExoPlayer {
        if (dubbedAudioPlayer == null) {
            val renderersFactory = VlcGradeRenderersFactory(context)
            val mediaSourceFactory = DefaultMediaSourceFactory(context, createExtractorsFactory())
            dubbedAudioPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = player?.isPlaying ?: true
            }
        }
        return dubbedAudioPlayer!!
    }

    fun setupMedia(
        videoUri: Uri,
        dubbedAudioFile: File?,
        srtFile: File?,
        sourceSrtFile: File? = null
    ) {
        loadSubtitleFiles(srtFile, sourceSrtFile)

        val effectiveDubbedFile = when {
            dubbedAudioFile != null && dubbedAudioFile.exists() && dubbedAudioFile.length() > 44 -> dubbedAudioFile
            dubbedAudioFile != null -> {
                val parent = dubbedAudioFile.parentFile
                val wavFile = if (parent != null) File(parent, "dubbed_bn.wav") else null
                if (wavFile != null && wavFile.exists() && wavFile.length() > 44) wavFile else null
            }
            else -> null
        }

        if (currentVideoUri == videoUri &&
            currentDubbedAudioFile?.absolutePath == effectiveDubbedFile?.absolutePath &&
            player != null &&
            (player?.mediaItemCount ?: 0) > 0
        ) {
            Log.d(TAG, "setupMedia: Video and audio already initialized for $videoUri, ensuring active playback state")
            currentSrtFile = srtFile
            currentSourceSrtFile = sourceSrtFile
            val hasPreRendered = effectiveDubbedFile != null
            val canLive = !hasPreRendered && bnSubtitleEntries.isNotEmpty()
            _playerState.value = _playerState.value.copy(
                hasDubbedAudio = hasPreRendered || canLive,
                isLiveDubbing = canLive,
                hasBanglaSubtitles = bnSubtitleEntries.isNotEmpty(),
                hasEnglishSubtitles = enSubtitleEntries.isNotEmpty(),
                dubbedAudioFile = effectiveDubbedFile
            )
            player?.let { exo ->
                if (exo.playbackState == Player.STATE_IDLE) {
                    exo.prepare()
                } else if (exo.playbackState == Player.STATE_ENDED) {
                    exo.seekTo(0)
                }
                exo.playWhenReady = true
                exo.play()
                dubbedAudioPlayer?.let { dp ->
                    if (dp.playbackState == Player.STATE_IDLE) dp.prepare()
                    dp.seekTo(exo.currentPosition)
                    dp.playWhenReady = true
                    dp.play()
                }
                updateAudioVolumes(exo.currentPosition)
            }
            return
        }

        currentVideoUri = videoUri
        currentDubbedAudioFile = effectiveDubbedFile
        currentSrtFile = srtFile
        currentSourceSrtFile = sourceSrtFile
        errorRecoveryAttempts = 0
        zeroFramePlayingChecks = 0

        val exo = initializePlayer()
        val mediaSourceFactory = DefaultMediaSourceFactory(context, createExtractorsFactory())

        val isVideoUriAudioFile = videoUri.path?.let { p ->
            p.endsWith(".wav") || p.endsWith(".m4a") || p.endsWith(".mp3") || p.endsWith(".aac")
        } ?: false
        val isVideoUriSameAsDubbed = effectiveDubbedFile != null && videoUri.toString() == Uri.fromFile(effectiveDubbedFile).toString()
        val isAudioOnly = isVideoUriAudioFile || isVideoUriSameAsDubbed

        val hasPreRenderedDubbed = effectiveDubbedFile != null
        val canLiveDub = !hasPreRenderedDubbed && bnSubtitleEntries.isNotEmpty()
        val hasDubbed = hasPreRenderedDubbed || canLiveDub

        val initialAudioChoice = if (hasDubbed) AudioTrackChoice.BANGLA_DUB else AudioTrackChoice.ORIGINAL
        val initialSubChoice = when {
            bnSubtitleEntries.isNotEmpty() -> SubtitleChoice.BANGLA
            enSubtitleEntries.isNotEmpty() -> SubtitleChoice.ENGLISH
            else -> SubtitleChoice.ENGLISH // Allow embedded MKV subtitles when playing directly
        }

        // Set state including dubbedAudioFile BEFORE preparing video source
        _playerState.value = _playerState.value.copy(
            hasDubbedAudio = hasDubbed,
            isLiveDubbing = canLiveDub,
            isAudioOnlyFallback = isAudioOnly,
            hasError = false,
            errorMessage = null,
            audioChoice = initialAudioChoice,
            subtitleChoice = initialSubChoice,
            hasBanglaSubtitles = bnSubtitleEntries.isNotEmpty(),
            hasEnglishSubtitles = enSubtitleEntries.isNotEmpty(),
            dubbedAudioFile = effectiveDubbedFile
        )

        // 1. Setup Video with Original Audio
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        val videoItem = MediaItem.fromUri(videoUri)
        val videoSource = mediaSourceFactory.createMediaSource(videoItem)
        exo.setMediaSource(videoSource)
        exo.prepare()
        exo.playWhenReady = true
        exo.play()

        if (hasPreRenderedDubbed && !isVideoUriSameAsDubbed) {
            val dPlayer = initializeDubbedPlayer()
            val audioItem = MediaItem.fromUri(Uri.fromFile(effectiveDubbedFile!!))
            val audioSource = mediaSourceFactory.createMediaSource(audioItem)
            dPlayer.setMediaSource(audioSource)
            dPlayer.prepare()
            dPlayer.playWhenReady = true
            dPlayer.seekTo(exo.currentPosition.coerceAtLeast(0L))
            dPlayer.play()
            Log.i(TAG, "Initialized secondary dubbed player for real-time dynamic track switching: ${effectiveDubbedFile.absolutePath}")
        } else {
            dubbedAudioPlayer?.stop()
            dubbedAudioPlayer?.release()
            dubbedAudioPlayer = null
        }

        if (canLiveDub) {
            initLiveTts()
            Log.i(TAG, "Initialized Live Real-time TTS Dubbing (0-second wait mode)")
        }

        updateAudioVolumes(exo.currentPosition.coerceAtLeast(0L))
    }

    fun setAudioChoice(choice: AudioTrackChoice) {
        _playerState.value = _playerState.value.copy(audioChoice = choice)
        player?.let { exo ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
            updateAudioVolumes(exo.currentPosition.coerceAtLeast(0L))
        }
    }

    /**
     * Dynamic Alternative Audio Track System:
     * When dialogue is active -> Dubbed Bangla speech plays, original video audio is ducked (or muted).
     * When dialogue is NOT active -> Original video audio (cars, storms, engines, footsteps, music) plays at 100% volume!
     */
    private fun updateAudioVolumes(currentPosMs: Long) {
        val exo = player ?: return
        val dPlayer = dubbedAudioPlayer

        val choice = _playerState.value.audioChoice
        val hasDubbed = _playerState.value.hasDubbedAudio
        val isLive = _playerState.value.isLiveDubbing

        if (!hasDubbed || _playerState.value.isAudioOnlyFallback) {
            exo.volume = 1.0f
            return
        }

        val isSpeaking = isDialogueActive(currentPosMs)
        if (_playerState.value.isSpeechActive != isSpeaking) {
            _playerState.value = _playerState.value.copy(isSpeechActive = isSpeaking)
        }

        when (choice) {
            AudioTrackChoice.BANGLA_DUB -> {
                if (isSpeaking) {
                    // Smart Ducking: Original ambient audio at 0.32f keeps cars, storms, music,
                    // footsteps, and explosions clearly audible behind the crisp 1.0f Bangla speech!
                    exo.volume = 0.32f
                    if (isLive) {
                        val activeCue = bnSubtitleEntries.firstOrNull { currentPosMs in it.startMs..it.endMs }
                        if (activeCue != null && activeCue.startMs != lastSpokenCueStartMs) {
                            lastSpokenCueStartMs = activeCue.startMs
                            if (isLiveTtsReady && _playerState.value.isPlaying) {
                                val params = android.os.Bundle()
                                params.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                                liveTts?.speak(activeCue.text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "cue_${activeCue.startMs}")
                            }
                        }
                    } else {
                        dPlayer?.volume = 1.0f
                    }
                } else {
                    // Ambient / music scene between dialogues: original video sound at full 100% volume!
                    exo.volume = if (bnSubtitleEntries.isEmpty()) 0.35f else 1.0f
                    if (!isLive) {
                        dPlayer?.volume = 1.0f
                    }
                }
            }
            AudioTrackChoice.ORIGINAL -> {
                exo.volume = 1.0f
                dPlayer?.volume = 0.0f
                if (isLive) liveTts?.stop()
            }
            AudioTrackChoice.BANGLA_ONLY -> {
                exo.volume = 0.0f
                if (isLive) {
                    val activeCue = bnSubtitleEntries.firstOrNull { currentPosMs in it.startMs..it.endMs }
                    if (activeCue != null && activeCue.startMs != lastSpokenCueStartMs) {
                        lastSpokenCueStartMs = activeCue.startMs
                        if (isLiveTtsReady && _playerState.value.isPlaying) {
                            val params = android.os.Bundle()
                            params.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                            liveTts?.speak(activeCue.text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "cue_${activeCue.startMs}")
                        }
                    }
                } else {
                    dPlayer?.volume = 1.0f
                }
            }
        }
    }

    private fun isDialogueActive(posMs: Long): Boolean {
        if (bnSubtitleEntries.isEmpty()) return true
        // Include small 40ms lead-in/lead-out for natural speech pacing
        return bnSubtitleEntries.any { posMs in (it.startMs - 40L)..(it.endMs + 80L) }
    }

    fun setSubtitleChoice(choice: SubtitleChoice) {
        _playerState.value = _playerState.value.copy(subtitleChoice = choice)
        updateCurrentSubtitleText(_playerState.value.currentPositionMs)
    }

    fun setResizeMode(mode: ResizeModeChoice) {
        _playerState.value = _playerState.value.copy(resizeMode = mode)
    }

    fun cycleResizeMode(): ResizeModeChoice {
        val modes = ResizeModeChoice.values()
        val currentIdx = modes.indexOf(_playerState.value.resizeMode)
        val nextMode = modes[(currentIdx + 1) % modes.size]
        _playerState.value = _playerState.value.copy(resizeMode = nextMode)
        return nextMode
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        dubbedAudioPlayer?.setPlaybackSpeed(speed)
        _playerState.value = _playerState.value.copy(playbackSpeed = speed)
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val current = exo.currentPosition
        val target = (current + deltaMs).coerceIn(0L, exo.duration.coerceAtLeast(0L))
        seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        liveTts?.stop()
        lastSpokenCueStartMs = -1L
        player?.seekTo(positionMs)
        dubbedAudioPlayer?.seekTo(positionMs)
        updateAudioVolumes(positionMs)
    }

    fun togglePlayPause() {
        val exo = player ?: initializePlayer()
        val isCurrentlyPlaying = exo.isPlaying || (exo.playWhenReady && (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING))
        val shouldPlay = !isCurrentlyPlaying

        if (shouldPlay) {
            if (exo.playbackState == Player.STATE_IDLE) {
                exo.prepare()
            } else if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0)
            }
            exo.playWhenReady = true
            exo.play()
            dubbedAudioPlayer?.let { dp ->
                if (dp.playbackState == Player.STATE_IDLE) dp.prepare()
                dp.seekTo(exo.currentPosition.coerceAtLeast(0L))
                dp.playWhenReady = true
                dp.play()
            }
            _playerState.value = _playerState.value.copy(isPlaying = true)
        } else {
            exo.playWhenReady = false
            exo.pause()
            dubbedAudioPlayer?.playWhenReady = false
            dubbedAudioPlayer?.pause()
            liveTts?.stop()
            _playerState.value = _playerState.value.copy(isPlaying = false)
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

        // Detect silent hardware black-screen on 10-bit HEVC (Main 10) where audio/clock advances
        // but 8-bit hardware decoder renders 0 video frames
        if (exo.isPlaying && !forceSoftwareVideoDecoder && !_playerState.value.isAudioOnlyFallback) {
            val hasSelectedVideoTrack = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val renderedFrames = exo.videoDecoderCounters?.renderedOutputBufferCount ?: -1
            if (hasSelectedVideoTrack && renderedFrames == 0 && pos > 1500L) {
                zeroFramePlayingChecks++
                if (zeroFramePlayingChecks >= 10) {
                    Log.w(TAG, "Detected 0 rendered video frames after ${pos}ms; switching to software video decoder for 10-bit HEVC")
                    zeroFramePlayingChecks = 0
                    forceSoftwareVideoDecoder = true
                    rebuildPrimaryPlayerWithSoftwareFallback()
                    return
                }
            } else if (renderedFrames > 0) {
                zeroFramePlayingChecks = 0
            }
        }

        // Resync secondary player if clock drift exceeds threshold
        val dPlayer = dubbedAudioPlayer
        if (dPlayer != null && exo.isPlaying) {
            if (!dPlayer.isPlaying && dPlayer.playbackState == Player.STATE_READY) {
                dPlayer.play()
            }
            val drift = abs(pos - dPlayer.currentPosition)
            if (drift > 120L) {
                dPlayer.seekTo(pos)
            }
        }

        updateCurrentSubtitleText(pos)
        updateAudioVolumes(pos)
    }

    private fun updateCurrentSubtitleText(currentPositionMs: Long) {
        val choice = _playerState.value.subtitleChoice
        if (choice == SubtitleChoice.OFF) {
            if (_playerState.value.activeSubtitleText != null) {
                _playerState.value = _playerState.value.copy(activeSubtitleText = null)
            }
            return
        }

        val entries = when (choice) {
            SubtitleChoice.BANGLA -> bnSubtitleEntries
            SubtitleChoice.ENGLISH -> enSubtitleEntries
            SubtitleChoice.OFF -> emptyList()
        }

        if (entries.isEmpty()) {
            // Fall back to embedded MKV subtitle cues if external SRT list is empty
            val fallbackText = embeddedCueText
            if (_playerState.value.activeSubtitleText != fallbackText) {
                _playerState.value = _playerState.value.copy(activeSubtitleText = fallbackText)
            }
            return
        }

        val active = entries.firstOrNull { currentPositionMs in it.startMs..it.endMs }
        val text = active?.text
        if (_playerState.value.activeSubtitleText != text) {
            _playerState.value = _playerState.value.copy(activeSubtitleText = text)
        }
    }

    private fun loadSubtitleFiles(bnSrtFile: File?, enSrtFile: File?) {
        bnSubtitleEntries.clear()
        enSubtitleEntries.clear()

        bnSrtFile?.let { parseSrtIntoList(it, bnSubtitleEntries) }
        enSrtFile?.let { parseSrtIntoList(it, enSubtitleEntries) }
    }

    private fun parseSrtIntoList(srtFile: File, targetList: MutableList<ParsedSubtitle>) {
        if (!srtFile.exists() || srtFile.length() == 0L) return
        try {
            val content = srtFile.readText(Charsets.UTF_8)
            val cues = com.example.subtitle.SubtitleExtractor.parseSubtitleContent(content)
            for (cue in cues) {
                if (cue.text.isNotBlank()) {
                    targetList.add(ParsedSubtitle(cue.startMs, cue.endMs, cue.text.replace("\n", " ").trim()))
                }
            }
            Log.d(TAG, "Loaded ${targetList.size} subtitle entries from ${srtFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing SRT file: ${srtFile.name}", e)
        }
    }

    fun release() {
        player?.release()
        player = null
        dubbedAudioPlayer?.release()
        dubbedAudioPlayer = null
        try {
            liveTts?.stop()
            liveTts?.shutdown()
            liveTts = null
        } catch (_: Exception) {}
    }
}
