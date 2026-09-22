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

    // Model 3: MMS Bangla Neural Voice (naklitechie/mms-tts-bn-ONNX)
    val MMS_BANGLA_TTS = ModelInfo(
        id = "mms_tts_bn",
        name = "MMS Bangla Voice (ONNX)",
        version = "1.0.0",
        sourceLanguage = "bn",
        targetLanguage = null,
        type = ModelType.TTS,
        downloadUrl = "https://huggingface.co/naklitechie/mms-tts-bn-ONNX/resolve/main/model.onnx",
        sizeBytes = 114_314_259L, // ~114 MB
        sha256 = "476fbc3fe414637ea21d82d2e5ebc743a486fa64c490203f2bf8adbefbf714a7",
        format = ModelFormat.ONNX,
        archiveName = "model.onnx",
        minimumRamMb = 384,
        minimumStorageMb = 200,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "input_ids",
            outputTensorName = "waveform",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            sampleRate = 16000,
            vocabFileName = "vocab.json",
            decoderType = "MMS_VITS"
        ),
        description = "Meta MMS VITS neural text-to-speech model for Bengali (ONNX, ~114 MB) by naklitechie. Supports realistic Man and Woman voices.",
        licenseSource = "naklitechie / Meta MMS (CC-BY-NC-4.0)",
        runtimeRequirements = "ONNX Runtime Mobile (MMS VITS End-to-End, 16000Hz)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "vocab.json",
                downloadUrl = "https://huggingface.co/naklitechie/mms-tts-bn-ONNX/raw/main/vocab.json",
                expectedSizeBytes = 927L,
                sha256 = ""
            ),
            ModelAuxiliaryFile(
                fileName = "config.json",
                downloadUrl = "https://huggingface.co/naklitechie/mms-tts-bn-ONNX/raw/main/config.json",
                expectedSizeBytes = 1630L,
                sha256 = ""
            )
        )
    )

    // Active default Bangla voice TTS model (MMS VITS)
    val BANGLA_VOICE_TTS = MMS_BANGLA_TTS

    // Required models for the current English -> Bangla Dubbing pipeline
    val REQUIRED_MODELS: List<ModelInfo>
        get() = listOf(
            ENGLISH_ASR,
            ENGLISH_TO_BANGLA_TRANSLATION,
            MMS_BANGLA_TTS
        )

    val ALL_MODELS: List<ModelInfo>
        get() = listOf(
            ENGLISH_ASR,
            ENGLISH_TO_BANGLA_TRANSLATION,
            MMS_BANGLA_TTS
        )

    fun configureTranslationReleaseUrl(url: String) {
        // No-op: Google ML Kit manages translation model downloads directly via on-device SDK
    }
}
