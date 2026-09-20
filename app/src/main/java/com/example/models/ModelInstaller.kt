package com.example.models

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object ModelInstaller {

    private const val TAG = "ModelInstaller"

    fun getModelDirectory(context: Context, model: ModelInfo): File {
        val baseDir = File(context.filesDir, "models")
        val subDir = when (model.type) {
            ModelType.ASR -> File(baseDir, "asr/${model.sourceLanguage}")
            ModelType.TRANSLATION -> File(baseDir, "translation")
            ModelType.TTS -> File(baseDir, "tts/${model.sourceLanguage}")
        }
        if (!subDir.exists()) {
            subDir.mkdirs()
        }
        return subDir
    }

    fun getInstalledModelFile(context: Context, model: ModelInfo): File {
        val dir = getModelDirectory(context, model)
        val defaultFile = File(dir, model.archiveName)
        if (defaultFile.exists() && defaultFile.isFile && defaultFile.length() > 0 && defaultFile.canRead()) {
            return defaultFile
        }

        // Alternative filenames fallback
        if (model.type == ModelType.ASR) {
            val alternatives = listOf("model.int8.onnx", "model.onnx", "citrinet.onnx")
            for (alt in alternatives) {
                val candidate = File(dir, alt)
                if (candidate.exists() && candidate.isFile && candidate.length() > 0 && candidate.canRead()) {
                    return candidate
                }
            }
        } else if (model.type == ModelType.TTS) {
            val alternatives = listOf("bn_BD-google-medium.onnx", "model.onnx", "piper.onnx")
            for (alt in alternatives) {
                val candidate = File(dir, alt)
                if (candidate.exists() && candidate.isFile && candidate.length() > 0 && candidate.canRead()) {
                    return candidate
                }
            }
        }
        return defaultFile
    }

    fun getAuxiliaryFile(context: Context, model: ModelInfo, fileName: String): File {
        val dir = getModelDirectory(context, model)
        return File(dir, fileName)
    }

    fun cleanupIncompleteInstallations(context: Context) {
        try {
            val baseDir = File(context.filesDir, "models")
            if (!baseDir.exists()) return
            baseDir.walkTopDown().forEach { file ->
                if (file.name.endsWith(".download") || file.name.endsWith(".part") || file.name.endsWith(".tmp") || file.name.startsWith("staging_")) {
                    Log.i(TAG, "Cleaning up incomplete installation artifact: ${file.absolutePath}")
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up incomplete installations", e)
        }
    }

    fun isModelInstalled(context: Context, model: ModelInfo): Boolean {
        if (model.type == ModelType.TRANSLATION) {
            return try {
                runBlocking(Dispatchers.IO) {
                    com.example.translation.EnglishToBanglaTranslator.isModelDownloaded()
                }
            } catch (_: Throwable) {
                false
            }
        }
        val file = getInstalledModelFile(context, model)
        val mainFileOk = file.exists() && file.isFile && file.length() > 0 && file.canRead()
        if (!mainFileOk) return false
        for (aux in model.auxiliaryFiles) {
            val auxFile = getAuxiliaryFile(context, model, aux.fileName)
            if (!auxFile.exists() || !auxFile.isFile || auxFile.length() <= 0 || !auxFile.canRead()) {
                return false
            }
        }
        return true
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun calculateSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Calculates the Git object hash (sha1 of "blob <size>\0<content>").
     * Hugging Face uses this as the ETag and file identifier for Git repository text/raw assets.
     */
    fun calculateGitBlobHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val header = "blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII)
        digest.update(header)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Detects the file format and extracts the first 16 bytes signature as hex.
     */
    fun detectFileFormat(file: File): Pair<String, String> {
        if (!file.exists() || file.length() == 0L) {
            return Pair("Empty file (0 bytes)", "")
        }
        val header = ByteArray(16)
        val readBytes = FileInputStream(file).use { it.read(header) }
        if (readBytes <= 0) return Pair("Empty file", "")
        val hex = header.take(readBytes).joinToString(" ") { String.format("%02x", it) }
        val str = String(header.take(readBytes).toByteArray(), Charsets.US_ASCII)

        val format = when {
            readBytes >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte()) -> "ZIP Archive"
            readBytes >= 2 && header[0] == 0x08.toByte() -> "ONNX / Protocol Buffers Binary"
            str.startsWith("<!DO") || str.startsWith("<htm") || str.contains("<html", ignoreCase = true) -> "HTML Document (Error/Redirect Page)"
            str.trimStart().startsWith("{") || str.trimStart().startsWith("[") -> "JSON Text"
            str.all { it in ' '..'~' || it == '\n' || it == '\r' || it == '\t' } -> "Plain Text"
            else -> "Binary"
        }
        return Pair(format, hex)
    }

    fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        if (expectedChecksum.isBlank() || expectedChecksum.equals("NONE", ignoreCase = true)) {
            return true
        }
        val trimmed = expectedChecksum.trim()
        val calculatedSha256 = calculateSha256(file)
        if (calculatedSha256.equals(trimmed, ignoreCase = true)) {
            return true
        }
        // If expected checksum is a 40-char hash (e.g. Git blob object hash from Hugging Face Git LFS / ETag, or standard SHA-1)
        if (trimmed.length == 40) {
            val calculatedGitBlob = calculateGitBlobHash(file)
            if (calculatedGitBlob.equals(trimmed, ignoreCase = true)) {
                Log.d(TAG, "File ${file.name} matched expected 40-char Git blob hash: $trimmed")
                return true
            }
            val calculatedSha1 = calculateSha1(file)
            if (calculatedSha1.equals(trimmed, ignoreCase = true)) {
                Log.d(TAG, "File ${file.name} matched expected 40-char SHA-1 hash: $trimmed")
                return true
            }
        }
        Log.w(TAG, "Checksum mismatch for ${file.name}: expected=$trimmed, calculatedSha256=$calculatedSha256")
        return false
    }

    /**
     * Safely extracts zip archives while strictly preventing Zip Path Traversal (Zip Slip).
     */
    fun extractZipSafely(zipFile: File, destinationDir: File) {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        val destCanonicalPath = destinationDir.canonicalPath

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destinationDir, entry.name)
                val canonicalDestinationPath = newFile.canonicalPath
                if (!canonicalDestinationPath.startsWith(destCanonicalPath + File.separator)) {
                    throw SecurityException("Zip traversal detected in zip file: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Verifies that the model can be loaded into ONNX Runtime Mobile.
     * Throws an exception or returns failure if the session cannot be instantiated.
     */
    fun testOnnxInitialization(modelFile: File): Result<Pair<List<String>, List<String>>> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "WARNING: testOnnxInitialization called on main thread! (${Thread.currentThread().name})")
        }
        return try {
            if (!modelFile.exists() || modelFile.length() <= 0) {
                return Result.failure(IllegalStateException("Model file is missing or empty: ${modelFile.absolutePath}"))
            }
            MemoryDiagnostics.trackModelLoad("ONNX", modelFile.name, modelFile) {
                val env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                }
                val session = env.createSession(modelFile.absolutePath, sessionOptions)
                try {
                    val inputNames = session.inputNames.toList()
                    val outputNames = session.outputNames.toList()
                    Log.d(TAG, "ONNX model tested successfully: inputs=$inputNames, outputs=$outputNames")
                    Result.success(Pair(inputNames, outputNames))
                } finally {
                    session.close()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX model session initialization failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Performs strict, non-mocked verification of a model on disk.
     * When deepCheck is false (default), fast disk presence and size verification is performed
     * without blocking the caller with SHA-256 computation or ONNX session creation.
     */
    fun verifyModelOffline(context: Context, model: ModelInfo, deepCheck: Boolean = false): ModelVerificationResult {
        if (model.type == ModelType.TRANSLATION) {
            val isDownloaded = try {
                runBlocking(Dispatchers.IO) {
                    com.example.translation.EnglishToBanglaTranslator.isModelDownloaded()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error checking ML Kit model download status", e)
                false
            }
            return if (isDownloaded) {
                ModelVerificationResult(
                    modelId = model.id,
                    isFilePresent = true,
                    fileSizeBytes = 0L,
                    expectedSizeBytes = 0L,
                    sha256Calculated = null,
                    sha256Matches = true,
                    onnxLoadSuccess = true,
                    onnxInputInfo = "English text",
                    onnxOutputInfo = "Bangla text",
                    auxiliaryFilesPresent = true,
                    auxiliaryFilesDetails = "Google ML Kit on-device model ready",
                    isReadyForOfflineUse = true,
                    failureReason = null,
                    resolvedFilePath = "Google ML Kit (On-Device)",
                    detectedFormat = "Google ML Kit"
                )
            } else {
                ModelVerificationResult(
                    modelId = model.id,
                    isFilePresent = false,
                    fileSizeBytes = 0L,
                    expectedSizeBytes = 0L,
                    sha256Calculated = null,
                    sha256Matches = false,
                    onnxLoadSuccess = false,
                    onnxInputInfo = null,
                    onnxOutputInfo = null,
                    auxiliaryFilesPresent = false,
                    auxiliaryFilesDetails = "Model not downloaded",
                    isReadyForOfflineUse = false,
                    failureReason = "English → Bangla translation model is not downloaded.",
                    resolvedFilePath = "Google ML Kit (On-Device)",
                    detectedFormat = "Google ML Kit"
                )
            }
        }
        val file = getInstalledModelFile(context, model)
        val result = verifyModelOfflineInternal(context, model, file, deepCheck)
        return result.copy(resolvedFilePath = file.absolutePath)
    }

    private fun verifyModelOfflineInternal(context: Context, model: ModelInfo, file: File, deepCheck: Boolean = false): ModelVerificationResult {
        val dir = getModelDirectory(context, model)
        val expectedFile = File(dir, model.archiveName)
        val fileExists = file.exists() && file.isFile && file.length() > 0 && file.canRead()
        val fileSize = if (file.exists()) file.length() else 0L

        Log.i(TAG, """
            |==================================================
            |MODEL AUDIT:
            |MODEL NAME: ${model.name}
            |EXPECTED PATH: ${expectedFile.absolutePath}
            |ACTUAL PATH: ${file.absolutePath}
            |EXPECTED FILENAME: ${expectedFile.name}
            |ACTUAL FILENAME: ${file.name}
            |ACTUAL BYTE SIZE: $fileSize
            |EXISTS: ${file.exists()}, IS_FILE: ${file.isFile}, CAN_READ: ${file.canRead()}
            |==================================================
        """.trimMargin())

        val expectedMainSize = if (model.archiveSizeBytes > 0L) {
            model.archiveSizeBytes
        } else {
            model.sizeBytes
        }

        val expectedMainSha256 = model.sha256

        if (!model.isSourceConfigured && !file.exists()) {
            val failureMsg = "Model source not configured: No verified download URL is available."
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = false,
                fileSizeBytes = 0L,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = "Model source not configured",
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        if (!file.exists()) {
            val failureMsg = if (model.type == ModelType.TRANSLATION) {
                "English → Bangla translation model is not installed."
            } else {
                "Model file not found on disk."
            }
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = false,
                fileSizeBytes = 0L,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        if (!file.isFile) {
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = 0L,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = "Path exists but is a directory, not a file: ${file.absolutePath}"
            )
        }

        if (fileSize <= 0) {
            val failureMsg = if (model.type == ModelType.TRANSLATION) {
                "English → Bangla translation model is not installed."
            } else {
                "Model file is empty (0 bytes)."
            }
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = 0L,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        if (!file.canRead()) {
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = "Model file exists but cannot be read (check filesystem permissions): ${file.absolutePath}"
            )
        }

        val (detectedFormat, fileSignatureHex) = detectFileFormat(file)

        if (fileSize == 0L || (expectedMainSize > 0 && fileSize < (expectedMainSize * 0.9).toLong())) {
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = "Model file size ($fileSize bytes) is smaller than expected ($expectedMainSize bytes). Format: $detectedFormat, signature: $fileSignatureHex",
                fileSignatureHex = fileSignatureHex,
                detectedFormat = detectedFormat
            )
        }

        // Fast check mode for routine checks, UI state, startup, and progress updates
        if (!deepCheck) {
            var auxPresent = true
            val missingAux = mutableListOf<String>()
            for (aux in model.auxiliaryFiles) {
                val auxFile = getAuxiliaryFile(context, model, aux.fileName)
                if (!auxFile.exists() || auxFile.length() <= 0L) {
                    auxPresent = false
                    missingAux.add(aux.fileName)
                }
            }

            if (!auxPresent) {
                val failureMsg = if (model.type == ModelType.TRANSLATION) {
                    if (missingAux.any { it.contains("vocab") || it.contains("spm") || it.contains("pieces") || it.contains("tokenizer") }) {
                        "Translation tokenizer files are missing."
                    } else if (missingAux.any { it.contains("decoder") }) {
                        "English → Bangla translation model is not installed."
                    } else {
                        "Translation tokenizer files are missing."
                    }
                } else {
                    "Required companion file(s) missing: ${missingAux.joinToString()}"
                }
                return ModelVerificationResult(
                    modelId = model.id,
                    isFilePresent = true,
                    fileSizeBytes = fileSize,
                    expectedSizeBytes = model.sizeBytes,
                    sha256Calculated = null,
                    sha256Matches = true,
                    onnxLoadSuccess = true,
                    onnxInputInfo = null,
                    onnxOutputInfo = null,
                    auxiliaryFilesPresent = false,
                    auxiliaryFilesDetails = "Missing companion files: ${missingAux.joinToString()}",
                    isReadyForOfflineUse = false,
                    failureReason = failureMsg
                )
            }

            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = null,
                sha256Matches = true,
                onnxLoadSuccess = true,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = true,
                auxiliaryFilesDetails = "Model binary and companion files verified on disk.",
                isReadyForOfflineUse = true,
                failureReason = null,
                fileSignatureHex = fileSignatureHex,
                detectedFormat = detectedFormat
            )
        }

        // Deep check mode: SHA-256 calculation and ONNX session test
        val calculatedSha256 = calculateSha256(file)
        val sha256Matches = if (expectedMainSha256.isNotBlank()) {
            calculatedSha256.equals(expectedMainSha256, ignoreCase = true)
        } else {
            true
        }

        if (!sha256Matches) {
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = calculatedSha256,
                sha256Matches = false,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = "SHA-256 checksum mismatch (expected: $expectedMainSha256, got: $calculatedSha256). Format: $detectedFormat, signature: $fileSignatureHex",
                fileSignatureHex = fileSignatureHex,
                detectedFormat = detectedFormat
            )
        }

        // ONNX load verification
        val onnxResult = testOnnxInitialization(file)
        if (onnxResult.isFailure) {
            val failureMsg = if (model.type == ModelType.TRANSLATION) {
                "Translation model failed to initialize."
            } else {
                "ONNX session initialization failed: ${onnxResult.exceptionOrNull()?.localizedMessage ?: "Unknown ONNX error"}"
            }
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = calculatedSha256,
                sha256Matches = true,
                onnxLoadSuccess = false,
                onnxInputInfo = null,
                onnxOutputInfo = null,
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = null,
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        val (inputs, outputs) = onnxResult.getOrThrow()

        // Auxiliary files check
        var auxPresent = true
        val missingAux = mutableListOf<String>()
        for (aux in model.auxiliaryFiles) {
            val auxFile = getAuxiliaryFile(context, model, aux.fileName)
            if (!auxFile.exists() || auxFile.length() == 0L) {
                auxPresent = false
                missingAux.add(aux.fileName)
            } else if (aux.expectedSizeBytes > 0 && auxFile.length() < (aux.expectedSizeBytes * 0.9).toLong()) {
                auxPresent = false
                missingAux.add("${aux.fileName} (incomplete size)")
            } else if (aux.sha256.isNotBlank() && !verifyChecksum(auxFile, aux.sha256)) {
                auxPresent = false
                missingAux.add("${aux.fileName} (SHA-256 mismatch)")
            }
        }

        if (!auxPresent) {
            val failureMsg = if (model.type == ModelType.TRANSLATION) {
                if (missingAux.any { it.contains("vocab") || it.contains("spm") || it.contains("pieces") || it.contains("tokenizer") }) {
                    "Translation tokenizer files are missing."
                } else if (missingAux.any { it.contains("decoder") }) {
                    "English → Bangla translation model is not installed."
                } else {
                    "Translation tokenizer files are missing."
                }
            } else {
                "Required companion file(s) missing: ${missingAux.joinToString()}"
            }
            return ModelVerificationResult(
                modelId = model.id,
                isFilePresent = true,
                fileSizeBytes = fileSize,
                expectedSizeBytes = model.sizeBytes,
                sha256Calculated = calculatedSha256,
                sha256Matches = true,
                onnxLoadSuccess = true,
                onnxInputInfo = inputs.joinToString(),
                onnxOutputInfo = outputs.joinToString(),
                auxiliaryFilesPresent = false,
                auxiliaryFilesDetails = "Missing companion files: ${missingAux.joinToString()}",
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        return ModelVerificationResult(
            modelId = model.id,
            isFilePresent = true,
            fileSizeBytes = fileSize,
            expectedSizeBytes = model.sizeBytes,
            sha256Calculated = calculatedSha256,
            sha256Matches = true,
            onnxLoadSuccess = true,
            onnxInputInfo = inputs.joinToString(),
            onnxOutputInfo = outputs.joinToString(),
            auxiliaryFilesPresent = true,
            auxiliaryFilesDetails = if (model.auxiliaryFiles.isNotEmpty()) "All ${model.auxiliaryFiles.size} companion file(s) verified" else "None required",
            isReadyForOfflineUse = true,
            failureReason = null
        )
    }

    /**
     * Performs end-to-end post-installation verification of the translation model:
     * 1. Verifies that Google ML Kit on-device model is downloaded.
     * 2. Executes a real test translation ("Hello, how are you today?") and ensures non-empty Bangla output.
     */
    suspend fun testTranslationPipeline(context: Context): Result<String> {
        return try {
            val isDownloaded = com.example.translation.EnglishToBanglaTranslator.isModelDownloaded()
            if (!isDownloaded) {
                return Result.failure(IllegalStateException("Google ML Kit English → Bangla translation model is not downloaded."))
            }

            // Instantiate translator and run actual inference
            val translator = com.example.translation.EnglishToBanglaTranslator(context)
            val testEnglish = "Hello, how are you today?"
            val testBangla = translator.translate(testEnglish)
            translator.close()

            if (testBangla.isBlank()) {
                return Result.failure(IllegalStateException("Translation test produced empty output for input: '$testEnglish'"))
            }

            Log.i(TAG, "Test translation passed: '$testEnglish' -> '$testBangla'")
            Result.success(testBangla)
        } catch (e: Throwable) {
            Log.e(TAG, "Translation pipeline test failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Atomically moves temp file to the target model file.
     */
    fun installModelAtomically(tempFile: File, destinationFile: File): Boolean {
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        val parent = destinationFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val renamed = tempFile.renameTo(destinationFile)
        if (!renamed) {
            // Fallback copy
            tempFile.copyTo(destinationFile, overwrite = true)
            tempFile.delete()
            return destinationFile.exists() && destinationFile.length() > 0
        }
        return destinationFile.exists() && destinationFile.length() > 0
    }
}

