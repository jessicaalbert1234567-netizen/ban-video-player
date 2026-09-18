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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.DubbingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestPipelineScreen(
    viewModel: DubbingViewModel,
    onNavigateBack: () -> Unit
) {
    val logs by viewModel.testLogs.collectAsState()
    val isRunning by viewModel.isTestingRunning.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stage Verification Test", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("test_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearTestLogs() }) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear Logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Independent Model & Stage Diagnostic Tests",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Test Buttons Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runStageTest("ASR") },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_asr_button")
                    ) {
                        Text("Test ASR")
                    }
                    Button(
                        onClick = { viewModel.runStageTest("TRANSLATION") },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_trans_button")
                    ) {
                        Text("Test Trans")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runStageTest("TTS") },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_tts_button")
                    ) {
                        Text("Test TTS")
                    }
                    Button(
                        onClick = { viewModel.runStageTest("AUDIO_SYNC") },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_sync_button")
                    ) {
                        Text("Test Sync")
                    }
                }
            }

            if (isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Diagnostic Log Output Console
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E17)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "Press any test button above to verify model adapters, tensor inputs, vocabulary decoders, and audio pipelines.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        items(logs) { line ->
                            Text(
                                text = line,
                                color = when {
                                    line.contains("✓") -> Color(0xFF10B981)
                                    line.contains("❌") -> Color(0xFFEF4444)
                                    line.contains("---") -> MaterialTheme.colorScheme.primary
                                    else -> Color(0xFFCBD5E1)
                                },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
