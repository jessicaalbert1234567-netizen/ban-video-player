package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.settings.ProcessingMode
import com.example.settings.VoiceGender
import com.example.ui.DubbingViewModel
import com.example.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DubbingViewModel,
    onNavigateBack: () -> Unit
) {
    val processingMode by viewModel.processingMode.collectAsState()
    val voiceGender by viewModel.voiceGender.collectAsState()
    val modelsState by viewModel.modelsState.collectAsState()
    var storageBreakdown by remember { mutableStateOf(viewModel.getStorageBreakdown()) }
    var clearedBytesMsg by remember { mutableStateOf<String?>(null) }

    val modelsMb = storageBreakdown.first / (1024 * 1024)
    val dubsMb = storageBreakdown.second / (1024 * 1024)
    val tempMb = storageBreakdown.third / (1024 * 1024)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Storage", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Bangla Dubbing Voice Section
            item {
                Text(
                    text = "Bangla Dubbing Voice (ভয়েস নির্বাচন)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Select Bhashini neural voice for natural pronunciation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(VoiceGender.entries) { gender ->
                val targetModel = if (gender == VoiceGender.FEMALE) com.example.models.ModelCatalog.BHASHINI_BANGLA_FEMALE_TTS else com.example.models.ModelCatalog.BHASHINI_BANGLA_MALE_TTS
                val modelItem = modelsState.firstOrNull { it.info.id == targetModel.id }
                val isInstalled = modelItem?.isReadyForOfflineUse == true
                val isDownloading = modelItem?.downloadProgress?.status == com.example.models.ModelStatus.DOWNLOADING ||
                        modelItem?.downloadProgress?.status == com.example.models.ModelStatus.INSTALLING ||
                        modelItem?.downloadProgress?.status == com.example.models.ModelStatus.VERIFYING
                val progressPercent = modelItem?.downloadProgress?.progressPercent ?: 0
                val progressStatus = modelItem?.downloadProgress?.verificationStatus

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (voiceGender == gender) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = voiceGender == gender,
                                onClick = { viewModel.setVoiceGender(gender) },
                                modifier = Modifier.testTag("voice_${gender.name}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gender.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = gender.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isInstalled) {
                                Surface(
                                    color = SuccessGreen.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "✓ Ready",
                                        color = SuccessGreen,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = progressStatus ?: "Downloading: $progressPercent%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (!isInstalled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Not downloaded (~123 MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = { viewModel.downloadModel(targetModel) },
                                    modifier = Modifier.testTag("download_voice_${gender.name}")
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download Voice", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            // Processing Mode Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Processing Mode (RAM & Speed)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(ProcessingMode.entries) { mode ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (processingMode == mode) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = processingMode == mode,
                            onClick = { viewModel.setProcessingMode(mode) },
                            modifier = Modifier.testTag("mode_${mode.name}")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Storage Breakdown Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Storage Management",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("AI Models Directory", style = MaterialTheme.typography.bodyMedium)
                            Text("$modelsMb MB", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dubbed Video Projects", style = MaterialTheme.typography.bodyMedium)
                            Text("$dubsMb MB", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Temporary Working Files", style = MaterialTheme.typography.bodyMedium)
                            Text("$tempMb MB", fontWeight = FontWeight.Bold)
                        }

                        if (clearedBytesMsg != null) {
                            Text(
                                text = clearedBytesMsg!!,
                                color = SuccessGreen,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        OutlinedButton(
                            onClick = {
                                val freed = viewModel.clearTemporaryFiles()
                                storageBreakdown = viewModel.getStorageBreakdown()
                                clearedBytesMsg = "Freed ${freed / (1024 * 1024)} MB of temporary files."
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear_temp_files_button")
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clear Temporary Files")
                        }
                    }
                }
            }

            // Privacy Guarantee Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Zero-Cloud Privacy Guarantee",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PrivacyItem("100% Offline Processing", "No internet needed once models are downloaded.")
                        PrivacyItem("No Accounts or Login", "No profile, no email, no sign-up.")
                        PrivacyItem("No Cloud Uploads", "Your videos never leave this device.")
                        PrivacyItem("No Analytics or Tracking", "Zero telemetry, zero trackers, zero ad SDKs.")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyItem(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
