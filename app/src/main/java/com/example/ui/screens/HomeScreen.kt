package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.res.painterResource
import com.example.ads.AdMobBanner
import com.example.ads.AdMobManager
import com.example.security.AntiTamperGuard
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.DubbingProject
import com.example.dubbing.ProcessingStage
import com.example.ui.DubbingViewModel
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen

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
    val context = LocalContext.current
    val projects by viewModel.allProjects.collectAsState()
    val noSubtitleError by viewModel.noSubtitleError.collectAsState()
    val isAnalyzingMkv by viewModel.isAnalyzingMkv.collectAsState()
    val audioExportStatus by viewModel.audioExportStatus.collectAsState()

    var pendingVideoForExternalSubtitle by remember { mutableStateOf<Uri?>(null) }
    var fileAccessProject by remember { mutableStateOf<DubbingProject?>(null) }

    // Launcher for picking external subtitle (.srt, .vtt)
    val externalSubtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { subUri: Uri? ->
        val vidUri = pendingVideoForExternalSubtitle
        if (subUri != null && vidUri != null) {
            com.example.storage.VideoUriResolver.takePersistablePermission(context, subUri)
            viewModel.startDubbingWithExternalSubtitle(vidUri, subUri, onNavigateToProgress)
            pendingVideoForExternalSubtitle = null
        }
    }

    // Launcher for Option 2: Pick Video first, then pick Subtitle
    val videoForExternalSubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { vidUri: Uri? ->
        if (vidUri != null) {
            com.example.storage.VideoUriResolver.takePersistablePermission(context, vidUri)
            pendingVideoForExternalSubtitle = vidUri
            externalSubtitlePickerLauncher.launch(arrayOf("application/x-subrip", "text/plain", "text/*", "*/*"))
        }
    }

    // Launcher for Option 1: Main MKV Video Picker - Auto detects SRT and starts dubbing immediately!
    val mkvVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            com.example.storage.VideoUriResolver.takePersistablePermission(context, uri)
            viewModel.startAutomaticMkvDubbing(uri, onNavigateToProgress)
        }
    }

    // Direct Video Player Launcher: Open & Play ANY video directly in VLC-grade PlayerActivity
    val directVideoPlayerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            com.example.storage.VideoUriResolver.takePersistablePermission(context, uri)
            val intent = com.example.player.PlayerActivity.createIntent(context, uri)
            context.startActivity(intent)
        }
    }

    var hasMediaPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMediaPermission = results.values.any { it }
    }

    val homeSnackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(audioExportStatus) {
        audioExportStatus?.let { msg ->
            homeSnackbarHostState.showSnackbar(msg)
            viewModel.clearAudioExportStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = homeSnackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.R.drawable.ic_nimpata_leaf),
                            contentDescription = "Nimpata Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = "Nimpata Bangla Dubbing",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            )
                            Text(
                                text = "নিমপাতা এআই বাংলা ডাবিং",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    // "ফলো মি" (Follow Me) Button linking to Facebook
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.facebook.com/share/19sfESx5pQ/")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.w("HomeScreen", "Could not open follow URL: ${e.message}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .testTag("follow_me_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ThumbUp,
                            contentDescription = "Follow Me",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ফলো মি",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onNavigateToTest,
                        modifier = Modifier.testTag("nav_test_button")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Test")
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
            // CRITICAL NOTICE CARD: English subtitle requirement for MKV dubbing
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.R.drawable.ic_nimpata_leaf),
                            contentDescription = "Neem Leaf",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(36.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "জরুরি নির্দেশনা",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = "ডাবিং সফল হওয়ার জন্য mkv ভিডিও ফাইল এর ভিতর ইংরেজি সাবটাইটেল থাকতে হবে",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // AdMob Banner 1 (Empty Space Banner)
            item {
                AdMobBanner(adUnitId = AntiTamperGuard.getBanner1UnitId())
            }

            // OPTION 1: Main Hero Card — Select MKV Video (Auto Dubbing)
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.MovieFilter,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "MKV ভিডিও অটো ডাবিং",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "১ ক্লিকে সম্পূর্ণ অটোমেটিক প্রসেস",
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
                                    text = "AUTO",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            text = "শুধু আপনার MKV মুভি বা ভিডিও ফাইল সিলেক্ট করুন। অ্যাপটি স্বয়ংক্রিয়ভাবে ভিডিওর ভেতর থেকে SRT সাবটাইটেল বের করে দ্রুত বাংলায় অনুবাদ ও Google TTS দিয়ে পূর্ণাঙ্গ বাংলা ডাবিং তৈরি করবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { mkvVideoPickerLauncher.launch(arrayOf("video/*", "*/*")) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("mkv_open_video_button")
                        ) {
                            Icon(Icons.Default.VideoFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "MKV ভিডিও সিলেক্ট করুন (অটো ডাবিং)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // OPTION 2: External Subtitle Dubbing Card
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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Subtitles,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "বাংলা অনুবাদকৃত SRT দিয়ে সরাসরি ডাবিং",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "ভিডিও + নিজস্ব বাংলা SRT ফাইল",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = "অন্য কোনো উৎস বা ইন্টারনেট থেকে ডাউনলোড করা বাংলা অনুবাদকৃত SRT ফাইল এবং ভিডিও বেছে নিয়ে সরাসরি দ্রুত ডাবিং তৈরি করুন (অনুবাদ স্কিপ করে সরাসরি ভয়েস তৈরি হবে)।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = { videoForExternalSubLauncher.launch(arrayOf("video/*", "*/*")) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("external_sub_dub_button")
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ভিডিও ও বাংলা SRT বেছে নিন",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            // OPTION 3: Direct Video Player Card (Play Any Video Directly)
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "সরাসরি যেকোনো ভিডিও চালান",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "ডাবিং অপেক্ষা ছাড়াই নিজস্ব ভিডিও প্লেয়ারে দেখুন",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = "আপনার ফোনের যেকোনো MKV বা MP4 ভিডিও ফাইল সরাসরি হাই-কোয়ালিটি প্লেয়ারে চালান। সম্পূর্ণ স্ক্রিন রোটেশন, সাবটাইটেল এবং জেসচার কন্ট্রোল থাকবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { directVideoPlayerLauncher.launch(arrayOf("video/*", "*/*")) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("direct_video_player_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ভিডিও সিলেক্ট ও সরাসরি প্লে করুন",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // PERMISSION CARD: If video/audio permissions are not yet granted
            if (!hasMediaPermission) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "অডিও ও ভিডিও ফাইল এক্সেস প্রয়োজন",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                text = "আপনার ফোনের MKV মুভি ও ভিডিও ফাইল সরাসরি লোড করা এবং নিরবচ্ছিন্নভাবে প্লে করার জন্য ফাইল পারমিশন অনুমোদন করুন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        arrayOf(
                                            android.Manifest.permission.READ_MEDIA_VIDEO,
                                            android.Manifest.permission.READ_MEDIA_AUDIO
                                        )
                                    } else {
                                        arrayOf(
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
                                    }
                                    mediaPermissionLauncher.launch(perms)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ফাইল পারমিশন অনুমোদন দিন (Allow Access)")
                            }
                        }
                    }
                }
            }

            // SECTION 3: Dubbed Videos Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ডাবিং করা ভিডিওসমূহ",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${projects.size} টি ডাবিং",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // SECTION 4: Dubbed Videos List
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
                                text = "কোনো ডাবিং করা ভিডিও নেই",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "উপরে থাকা অপশন থেকে একটি MKV বা ভিডিও সিলেক্ট করে ডাবিং শুরু করুন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = projects,
                    key = { it.id },
                    contentType = { "project" }
                ) { project ->
                    ProjectCard(
                        project = project,
                        onPlay = {
                            val activity = context as? Activity
                            if (activity != null) {
                                AdMobManager.showRewardedAd(activity) {
                                    viewModel.selectProjectForPlayback(project)
                                    onNavigateToPlayer()
                                }
                            } else {
                                viewModel.selectProjectForPlayback(project)
                                onNavigateToPlayer()
                            }
                        },
                        onReDub = {
                            viewModel.reDubProject(project, onNavigateToProgress)
                        },
                        onSaveAudio = {
                            viewModel.exportDubbedAudioToDownloads(project)
                        },
                        onViewFiles = {
                            fileAccessProject = project
                        },
                        onDelete = {
                            viewModel.deleteProject(project)
                        }
                    )
                }
            }

            // Bottom AdMob Banner (Banner 4)
            item {
                AdMobBanner(adUnitId = AntiTamperGuard.getBanner4UnitId())
            }
        }
    }

    // Analyzing Subtitles Progress Modal
    if (isAnalyzingMkv) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            },
            title = {
                Text("MKV স্ক্যান করা হচ্ছে...", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "ভিডিওর ভেতর থেকে সাবটাইটেল ট্র্যাক খোঁজা হচ্ছে। এক মুহূর্ত অপেক্ষা করুন...",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {}
        )
    }

    // No Subtitle Found Alert Dialog
    noSubtitleError?.let { errorText ->
        AlertDialog(
            onDismissRequest = { viewModel.clearNoSubtitleError() },
            icon = {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("সাবটাইটেল পাওয়া যায়নি", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearNoSubtitleError() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ঠিক আছে")
                }
            }
        )
    }

    // Saved Files & Storage Access Dialog
    fileAccessProject?.let { proj ->
        val projectDir = java.io.File(proj.projectDirPath)
        val audioFile = proj.dubbedAudioPath?.let { java.io.File(it) } ?: java.io.File(projectDir, "dubbed_bn.wav")
        val srtFile = proj.subtitleSrtPath?.let { java.io.File(it) } ?: java.io.File(projectDir, "subtitle_bn.srt")
        val audioExists = audioFile.exists() && audioFile.length() > 44
        val srtExists = srtFile.exists() && srtFile.length() > 0

        AlertDialog(
            onDismissRequest = { fileAccessProject = null },
            icon = {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("সংরক্ষিত ডাবিং ফাইল ও অ্যাক্সেস", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "অ্যাপ বন্ধ হওয়ার পরও আপনার অডিও ট্র্যাক ও সাবটাইটেল ১০০% সংরক্ষিত থাকবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Audio Track File Box
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("বাংলা ডাবিং অডিও ট্র্যাক (.wav)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Text(
                                text = if (audioExists) "সাইজ: ${(audioFile.length() / 1024)} KB\nপাথ: ${audioFile.name}" else "অডিও ফাইল তৈরি হচ্ছে / সংরক্ষিত...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (audioExists) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.openFileInExternalApp(audioFile, "audio/*") },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("প্লেয়ার এ চালান", style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.shareFile(audioFile, "audio/*") },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("শেয়ার করুন", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // Subtitle File Box
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Subtitles, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                                Text("বাংলা সাবটাইটেল (.srt)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Text(
                                text = if (srtExists) "সাইজ: ${(srtFile.length() / 1024)} KB\nপাথ: ${srtFile.name}" else "সাবটাইটেল ফাইল সংরক্ষিত",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (srtExists) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.shareFile(srtFile, "text/plain") },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("SRT শেয়ার করুন", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { fileAccessProject = null }) {
                    Text("ঠিক আছে")
                }
            }
        )
    }
}

@Composable
fun ProjectCard(
    project: DubbingProject,
    onPlay: () -> Unit,
    onReDub: () -> Unit,
    onSaveAudio: () -> Unit = {},
    onViewFiles: () -> Unit = {},
    onDelete: () -> Unit
) {
    val isComplete = project.currentStage == ProcessingStage.COMPLETE
    val durationText = remember(project.durationMs) {
        if (project.durationMs > 0) {
            val minutes = (project.durationMs / 60000)
            val seconds = (project.durationMs % 60000) / 1000
            val mStr = if (minutes < 10) "0$minutes" else "$minutes"
            val sStr = if (seconds < 10) "0$seconds" else "$seconds"
            "⏱ $mStr:$sStr"
        } else null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project_card_${project.id}")
            .clickable { onPlay() }
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
                if (durationText != null) {
                    Text(
                        text = durationText,
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
                    Button(
                        onClick = onPlay,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("play_button_${project.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isComplete) "Play" else "ভিডিও প্লে")
                    }

                    IconButton(
                        onClick = onViewFiles,
                        modifier = Modifier.testTag("view_files_button_${project.id}")
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "View Saved Files",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (isComplete) {
                        IconButton(
                            onClick = onSaveAudio,
                            modifier = Modifier.testTag("save_audio_button_${project.id}")
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Save Dubbed Audio",
                                tint = MaterialTheme.colorScheme.primary
                            )
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
