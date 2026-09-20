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

        // Google ML Kit Translation handling
        if (model.type == ModelType.TRANSLATION) {
            updateState(
                DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    progressPercent = 0,
                    status = ModelStatus.DOWNLOADING,
                    verificationStatus = "Preparing..."
                ),
                onProgressUpdate
            )

            try {
                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        progressPercent = 0,
                        status = ModelStatus.DOWNLOADING,
                        verificationStatus = "Downloading Google ML Kit model..."
                    ),
                    onProgressUpdate
                )

                val translator = com.example.translation.EnglishToBanglaTranslator(context)
                translator.downloadModel(requireWifi = false)

                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        progressPercent = 100,
                        status = ModelStatus.INSTALLING,
                        verificationStatus = "Installing on-device..."
                    ),
                    onProgressUpdate
                )

                val isDownloaded = com.example.translation.EnglishToBanglaTranslator.isModelDownloaded()
                translator.close()

                if (!isDownloaded) {
                    throw IllegalStateException("Google ML Kit translation model was not saved to on-device storage.")
                }

                updateState(
                    DownloadProgress(
                        modelId = model.id,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        progressPercent = 100,
                        status = ModelStatus.READY_ON_DISK,
                        verificationStatus = "Offline Ready"
                    ),
                    onProgressUpdate
                )

                val targetFile = File(context.filesDir, "models/mlkit_en_bn")
                return@withContext Result.success(targetFile)
            } catch (e: Throwable) {
                val errMsg = e.message ?: "Failed to download Google ML Kit translation model"
                Log.e(TAG, "Google ML Kit translation download failed: $errMsg", e)
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

            // Stage 5: Final offline verification (fast check, binary was already tested in stage 2)
            val verification = ModelInstaller.verifyModelOffline(context, model, deepCheck = false)
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
                    status = ModelStatus.READY_ON_DISK,
                    verificationStatus = "Ready on disk for offline use."
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

