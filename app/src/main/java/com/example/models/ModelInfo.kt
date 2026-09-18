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

enum class ModelStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    ERROR
}

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
    val targetLanguage: String?,
    val type: ModelType,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: ModelFormat,
    val archiveName: String,
    val minimumRamMb: Int,
    val minimumStorageMb: Int,
    val onnxMetadata: OnnxMetadata,
    val description: String
)
