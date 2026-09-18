package com.example.models

enum class ModelType(val displayName: String) {
    ASR("Speech Recognition"),
    TRANSLATION("Translation"),
    TTS("Voice Synthesis")
}

enum class ModelFormat {
    ONNX,
    PYTORCH_TFLITE,
    BINARY_ARCHIVE
}

enum class ModelStatus(val label: String) {
    NOT_DOWNLOADED("Not Downloaded"),
    DOWNLOADING("Downloading..."),
    VERIFYING("Verifying..."),
    INSTALLING("Installing..."),
    READY("Installed & Ready"),
    ERROR("Error"),

    // Compatibility aliases
    NOT_INSTALLED("Not Downloaded"),
    NOT_CONFIGURED("Model source not configured"),
    NOT_AVAILABLE("Not available"),
    INSTALLED("Installed & Ready"),
    INCOMPATIBLE("Incompatible");

    val isReady: Boolean get() = this == READY
}

data class ModelAuxiliaryFile(
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long = 0L,
    val sha256: String = ""
)

data class ModelVerificationResult(
    val modelId: String,
    val isFilePresent: Boolean,
    val fileSizeBytes: Long,
    val expectedSizeBytes: Long,
    val sha256Calculated: String?,
    val sha256Matches: Boolean,
    val onnxLoadSuccess: Boolean,
    val onnxInputInfo: String?,
    val onnxOutputInfo: String?,
    val auxiliaryFilesPresent: Boolean,
    val auxiliaryFilesDetails: String?,
    val isReadyForOfflineUse: Boolean,
    val failureReason: String?,
    val httpStatusCode: Int = 0,
    val contentType: String? = null,
    val downloadedFilename: String? = null,
    val fileSignatureHex: String? = null,
    val detectedFormat: String? = null,
    val extractionError: String? = null,
    val manifestError: String? = null,
    val diagnosticDetails: String? = null
)

data class OnnxMetadata(
    val inputTensorName: String,
    val outputTensorName: String,
    val inputShape: List<Long>,
    val dataType: String = "FLOAT32",
    val sampleRate: Int = 16000,
    val blankTokenId: Int = 0,
    val vocabFileName: String = "vocab.txt",
    val decoderType: String = "CTC_GREEDY"
)

data class ModelInfo(
    val id: String,
    val name: String,
    val version: String,
    val sourceLanguage: String,
    val targetLanguage: String? = null,
    val type: ModelType,
    val downloadUrl: String,
    val sizeBytes: Long,
    val archiveSizeBytes: Long = 0L,
    val sha256: String,
    val format: ModelFormat,
    val archiveName: String,
    val minimumRamMb: Int,
    val minimumStorageMb: Int,
    val onnxMetadata: OnnxMetadata,
    val description: String,
    val licenseSource: String = "Open Source",
    val runtimeRequirements: String = "ONNX Runtime Mobile >= 1.19",
    val auxiliaryFiles: List<ModelAuxiliaryFile> = emptyList()
) {
    val isSourceConfigured: Boolean
        get() = downloadUrl.isNotBlank() && downloadUrl.startsWith("https://")
}

