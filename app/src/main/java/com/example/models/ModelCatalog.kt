package com.example.models

object ModelCatalog {

    const val MODEL_URL_NOT_CONFIGURED = ""

    // Model 1: English Speech Recognition (OpenAI Whisper Tiny English - tiny.en ONNX Mobile)
    val ENGLISH_ASR = ModelInfo(
        id = "whisper_tiny_en",
        name = "English Speech Recognition (Whisper Tiny)",
        version = "1.0.0",
        sourceLanguage = "en",
        targetLanguage = null,
        type = ModelType.ASR,
        // Real HuggingFace Sherpa-ONNX Whisper Tiny English (tiny.en) INT8 model
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx",
        sizeBytes = 12_937_772L, // ~13 MB encoder
        sha256 = "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6",
        format = ModelFormat.ONNX,
        archiveName = "tiny.en-encoder.int8.onnx",
        minimumRamMb = 512,
        minimumStorageMb = 250,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "mel",
            outputTensorName = "n_layer_cross_k",
            inputShape = listOf(1L, 80L, 3000L),
            dataType = "FLOAT32",
            sampleRate = 16000,
            blankTokenId = 50256,
            vocabFileName = "tiny.en-tokens.txt",
            decoderType = "WHISPER_ENCODER_DECODER"
        ),
        description = "OpenAI Whisper Tiny English (tiny.en) quantized encoder-decoder model for high-accuracy on-device speech recognition.",
        licenseSource = "OpenAI / Sherpa-ONNX (MIT)",
        runtimeRequirements = "ONNX Runtime Mobile (16kHz mono PCM, 80-bin mel spectrogram, encoder + decoder + tokens.txt)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "tiny.en-decoder.int8.onnx",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx",
                expectedSizeBytes = 89_853_865L,
                sha256 = "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527"
            ),
            ModelAuxiliaryFile(
                fileName = "tiny.en-tokens.txt",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
                expectedSizeBytes = 835_554L,
                sha256 = "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"
            )
        )
    )

    // Model 2: English -> Bangla Google ML Kit Offline Translation
    val ENGLISH_TO_BANGLA_TRANSLATION = ModelInfo(
        id = "google_mlkit_en_bn",
        name = "English → Bangla",
        version = "Google ML Kit",
        sourceLanguage = "en",
        targetLanguage = "bn",
        type = ModelType.TRANSLATION,
        downloadUrl = "https://developers.google.com/ml-kit/language/translation",
        sizeBytes = 0L, // Google-managed offline language model
        archiveSizeBytes = 0L,
        sha256 = "",
        format = ModelFormat.BINARY_ARCHIVE,
        archiveName = "google_mlkit_en_bn",
        minimumRamMb = 256,
        minimumStorageMb = 50,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "",
            outputTensorName = "",
            inputShape = emptyList(),
            dataType = "TEXT",
            vocabFileName = "",
            decoderType = "MLKIT_TRANSLATE"
        ),
        description = "Google ML Kit Offline Translation (on-device neural translation).",
        licenseSource = "Google ML Kit On-Device Translation SDK",
        runtimeRequirements = "Google-managed offline language model",
        auxiliaryFiles = emptyList()
    )

    // Model 3: Bangla Voice / Speech Synthesis (Piper VITS ONNX)
    val BANGLA_VOICE_TTS = ModelInfo(
        id = "bn_voice_piper_onnx",
        name = "Bangla Voice (Piper VITS)",
        version = "1.0.0",
        sourceLanguage = "bn",
        targetLanguage = null,
        type = ModelType.TTS,
        // Real HuggingFace Piper Bengali (Google Medium) ONNX checkpoint
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/bn/bn_BD/google/medium/bn_BD-google-medium.onnx",
        sizeBytes = 76_782_515L, // ~76.8 MB
        sha256 = "f2e7518ed5534a755024a48c71b80bf617efaf12570bbdf3ce255a9526a8afd3",
        format = ModelFormat.ONNX,
        archiveName = "bn_BD-google-medium.onnx",
        minimumRamMb = 384,
        minimumStorageMb = 120,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "input",
            outputTensorName = "output",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            sampleRate = 22050,
            vocabFileName = "bn_BD-google-medium.onnx.json",
            decoderType = "VITS_SYNTHESIZER"
        ),
        description = "Real offline Bangla neural voice synthesizer trained with Piper VITS architecture.",
        licenseSource = "Rhasspy Piper / Google Bengali TTS (MIT)",
        runtimeRequirements = "ONNX Runtime Mobile (VITS 22050Hz, JSON phoneme config)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "bn_BD-google-medium.onnx.json",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/bn/bn_BD/google/medium/bn_BD-google-medium.onnx.json",
                expectedSizeBytes = 5494L,
                sha256 = "ee79e469edaed486747fb5f05067ed04f0d3d201"
            )
        )
    )

    // Required models for the current English -> Bangla Dubbing pipeline
    val REQUIRED_MODELS: List<ModelInfo>
        get() = listOf(
            ENGLISH_ASR,
            ENGLISH_TO_BANGLA_TRANSLATION,
            BANGLA_VOICE_TTS
        )

    fun configureTranslationReleaseUrl(url: String) {
        // No-op: Google ML Kit manages translation model downloads directly via on-device SDK
    }
}
