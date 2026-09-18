package com.example.models

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
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
            ModelType.TRANSLATION -> File(baseDir, "translation/${model.sourceLanguage}_${model.targetLanguage ?: "bn"}")
            ModelType.TTS -> File(baseDir, "tts/${model.sourceLanguage}")
        }
        if (!subDir.exists()) {
            subDir.mkdirs()
        }
        return subDir
    }

    fun getInstalledModelFile(context: Context, model: ModelInfo): File {
        val dir = getModelDirectory(context, model)
        val fileName = if (model.type == ModelType.TRANSLATION && model.archiveName.endsWith(".zip")) {
            "encoder_model.onnx"
        } else {
            model.archiveName
        }
        return File(dir, fileName)
    }

    fun getAuxiliaryFile(context: Context, model: ModelInfo, fileName: String): File {
        val dir = getModelDirectory(context, model)
        return File(dir, fileName)
    }

    fun isModelInstalled(context: Context, model: ModelInfo): Boolean {
        if (model.type == ModelType.TRANSLATION) {
            val dir = getModelDirectory(context, model)
            val encoder = File(dir, "encoder_model.onnx")
            val decoder = File(dir, "decoder_model.onnx")
            val decoderWithPast = File(dir, "decoder_with_past_model.onnx")
            val vocab = File(dir, "vocab.json")
            val sourcePieces = File(dir, "source_pieces.json")
            val sourceSpm = File(dir, "source.spm")
            val targetSpm = File(dir, "target.spm")
            val manifest = File(dir, "model_manifest.json")
            return encoder.exists() && encoder.length() > 0 &&
                   decoder.exists() && decoder.length() > 0 &&
                   decoderWithPast.exists() && decoderWithPast.length() > 0 &&
                   vocab.exists() && vocab.length() > 0 &&
                   (sourcePieces.exists() || sourceSpm.exists()) &&
                   manifest.exists() && manifest.length() > 0
        }
        val file = getInstalledModelFile(context, model)
        return file.exists() && file.length() > 0
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
        return try {
            if (!modelFile.exists() || modelFile.length() <= 0) {
                return Result.failure(IllegalStateException("Model file is missing or empty: ${modelFile.absolutePath}"))
            }
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            val inputNames = session.inputNames.toList()
            val outputNames = session.outputNames.toList()
            Log.d(TAG, "ONNX model tested successfully: inputs=$inputNames, outputs=$outputNames")
            session.close()
            Result.success(Pair(inputNames, outputNames))
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX model session initialization failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Performs strict, non-mocked verification of a model on disk.
     */
    fun verifyModelOffline(context: Context, model: ModelInfo): ModelVerificationResult {
        val file = getInstalledModelFile(context, model)
        val fileExists = file.exists()
        val fileSize = if (fileExists) file.length() else 0L

        val expectedMainSize = if (model.type == ModelType.TRANSLATION) {
            51_062_030L // encoder_model.onnx size
        } else if (model.archiveSizeBytes > 0L) {
            model.archiveSizeBytes
        } else {
            model.sizeBytes
        }

        val expectedMainSha256 = if (model.type == ModelType.TRANSLATION) {
            "ddb11a17b599458d736b4f1f65b8c69ea778e32348316705193c1c9226e2f2a8" // encoder_model.onnx SHA-256
        } else {
            model.sha256
        }

        if (!model.isSourceConfigured && !fileExists) {
            val failureMsg = if (model.type == ModelType.TRANSLATION) {
                "English → Bangla translation model is not installed. (Model source not configured)"
            } else {
                "Model source not configured: No verified download URL is available."
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
                auxiliaryFilesDetails = "Model source not configured",
                isReadyForOfflineUse = false,
                failureReason = failureMsg
            )
        }

        if (!fileExists) {
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

        // SHA-256 calculation
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

        // For translation model: also verify decoder and decoder_with_past ONNX initialization
        if (model.type == ModelType.TRANSLATION) {
            val decoderFile = getAuxiliaryFile(context, model, "decoder_model.onnx")
            if (decoderFile.exists()) {
                val decResult = testOnnxInitialization(decoderFile)
                if (decResult.isFailure) {
                    return ModelVerificationResult(
                        modelId = model.id,
                        isFilePresent = true,
                        fileSizeBytes = fileSize,
                        expectedSizeBytes = model.sizeBytes,
                        sha256Calculated = calculatedSha256,
                        sha256Matches = true,
                        onnxLoadSuccess = false,
                        onnxInputInfo = inputs.joinToString(),
                        onnxOutputInfo = outputs.joinToString(),
                        auxiliaryFilesPresent = true,
                        auxiliaryFilesDetails = "Decoder ONNX session failed",
                        isReadyForOfflineUse = false,
                        failureReason = "Translation model decoder failed to initialize."
                    )
                }
            }

            val decoderPastFile = getAuxiliaryFile(context, model, "decoder_with_past_model.onnx")
            if (decoderPastFile.exists()) {
                val decPastResult = testOnnxInitialization(decoderPastFile)
                if (decPastResult.isFailure) {
                    return ModelVerificationResult(
                        modelId = model.id,
                        isFilePresent = true,
                        fileSizeBytes = fileSize,
                        expectedSizeBytes = model.sizeBytes,
                        sha256Calculated = calculatedSha256,
                        sha256Matches = true,
                        onnxLoadSuccess = false,
                        onnxInputInfo = inputs.joinToString(),
                        onnxOutputInfo = outputs.joinToString(),
                        auxiliaryFilesPresent = true,
                        auxiliaryFilesDetails = "Decoder with past ONNX session failed",
                        isReadyForOfflineUse = false,
                        failureReason = "Translation model decoder with past failed to initialize."
                    )
                }
            }
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
     * 1. Verifies that encoder, decoder, and decoder_with_past ONNX sessions initialize.
     * 2. Verifies that tokenizer files load correctly.
     * 3. Executes a real test translation ("Hello, how are you today?") and ensures non-empty Bangla output.
     */
    suspend fun testTranslationPipeline(context: Context): Result<String> {
        return try {
            val modelDir = getModelDirectory(context, ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION)
            val encoderFile = File(modelDir, "encoder_model.onnx")
            val decoderFile = File(modelDir, "decoder_model.onnx")
            val decoderWithPastFile = File(modelDir, "decoder_with_past_model.onnx")

            if (!encoderFile.exists() || !decoderFile.exists() || !decoderWithPastFile.exists()) {
                return Result.failure(IllegalStateException("One or more required ONNX model files are missing from $modelDir"))
            }

            // Test ONNX session creations
            val encInit = testOnnxInitialization(encoderFile)
            if (encInit.isFailure) {
                return Result.failure(IllegalStateException("Encoder ONNX failed to initialize: ${encInit.exceptionOrNull()?.message}"))
            }
            val decInit = testOnnxInitialization(decoderFile)
            if (decInit.isFailure) {
                return Result.failure(IllegalStateException("Decoder ONNX failed to initialize: ${decInit.exceptionOrNull()?.message}"))
            }
            val decPastInit = testOnnxInitialization(decoderWithPastFile)
            if (decPastInit.isFailure) {
                return Result.failure(IllegalStateException("Decoder-with-past ONNX failed to initialize: ${decPastInit.exceptionOrNull()?.message}"))
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

