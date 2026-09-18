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
        return File(dir, model.archiveName)
    }

    fun getAuxiliaryFile(context: Context, model: ModelInfo, fileName: String): File {
        val dir = getModelDirectory(context, model)
        return File(dir, fileName)
    }

    fun isModelInstalled(context: Context, model: ModelInfo): Boolean {
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

    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isEmpty() || expectedSha256.equals("NONE", ignoreCase = true)) {
            return true
        }
        val calculated = calculateSha256(file)
        Log.d(TAG, "Verifying checksum: calculated=$calculated expected=$expectedSha256")
        return calculated.equals(expectedSha256, ignoreCase = true)
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

        val expectedArchiveSize = if (model.archiveSizeBytes > 0L) model.archiveSizeBytes else model.sizeBytes

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

        if (expectedArchiveSize > 0 && fileSize < (expectedArchiveSize * 0.9).toLong()) {
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
                failureReason = "Model file size ($fileSize bytes) is smaller than expected ($expectedArchiveSize bytes)."
            )
        }

        // SHA-256 calculation
        val calculatedSha256 = calculateSha256(file)
        val sha256Matches = if (model.sha256.isNotBlank()) {
            calculatedSha256.equals(model.sha256, ignoreCase = true)
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
                failureReason = "SHA-256 checksum mismatch (expected: ${model.sha256.take(8)}..., got: ${calculatedSha256.take(8)}...)."
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

        // For translation model: also verify decoder ONNX initialization
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
                        failureReason = "Translation model failed to initialize."
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

