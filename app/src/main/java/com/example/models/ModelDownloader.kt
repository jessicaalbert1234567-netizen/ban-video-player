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

            val baseModelsDir = File(context.filesDir, "models")
            if (!baseModelsDir.exists()) baseModelsDir.mkdirs()

            val partFile = File(baseModelsDir, "translation_en_bn_v1.0.zip.part")
            val finalZipFile = File(baseModelsDir, "translation_en_bn_v1.0.zip")
            val tmpExtractDir = File(baseModelsDir, "translation_en_bn_v1.0.tmp")
            val finalExtractDir = File(baseModelsDir, "translation_en_bn_v1.0")

            // Clean up any stale partial files
            if (partFile.exists()) partFile.delete()
            if (tmpExtractDir.exists()) tmpExtractDir.deleteRecursively()

            updateState(
                DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = 0L,
                    totalBytes = archiveExpectedSize,
                    progressPercent = 0,
                    status = ModelStatus.DOWNLOADING,
                    verificationStatus = "Starting download to translation_en_bn_v1.0.zip.part..."
                ),
                onProgressUpdate
            )

            try {
                // Step 0: Download package archive to translation_en_bn_v1.0.zip.part
                downloadFileWithProgress(
                    url = model.downloadUrl,
                    destinationTempFile = partFile,
                    expectedSizeBytes = archiveExpectedSize,
                    modelId = model.id,
                    onProgressUpdate = onProgressUpdate
                )

                if (!partFile.exists() || partFile.length() <= 0) {
                    if (partFile.exists()) partFile.delete()
                    throw IllegalStateException("Downloaded translation archive is empty (0 bytes).")
                }

                // Step 1: Verify downloaded byte size
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = partFile.length(),
                        totalBytes = partFile.length(),
                        progressPercent = 90,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 1/8: Verifying downloaded byte size..."
                    ),
                    onProgressUpdate
                )
                val actualArchiveSize = partFile.length()
                if (actualArchiveSize != 157681950L && actualArchiveSize != archiveExpectedSize) {
                    Log.w(TAG, "Archive size ($actualArchiveSize bytes) differs from expected ($archiveExpectedSize bytes)")
                }

                // Step 2: Verify SHA-256
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = partFile.length(),
                        totalBytes = partFile.length(),
                        progressPercent = 92,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 2/8: Verifying archive SHA-256 checksum..."
                    ),
                    onProgressUpdate
                )
                val calculatedArchiveSha = ModelInstaller.calculateSha256(partFile)
                if (!calculatedArchiveSha.equals(expectedArchiveSha256, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException("Archive SHA-256 mismatch! Expected: ${expectedArchiveSha256.take(12)}..., got: ${calculatedArchiveSha.take(12)}...")
                }
                Log.i(TAG, "Step 2 PASSED: Archive SHA-256 verified ($calculatedArchiveSha).")

                // Step 3: Rename to final ZIP
                if (finalZipFile.exists()) finalZipFile.delete()
                val renameSuccess = partFile.renameTo(finalZipFile)
                val activeZip = if (renameSuccess) finalZipFile else partFile

                // Step 4: Extract into temporary directory translation_en_bn_v1.0.tmp/
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = activeZip.length(),
                        totalBytes = activeZip.length(),
                        progressPercent = 94,
                        status = ModelStatus.EXTRACTING,
                        verificationStatus = "Step 3/8: Extracting into translation_en_bn_v1.0.tmp..."
                    ),
                    onProgressUpdate
                )
                tmpExtractDir.mkdirs()
                ModelInstaller.extractZipSafely(activeZip, tmpExtractDir)
                Log.i(TAG, "Step 3 PASSED: Extracted archive into temporary directory.")

                // Step 5: Verify every required file in tmpExtractDir
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = activeZip.length(),
                        totalBytes = activeZip.length(),
                        progressPercent = 95,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 4/8: Verifying all 8 required translation files..."
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
                    val f = File(tmpExtractDir, rf)
                    if (!f.exists() || !f.isFile || f.length() == 0L || !f.canRead()) {
                        throw IllegalStateException("Required translation model file missing, empty or unreadable: $rf")
                    }
                }
                Log.i(TAG, "Step 4 PASSED: All 8 required files verified.")

                // Step 6: Verify model_manifest.json
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = activeZip.length(),
                        totalBytes = activeZip.length(),
                        progressPercent = 96,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 5/8: Verifying model_manifest.json..."
                    ),
                    onProgressUpdate
                )
                val manifestFile = File(tmpExtractDir, "model_manifest.json")
                val manifestJson = org.json.JSONObject(manifestFile.readText())
                if (manifestJson.has("files")) {
                    val filesArray = manifestJson.getJSONArray("files")
                    for (i in 0 until filesArray.length()) {
                        val fObj = filesArray.getJSONObject(i)
                        val fName = fObj.getString("name")
                        val fExpectedSize = fObj.optLong("sizeBytes", 0L)
                        val fExpectedSha = fObj.optString("sha256", "")

                        val f = File(tmpExtractDir, fName)
                        if (!f.exists() || !f.isFile) {
                            throw IllegalStateException("Manifest file '$fName' is missing in extracted package.")
                        }
                        if (fExpectedSize > 0 && f.length() != fExpectedSize) {
                            throw IllegalStateException("Manifest file '$fName' size mismatch: expected $fExpectedSize bytes, got ${f.length()} bytes")
                        }
                        // Step 7: Verify individual files where hashes are available
                        if (fExpectedSha.isNotBlank()) {
                            val actualSha = ModelInstaller.calculateSha256(f)
                            if (!actualSha.equals(fExpectedSha, ignoreCase = true)) {
                                throw IllegalStateException("Manifest file '$fName' SHA-256 mismatch: expected $fExpectedSha, got $actualSha")
                            }
                        }
                    }
                }
                Log.i(TAG, "Step 5 PASSED: Manifest verified.")

                // Step 8: Only then rename translation_en_bn_v1.0.tmp/ to translation_en_bn_v1.0/
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = activeZip.length(),
                        totalBytes = activeZip.length(),
                        progressPercent = 97,
                        status = ModelStatus.INSTALLING,
                        verificationStatus = "Step 6/8: Installing directory atomically..."
                    ),
                    onProgressUpdate
                )
                if (finalExtractDir.exists()) {
                    finalExtractDir.deleteRecursively()
                }
                val dirRenamed = tmpExtractDir.renameTo(finalExtractDir)
                if (!dirRenamed) {
                    finalExtractDir.mkdirs()
                    tmpExtractDir.copyRecursively(finalExtractDir, overwrite = true)
                    tmpExtractDir.deleteRecursively()
                }

                // Also populate destDir (translation/en_bn) to guarantee both paths work
                if (destDir.absolutePath != finalExtractDir.absolutePath) {
                    finalExtractDir.copyRecursively(destDir, overwrite = true)
                }

                // Clean up zip files
                if (finalZipFile.exists()) finalZipFile.delete()
                if (partFile.exists()) partFile.delete()

                // Step 9: Verify ONNX sessions
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 98,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 7/8: Loading MarianMT ONNX sessions..."
                    ),
                    onProgressUpdate
                )
                val encFile = File(finalExtractDir, "encoder_model.onnx")
                val decFile = File(finalExtractDir, "decoder_model.onnx")
                val decPastFile = File(finalExtractDir, "decoder_with_past_model.onnx")

                val encCheck = ModelInstaller.testOnnxInitialization(encFile)
                if (encCheck.isFailure) throw IllegalStateException("Encoder ONNX session failed: ${encCheck.exceptionOrNull()?.message}")
                val decCheck = ModelInstaller.testOnnxInitialization(decFile)
                if (decCheck.isFailure) throw IllegalStateException("Decoder ONNX session failed: ${decCheck.exceptionOrNull()?.message}")
                val decPastCheck = ModelInstaller.testOnnxInitialization(decPastFile)
                if (decPastCheck.isFailure) throw IllegalStateException("Decoder-with-past ONNX session failed: ${decPastCheck.exceptionOrNull()?.message}")
                Log.i(TAG, "Step 7 PASSED: MarianMT ONNX sessions initialized.")

                // Step 10: Run test translation
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 99,
                        status = ModelStatus.VERIFYING,
                        verificationStatus = "Step 8/8: Running offline English → Bangla translation test..."
                    ),
                    onProgressUpdate
                )
                val testPipelineResult = ModelInstaller.testTranslationPipeline(context)
                if (testPipelineResult.isFailure) {
                    val err = testPipelineResult.exceptionOrNull()?.message ?: "Translation test inference failed"
                    throw IllegalStateException("Step 8 translation test failed: $err")
                }
                Log.i(TAG, "Step 8 PASSED: Real offline translation test succeeded: '${testPipelineResult.getOrNull()}'")

                // Step 11: Mark ready
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = model.sizeBytes,
                        totalBytes = model.sizeBytes,
                        progressPercent = 100,
                        status = ModelStatus.READY,
                        verificationStatus = "Installed & verified successfully"
                    ),
                    onProgressUpdate
                )
                Log.i(TAG, "English → Bangla translation model marked READY.")
                return@withContext Result.success(encFile)
            } catch (e: Throwable) {
                // If any step fails: delete incomplete temporary directory and part file. Never report READY.
                if (tmpExtractDir.exists()) tmpExtractDir.deleteRecursively()
                if (partFile.exists()) partFile.delete()
                val errMsg = e.message ?: "Failed to install translation package"
                Log.e(TAG, "Translation package installation failed: $errMsg", e)
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

    data class HttpProbeResult(
        val statusCode: Int,
        val statusMessage: String,
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long,
        val isAvailable: Boolean,
        val errorMessage: String? = null
    )

    fun probeUrl(url: String): HttpProbeResult {
        if (url.isBlank() || url.startsWith("unconfigured://")) {
            return HttpProbeResult(
                statusCode = 0,
                statusMessage = "Unconfigured",
                finalUrl = url,
                contentType = null,
                contentLength = 0L,
                isAvailable = false,
                errorMessage = "Model source URL is not configured."
            )
        }
        if (url.startsWith("file://") || url.startsWith("/")) {
            val f = File(url.removePrefix("file://"))
            val available = f.exists() && f.isFile && f.length() > 0
            return HttpProbeResult(
                statusCode = if (available) 200 else 404,
                statusMessage = if (available) "OK (Local File)" else "Local File Not Found",
                finalUrl = url,
                contentType = "application/octet-stream",
                contentLength = if (available) f.length() else 0L,
                isAvailable = available,
                errorMessage = if (available) null else "Local file not found at ${f.absolutePath}"
            )
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "OfflineAIDubbingPlayer/1.0")
                .build()

            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                // If HEAD fails, try GET with Range
                val getReq = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "OfflineAIDubbingPlayer/1.0")
                    .build()
                okHttpClient.newCall(getReq).execute()
            }

            val finalUrl = response.request.url.toString()
            val code = response.code
            val message = response.message
            val contentType = response.header("Content-Type")
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: response.body?.contentLength() ?: -1L
            val isSuccess = response.isSuccessful || code == 206
            val errorMsg = if (!isSuccess) {
                if (code == 404) "HTTP 404: Not Found on remote server" else "HTTP $code: $message"
            } else null

            Log.i(TAG, """
                |==================================================
                |HTTP PROBE RESULT:
                |URL: $url
                |HTTP STATUS: $code $message
                |FINAL URL AFTER REDIRECT: $finalUrl
                |Content-Type: ${contentType ?: "unknown"}
                |Content-Length: $contentLength
                |Downloaded byte count: 0
                |Exception/error message: ${errorMsg ?: "None"}
                |==================================================
            """.trimMargin())

            response.close()

            HttpProbeResult(
                statusCode = code,
                statusMessage = message,
                finalUrl = finalUrl,
                contentType = contentType,
                contentLength = contentLength,
                isAvailable = isSuccess,
                errorMessage = errorMsg
            )
        } catch (e: Exception) {
            Log.e(TAG, """
                |==================================================
                |HTTP PROBE FAILED:
                |URL: $url
                |HTTP STATUS: 0 Exception
                |FINAL URL AFTER REDIRECT: $url
                |Content-Type: unknown
                |Content-Length: -1
                |Downloaded byte count: 0
                |Exception/error message: ${e.message}
                |==================================================
            """.trimMargin(), e)
            HttpProbeResult(
                statusCode = 0,
                statusMessage = "Network Exception",
                finalUrl = url,
                contentType = null,
                contentLength = -1L,
                isAvailable = false,
                errorMessage = e.message ?: "Network probe failed"
            )
        }
    }

    private fun updateState(progress: DownloadProgress, callback: ((DownloadProgress) -> Unit)? = null) {
        val current = _downloadProgressMap.value.toMutableMap()
        current[progress.modelId] = progress
        _downloadProgressMap.value = current
        callback?.invoke(progress)
    }
}

