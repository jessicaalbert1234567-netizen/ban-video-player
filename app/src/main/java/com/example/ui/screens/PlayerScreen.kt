package com.example.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.player.AudioTrackChoice
import com.example.player.SubtitleChoice
import com.example.ui.DubbingViewModel
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: DubbingViewModel,
    onNavigateBack: () -> Unit
) {
    val playerManager = viewModel.playerManager
    val playerState by playerManager.playerState.collectAsState()
    val project by viewModel.selectedProject.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    // Position polling loop for smooth seekbar updates
    LaunchedEffect(playerState.isPlaying) {
        while (true) {
            playerManager.updateProgress()
            delay(250)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showControls = !showControls }
    ) {
        // 1. Media3 Video Viewport
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.initializePlayer()
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.player = playerManager.player
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Active Subtitle Overlay (Burned-in styling: bold, outlined or dark translucent pill)
        if (playerState.subtitleChoice == SubtitleChoice.BANGLA && !playerState.activeSubtitleText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 180.dp else 40.dp)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = playerState.activeSubtitleText!!,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 3. Media Controls Overlay
        if (showControls) {
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

                    IconButton(onClick = { playerManager.toggleLock() }) {
                        Icon(
                            if (playerState.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock",
                            tint = if (playerState.isLocked) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    IconButton(onClick = { showSpeedDialog = true }) {
                        Text(
                            text = "${playerState.playbackSpeed}x",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
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

                // Bottom Panel: Timeline & Dual Audio / Subtitle Selectors
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

                    // Selectors Row: Audio & Subtitle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Audio Selection
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
                                    selected = playerState.audioChoice == AudioTrackChoice.ORIGINAL,
                                    onClick = { playerManager.setAudioChoice(AudioTrackChoice.ORIGINAL) },
                                    label = { Text("Original") },
                                    modifier = Modifier.testTag("audio_choice_original")
                                )
                                FilterChip(
                                    selected = playerState.audioChoice == AudioTrackChoice.BANGLA_DUB,
                                    onClick = { playerManager.setAudioChoice(AudioTrackChoice.BANGLA_DUB) },
                                    label = { Text("বাংলা AI Dub") },
                                    modifier = Modifier.testTag("audio_choice_bangla")
                                )
                            }
                        }

                        // Subtitle Selection
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
