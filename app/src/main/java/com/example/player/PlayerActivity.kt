package com.example.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ads.AdMobManager
import com.example.subtitle.SubtitleCue
import com.example.subtitle.SubtitleExtractor
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class PlayerAudioMode(val label: String, val description: String) {
    SMART_DUB("স্মার্ট অটো ডাব (পারিপার্শ্বিক অডিও সহ)", "বাংলা ডাবিং এবং ভিডিওর পেছনের ব্যাকগ্রাউন্ড সাউন্ড ও মিউজিক একসাথে চলবে"),
    ORIGINAL_ONLY("শুধুমাত্র মূল ভিডিওর অডিও", "মূল ভিডিওর অরিজিনাল সাউন্ডট্র্যাক ১০০% ভলিউমে"),
    BANGLA_ONLY("শুধুমাত্র বাংলা ডাবিং", "শুধুমাত্র অনূদিত বাংলা কণ্ঠ (মূল ভিডিও মিউট)")
}

/**
 * Production-ready VLC-grade Offline Video Player Activity using AndroidX Media3 ExoPlayer.
 *
 * Features & Architecture:
 * 1. Robust Software/Hardware Decoding: VlcGradeRenderersFactory with EXTENSION_RENDERER_MODE_PREFER
 *    and automatic software decoder fallback for 10-bit HEVC (Main 10, yuv420p10le), Hi10P, and legacy codecs.
 * 2. Multichannel Audio Downmixing: Automatic 5.1/7.1 HE-AAC/AAC/AC3 & float PCM to 16-bit Stereo via
 *    UniversalStereoAudioProcessor and decoder channel hints.
 * 3. Dual-Player Smart Audio Ducking: Simultaneous playback of video ambient soundtrack and Bangla speech,
 *    ducking original background audio to 32% during speech so ambient cars, music, footsteps, and explosions
 *    remain audible behind clear Bangla dubbing.
 * 4. Multi-track Audio & Embedded/External Subtitle Management: DefaultTrackSelector + DefaultMediaSourceFactory.
 * 5. Local Storage / SAF: Handles content:// and file:// local URIs seamlessly with persistable permissions.
 * 6. VLC Gestures: Brightness (left swipe), Volume (right swipe), Scrub (horizontal swipe), Double tap seek.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DUBBED_AUDIO_PATH = "extra_dubbed_audio_path"
        const val EXTRA_SRT_PATH = "extra_srt_path"

        private const val HIDE_CONTROLS_DELAY_MS = 4000L
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        fun createIntent(
            context: Context,
            videoUri: Uri,
            title: String? = null,
            dubbedAudioPath: String? = null,
            srtPath: String? = null
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                data = videoUri
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DUBBED_AUDIO_PATH, dubbedAudioPath)
                putExtra(EXTRA_SRT_PATH, srtPath)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }

    // Media3 ExoPlayer (Primary: Video + Original Multichannel Audio)
    private var player: ExoPlayer? = null
    // Media3 ExoPlayer (Secondary: Dubbed Bangla Speech Track for Real-time Smart Ducking)
    private var dubbedPlayer: ExoPlayer? = null

    private var trackSelector: DefaultTrackSelector? = null
    private var videoUri: Uri? = null
    private var videoTitle: String? = null
    private var dubbedAudioPath: String? = null
    private var srtPath: String? = null
    private var launchedWithExplicitDub: Boolean = false

    private var currentAudioMode = PlayerAudioMode.SMART_DUB
    private val bnSubtitleCues = mutableListOf<SubtitleCue>()

    // Playback state restoration across lifecycles
    private var startPositionMs: Long = 0L
    private var startPlayWhenReady: Boolean = true
    private var isAdShownOrSkipped: Boolean = false
    private var isWaitingForAd: Boolean = false

    // Decoder recovery state
    private var forceSoftwareVideoDecoder: Boolean = false
    private var errorRecoveryAttempts: Int = 0
    private var zeroFrameChecks: Int = 0

    // UI View References
    private lateinit var playerRoot: View
    private lateinit var playerView: PlayerView
    private lateinit var controlsContainer: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var centerControls: View
    private lateinit var progressBuffering: ProgressBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnReplay10: ImageButton
    private lateinit var btnForward10: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnRotateBottom: ImageButton
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnAudioTracks: ImageButton
    private lateinit var btnSpeed: Button
    private lateinit var btnLock: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtTimeCurrent: TextView
    private lateinit var txtTimeDuration: TextView
    private lateinit var txtVideoTitle: TextView

    // VLC-style HUD overlay
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudIcon: ImageView
    private lateinit var hudText: TextView
    private lateinit var hudProgress: ProgressBar

    // Gesture & Audio Helpers
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsLocked = false
    private var currentSpeedIndex = 2 // 1.0x
    private var currentResizeModeIndex = 0 // FIT

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeModeLabels = arrayOf("Fit (ফিট)", "Stretch (টেনে পূর্ণ)", "Crop / Zoom (জুম)")

    // Timeline update runnable
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateTimeline()
            handler.postDelayed(this, 500L)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        parseIntentData(intent)
        initViews()
        setupGestureDetector()
        setupListeners()
        setupImmersiveMode()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isControlsLocked) {
                    toggleLock()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        releasePlayer()
        forceSoftwareVideoDecoder = false
        errorRecoveryAttempts = 0
        parseIntentData(intent)
        txtVideoTitle.text = videoTitle
        initializePlayer()
    }

    private fun parseIntentData(intent: Intent?) {
        if (intent == null) return
        val rawUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        val dataUri = intent.data

        videoUri = when {
            dataUri != null -> dataUri
            rawUriStr != null -> try { Uri.parse(rawUriStr) } catch (_: Exception) { null }
            else -> null
        }

        videoUri?.let { uri ->
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
        }

        val resolvedDisplayName = videoUri?.let { queryDisplayName(it) }
        videoTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: resolvedDisplayName
            ?: videoUri?.lastPathSegment
            ?: "Offline Video"

        dubbedAudioPath = intent.getStringExtra(EXTRA_DUBBED_AUDIO_PATH)
        srtPath = intent.getStringExtra(EXTRA_SRT_PATH)
        launchedWithExplicitDub = !dubbedAudioPath.isNullOrBlank()

        val titleForLookup = resolvedDisplayName ?: videoTitle ?: ""
        if (titleForLookup.isNotBlank() && (dubbedAudioPath.isNullOrBlank() || srtPath.isNullOrBlank())) {
            val baseTitle = titleForLookup.substringBeforeLast(".")
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val fileTranslatoDir = File(publicDownloads, "FileTranslato")
            val banglaDubbingDir = File(publicDownloads, "BanglaDubbing")

            if (dubbedAudioPath.isNullOrBlank()) {
                val candidateAudio = File(fileTranslatoDir, "${baseTitle}_bangla_dubbed_audio.wav")
                if (candidateAudio.exists() && candidateAudio.length() > 44) {
                    dubbedAudioPath = candidateAudio.absolutePath
                }
            }
            if (srtPath.isNullOrBlank()) {
                val candidateSrt1 = File(fileTranslatoDir, "${baseTitle}_bangla.srt")
                val candidateSrt2 = File(banglaDubbingDir, "${baseTitle}_bangla.srt")
                if (candidateSrt1.exists() && candidateSrt1.length() > 0) {
                    srtPath = candidateSrt1.absolutePath
                } else if (candidateSrt2.exists() && candidateSrt2.length() > 0) {
                    srtPath = candidateSrt2.absolutePath
                }
            }
        }

        bnSubtitleCues.clear()
        srtPath?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                try {
                    bnSubtitleCues.addAll(SubtitleExtractor.parseSubtitleContent(f.readText(Charsets.UTF_8)))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse SRT for smart ducking: ${e.message}")
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).name }
        }
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun initViews() {
        playerRoot = findViewById(R.id.player_root)
        playerView = findViewById(R.id.player_view)
        controlsContainer = findViewById(R.id.controls_container)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        centerControls = findViewById(R.id.center_controls)
        progressBuffering = findViewById(R.id.progress_buffering)

        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnReplay10 = findViewById(R.id.btn_replay_10)
        btnForward10 = findViewById(R.id.btn_forward_10)
        btnBack = findViewById(R.id.btn_back)
        btnAspectRatio = findViewById(R.id.btn_aspect_ratio)
        btnRotate = findViewById(R.id.btn_rotate)
        btnRotateBottom = findViewById(R.id.btn_rotate_bottom)
        btnSubtitles = findViewById(R.id.btn_subtitles)
        btnAudioTracks = findViewById(R.id.btn_audio_tracks)
        btnSpeed = findViewById(R.id.btn_speed)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlock = findViewById(R.id.btn_unlock)

        seekBar = findViewById(R.id.seek_bar)
        txtTimeCurrent = findViewById(R.id.txt_time_current)
        txtTimeDuration = findViewById(R.id.txt_time_duration)
        txtVideoTitle = findViewById(R.id.txt_video_title)

        hudContainer = findViewById(R.id.hud_container)
        hudIcon = findViewById(R.id.hud_icon)
        hudText = findViewById(R.id.hud_text)
        hudProgress = findViewById(R.id.hud_progress)

        txtVideoTitle.text = videoTitle
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            resetControlsHideTimer()
        }

        btnReplay10.setOnClickListener {
            player?.let { p ->
                val newPos = maxOf(0L, p.currentPosition - 10_000L)
                p.seekTo(newPos)
                dubbedPlayer?.seekTo(newPos)
                showSeekHud(newPos, -10_000L)
            }
            resetControlsHideTimer()
        }

        btnForward10.setOnClickListener {
            player?.let { p ->
                val dur = p.duration.coerceAtLeast(0L)
                val newPos = if (dur > 0) minOf(dur, p.currentPosition + 10_000L) else p.currentPosition + 10_000L
                p.seekTo(newPos)
                dubbedPlayer?.seekTo(newPos)
                showSeekHud(newPos, 10_000L)
            }
            resetControlsHideTimer()
        }

        btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
            resetControlsHideTimer()
        }

        btnRotate.setOnClickListener {
            toggleScreenRotation()
        }

        btnRotateBottom.setOnClickListener {
            toggleScreenRotation()
        }

        btnAudioTracks.setOnClickListener {
            showAudioTrackSelectionDialog()
            resetControlsHideTimer()
        }

        btnSubtitles.setOnClickListener {
            showSubtitleTrackSelectionDialog()
            resetControlsHideTimer()
        }

        btnSpeed.setOnClickListener {
            cyclePlaybackSpeed()
            resetControlsHideTimer()
        }

        btnLock.setOnClickListener { toggleLock() }
        btnUnlock.setOnClickListener { toggleLock() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var isUserSeeking = false

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) {
                        val seekToMs = (duration * progress) / 1000L
                        txtTimeCurrent.text = formatTime(seekToMs)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                player?.let { p ->
                    val duration = p.duration
                    if (duration > 0 && sb != null) {
                        val seekToMs = (duration * sb.progress) / 1000L
                        p.seekTo(seekToMs)
                        dubbedPlayer?.seekTo(seekToMs)
                    }
                }
                resetControlsHideTimer()
            }
        })
    }

    private fun createExtractorsFactory(): DefaultExtractorsFactory {
        return DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
    }

    private fun initializePlayer() {
        if (player != null) return
        val targetUri = videoUri ?: run {
            Toast.makeText(this, "ভিডিও ফাইল খুঁজে পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val renderersFactory = VlcGradeRenderersFactory(this) { forceSoftwareVideoDecoder }

            trackSelector = DefaultTrackSelector(this).apply {
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

            val dataSourceFactory = DefaultDataSource.Factory(this)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, createExtractorsFactory())

            player = ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = startPlayWhenReady
                    addListener(PlayerEventListener())
                }

            playerView.player = player
            playerView.resizeMode = resizeModes[currentResizeModeIndex]

            val mediaItemBuilder = MediaItem.Builder().setUri(targetUri)

            srtPath?.let { sPath ->
                val srtFile = File(sPath)
                if (srtFile.exists() && srtFile.length() > 0) {
                    val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srtFile))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("ben")
                        .setLabel("অনূদিত বাংলা সাবটাইটেল (Bangla Dub)")
                        .setSelectionFlags(if (launchedWithExplicitDub) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
                }
            }

            val mainMediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
            player?.setMediaSource(mainMediaSource)
            player?.prepare()

            val dubbedFile = dubbedAudioPath?.let { File(it) }
            if (dubbedFile != null && dubbedFile.exists() && dubbedFile.length() > 44) {
                val dubbedRenderersFactory = VlcGradeRenderersFactory(this)
                dubbedPlayer = ExoPlayer.Builder(this, dubbedRenderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build().apply {
                        val audioItem = MediaItem.fromUri(Uri.fromFile(dubbedFile))
                        val audioSource = mediaSourceFactory.createMediaSource(audioItem)
                        setMediaSource(audioSource)
                        prepare()
                        playWhenReady = startPlayWhenReady
                        seekTo(startPositionMs)
                    }
                currentAudioMode = if (launchedWithExplicitDub) PlayerAudioMode.SMART_DUB else PlayerAudioMode.ORIGINAL_ONLY
                Log.i(TAG, "Dubbed audio player initialized (mode=$currentAudioMode): ${dubbedFile.absolutePath}")
            } else {
                dubbedPlayer = null
                currentAudioMode = PlayerAudioMode.ORIGINAL_ONLY
            }

            applyAudioModeVolumes(startPositionMs)

            if (startPositionMs > 0) {
                player?.seekTo(startPositionMs)
                dubbedPlayer?.seekTo(startPositionMs)
            }

            if (!isAdShownOrSkipped) {
                isAdShownOrSkipped = true
                isWaitingForAd = true
                player?.pause()
                dubbedPlayer?.pause()
                btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                AdMobManager.showRewardedAd(this) {
                    isWaitingForAd = false
                    startPlayWhenReady = true
                    if (player == null) {
                        initializePlayer()
                    }
                    player?.playWhenReady = true
                    player?.play()
                    dubbedPlayer?.playWhenReady = true
                    dubbedPlayer?.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }

            handler.removeCallbacks(updateProgressRunnable)
            handler.post(updateProgressRunnable)
            resetControlsHideTimer()

            Log.i(TAG, "ExoPlayer initialized with VLC-grade 10-bit HEVC decoding, stereo downmixing, and Smart Dubbing")
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer initialization failed: ${e.message}", e)
            Toast.makeText(this, "ভিডিও প্লেয়ার শুরু করতে সমস্যা হয়েছে: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun rebuildPlayerWithSoftwareVideoFallback() {
        val currentPos = player?.currentPosition?.coerceAtLeast(0L) ?: startPositionMs
        startPositionMs = currentPos
        startPlayWhenReady = true
        releasePlayer()
        initializePlayer()
    }

    private fun releasePlayer() {
        player?.let { p ->
            startPositionMs = p.currentPosition.coerceAtLeast(0L)
            startPlayWhenReady = if (isWaitingForAd) true else p.playWhenReady
            p.stop()
            p.release()
        }
        player = null
        trackSelector = null
        playerView.player = null

        dubbedPlayer?.let { dp ->
            dp.stop()
            dp.release()
        }
        dubbedPlayer = null

        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 24) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        if (Build.VERSION.SDK_INT < 24 || player == null) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < 24) {
            releasePlayer()
        } else {
            player?.pause()
            dubbedPlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= 24) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    progressBuffering.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    progressBuffering.visibility = View.GONE
                    errorRecoveryAttempts = 0
                    updateTimeline()
                }
                Player.STATE_ENDED -> {
                    progressBuffering.visibility = View.GONE
                    btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                    showControls()
                }
                Player.STATE_IDLE -> {
                    progressBuffering.visibility = View.GONE
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            if (isPlaying) {
                dubbedPlayer?.play()
                resetControlsHideTimer()
            } else {
                dubbedPlayer?.pause()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            progressBuffering.visibility = View.GONE
            Log.e(TAG, "Player playback error: [${error.errorCodeName}] (code=${error.errorCode}) ${error.message}", error)

            val isVideoCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

            if (isVideoCodecError && !forceSoftwareVideoDecoder) {
                Log.w(TAG, "Video decoder error; switching to software video decoder for 10-bit HEVC compatibility")
                forceSoftwareVideoDecoder = true
                rebuildPlayerWithSoftwareVideoFallback()
                return
            }

            if (errorRecoveryAttempts < 3) {
                errorRecoveryAttempts++
                Log.w(TAG, "Attempting playback recovery ($errorRecoveryAttempts/3) with 2-channel stereo audio constraint")
                try {
                    val savedPos = player?.currentPosition?.coerceAtLeast(0L) ?: startPositionMs
                    trackSelector?.let { ts ->
                        ts.setParameters(
                            ts.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setMaxAudioChannelCount(2)
                        )
                    }
                    player?.prepare()
                    if (savedPos > 0L) player?.seekTo(savedPos)
                    player?.playWhenReady = true
                    player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to recover playback: ${e.message}")
                }
            }
        }
    }

    private data class AudioOptionItem(
        val title: String,
        val subtitle: String,
        val mode: PlayerAudioMode? = null,
        val trackOverride: TrackSelectionOverride? = null
    )

    private fun showAudioTrackSelectionDialog() {
        val p = player ?: return
        val ts = trackSelector ?: return
        val currentTracks = p.currentTracks

        val audioOptions = mutableListOf<AudioOptionItem>()
        var selectedItemIndex = 0

        if (dubbedPlayer != null) {
            for (mode in PlayerAudioMode.values()) {
                if (currentAudioMode == mode) {
                    selectedItemIndex = audioOptions.size
                }
                audioOptions.add(AudioOptionItem(mode.label, mode.description, mode = mode))
            }
        }

        var internalTrackNum = 1
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val lang = format.language ?: "und"
                    val channels = format.channelCount
                    val chLabel = when (channels) {
                        6 -> "5.1"
                        8 -> "7.1"
                        2 -> "Stereo"
                        1 -> "Mono"
                        else -> "${channels}-ch"
                    }
                    val mime = format.sampleMimeType?.substringAfterLast("/")?.uppercase(Locale.ROOT) ?: "AUDIO"
                    val langName = when (lang.lowercase(Locale.ROOT)) {
                        "ben", "bn" -> "বাংলা (Bangla)"
                        "hin", "hi" -> "Hindi (হিন্দি)"
                        "mal", "ml" -> "Malayalam (মালায়ালাম)"
                        "tam", "ta" -> "Tamil (তামিল)"
                        "tel", "te" -> "Telugu (তেলেগু)"
                        "eng", "en" -> "English (ইংরেজি)"
                        else -> lang.uppercase(Locale.ROOT)
                    }
                    val label = if (!format.label.isNullOrBlank()) {
                        "${format.label} [$langName • $chLabel $mime]"
                    } else {
                        "মূল অডিও #$internalTrackNum: $langName ($chLabel $mime)"
                    }
                    internalTrackNum++

                    if (dubbedPlayer == null && group.isTrackSelected(i)) {
                        selectedItemIndex = audioOptions.size
                    }
                    audioOptions.add(
                        AudioOptionItem(
                            title = label,
                            subtitle = "ভিডিওর অভ্যন্তরীণ অডিও ট্র্যাক",
                            trackOverride = TrackSelectionOverride(mediaTrackGroup, i)
                        )
                    )
                }
            }
        }

        if (audioOptions.isEmpty()) {
            Toast.makeText(this, "এই ভিডিওতে বিকল্প অডিও ট্র্যাক পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        val items = audioOptions.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("অডিও ট্র্যাক নির্বাচন করুন (Smart Audio & Multi-Track)")
            .setSingleChoiceItems(items, selectedItemIndex) { dialog, which ->
                val chosen = audioOptions[which]
                if (chosen.mode != null) {
                    setAudioMode(chosen.mode)
                } else if (chosen.trackOverride != null) {
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(chosen.trackOverride)
                    )
                    if (dubbedPlayer != null && currentAudioMode == PlayerAudioMode.BANGLA_ONLY) {
                        setAudioMode(PlayerAudioMode.SMART_DUB)
                    } else {
                        applyAudioModeVolumes(p.currentPosition.coerceAtLeast(0L))
                    }
                }
                Toast.makeText(this, "নির্বাচিত অডিও: ${chosen.title}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun setAudioMode(mode: PlayerAudioMode) {
        currentAudioMode = mode
        applyAudioModeVolumes(player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
    }

    private fun applyAudioModeVolumes(currentPosMs: Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L) {
        val p = player ?: return
        val dp = dubbedPlayer
        if (dp == null) {
            p.volume = 1.0f
            return
        }

        when (currentAudioMode) {
            PlayerAudioMode.SMART_DUB -> {
                val isSpeaking = if (bnSubtitleCues.isEmpty()) {
                    true
                } else {
                    bnSubtitleCues.any { currentPosMs in (it.startMs - 40L)..(it.endMs + 80L) }
                }
                p.volume = if (isSpeaking) 0.32f else 1.0f
                dp.volume = 1.0f
            }
            PlayerAudioMode.ORIGINAL_ONLY -> {
                p.volume = 1.0f
                dp.volume = 0.0f
            }
            PlayerAudioMode.BANGLA_ONLY -> {
                p.volume = 0.0f
                dp.volume = 1.0f
            }
        }
    }

    private fun showSubtitleTrackSelectionDialog() {
        val p = player ?: return
        val ts = trackSelector ?: return
        val currentTracks = p.currentTracks

        val subtitleOptions = mutableListOf<Pair<String, TrackSelectionOverride?>>()
        subtitleOptions.add(Pair("সাবটাইটেল বন্ধ (Off)", null))
        var selectedIndex = 0

        val isTextDisabled = ts.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val lang = format.language ?: "und"
                    val label = when {
                        !format.label.isNullOrBlank() -> format.label!!
                        lang == "ben" || lang == "bn" -> "বাংলা সাবটাইটেল (Bangla)"
                        lang == "eng" || lang == "en" -> "English Subtitles"
                        else -> "সাবটাইটেল [${lang.uppercase(Locale.ROOT)}]"
                    }
                    val isSelected = !isTextDisabled && group.isTrackSelected(i)
                    if (isSelected) {
                        selectedIndex = subtitleOptions.size
                    }
                    subtitleOptions.add(Pair(label, TrackSelectionOverride(mediaTrackGroup, i)))
                }
            }
        }

        val items = subtitleOptions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("সাবটাইটেল নির্বাচন করুন (Subtitles)")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val choice = subtitleOptions[which]
                if (choice.second == null) {
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    )
                    Toast.makeText(this, "সাবটাইটেল বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
                } else {
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(choice.second!!)
                    )
                    Toast.makeText(this, "নির্বাচিত সাবটাইটেল: ${choice.first}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun togglePlayPause() {
        val p = player ?: run {
            initializePlayer()
            return
        }
        if (p.isPlaying) {
            p.pause()
            dubbedPlayer?.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            } else if (p.playbackState == Player.STATE_ENDED) {
                p.seekTo(0)
            }
            p.playWhenReady = true
            p.play()
            dubbedPlayer?.let { dp ->
                if (dp.playbackState == Player.STATE_IDLE) dp.prepare()
                dp.seekTo(p.currentPosition.coerceAtLeast(0L))
                dp.playWhenReady = true
                dp.play()
            }
        }
    }

    private fun cycleAspectRatio() {
        currentResizeModeIndex = (currentResizeModeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[currentResizeModeIndex]
        Toast.makeText(this, "অ্যাসপেক্ট রেশিও: ${resizeModeLabels[currentResizeModeIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleScreenRotation() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val label = if (isLandscape) "স্ক্রিন: পোর্ট্রেট" else "স্ক্রিন: ল্যান্ডস্কেপ"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        resetControlsHideTimer()
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % SPEEDS.size
        val speed = SPEEDS[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        dubbedPlayer?.playbackParameters = PlaybackParameters(speed)
        btnSpeed.text = "${speed}x"
        Toast.makeText(this, "গতি: ${speed}x", Toast.LENGTH_SHORT).show()
    }

    private fun toggleLock() {
        isControlsLocked = !isControlsLocked
        if (isControlsLocked) {
            controlsContainer.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            Toast.makeText(this, "স্ক্রিন লক করা হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            btnUnlock.visibility = View.GONE
            controlsContainer.visibility = View.VISIBLE
            resetControlsHideTimer()
            Toast.makeText(this, "স্ক্রিন আনলক করা হয়েছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTimeline() {
        val p = player ?: return
        val currentMs = p.currentPosition.coerceAtLeast(0L)
        val durationMs = p.duration.coerceAtLeast(0L)

        txtTimeCurrent.text = formatTime(currentMs)
        if (durationMs > 0) {
            txtTimeDuration.text = formatTime(durationMs)
            val progress = ((currentMs * 1000L) / durationMs).toInt()
            seekBar.progress = progress
        }

        if (p.isPlaying && !forceSoftwareVideoDecoder && currentMs > 1500L) {
            val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val renderedFrames = p.videoDecoderCounters?.renderedOutputBufferCount ?: -1
            if (hasVideo && renderedFrames == 0) {
                zeroFrameChecks++
                if (zeroFrameChecks >= 3) {
                    Log.w(TAG, "0 video frames rendered on hardware decoder; switching to software decoder for 10-bit HEVC")
                    zeroFrameChecks = 0
                    forceSoftwareVideoDecoder = true
                    rebuildPlayerWithSoftwareVideoFallback()
                    return
                }
            } else if (renderedFrames > 0) {
                zeroFrameChecks = 0
            }
        }

        dubbedPlayer?.let { dp ->
            if (p.isPlaying && !dp.isPlaying && dp.playbackState == Player.STATE_READY) {
                dp.play()
            }
            val drift = abs(currentMs - dp.currentPosition)
            if (drift > 120L) {
                dp.seekTo(currentMs)
            }
        }

        applyAudioModeVolumes(currentMs)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun showControls() {
        controlsContainer.visibility = View.VISIBLE
        resetControlsHideTimer()
    }

    private fun hideControls() {
        if (!isControlsLocked) {
            controlsContainer.visibility = View.GONE
        }
    }

    private fun resetControlsHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_CONTROLS_DELAY_MS)
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlock.visibility = if (btnUnlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    if (controlsContainer.visibility == View.VISIBLE) {
                        hideControls()
                    } else {
                        showControls()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isControlsLocked) return true
                val width = playerRoot.width.toFloat()
                if (width <= 0f) return true

                if (e.x < width / 3f) {
                    btnReplay10.performClick()
                } else if (e.x > (width * 2f) / 3f) {
                    btnForward10.performClick()
                } else {
                    togglePlayPause()
                }
                return true
            }
        })

        var initialTouchX = 0f
        var initialTouchY = 0f
        var isSwipeGesture = false
        var gestureMode = 0 // 1 = Brightness, 2 = Volume, 3 = Seek

        playerRoot.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            if (isControlsLocked) return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.x
                    initialTouchY = event.y
                    isSwipeGesture = false
                    gestureMode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - initialTouchX
                    val deltaY = event.y - initialTouchY
                    val screenWidth = playerRoot.width.toFloat().coerceAtLeast(1f)
                    val screenHeight = playerRoot.height.toFloat().coerceAtLeast(1f)

                    if (!isSwipeGesture && (abs(deltaX) > 40f || abs(deltaY) > 40f)) {
                        isSwipeGesture = true
                        gestureMode = if (abs(deltaX) > abs(deltaY)) {
                            3
                        } else if (initialTouchX < screenWidth / 2f) {
                            1
                        } else {
                            2
                        }
                    }

                    if (isSwipeGesture) {
                        when (gestureMode) {
                            1 -> adjustBrightness(-deltaY / screenHeight)
                            2 -> adjustVolume(-deltaY / screenHeight)
                            3 -> previewSeek(deltaX, screenWidth)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwipeGesture && gestureMode == 3) {
                        commitSeek()
                    }
                    handler.postDelayed({ hudContainer.visibility = View.GONE }, 1200L)
                }
            }
            true
        }
    }

    private fun adjustBrightness(factorDelta: Float) {
        val lp = window.attributes
        var current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        current = (current + factorDelta * 0.4f).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = current
        window.attributes = lp

        val percent = (current * 100).toInt()
        hudIcon.setImageResource(R.drawable.ic_brightness)
        hudText.text = "$percent%"
        hudProgress.progress = percent
        hudContainer.visibility = View.VISIBLE
    }

    private fun adjustVolume(factorDelta: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val deltaSteps = (factorDelta * maxVol * 0.5f).toInt()
        val newVol = (currentVol + deltaSteps).coerceIn(0, maxVol)

        if (newVol != currentVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        }

        val percent = ((newVol.toFloat() / maxVol.toFloat()) * 100).toInt()
        hudIcon.setImageResource(R.drawable.ic_volume_up)
        hudText.text = "$percent%"
        hudProgress.progress = percent
        hudContainer.visibility = View.VISIBLE
    }

    private var pendingSeekPositionMs = 0L

    private fun previewSeek(deltaX: Float, screenWidth: Float) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) return

        val scrubMs = ((deltaX / screenWidth) * 90_000L).toLong()
        pendingSeekPositionMs = (p.currentPosition + scrubMs).coerceIn(0L, duration)

        hudIcon.setImageResource(R.drawable.ic_aspect_ratio)
        hudText.text = "${formatTime(pendingSeekPositionMs)} / ${formatTime(duration)}"
        hudProgress.progress = ((pendingSeekPositionMs * 100L) / duration).toInt()
        hudContainer.visibility = View.VISIBLE
    }

    private fun commitSeek() {
        player?.let { p ->
            if (pendingSeekPositionMs in 0L..p.duration) {
                p.seekTo(pendingSeekPositionMs)
                dubbedPlayer?.seekTo(pendingSeekPositionMs)
            }
        }
    }

    private fun showSeekHud(targetMs: Long, deltaMs: Long) {
        val p = player ?: return
        val sign = if (deltaMs >= 0) "+10s" else "-10s"
        hudIcon.setImageResource(if (deltaMs >= 0) R.drawable.ic_forward_10 else R.drawable.ic_replay_10)
        hudText.text = "$sign (${formatTime(targetMs)})"
        hudProgress.progress = if (p.duration > 0) ((targetMs * 100L) / p.duration).toInt() else 0
        hudContainer.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed({ hudContainer.visibility = View.GONE }, 1200L)
    }
}
