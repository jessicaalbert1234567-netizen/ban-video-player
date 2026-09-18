package com.example.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int = 0,
    val speedKbps: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L,
    val status: ModelStatus = ModelStatus.NOT_INSTALLED,
    val errorMessage: String? = null
)

class ModelDownloader(private val context: Context) {

    private val TAG = "ModelDownloader"

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadProgressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, DownloadProgress>> = _downloadProgressMap.asStateFlow()

    private val activeCancellations = mutableSetOf<String>()

    fun cancelDownload(modelId: String) {
        synchronized(activeCancellations) {
            activeCancellations.add(modelId)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun downloadAndInstall(
        model: ModelInfo,
        onProgressUpdate: ((DownloadProgress) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        synchronized(activeCancellations) {
            activeCancellations.remove(model.id)
        }

        val destDir = ModelInstaller.getModelDirectory(context, model)
        if (!destDir.exists()) destDir.mkdirs()
        val tempFile = File(destDir, "${model.archiveName}.download")
        val finalFile = ModelInstaller.getInstalledModelFile(context, model)

        // 1. Check if model source is configured
        if (!model.isSourceConfigured) {
            val errorMsg = "Model source not configured: No verified download URL is available."
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    status = ModelStatus.NOT_CONFIGURED,
                    errorMessage = errorMsg
                ),
                onProgressUpdate
            )
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        // 2. Check real internet connectivity
        if (!isNetworkAvailable()) {
            val errorMsg = "No internet connection. Connect to the internet to download this model."
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    status = ModelStatus.ERROR,
                    errorMessage = errorMsg
                ),
                onProgressUpdate
            )
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        // 3. Storage check
        val availableStorageBytes = destDir.usableSpace
        val requiredBytes = (model.sizeBytes * 1.5).toLong()
        if (availableStorageBytes < requiredBytes && model.sizeBytes > 0) {
            val errorMsg = "Insufficient storage space. Available: ${availableStorageBytes / (1024 * 1024)} MB, Required: ${requiredBytes / (1024 * 1024)} MB"
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    status = ModelStatus.ERROR,
                    errorMessage = errorMsg
                ),
                onProgressUpdate
            )
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        // Translation package archive handling
        if (model.type == ModelType.TRANSLATION || model.archiveName.endsWith(".zip") || model.format == ModelFormat.BINARY_ARCHIVE) {
            val archiveExpectedSize = if (model.archiveSizeBytes > 0L) model.archiveSizeBytes else model.sizeBytes
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    totalBytes = archiveExpectedSize,
                    status = ModelStatus.DOWNLOADING
                ),
                onProgressUpdate
            )

            try {
                // Step 1: Download package archive with live progress
                downloadFileWithProgress(
                    url = model.downloadUrl,
                    destinationTempFile = tempFile,
                    expectedSizeBytes = archiveExpectedSize,
                    modelId = model.id,
                    onProgressUpdate = onProgressUpdate
                )

                // Step 2: Verification of archive
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        progressPercent = 95,
                        status = ModelStatus.VERIFYING
                    ),
                    onProgressUpdate
                )

                if (tempFile.length() <= 0) {
                    tempFile.delete()
                    throw IllegalStateException("Downloaded translation archive is empty (0 bytes).")
                }

                // Check zip SHA-256 if configured
                if (model.sha256.isNotBlank()) {
                    val archiveSha = ModelInstaller.calculateSha256(tempFile)
                    if (!archiveSha.equals(model.sha256, ignoreCase = true)) {
                        tempFile.delete()
                        throw IllegalStateException("Package SHA-256 checksum mismatch! Expected: ${model.sha256.take(8)}..., got: ${archiveSha.take(8)}...")
                    }
                }

                // Step 3: Extract archive safely into staging folder
                val stagingDir = File(destDir, "staging_${System.currentTimeMillis()}")
                stagingDir.mkdirs()
                try {
                    ModelInstaller.extractZipSafely(tempFile, stagingDir)

                    // Step 4: Verify all required files against manifest
                    val requiredFiles = listOf(
                        "encoder_model.onnx",
                        "decoder_model.onnx",
                        "decoder_with_past_model.onnx",
                        "source.spm",
                        "target.spm",
                        "vocab.json",
                        "source_pieces.json",
                        "model_manifest.json"
                    )

                    for (rf in requiredFiles) {
                        val f = File(stagingDir, rf)
                        if (!f.exists() || f.length() == 0L) {
                            throw IllegalStateException("Missing required model file after extraction: $rf")
                        }
                    }

                    // Move extracted files into destDir
                    stagingDir.listFiles()?.forEach { extractedFile ->
                        val targetFile = File(destDir, extractedFile.name)
                        extractedFile.copyTo(targetFile, overwrite = true)
                    }
                } finally {
                    stagingDir.deleteRecursively()
                    tempFile.delete()
                }

                // Step 5: Test complete ONNX models and translation pipeline
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        progressPercent = 98,
                        status = ModelStatus.VERIFYING
                    ),
                    onProgressUpdate
                )

                val testPipeline = ModelInstaller.testTranslationPipeline(context)
                if (testPipeline.isFailure) {
                    finalFile.delete()
                    val err = testPipeline.exceptionOrNull()?.message ?: "Translation inference test failed"
                    throw IllegalStateException("Post-installation translation test failed: $err")
                }

                // Step 6: Final offline verification
                val verification = ModelInstaller.verifyModelOffline(context, model)
                if (!verification.isReadyForOfflineUse) {
                    finalFile.delete()
                    throw IllegalStateException("Model verification failed: ${verification.failureReason}")
                }

                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 100,
                        status = ModelStatus.READY
                    ),
                    onProgressUpdate
                )

                return@withContext Result.success(finalFile)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading/installing translation package ${model.name}", e)
                if (tempFile.exists()) tempFile.delete()
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        status = ModelStatus.ERROR,
                        errorMessage = e.message ?: "Download failed"
                    ),
                    onProgressUpdate
                )
                return@withContext Result.failure(e)
            }
        }

        updateState(
            DownloadProgress(
                modelId = model.id,
                totalBytes = model.sizeBytes,
                status = ModelStatus.DOWNLOADING
            ),
            onProgressUpdate
        )

        try {
            // Download main model binary
            downloadFileWithProgress(
                url = model.downloadUrl,
                destinationTempFile = tempFile,
                expectedSizeBytes = model.sizeBytes,
                modelId = model.id,
                onProgressUpdate = onProgressUpdate
            )

            // Stage 2: Verifying
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    progressPercent = 99,
                    status = ModelStatus.VERIFYING
                ),
                onProgressUpdate
            )

            if (tempFile.length() <= 0) {
                tempFile.delete()
                throw IllegalStateException("Downloaded model file is empty (0 bytes).")
            }

            // Test ONNX load directly on tempFile
            val onnxInit = ModelInstaller.testOnnxInitialization(tempFile)
            if (onnxInit.isFailure) {
                val err = onnxInit.exceptionOrNull()?.localizedMessage ?: "Invalid ONNX model structure"
                tempFile.delete()
                throw IllegalStateException("Model verification failed: $err")
            }

            // Stage 3: Download auxiliary files (tokens, phoneme mappings, etc.)
            for (aux in model.auxiliaryFiles) {
                val auxDestFile = ModelInstaller.getAuxiliaryFile(context, model, aux.fileName)
                val auxTemp = File(destDir, "${aux.fileName}.download")
                try {
                    downloadFileWithProgress(
                        url = aux.downloadUrl,
                        destinationTempFile = auxTemp,
                        expectedSizeBytes = aux.expectedSizeBytes,
                        modelId = model.id,
                        onProgressUpdate = null
                    )
                    if (auxTemp.exists() && auxTemp.length() > 0) {
                        auxTemp.copyTo(auxDestFile, overwrite = true)
                        auxTemp.delete()
                    } else {
                        throw IllegalStateException("Failed to download companion file: ${aux.fileName}")
                    }
                } catch (e: Exception) {
                    if (auxTemp.exists()) auxTemp.delete()
                    throw IllegalStateException("Failed to download required companion file '${aux.fileName}': ${e.message}")
                }
            }

            // Stage 4: Atomic install
            val installed = ModelInstaller.installModelAtomically(tempFile, finalFile)
            if (!installed) {
                tempFile.delete()
                throw IllegalStateException("Failed to atomically install model file to destination.")
            }

            // Stage 5: Final offline verification
            val verification = ModelInstaller.verifyModelOffline(context, model)
            if (!verification.isReadyForOfflineUse) {
                finalFile.delete()
                throw IllegalStateException("Model verification failed: ${verification.failureReason}")
            }

            updateState(
                DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    progressPercent = 100,
                    status = ModelStatus.READY
                ),
                onProgressUpdate
            )

            Result.success(finalFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model ${model.name}", e)
            if (tempFile.exists()) tempFile.delete()
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    status = ModelStatus.ERROR,
                    errorMessage = e.message ?: "Download failed"
                ),
                onProgressUpdate
            )
            Result.failure(e)
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        destinationTempFile: File,
        expectedSizeBytes: Long,
        modelId: String,
        onProgressUpdate: ((DownloadProgress) -> Unit)?
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OfflineAIDubbingPlayer/1.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Server returned HTTP ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw IllegalStateException("Received empty response body from server.")
        val contentLength = if (body.contentLength() > 0) body.contentLength() else expectedSizeBytes

        body.byteStream().use { input ->
            FileOutputStream(destinationTempFile).use { output ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalDownloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled(modelId)) {
                        destinationTempFile.delete()
                        throw IllegalStateException("Download cancelled by user.")
                    }

                    output.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 500) {
                        val timeDiffSec = (now - lastTime) / 1000.0
                        val bytesDiff = totalDownloaded - lastBytes
                        val speedKbps = if (timeDiffSec > 0) ((bytesDiff / 1024) / timeDiffSec).toLong() else 0L
                        val percent = if (contentLength > 0) ((totalDownloaded * 100) / contentLength).toInt().coerceIn(0, 99) else 50
                        val remainingBytes = (contentLength - totalDownloaded).coerceAtLeast(0L)
                        val estRemainingSec = if (speedKbps > 0) remainingBytes / (speedKbps * 1024) else 0L

                        updateState(
                            DownloadProgress(
                                modelId = modelId,
                                downloadedBytes = totalDownloaded,
                                totalBytes = contentLength,
                                progressPercent = percent,
                                speedKbps = speedKbps,
                                estimatedRemainingSeconds = estRemainingSec,
                                status = ModelStatus.DOWNLOADING
                            ),
                            onProgressUpdate
                        )
                        lastTime = now
                        lastBytes = totalDownloaded
                    }
                }
                output.flush()
            }
        }
    }

    private fun isCancelled(modelId: String): Boolean {
        synchronized(activeCancellations) {
            return activeCancellations.contains(modelId)
        }
    }

    private fun updateState(progress: DownloadProgress, callback: ((DownloadProgress) -> Unit)? = null) {
        val current = _downloadProgressMap.value.toMutableMap()
        current[progress.modelId] = progress
        _downloadProgressMap.value = current
        callback?.invoke(progress)
    }
}

