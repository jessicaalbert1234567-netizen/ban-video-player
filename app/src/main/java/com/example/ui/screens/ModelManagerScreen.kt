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
    val storageSummary = viewModel.getStorageSummary()
    val requiredMb = storageSummary.first / (1024 * 1024)
    val availableMb = storageSummary.second / (1024 * 1024)

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
                    onDelete = { viewModel.deleteModel(item.info) }
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    item: ModelItemUiState,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val info = item.info
    val isDownloading = item.downloadProgress?.status == ModelStatus.DOWNLOADING
    val isVerifying = item.downloadProgress?.status == ModelStatus.VERIFYING
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

                // Status chip
                val statusText = when {
                    !info.isSourceConfigured && !item.isReadyForOfflineUse -> "Not Configured"
                    item.isReadyForOfflineUse -> "Verified & Ready"
                    isDownloading -> "${item.downloadProgress?.progressPercent ?: 0}%"
                    isVerifying -> "Verifying..."
                    item.status == ModelStatus.INCOMPATIBLE -> "Incompatible"
                    item.status == ModelStatus.ERROR -> "Error"
                    else -> "Not Installed"
                }

                val statusColor = when {
                    !info.isSourceConfigured && !item.isReadyForOfflineUse -> WarningAmber
                    item.isReadyForOfflineUse -> SuccessGreen
                    isDownloading -> MaterialTheme.colorScheme.primary
                    isVerifying -> WarningAmber
                    item.status == ModelStatus.INCOMPATIBLE || item.status == ModelStatus.ERROR -> ErrorRed
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

            // Progress bar if downloading
            if (isDownloading && item.downloadProgress != null) {
                val prog = item.downloadProgress
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
                            text = "${prog.downloadedBytes / (1024 * 1024)} / ${prog.totalBytes / (1024 * 1024)} MB (${prog.speedKbps} KB/s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${prog.estimatedRemainingSeconds}s remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Error or Incompatibility explanation
            val failureReason = item.verification?.failureReason ?: item.downloadProgress?.errorMessage
            if (failureReason != null && !item.isReadyForOfflineUse && (item.status == ModelStatus.ERROR || item.status == ModelStatus.INCOMPATIBLE)) {
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
                horizontalArrangement = Arrangement.End,
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
                        Text("Delete Model")
                    }
                } else if (!info.isSourceConfigured) {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Source Not Configured")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading && !isVerifying,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("download_model_${info.id}")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isDownloading) "Downloading..." else "Download")
                    }
                }
            }
        }
    }
}
