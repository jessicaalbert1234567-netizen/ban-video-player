package com.example.update

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import kotlinx.coroutines.launch

@Composable
fun MandatoryUpdateScreen(
    updateManager: AppUpdateManager,
    uiState: UpdateUiState
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // Strictly intercept and block hardware Back button during mandatory update
    BackHandler(enabled = true) {
        // Back press deliberately intercepted and blocked
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .testTag("mandatory_update_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // App Logo with Neem Leaf
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nimpata_leaf),
                        contentDescription = "Neem Leaf Logo",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(54.dp)
                    )
                }

                // Header Title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "বাধ্যতামূলক অ্যাপ আপডেট",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Nimpata Bangla Dubbing Player",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Dynamic State Content
                when (uiState) {
                    is UpdateUiState.Checking -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(42.dp),
                                strokeWidth = 3.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "সর্বশেষ ভার্সন পরীক্ষা করা হচ্ছে...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is UpdateUiState.UpdateRequired -> {
                        UpdateRequiredContent(
                            versionInfo = uiState.versionInfo,
                            onUpdateClick = {
                                scope.launch {
                                    updateManager.downloadAndVerifyApk(uiState.versionInfo)
                                }
                            }
                        )
                    }

                    is UpdateUiState.Downloading -> {
                        DownloadingContent(
                            versionInfo = uiState.versionInfo,
                            progressPercent = uiState.progressPercent,
                            bytesDownloaded = uiState.bytesDownloaded,
                            totalBytes = uiState.totalBytes
                        )
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        ReadyToInstallContent(
                            versionInfo = uiState.versionInfo,
                            onInstallClick = {
                                if (activity != null) {
                                    updateManager.installApk(activity, uiState.apkFile)
                                }
                            }
                        )
                    }

                    is UpdateUiState.Error -> {
                        ErrorContent(
                            errorMessage = uiState.errorMessage,
                            canRetry = uiState.canRetry,
                            onRetryClick = {
                                scope.launch {
                                    updateManager.checkForUpdates(forceCheck = true)
                                }
                            }
                        )
                    }

                    else -> {
                        // Idle or up-to-date transition
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRequiredContent(
    versionInfo: VersionInfo,
    onUpdateClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Version Comparison Chip
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "বর্তমান ভার্সন",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "নতুন ভার্সন",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "v${versionInfo.versionName} (${versionInfo.versionCode})",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // Server Message / Changelog
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "আপডেটের বিবরণ",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Text(
                    text = versionInfo.message.ifBlank { "অ্যাপটি নির্বিঘ্নে ও সুরক্ষিতভাবে ব্যবহার করতে অনুগ্রহ করে সর্বশেষ ভার্সনটি এখনই ইনস্টল করুন।" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Security Notice
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "অফিসিয়াল GitHub Release ও SHA-256 সুরক্ষিত",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Update Now Button
        Button(
            onClick = onUpdateClick,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("update_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "এখনই আপডেট করুন (Update Now)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun DownloadingContent(
    versionInfo: VersionInfo,
    progressPercent: Int,
    bytesDownloaded: Long,
    totalBytes: Long
) {
    val downloadedMb = bytesDownloaded / (1024.0 * 1024.0)
    val totalMb = totalBytes / (1024.0 * 1024.0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "নতুন APK ডাউনলোড হচ্ছে...",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        LinearProgressIndicator(
            progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$progressPercent%",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (totalBytes > 0) {
                    "${String.format("%.1f", downloadedMb)} MB / ${String.format("%.1f", totalMb)} MB"
                } else {
                    "${String.format("%.1f", downloadedMb)} MB"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "ডাউনলোড শেষে স্বয়ংক্রিয়ভাবে SHA-256 অখণ্ডতা যাচাই করা হবে। অনুগ্রহ করে অপেক্ষা করুন...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadyToInstallContent(
    versionInfo: VersionInfo,
    onInstallClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Text(
            text = "ডাউনলোড ও নিরাপত্তা যাচাই সম্পন্ন!",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "নতুন APK ফাইলটির SHA-256 ডিজিটাল হ্যাশ সফলভাবে যাচাই করা হয়েছে। নিচের বোতামে চাপ দিয়ে ইনস্টল সম্পন্ন করুন।",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = onInstallClick,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("install_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
        ) {
            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ইনস্টল সম্পন্ন করুন (Install APK)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }

        // Package conflict guidance note
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "প্যাকেজ দ্বন্দ্ব (Package Conflict) দেখা দিলে:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Text(
                    text = "পূর্বে টেস্ট/ডিবাগ ভার্সন ইনস্টল থাকলে অ্যান্ড্রয়েডের নিরাপত্তা নিয়মে সরাসরি আপডেট নেয় না। ফোনের হোম থেকে পুরোনো অ্যাপটি একবার আনইনস্টল করে এই নতুন অফিসিয়াল রিলিজটি ইনস্টল করুন।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    errorMessage: String,
    canRetry: Boolean,
    onRetryClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Text(
            text = "আপডেট পরীক্ষা ব্যর্থ হয়েছে",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.error
        )

        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (canRetry) {
            Button(
                onClick = onRetryClick,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("retry_update_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("পুনরায় চেষ্টা করুন (Retry)")
            }
        }
    }
}
