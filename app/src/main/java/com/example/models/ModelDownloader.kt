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
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val verificationStatus: String? = null,
    val errorMessage: String? = null,
    val diagnosticDetails: String? = null
)

class ModelDownloader(private val context: Context) {

    private val TAG = "ModelDownloader"

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
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
            val archiveExpectedSize = if (model.archiveSizeBytes > 0L) model.archiveSizeBytes else 157_681_950L
            val expectedArchiveSha256 = if (model.sha256.isNotBlank()) model.sha256 else "ff8b97888c8413c4ba2509e39a50234d040c6b894ab0a005f39bbd47595c0e27"

            updateState(
                DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = 0L,
                    totalBytes = archiveExpectedSize,
                    progressPercent = 0,
                    status = ModelStatus.DOWNLOADING,
                    verificationStatus = "Starting download of translation package..."
                ),
                onProgressUpdate
            )

            try {
                // Step 0: Download package archive with live progress
                downloadFileWithProgress(
                    url = model.downloadUrl,
                    destinationTempFile = tempFile,
                    expectedSizeBytes = archiveExpectedSize,
                    modelId = model.id,
                    onProgressUpdate = onProgressUpdate
                )

                if (!tempFile.exists() || tempFile.length() <= 0) {
                    if (tempFile.exists()) tempFile.delete()
                    throw IllegalStateException("Downloaded translation archive is empty (0 bytes).")
                }

                // Step 1: Verify archive SHA-256
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = tempFile.length(),
                        totalBytes = tempFile.length(),
                        progressPercent = 91,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 1/8: Verifying archive SHA-256 checksum..."
                    ),
                    onProgressUpdate
                )

                val actualArchiveSize = tempFile.length()
                if (actualArchiveSize != 157681950L && actualArchiveSize != archiveExpectedSize) {
                    Log.w(TAG, "Archive size ($actualArchiveSize bytes) differs from expected ($archiveExpectedSize bytes)")
                }

                val calculatedArchiveSha = ModelInstaller.calculateSha256(tempFile)
                if (!calculatedArchiveSha.equals(expectedArchiveSha256, ignoreCase = true)) {
                    tempFile.delete()
                    throw IllegalStateException("Archive SHA-256 mismatch! Expected: ${expectedArchiveSha256.take(12)}..., got: ${calculatedArchiveSha.take(12)}...")
                }
                Log.i(TAG, "Step 1 PASSED: Archive SHA-256 verified ($calculatedArchiveSha).")

                // Step 2: Extract atomically into a clean staging folder
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = tempFile.length(),
                        totalBytes = tempFile.length(),
                        progressPercent = 93,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 2/8: Extracting model archive atomically..."
                    ),
                    onProgressUpdate
                )

                val stagingDir = File(destDir, "staging_${System.currentTimeMillis()}")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                try {
                    ModelInstaller.extractZipSafely(tempFile, stagingDir)
                    Log.i(TAG, "Step 2 PASSED: Extracted archive atomically into staging directory.")

                    // Step 3: Verify all files using model_manifest.json
                    updateState(
                        DownloadProgress(
                            modelId = model.id,
                            downloadedBytes = tempFile.length(),
                            totalBytes = tempFile.length(),
                            progressPercent = 95,
                            status = ModelStatus.VERIFYING,
                            verificationStatus = "Step 3/8: Verifying all files using model_manifest.json..."
                        ),
                        onProgressUpdate
                    )

                    val manifestFile = File(stagingDir, "model_manifest.json")
                    if (!manifestFile.exists() || manifestFile.length() == 0L) {
                        throw IllegalStateException("Extracted package is missing model_manifest.json")
                    }

                    val manifestJson = org.json.JSONObject(manifestFile.readText())
                    if (manifestJson.has("files")) {
                        val filesArray = manifestJson.getJSONArray("files")
                        for (i in 0 until filesArray.length()) {
                            val fObj = filesArray.getJSONObject(i)
                            val fName = fObj.getString("name")
                            val fExpectedSize = fObj.optLong("sizeBytes", 0L)
                            val fExpectedSha = fObj.optString("sha256", "")

                            val f = File(stagingDir, fName)
                            if (!f.exists()) {
                                throw IllegalStateException("Manifest file '$fName' is missing in extracted package.")
                            }
                            if (fExpectedSize > 0 && f.length() != fExpectedSize) {
                                throw IllegalStateException("Manifest file '$fName' size mismatch: expected $fExpectedSize bytes, got ${f.length()} bytes")
                            }
                            if (fExpectedSha.isNotBlank()) {
                                val actualSha = ModelInstaller.calculateSha256(f)
                                if (!actualSha.equals(fExpectedSha, ignoreCase = true)) {
                                    throw IllegalStateException("Manifest file '$fName' SHA-256 mismatch: expected $fExpectedSha, got $actualSha")
                                }
                            }
                        }
                    }
                    Log.i(TAG, "Step 3 PASSED: All files in model_manifest.json verified successfully.")

                    // Step 4: Verify core required files explicitly:
                    // encoder_model.onnx, decoder_model.onnx, decoder_with_past_model.onnx,
                    // source.spm, target.spm, vocab.json, source_pieces.json, model_manifest.json
                    updateState(
                        DownloadProgress(
                            modelId = model.id,
                            downloadedBytes = tempFile.length(),
                            totalBytes = tempFile.length(),
                            progressPercent = 96,
                            status = ModelStatus.VERIFYING,
                            verificationStatus = "Step 4/8: Verifying core neural models & tokenizers..."
                        ),
                        onProgressUpdate
                    )

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
                            throw IllegalStateException("Required translation model file missing or empty: $rf")
                        }
                    }
                    Log.i(TAG, "Step 4 PASSED: All 8 required files verified.")

                    // Atomically move/copy all verified files from stagingDir to destDir
                    stagingDir.listFiles()?.forEach { extractedFile ->
                        val targetFile = File(destDir, extractedFile.name)
                        extractedFile.copyTo(targetFile, overwrite = true)
                    }
                } finally {
                    stagingDir.deleteRecursively()
                    tempFile.delete()
                }

                // Step 5: Load the tokenizer
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 97,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 5/8: Loading SentencePiece tokenizer..."
                    ),
                    onProgressUpdate
                )

                val vocabFile = File(destDir, "vocab.json")
                val piecesFile = File(destDir, "source_pieces.json")
                val spmFile = File(destDir, "source.spm")
                if (!vocabFile.exists() || (!piecesFile.exists() && !spmFile.exists())) {
                    throw IllegalStateException("Translation tokenizer files are missing.")
                }
                Log.i(TAG, "Step 5 PASSED: Tokenizer verified.")

                // Step 6: Load the MarianMT ONNX sessions
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 98,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 6/8: Loading MarianMT ONNX sessions..."
                    ),
                    onProgressUpdate
                )

                val encFile = File(destDir, "encoder_model.onnx")
                val decFile = File(destDir, "decoder_model.onnx")
                val decPastFile = File(destDir, "decoder_with_past_model.onnx")

                val encCheck = ModelInstaller.testOnnxInitialization(encFile)
                if (encCheck.isFailure) {
                    throw IllegalStateException("Encoder ONNX session failed: ${encCheck.exceptionOrNull()?.message}")
                }
                val decCheck = ModelInstaller.testOnnxInitialization(decFile)
                if (decCheck.isFailure) {
                    throw IllegalStateException("Decoder ONNX session failed: ${decCheck.exceptionOrNull()?.message}")
                }
                val decPastCheck = ModelInstaller.testOnnxInitialization(decPastFile)
                if (decPastCheck.isFailure) {
                    throw IllegalStateException("Decoder-with-past ONNX session failed: ${decPastCheck.exceptionOrNull()?.message}")
                }
                Log.i(TAG, "Step 6 PASSED: MarianMT ONNX sessions initialized.")

                // Step 7: Run a real offline English → Bangla translation test
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 99,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 7/8: Running offline English → Bangla translation test..."
                    ),
                    onProgressUpdate
                )

                val testPipelineResult = ModelInstaller.testTranslationPipeline(context)
                if (testPipelineResult.isFailure) {
                    val err = testPipelineResult.exceptionOrNull()?.message ?: "Translation test inference failed"
                    throw IllegalStateException("Step 7 translation test failed: $err")
                }
                Log.i(TAG, "Step 7 PASSED: Real offline translation test succeeded: '${testPipelineResult.getOrNull()}'")

                // Step 8: Only then mark the model as READY/Installed
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 100,
                        status = ModelStatus.READY,
                        verificationStatus = "Step 8/8: Model verified and ready for offline use."
                    ),
                    onProgressUpdate
                )

                Log.i(TAG, "Step 8 PASSED: English → Bangla translation model marked READY.")
                return@withContext Result.success(finalFile)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading/installing translation package ${model.name}", e)
                if (tempFile.exists()) tempFile.delete()
                val errMsg = e.message ?: "Download failed"
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        status = ModelStatus.ERROR,
                        errorMessage = errMsg,
                        diagnosticDetails = errMsg
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
                    progressPercent = 95,
                    status = ModelStatus.VERIFYING,
                    verificationStatus = "Verifying downloaded model artifact..."
                ),
                onProgressUpdate
            )

            if (!tempFile.exists() || tempFile.length() <= 0) {
                if (tempFile.exists()) tempFile.delete()
                throw IllegalStateException("Downloaded model file is empty (0 bytes).")
            }

            val actualSize = tempFile.length()
            val actualSha256 = ModelInstaller.calculateSha256(tempFile)
            val (detectedFormat, fileSigHex) = ModelInstaller.detectFileFormat(tempFile)

            if (detectedFormat.contains("HTML", ignoreCase = true)) {
                tempFile.delete()
                throw IllegalStateException(
                    "Verification failed: Downloaded file is an HTML error/redirect document, not a model binary.\n" +
                            "• File size: $actualSize bytes\n" +
                            "• Header: $fileSigHex\n" +
                            "• URL: ${model.downloadUrl}"
                )
            }

            if (model.sha256.isNotBlank() && !ModelInstaller.verifyChecksum(tempFile, model.sha256)) {
                tempFile.delete()
                throw IllegalStateException(
                    "Verification failed: SHA-256 mismatch for ${model.name}.\n" +
                            "• Expected SHA-256: ${model.sha256}\n" +
                            "• Actual SHA-256:   $actualSha256\n" +
                            "• Expected size:    ${model.sizeBytes} bytes\n" +
                            "• Actual size:      $actualSize bytes\n" +
                            "• Detected format:  $detectedFormat ($fileSigHex)"
                )
            }

            // Test ONNX load directly on tempFile
            val onnxInit = ModelInstaller.testOnnxInitialization(tempFile)
            if (onnxInit.isFailure) {
                val err = onnxInit.exceptionOrNull()?.localizedMessage ?: "Invalid ONNX model structure"
                tempFile.delete()
                throw IllegalStateException("ONNX model initialization failed: $err")
            }

            // Stage 3: Installing auxiliary files (tokens, phoneme mappings, etc.)
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    progressPercent = 98,
                    status = ModelStatus.INSTALLING,
                    verificationStatus = "Installing companion files..."
                ),
                onProgressUpdate
            )

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
                        val auxSize = auxTemp.length()
                        val auxSha256 = ModelInstaller.calculateSha256(auxTemp)
                        val auxGitBlob = ModelInstaller.calculateGitBlobHash(auxTemp)
                        val (auxFormat, auxSig) = ModelInstaller.detectFileFormat(auxTemp)

                        if (aux.sha256.isNotBlank() && !ModelInstaller.verifyChecksum(auxTemp, aux.sha256)) {
                            auxTemp.delete()
                            throw IllegalStateException(
                                "Verification failed for companion file '${aux.fileName}':\n" +
                                        "• Expected hash: ${aux.sha256}\n" +
                                        "• Actual SHA-256: $auxSha256\n" +
                                        "• Actual Git blob: $auxGitBlob\n" +
                                        "• Expected size: ${aux.expectedSizeBytes} bytes\n" +
                                        "• Actual size: $auxSize bytes\n" +
                                        "• Format: $auxFormat ($auxSig)"
                            )
                        }

                        auxTemp.copyTo(auxDestFile, overwrite = true)
                        auxTemp.delete()
                    } else {
                        throw IllegalStateException("Failed to download companion file: ${aux.fileName}")
                    }
                } catch (e: Exception) {
                    if (auxTemp.exists()) auxTemp.delete()
                    throw IllegalStateException("Failed companion file '${aux.fileName}': ${e.message}")
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
                throw IllegalStateException("Offline verification failed: ${verification.failureReason}")
            }

            updateState(
                DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    progressPercent = 100,
                    status = ModelStatus.READY,
                    verificationStatus = "Ready for offline use."
                ),
                onProgressUpdate
            )

            Result.success(finalFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model ${model.name}", e)
            if (tempFile.exists()) tempFile.delete()
            val errMsg = e.message ?: "Download failed"
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    status = ModelStatus.ERROR,
                    errorMessage = errMsg,
                    diagnosticDetails = errMsg
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
        if (url.startsWith("file://") || url.startsWith("/")) {
            val localPath = url.removePrefix("file://")
            val localFile = File(localPath)
            if (!localFile.exists()) {
                throw IllegalStateException("Local model package file not found: $localPath")
            }
            val contentLength = localFile.length()
            localFile.inputStream().use { input ->
                FileOutputStream(destinationTempFile).use { output ->
                    val buffer = ByteArray(65536)
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
                        if (now - lastTime >= 250) {
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
                                    status = ModelStatus.DOWNLOADING,
                                    verificationStatus = "Reading package (${totalDownloaded / (1024 * 1024)} MB / ${contentLength / (1024 * 1024)} MB)..."
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
            return
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OfflineAIDubbingPlayer/1.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val finalUrl = response.request.url.toString()
        val code = response.code
        val contentType = response.header("Content-Type") ?: "unknown"
        val serverContentLength = response.body?.contentLength() ?: -1L

        if (!response.isSuccessful) {
            val errorBodyPreview = try { response.body?.string()?.take(500) } catch (e: Exception) { null }
            val diag = StringBuilder()
            diag.append("HTTP $code: ${response.message}\n")
            diag.append("• URL: $url\n")
            if (finalUrl != url) {
                diag.append("• Final URL after redirect: $finalUrl\n")
            }
            diag.append("• Content-Type: $contentType\n")
            diag.append("• Content-Length: $serverContentLength bytes\n")
            if (code == 404) {
                if (url.contains("github.com") && url.contains("releases/download")) {
                    diag.append("• Diagnostic: GitHub Release asset is unavailable.")
                } else {
                    diag.append("• Diagnostic: Resource not found on server.")
                }
            }
            if (!errorBodyPreview.isNullOrBlank()) {
                diag.append("\n• Server response preview: ${errorBodyPreview.trim()}")
            }
            throw IllegalStateException(diag.toString().trim())
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
                    if (now - lastTime >= 250) {
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
                                status = ModelStatus.DOWNLOADING,
                                verificationStatus = "Downloading (${totalDownloaded / (1024 * 1024)} MB / ${contentLength / (1024 * 1024)} MB)..."
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

