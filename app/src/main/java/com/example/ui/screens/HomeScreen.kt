package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.DubbingProject
import com.example.dubbing.ProcessingStage
import com.example.tts.BengaliTtsStatus
import com.example.ui.DubbingViewModel
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: DubbingViewModel,
    onNavigateToModelManager: () -> Unit,
    onNavigateToProgress: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTest: () -> Unit
) {
    val isAllModelsReady by viewModel.isAllModelsReady.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val projects by viewModel.allProjects.collectAsState()
    val subtitleInspection by viewModel.subtitleInspectionState.collectAsState()
    val exportMessage by viewModel.extractedSrtExportMessage.collectAsState()
    val isTranslatingSrt by viewModel.isTranslatingSrt.collectAsState()
    val srtTranslationStatus by viewModel.srtTranslationStatus.collectAsState()

    var pendingVideoForExternalSubtitle by remember { mutableStateOf<Uri?>(null) }
    var selectedTrackIndex by remember { mutableIntStateOf(0) }

    // Direct SRT file translator launcher (File Translator framework)
    val directSrtTranslatorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { srtUri: Uri? ->
        if (srtUri != null) {
            viewModel.translateAndExportExternalSrtToBangla(srtUri)
        }
    }

    // External subtitle picker launcher
    val externalSubtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { subUri: Uri? ->
        val vidUri = pendingVideoForExternalSubtitle
        if (subUri != null && vidUri != null) {
            viewModel.startFastDubbingFromExternalSubtitle(vidUri, subUri, onNavigateToProgress)
            pendingVideoForExternalSubtitle = null
        }
    }

    // Video picker that will immediately follow with external subtitle picker
    val videoForExternalSubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { vidUri: Uri? ->
        if (vidUri != null) {
            pendingVideoForExternalSubtitle = vidUri
            externalSubtitlePickerLauncher.launch("*/*")
        }
    }

    // Main video picker - inspects for subtitle tracks to offer 10x faster dubbing
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.inspectVideoForSubtitles(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Offline AI Dubbing Player",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        )
                        Text(
                            text = "English to Bangla Neural Dubbing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToTest,
                        modifier = Modifier.testTag("nav_test_button")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Test Pipeline")
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("nav_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 1. Model Status Banner
            item {
                if (isAllModelsReady) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = SuccessGreen.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(SuccessGreen.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SuccessGreen
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Offline AI Dubbing is Ready",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "ASR, Translation & Bangla Voice Installed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = onNavigateToModelManager) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Manage Models",
                                    tint = SuccessGreen
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = WarningAmber.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(WarningAmber.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = WarningAmber
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Required AI Models Missing",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Download once to use 100% offline forever",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                onClick = onNavigateToModelManager,
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_models_banner_button")
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download Required Models",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 1b. Bengali Voice Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (ttsStatus) {
                            is BengaliTtsStatus.Available -> SuccessGreen.copy(alpha = 0.08f)
                            is BengaliTtsStatus.NotInstalled -> WarningAmber.copy(alpha = 0.08f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("home_tts_status_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "বাংলা ভয়েস",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Android TTS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                when (val status = ttsStatus) {
                                    is BengaliTtsStatus.Checking -> {
                                        Text(
                                            text = "Checking Bengali voice support...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is BengaliTtsStatus.Available -> {
                                        Text(
                                            text = "✓ Bengali voice available",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = SuccessGreen
                                        )
                                        Text(
                                            text = "Engine: ${status.engineName ?: "System Default"} • ${status.localeDisplayName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is BengaliTtsStatus.NotInstalled -> {
                                        Text(
                                            text = "⚠ Bengali voice not installed",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = WarningAmber
                                        )
                                        Text(
                                            text = status.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { viewModel.checkBengaliTts() },
                                modifier = Modifier.testTag("refresh_home_tts_btn")
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh TTS status",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (ttsStatus is BengaliTtsStatus.NotInstalled) {
                            Button(
                                onClick = { viewModel.openTtsSettings() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().testTag("install_bengali_voice_home_btn")
                            ) {
                                Icon(Icons.Default.SettingsVoice, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install Bengali voice", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 2. MKV Subtitle Fast Dubbing Feature Card (MKVEdit Style - No ASR)
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().testTag("mkv_subtitle_feature_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "MKV Subtitle Fast Dubbing",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "এমকেভি সাবটাইটেল ডাবিং (নো ASR)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "10X FASTER",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            text = "MKVEdit-এর মতো MKV বা যেকোনো ভিডিও থেকে সরাসরি সাবটাইটেল এক্সট্র্যাক্ট করে তাৎক্ষণিক বাংলায় ডাবিং করুন। স্পিচ-টু-টেক্সট করতে হবে না — সময় বাঁচবে ৯০% এবং ডায়লগ হবে ১০০% নিখুঁত!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { videoPickerLauncher.launch("video/*") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("mkv_open_video_button")
                            ) {
                                Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open MKV Video", style = MaterialTheme.typography.labelLarge)
                            }

                            OutlinedButton(
                                onClick = { videoForExternalSubLauncher.launch("video/*") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("external_sub_dub_button")
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Dub with .SRT", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        FilledTonalButton(
                            onClick = { directSrtTranslatorLauncher.launch("*/*") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("direct_translate_srt_button")
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Translate .SRT to Bangla (File Translator Mode)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 2b. Standard Video Import Action Card (With Speech-to-Text ASR)
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.VideoFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Choose Video for Full AI Dubbing",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Auto-detects subtitle tracks or falls back to offline Whisper ASR",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("choose_video_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Choose Local Video",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            // 3. Recent Projects Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dubbed Videos",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${projects.size} total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. Projects List
            if (projects.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.MovieFilter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No dubbed videos yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Choose a video above to generate offline Bangla dubbing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onPlay = {
                            viewModel.selectProjectForPlayback(project)
                            onNavigateToPlayer()
                        },
                        onReDub = {
                            viewModel.reDubProject(project, onNavigateToProgress)
                        },
                        onDelete = {
                            viewModel.deleteProject(project)
                        }
                    )
                }
            }
        }
    }

    // Subtitle Inspection & Selection Dialog
    subtitleInspection?.let { inspection ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSubtitleInspection() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (inspection.isInspecting) "Analyzing Subtitles..." else "MKV Subtitle Inspector",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                if (inspection.isInspecting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            "Scanning video tracks for embedded subtitles...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (inspection.tracks.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Video: ${inspection.videoTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "💡 ভিডিও থেকে ইংলিশ সাবটাইটেল এক্সট্র্যাক্ট করে সরাসরি বাংলায় অনুবাদ হবে এবং মোবাইল এর Download ফোল্ডারে সেভ হবে। অনুবাদকৃত বাংলা SRT দিয়ে বাংলা অডিও ট্র্যাক তৈরি করে প্লেয়ারে চালানো যাবে। অডিও এক্সট্র্যাক্ট বা ASR-এর প্রয়োজন নেই।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Text(
                            text = "Select Subtitle Track:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        inspection.tracks.forEach { track ->
                            val isSelected = selectedTrackIndex == track.trackIndex
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTrackIndex = track.trackIndex }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedTrackIndex = track.trackIndex }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${track.title} • ${track.displayLanguage}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            text = "Format: ${track.formatName} (Track #${track.trackIndex + 1})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Video: ${inspection.videoTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "এই ভিডিও ফাইলে কোনো এমবেডেড সাবটাইটেল ট্র্যাক পাওয়া যায়নি।",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "আপনি আলাদা .SRT / .VTT সাবটাইটেল ফাইল সিলেক্ট করতে পারেন অথবা অফলাইন Whisper ASR দিয়ে সম্পূর্ণ ডাবিং করতে পারেন।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (!inspection.isInspecting && inspection.tracks.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.startFastDubbingFromSubtitleTrack(
                                    inspection.videoUri,
                                    selectedTrackIndex,
                                    onNavigateToProgress
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_fast_dub_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("⚡ Start Fast Dubbing (No ASR)")
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.translateAndExportMkvSubtitleToBangla(
                                    inspection.videoUri,
                                    selectedTrackIndex
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("translate_mkv_srt_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🌐 Translate to Bangla .SRT (File Translator)")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.extractAndExportSrt(inspection.videoUri, selectedTrackIndex)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("extract_srt_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save .SRT")
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.dismissSubtitleInspection()
                                    viewModel.selectVideoForDubbing(inspection.videoUri, onNavigateToProgress)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("use_asr_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Use ASR")
                            }
                        }
                    }
                } else if (!inspection.isInspecting && inspection.tracks.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                pendingVideoForExternalSubtitle = inspection.videoUri
                                viewModel.dismissSubtitleInspection()
                                externalSubtitlePickerLauncher.launch("*/*")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_external_sub_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add External .SRT / .VTT")
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.dismissSubtitleInspection()
                                viewModel.selectVideoForDubbing(inspection.videoUri, onNavigateToProgress)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("continue_asr_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Continue with Offline ASR")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSubtitleInspection() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Export Srt Notification Dialog
    exportMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportMessage() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                    Text("Subtitle Export", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(msg, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(onClick = { viewModel.clearExportMessage() }) {
                    Text("OK")
                }
            }
        )
    }

    // Direct SRT Translation Progress Dialog
    if (isTranslatingSrt) {
        AlertDialog(
            onDismissRequest = { /* Modal while translation is executing */ },
            icon = {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            },
            title = {
                Text("Translating Subtitle to Bangla", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = srtTranslationStatus ?: "বাংলায় অনুবাদ হচ্ছে...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "গুগল ট্রান্সলেট ও ফাইল ট্রান্সলেটর ফ্রেমওয়ার্ক অনুযায়ী অতি দ্রুত ব্যাচে অনুবাদ সম্পন্ন হচ্ছে এবং স্বয়ংক্রিয়ভাবে ডাউনলোড ফোল্ডারে সেভ হবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun ProjectCard(
    project: DubbingProject,
    onPlay: () -> Unit,
    onReDub: () -> Unit,
    onDelete: () -> Unit
) {
    val isComplete = project.currentStage == ProcessingStage.COMPLETE

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project_card_${project.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "EN → বাংলা (Bangla)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (project.currentStage) {
                        ProcessingStage.COMPLETE -> SuccessGreen.copy(alpha = 0.15f)
                        ProcessingStage.FAILED -> ErrorRed.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = if (isComplete) "Ready" else project.currentStage.displayName,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = when (project.currentStage) {
                            ProcessingStage.COMPLETE -> SuccessGreen
                            ProcessingStage.FAILED -> ErrorRed
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            // Duration & status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (project.durationMs > 0) {
                    val minutes = (project.durationMs / 1000) / 60
                    val seconds = (project.durationMs / 1000) % 60
                    Text(
                        text = String.format("⏱ %02d:%02d", minutes, seconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = project.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isComplete) {
                        Button(
                            onClick = onPlay,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("play_button_${project.id}")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                    }

                    OutlinedButton(
                        onClick = onReDub,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("redub_button_${project.id}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Re-dub")
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_button_${project.id}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete Project",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
