package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.models.ModelCatalog
import com.example.models.ModelItemUiState
import com.example.models.ModelStatus
import com.example.tts.BengaliTtsStatus
import com.example.ui.DubbingViewModel
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    viewModel: DubbingViewModel,
    onNavigateBack: () -> Unit
) {
    val models by viewModel.modelsState.collectAsState()
    val isAllReady by viewModel.isAllModelsReady.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val probeResults by viewModel.probeResults.collectAsState()
    val storageSummary by viewModel.storageSummary.collectAsState()
    val requiredMb = storageSummary.first / (1024 * 1024)
    val availableMb = storageSummary.second / (1024 * 1024)

    var showConfigDialogForModel by remember { mutableStateOf<com.example.models.ModelInfo?>(null) }
    var releaseUrlInput by remember { mutableStateOf("") }
    var diagnosticsModelItem by remember { mutableStateOf<ModelItemUiState?>(null) }

    if (showConfigDialogForModel != null) {
        val model = showConfigDialogForModel!!
        AlertDialog(
            onDismissRequest = { showConfigDialogForModel = null },
            title = { Text("Configure Release Asset URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter the GitHub Release asset download URL for ${model.name}:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = releaseUrlInput,
                        onValueChange = { releaseUrlInput = it },
                        label = { Text("Asset URL") },
                        placeholder = { Text("https://github.com/.../translation_en_bn_v1.0.zip") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "The URL will be verified upon download (SHA-256 checksum & test translation).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = releaseUrlInput.trim()
                        if (trimmed.isNotBlank()) {
                            ModelCatalog.configureTranslationReleaseUrl(trimmed)
                            viewModel.refreshModelStatuses()
                        }
                        showConfigDialogForModel = null
                    },
                    enabled = releaseUrlInput.isNotBlank()
                ) {
                    Text("Save & Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialogForModel = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (diagnosticsModelItem != null) {
        val item = models.find { it.info.id == diagnosticsModelItem!!.info.id } ?: diagnosticsModelItem!!
        val probe = probeResults[item.info.id]
        ModelDiagnosticsDialog(
            item = item,
            probeResult = probe,
            onProbe = { viewModel.probeModel(item.info) },
            onRefresh = { viewModel.refreshModelStatuses() },
            onConfigureUrl = {
                releaseUrlInput = item.info.downloadUrl
                showConfigDialogForModel = item.info
                diagnosticsModelItem = null
            },
            onDismiss = { diagnosticsModelItem = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline AI Models", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("model_mgr_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { diagnosticsModelItem = models.firstOrNull() },
                        modifier = Modifier.testTag("model_diagnostics_button")
                    ) {
                        Icon(Icons.Default.Analytics, contentDescription = "Model Diagnostics")
                    }
                    IconButton(
                        onClick = { viewModel.refreshModelStatuses() },
                        modifier = Modifier.testTag("refresh_models_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Verify Models")
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
            // Storage Header Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Device Storage & Verification",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Available: $availableMb MB | Required: $requiredMb MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (availableMb > requiredMb) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SuccessGreen.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Storage OK",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = SuccessGreen
                                )
                            }
                        }
                    }
                }
            }

            // Download All Button
            item {
                val hasConfiguredPending = models.any { it.info.isSourceConfigured && !it.isReadyForOfflineUse }
                Button(
                    onClick = { viewModel.downloadAllModels() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("download_all_models_button"),
                    enabled = hasConfiguredPending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAllReady) SuccessGreen else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (isAllReady) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isAllReady -> "All Required Models Verified & Ready"
                            hasConfiguredPending -> "Download Configured Models"
                            else -> "Some Models Unconfigured"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            // Section: Required Pipeline Models
            item {
                Text(
                    text = "Required Models (English → Bangla)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(models, key = { it.info.id }) { item ->
                ModelCard(
                    item = item,
                    onDownload = { viewModel.downloadModel(item.info) },
                    onDelete = { viewModel.deleteModel(item.info) },
                    onConfigureUrl = {
                        releaseUrlInput = item.info.downloadUrl
                        showConfigDialogForModel = item.info
                    },
                    onOpenDiagnostics = { diagnosticsModelItem = item }
                )
            }

            // Section: Bengali Speech Synthesis (Native Android TTS)
            item {
                Text(
                    text = "Speech Synthesis (বাঙালি কণ্ঠ)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("model_mgr_tts_card")
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
                                    text = "বাংলা ভয়েস",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Android TTS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            when (val status = ttsStatus) {
                                is BengaliTtsStatus.Available -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SuccessGreen.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "✓ Ready",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = SuccessGreen
                                        )
                                    }
                                }
                                is BengaliTtsStatus.NotInstalled -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = WarningAmber.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "⚠ Not Installed",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = WarningAmber
                                        )
                                    }
                                }
                                is BengaliTtsStatus.Checking -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "Checking...",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        when (val status = ttsStatus) {
                            is BengaliTtsStatus.Available -> {
                                Text(
                                    text = "Native Android Text-to-Speech is ready with Bengali voice data (${status.engineName ?: "System Default"} • ${status.localeDisplayName}).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is BengaliTtsStatus.NotInstalled -> {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarningAmber
                                )
                                Button(
                                    onClick = { viewModel.openTtsSettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("install_bengali_voice_models_btn")
                                ) {
                                    Icon(Icons.Default.SettingsVoice, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Install Bengali voice", fontWeight = FontWeight.Bold)
                                }
                            }
                            is BengaliTtsStatus.Checking -> {
                                Text(
                                    text = "Checking device for installed offline Bengali voices...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    item: ModelItemUiState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onConfigureUrl: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    val info = item.info
    val isDownloading = item.downloadProgress?.status == ModelStatus.DOWNLOADING || item.status == ModelStatus.DOWNLOADING
    val isVerifying = item.downloadProgress?.status == ModelStatus.VERIFYING || item.status == ModelStatus.VERIFYING
    val isExtracting = item.downloadProgress?.status == ModelStatus.EXTRACTING || item.status == ModelStatus.EXTRACTING
    val isInstalling = item.downloadProgress?.status == ModelStatus.INSTALLING || item.status == ModelStatus.INSTALLING
    val sizeMb = if (info.sizeBytes > 0) info.sizeBytes / (1024 * 1024) else 0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${info.id}")
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
                        text = info.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${info.type.displayName} • v${info.version}${if (sizeMb > 0) " • ~$sizeMb MB" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Strict model states: NOT_DOWNLOADED, DOWNLOADING, VERIFYING, EXTRACTING, INSTALLING, READY, ERROR
                val statusText = when {
                    item.status == ModelStatus.READY || item.isReadyForOfflineUse -> "Ready"
                    isDownloading -> "Downloading ${item.downloadProgress?.progressPercent ?: 0}%"
                    isVerifying -> "Verifying..."
                    isExtracting -> "Extracting..."
                    isInstalling -> "Installing..."
                    item.status == ModelStatus.ERROR -> "Error"
                    item.status == ModelStatus.INCOMPATIBLE -> "Incompatible"
                    item.status == ModelStatus.SOURCE_UNAVAILABLE -> "Source 404"
                    !info.isSourceConfigured && !item.isReadyForOfflineUse -> "Not Configured"
                    else -> "Not Downloaded"
                }

                val statusColor = when {
                    item.status == ModelStatus.READY || item.isReadyForOfflineUse -> SuccessGreen
                    isDownloading -> MaterialTheme.colorScheme.primary
                    isVerifying || isExtracting || isInstalling -> WarningAmber
                    item.status == ModelStatus.INCOMPATIBLE || item.status == ModelStatus.ERROR || item.status == ModelStatus.SOURCE_UNAVAILABLE -> ErrorRed
                    !info.isSourceConfigured && !item.isReadyForOfflineUse -> WarningAmber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                }
            }

            Text(
                text = info.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // License / Requirements info
            if (info.licenseSource.isNotBlank()) {
                Text(
                    text = "Source: ${info.licenseSource}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            // Verification or extraction in progress
            if (isVerifying || isExtracting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = WarningAmber
                    )
                    Text(
                        text = item.downloadProgress?.verificationStatus
                            ?: if (isExtracting) "Extracting package contents..." else "Verifying integrity & checksums...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress bar if downloading: downloaded / total bytes, percentage, speed, ETA, verification status
            if (isDownloading && item.downloadProgress != null) {
                val prog = item.downloadProgress
                val downloadedMb = String.format(java.util.Locale.US, "%.1f", prog.downloadedBytes / (1024.0 * 1024.0))
                val totalMb = String.format(java.util.Locale.US, "%.1f", prog.totalBytes / (1024.0 * 1024.0))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { (prog.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${prog.downloadedBytes} / ${prog.totalBytes} bytes ($downloadedMb / $totalMb MB) [${prog.progressPercent}%]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${prog.speedKbps} KB/s • ETA: ${prog.estimatedRemainingSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!prog.verificationStatus.isNullOrBlank()) {
                        Text(
                            text = prog.verificationStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Error or Incompatibility explanation
            val failureReason = when {
                item.status == ModelStatus.ERROR && !item.downloadProgress?.errorMessage.isNullOrBlank() ->
                    item.downloadProgress?.errorMessage
                item.status == ModelStatus.ERROR && item.verification?.isFilePresent == true && !item.verification?.failureReason.isNullOrBlank() ->
                    item.verification?.failureReason
                item.status == ModelStatus.INCOMPATIBLE ->
                    item.verification?.failureReason ?: "Model architecture or tensor format incompatible with this device runtime."
                item.status == ModelStatus.SOURCE_UNAVAILABLE ->
                    item.verification?.failureReason ?: "Model download source is unavailable."
                item.status == ModelStatus.ERROR ->
                    item.downloadProgress?.errorMessage ?: (if (item.verification?.isFilePresent == true) item.verification?.failureReason else null)
                else -> null
            }
            if (failureReason != null && !item.isReadyForOfflineUse && (item.status == ModelStatus.ERROR || item.status == ModelStatus.INCOMPATIBLE || item.status == ModelStatus.SOURCE_UNAVAILABLE)) {
                Surface(
                    color = ErrorRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = failureReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed
                        )
                    }
                }
            }

            // Checksum / Verification details
            if (info.sha256.isNotBlank()) {
                Text(
                    text = "SHA-256: ${info.sha256.take(12)}...${info.sha256.takeLast(6)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.testTag("diagnostics_btn_${info.id}")
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Diagnostics", style = MaterialTheme.typography.labelMedium)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.isInstalled || item.verification?.isFilePresent == true) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.testTag("delete_model_${info.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    } else if (!info.isSourceConfigured) {
                        OutlinedButton(
                            onClick = onConfigureUrl,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set Release URL")
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            enabled = !isDownloading && !isVerifying && !isExtracting && !isInstalling,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("download_model_${info.id}")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                when {
                                    isDownloading -> "Downloading..."
                                    isVerifying -> "Verifying..."
                                    isExtracting -> "Extracting..."
                                    isInstalling -> "Installing..."
                                    else -> "Download"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelDiagnosticsDialog(
    item: ModelItemUiState,
    probeResult: com.example.models.ModelDownloader.HttpProbeResult?,
    onProbe: () -> Unit,
    onRefresh: () -> Unit,
    onConfigureUrl: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = item.info
    val v = item.verification

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Diagnostics",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = (if (item.isReadyForOfflineUse) SuccessGreen else ErrorRed).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (item.isReadyForOfflineUse) "READY" else item.status.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (item.isReadyForOfflineUse) SuccessGreen else ErrorRed
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Model Identity
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "${info.name} (${info.id})", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text(text = "Format: ${info.format.name} | Requirements: ${info.runtimeRequirements}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // File System Audit
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Local Storage & Path Audit:", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = "• Resolved Path: ${v?.resolvedFilePath ?: "(Unresolved)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• File Present: ${if (v?.isFilePresent == true) "YES (${v.fileSizeBytes} bytes)" else "NO (0 bytes)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (v?.isFilePresent == true) SuccessGreen else ErrorRed
                    )
                    Text(
                        text = "• SHA-256 Checksum: ${if (v?.sha256Matches == true) "MATCHED" else "UNVERIFIED / MISMATCH"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (v?.sha256Matches == true) SuccessGreen else WarningAmber
                    )
                    Text(
                        text = "• ONNX Session: ${if (v?.onnxLoadSuccess == true) "PASSED" else "NOT INITIALIZED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (v?.onnxLoadSuccess == true) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!v?.failureReason.isNullOrBlank()) {
                        Text(
                            text = "• Failure Reason: ${v?.failureReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed
                        )
                    }
                }

                HorizontalDivider()

                // Remote Distribution Audit
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Remote Distribution Audit:", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = "• Source URL: ${if (info.isSourceConfigured) info.downloadUrl else "NOT CONFIGURED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.isSourceConfigured) MaterialTheme.colorScheme.onSurfaceVariant else WarningAmber
                    )

                    if (probeResult != null) {
                        Text(
                            text = "• HTTP Status: ${probeResult.statusCode} ${probeResult.statusMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (probeResult.isAvailable) SuccessGreen else ErrorRed
                        )
                        if (probeResult.finalUrl != info.downloadUrl) {
                            Text(
                                text = "• Final URL after redirect: ${probeResult.finalUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "• Content-Type: ${probeResult.contentType ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Content-Length: ${probeResult.contentLength} bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!probeResult.errorMessage.isNullOrBlank()) {
                            Text(
                                text = "• Diagnostic Detail: ${probeResult.errorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed
                            )
                        }
                    } else {
                        Text(
                            text = "• HTTP Status: (Not probed yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onProbe,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Probe URL", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Verify Disk", style = MaterialTheme.typography.labelSmall)
                    }
                    if (info.type == com.example.models.ModelType.TRANSLATION) {
                        OutlinedButton(
                            onClick = onConfigureUrl,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Set URL", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
