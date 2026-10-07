package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.player.AudioTrackChoice
import com.example.player.ResizeModeChoice
import com.example.player.SubtitleChoice
import com.example.ui.DubbingViewModel
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class GestureHudType {
    NONE, BRIGHTNESS, VOLUME, SEEK, RESIZE
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: DubbingViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()

    val playerManager = viewModel.playerManager
    val playerState by playerManager.playerState.collectAsState()
    val project by viewModel.selectedProject.collectAsState()
    val audioExportStatus by viewModel.audioExportStatus.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAudioTrackDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }

    // Gesture State HUD
    var hudType by remember { mutableStateOf(GestureHudType.NONE) }
    var hudValueText by remember { mutableStateOf("") }
    var hudPercent by remember { mutableFloatStateOf(0.5f) }
    var hudTimeoutJob by remember { mutableStateOf<Job?>(null) }

    // Brightness tracking
    var currentBrightness by remember {
        val initial = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (initial < 0f) 0.5f else initial.coerceIn(0.05f, 1f))
    }

    // Volume tracking
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // Seeking drag state
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekTargetMs by remember { mutableLongStateOf(0L) }
    var dragSeekDiffSec by remember { mutableLongStateOf(0L) }

    // Double tap feedback
    var showDoubleTapFeedback by remember { mutableStateOf<String?>(null) }

    val configuration = LocalConfiguration.current
    val screenWidthPx = remember(configuration) {
        val displayMetrics = context.resources.displayMetrics
        displayMetrics.widthPixels.toFloat()
    }
    val screenHeightPx = remember(configuration) {
        val displayMetrics = context.resources.displayMetrics
        displayMetrics.heightPixels.toFloat()
    }

    fun showHud(type: GestureHudType, valueText: String, percent: Float, hideDelayMs: Long = 1200L) {
        hudType = type
        hudValueText = valueText
        hudPercent = percent.coerceIn(0f, 1f)
        hudTimeoutJob?.cancel()
        hudTimeoutJob = scope.launch {
            delay(hideDelayMs)
            hudType = GestureHudType.NONE
        }
    }

    // Position polling loop for smooth seekbar updates
    LaunchedEffect(playerState.isPlaying) {
        while (true) {
            playerManager.updateProgress()
            delay(250)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(audioExportStatus) {
        audioExportStatus?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearAudioExportStatus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Media3 Video Viewport with VLC-Style Resize Modes
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.initializePlayer()
                    useController = false
                    resizeMode = playerState.resizeMode.mode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.player = playerManager.player
                playerView.resizeMode = playerState.resizeMode.mode
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Gesture Touch Layer (Brightness, Volume, Seek Scrubbing & Double Tap)
        if (!playerState.isLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                showControls = !showControls
                            },
                            onDoubleTap = { offset ->
                                val isLeft = offset.x < size.width / 2f
                                if (isLeft) {
                                    playerManager.seekBy(-10_000)
                                    showDoubleTapFeedback = "⏪ -10s"
                                } else {
                                    playerManager.seekBy(10_000)
                                    showDoubleTapFeedback = "⏩ +10s"
                                }
                                scope.launch {
                                    delay(800)
                                    showDoubleTapFeedback = null
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        var startX = 0f
                        var startY = 0f
                        var isHorizontalDrag = false
                        var isVerticalDrag = false
                        var dragInitialPosMs = 0L

                        detectDragGestures(
                            onDragStart = { offset ->
                                startX = offset.x
                                startY = offset.y
                                isHorizontalDrag = false
                                isVerticalDrag = false
                                dragInitialPosMs = playerState.currentPositionMs
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val totalDeltaX = change.position.x - startX
                                val totalDeltaY = change.position.y - startY

                                if (!isHorizontalDrag && !isVerticalDrag) {
                                    if (abs(totalDeltaX) > abs(totalDeltaY) && abs(totalDeltaX) > 20f) {
                                        isHorizontalDrag = true
                                    } else if (abs(totalDeltaY) > abs(totalDeltaX) && abs(totalDeltaY) > 20f) {
                                        isVerticalDrag = true
                                    }
                                }

                                if (isHorizontalDrag) {
                                    // VLC-style Horizontal scrub seek
                                    isDraggingSeek = true
                                    val duration = playerState.durationMs.coerceAtLeast(1000L)
                                    // Scale: full screen drag moves through 90 seconds or 20% of video
                                    val seekScaleMs = maxOf(60_000L, duration / 5)
                                    val seekDiffMs = ((totalDeltaX / size.width.toFloat()) * seekScaleMs).toLong()
                                    val targetMs = (dragInitialPosMs + seekDiffMs).coerceIn(0L, duration)
                                    dragSeekTargetMs = targetMs
                                    dragSeekDiffSec = (targetMs - dragInitialPosMs) / 1000L

                                    val diffSign = if (dragSeekDiffSec >= 0) "+${dragSeekDiffSec}s" else "${dragSeekDiffSec}s"
                                    showHud(
                                        type = GestureHudType.SEEK,
                                        valueText = "${formatDuration(targetMs)} / ${formatDuration(duration)} ($diffSign)",
                                        percent = targetMs.toFloat() / duration.toFloat(),
                                        hideDelayMs = 2000L
                                    )
                                } else if (isVerticalDrag) {
                                    val isLeftHalf = startX < size.width * 0.45f
                                    val deltaRatio = -dragAmount.y / (size.height * 0.75f)

                                    if (isLeftHalf) {
                                        // Left side vertical drag: BRIGHTNESS
                                        val newBrightness = (currentBrightness + deltaRatio).coerceIn(0.05f, 1.0f)
                                        currentBrightness = newBrightness
                                        activity?.window?.let { window ->
                                            val lp = window.attributes
                                            lp.screenBrightness = newBrightness
                                            window.attributes = lp
                                        }
                                        val bPercent = (newBrightness * 100).toInt()
                                        showHud(
                                            type = GestureHudType.BRIGHTNESS,
                                            valueText = "Brightness: $bPercent%",
                                            percent = newBrightness
                                        )
                                    } else {
                                        // Right side vertical drag: VOLUME
                                        val volDelta = (deltaRatio * maxVolume).toInt()
                                        if (volDelta != 0 || abs(dragAmount.y) > 4f) {
                                            val targetVol = (currentVolume + (deltaRatio * maxVolume * 1.5f).toInt())
                                                .coerceIn(0, maxVolume)
                                            if (targetVol != currentVolume) {
                                                currentVolume = targetVol
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                            }
                                        }
                                        val vPercent = ((currentVolume.toFloat() / maxVolume) * 100).toInt()
                                        showHud(
                                            type = GestureHudType.VOLUME,
                                            valueText = "Volume: $vPercent%",
                                            percent = currentVolume.toFloat() / maxVolume
                                        )
                                    }
                                }
                            },
                            onDragEnd = {
                                if (isDraggingSeek) {
                                    playerManager.seekTo(dragSeekTargetMs)
                                    isDraggingSeek = false
                                }
                                isHorizontalDrag = false
                                isVerticalDrag = false
                            },
                            onDragCancel = {
                                isDraggingSeek = false
                                isHorizontalDrag = false
                                isVerticalDrag = false
                            }
                        )
                    }
            )
        }

        // 3. Active Subtitle Overlay
        if (playerState.subtitleChoice != SubtitleChoice.OFF && !playerState.activeSubtitleText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 180.dp else 40.dp)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.80f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = playerState.activeSubtitleText!!,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 4. Gesture HUD Overlays (Brightness / Volume / Scrub / Mode)
        AnimatedVisibility(
            visible = hudType != GestureHudType.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (hudType) {
                        GestureHudType.BRIGHTNESS -> {
                            Icon(
                                if (hudPercent > 0.6f) Icons.Default.BrightnessHigh
                                else if (hudPercent > 0.3f) Icons.Default.BrightnessMedium
                                else Icons.Default.BrightnessLow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        GestureHudType.VOLUME -> {
                            Icon(
                                if (hudPercent > 0.5f) Icons.Default.VolumeUp
                                else if (hudPercent > 0.05f) Icons.Default.VolumeDown
                                else Icons.Default.VolumeMute,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        GestureHudType.SEEK -> {
                            Icon(
                                if (dragSeekDiffSec >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        GestureHudType.RESIZE -> {
                            Icon(
                                Icons.Default.AspectRatio,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        else -> {}
                    }

                    Text(
                        text = hudValueText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )

                    if (hudType == GestureHudType.BRIGHTNESS || hudType == GestureHudType.VOLUME) {
                        LinearProgressIndicator(
                            progress = { hudPercent },
                            modifier = Modifier
                                .width(140.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.DarkGray
                        )
                    }
                }
            }
        }

        // Double Tap indicator
        if (showDoubleTapFeedback != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = showDoubleTapFeedback!!,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        // 5. Media Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("player_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Text(
                        text = project?.title ?: "Video Player",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )

                    // Audio Track Selector Button
                    IconButton(
                        onClick = { showAudioTrackDialog = true },
                        modifier = Modifier.testTag("player_audio_track_button")
                    ) {
                        Icon(
                            Icons.Default.Audiotrack,
                            contentDescription = "Audio Track",
                            tint = if (playerState.audioChoice == AudioTrackChoice.BANGLA_DUB) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // Subtitle Selector Button
                    IconButton(
                        onClick = { showSubtitleDialog = true },
                        modifier = Modifier.testTag("player_subtitle_button")
                    ) {
                        Icon(
                            Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            tint = if (playerState.subtitleChoice != SubtitleChoice.OFF) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // VLC-Style Aspect Ratio / Zoom Button
                    IconButton(
                        onClick = {
                            val nextMode = playerManager.cycleResizeMode()
                            showHud(GestureHudType.RESIZE, "Screen: ${nextMode.label}", 1.0f)
                        },
                        modifier = Modifier.testTag("player_resize_mode_button")
                    ) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = "Aspect Ratio / Zoom",
                            tint = if (playerState.resizeMode != ResizeModeChoice.FIT) SuccessGreen else Color.White
                        )
                    }

                    // Save / Export Audio Track Button
                    IconButton(
                        onClick = {
                            project?.let { p ->
                                viewModel.exportDubbedAudioToDownloads(p)
                            }
                        },
                        modifier = Modifier.testTag("player_save_audio_button")
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Save Audio Track",
                            tint = Color.White
                        )
                    }

                    // Playback Speed Button
                    IconButton(onClick = { showSpeedDialog = true }) {
                        Text(
                            text = "${playerState.playbackSpeed}x",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    // Lock Screen Button
                    IconButton(onClick = { playerManager.toggleLock() }) {
                        Icon(
                            if (playerState.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock",
                            tint = if (playerState.isLocked) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }

                // Center Play/Seek Buttons
                if (!playerState.isLocked) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { playerManager.seekBy(-10_000) },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .testTag("seek_back_10s_button")
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }

                        IconButton(
                            onClick = { playerManager.togglePlayPause() },
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("play_pause_button")
                        ) {
                            Icon(
                                if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        IconButton(
                            onClick = { playerManager.seekBy(10_000) },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .testTag("seek_forward_10s_button")
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }

                // Bottom Panel: Timeline & Dual Audio / Subtitle Quick Selectors
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Timeline Slider & Duration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatDuration(playerState.currentPositionMs),
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Slider(
                            value = if (playerState.durationMs > 0) playerState.currentPositionMs.toFloat() else 0f,
                            onValueChange = { targetPos -> playerManager.seekTo(targetPos.toLong()) },
                            valueRange = 0f..(playerState.durationMs.toFloat().coerceAtLeast(1f)),
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = formatDuration(playerState.durationMs),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    // Selectors Row: Audio & Subtitle & Mode Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Audio Selection Chips
                        Column {
                            Text(
                                text = "AUDIO TRACK",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = playerState.audioChoice == AudioTrackChoice.BANGLA_DUB,
                                    onClick = { playerManager.setAudioChoice(AudioTrackChoice.BANGLA_DUB) },
                                    label = { Text("বাংলা AI Dub") },
                                    modifier = Modifier.testTag("audio_choice_bangla")
                                )
                                FilterChip(
                                    selected = playerState.audioChoice == AudioTrackChoice.ORIGINAL,
                                    onClick = { playerManager.setAudioChoice(AudioTrackChoice.ORIGINAL) },
                                    label = { Text("Original") },
                                    modifier = Modifier.testTag("audio_choice_original")
                                )
                            }
                        }

                        // Subtitle Selection Chips
                        Column {
                            Text(
                                text = "SUBTITLES",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = playerState.subtitleChoice == SubtitleChoice.BANGLA,
                                    onClick = { playerManager.setSubtitleChoice(SubtitleChoice.BANGLA) },
                                    label = { Text("বাংলা") },
                                    modifier = Modifier.testTag("subtitle_choice_bangla")
                                )
                                FilterChip(
                                    selected = playerState.subtitleChoice == SubtitleChoice.ENGLISH,
                                    onClick = { playerManager.setSubtitleChoice(SubtitleChoice.ENGLISH) },
                                    label = { Text("English") },
                                    modifier = Modifier.testTag("subtitle_choice_english")
                                )
                                FilterChip(
                                    selected = playerState.subtitleChoice == SubtitleChoice.OFF,
                                    onClick = { playerManager.setSubtitleChoice(SubtitleChoice.OFF) },
                                    label = { Text("Off") },
                                    modifier = Modifier.testTag("subtitle_choice_off")
                                )
                            }
                        }
                    }
                }
            }
        }

        // Snackbar Host for export notifications
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
        )
    }

    // Audio Track Selection Dialog
    if (showAudioTrackDialog) {
        AlertDialog(
            onDismissRequest = { showAudioTrackDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("অডিও ট্র্যাক সিলেক্ট করুন")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Bangla AI Dub
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (playerState.audioChoice == AudioTrackChoice.BANGLA_DUB)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playerManager.setAudioChoice(AudioTrackChoice.BANGLA_DUB)
                                showAudioTrackDialog = false
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("বাংলা AI Dub", fontWeight = FontWeight.Bold)
                                Text(
                                    "বাংলা কণ্ঠ + পারিপার্শ্বিক সাউন্ড (গাড়ি, ঝড়, ইঞ্জিন, আবহ)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (playerState.audioChoice == AudioTrackChoice.BANGLA_DUB) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Original Audio
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (playerState.audioChoice == AudioTrackChoice.ORIGINAL)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playerManager.setAudioChoice(AudioTrackChoice.ORIGINAL)
                                showAudioTrackDialog = false
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Original Audio", fontWeight = FontWeight.Bold)
                                Text(
                                    "মূল ভিডিও সাউন্ডট্র্যাক",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (playerState.audioChoice == AudioTrackChoice.ORIGINAL) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Save Dubbed Audio Button
                    OutlinedButton(
                        onClick = {
                            project?.let { p ->
                                viewModel.exportDubbedAudioToDownloads(p)
                                showAudioTrackDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ডাবিং অডিও ট্রাক সেভ করুন (Downloads)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioTrackDialog = false }) {
                    Text("ঠিক আছে")
                }
            }
        )
    }

    // Subtitle Selection Dialog
    if (showSubtitleDialog) {
        val options = listOf(
            Triple(SubtitleChoice.BANGLA, "বাংলা সাবটাইটেল (Bangla)", "অনূদিত বাংলা সাবটাইটেল"),
            Triple(SubtitleChoice.ENGLISH, "English Subtitles", "মূল ইংরেজি সাবটাইটেল"),
            Triple(SubtitleChoice.OFF, "Off (বন্ধ)", "কোন সাবটাইটেল দেখাবে না")
        )
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("সাবটাইটেল সিলেক্ট করুন")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (choice, title, desc) ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (playerState.subtitleChoice == choice)
                                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playerManager.setSubtitleChoice(choice)
                                    showSubtitleDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, fontWeight = FontWeight.Bold)
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (playerState.subtitleChoice == choice) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("ঠিক আছে")
                }
            }
        )
    }

    // Playback Speed Dialog
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column {
                    speeds.forEach { spd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playerManager.setPlaybackSpeed(spd)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${spd}x", fontWeight = if (playerState.playbackSpeed == spd) FontWeight.Bold else FontWeight.Normal)
                            if (playerState.playbackSpeed == spd) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format("%02d:%02d", minutes, seconds)
}
