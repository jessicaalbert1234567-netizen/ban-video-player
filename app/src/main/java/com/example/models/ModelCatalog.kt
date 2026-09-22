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

    // Model 3: Bhashini Bangla Neural Voice (Female - ONNX)
    val BHASHINI_BANGLA_FEMALE_TTS = ModelInfo(
        id = "bhashini_bn_female",
        name = "Bhashini Bangla Voice (Female - ONNX)",
        version = "1.1.0",
        sourceLanguage = "bn",
        targetLanguage = null,
        type = ModelType.TTS,
        // High-fidelity Bhashini FastSpeech2-HS + HiFi-GAN ONNX models
        downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Female/bengali_encoder_female.onnx",
        sizeBytes = 85_162_896L, // ~85.2 MB Encoder
        sha256 = "39f65c0ecac51a4efefa87cc709c764fa8254ada1001183b7a1273ff1f332c53",
        format = ModelFormat.ONNX,
        archiveName = "bengali_encoder_female.onnx",
        minimumRamMb = 384,
        minimumStorageMb = 250,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "text_ids",
            outputTensorName = "waveform",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            sampleRate = 22050,
            vocabFileName = "config.yaml",
            decoderType = "BHASHINI_FS2_HIFIGAN"
        ),
        description = "Bhashini IIT-Madras FastSpeech2-HS + HiFi-GAN ONNX neural model for sweet, clear, and natural Bengali female voice synthesis.",
        licenseSource = "Bhashini / IIT Madras (MIT)",
        runtimeRequirements = "ONNX Runtime Mobile (FastSpeech2-HS Encoder, Decoder + HiFi-GAN 22050Hz)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "bengali_decoder_female.onnx",
                downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Female/bengali_decoder_female.onnx",
                expectedSizeBytes = 78_776_078L,
                sha256 = "c03733606fe0697e964e1f29eb963fa607667f8437bcef2157a09236ce452654"
            ),
            ModelAuxiliaryFile(
                fileName = "hifigan_female.onnx",
                downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Female/hifigan_female.onnx",
                expectedSizeBytes = 55_736_915L,
                sha256 = "77807e3cc42da7ecd5a98293f552148350faa430acec669cb5c1af100a72d4db"
            ),
            ModelAuxiliaryFile(
                fileName = "feats_stats.npz",
                downloadUrl = "https://raw.githubusercontent.com/Henil21/Bhashini-TTS/main/Bengali/Bengali_Female/feats_stats.npz",
                expectedSizeBytes = 1402L,
                sha256 = "6299d70bc5b2a185c786cb678d8e526bf41464a32bee9ccf85d7aef27205224b"
            ),
            ModelAuxiliaryFile(
                fileName = "config.yaml",
                downloadUrl = "https://raw.githubusercontent.com/Henil21/Bhashini-TTS/main/Bengali/Bengali_Female/config.yaml",
                expectedSizeBytes = 5235L,
                sha256 = "7bcdb5eec1fb25614e68d53176d71bfaa88476e08bf00d26d1eb21f0f8c1dddf"
            )
        )
    )

    // Model 4: Bhashini Bangla Neural Voice (Male - ONNX)
    val BHASHINI_BANGLA_MALE_TTS = ModelInfo(
        id = "bhashini_bn_male",
        name = "Bhashini Bangla Voice (Male - ONNX)",
        version = "1.1.0",
        sourceLanguage = "bn",
        targetLanguage = null,
        type = ModelType.TTS,
        // High-fidelity Bhashini FastSpeech2-HS + HiFi-GAN ONNX models
        downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Male/en_encoder_male.onnx",
        sizeBytes = 85_162_896L, // ~85.2 MB Encoder
        sha256 = "00c43018bc84d667516a1dc009e7f9da0f4e36d423b901103691678719d8404f",
        format = ModelFormat.ONNX,
        archiveName = "en_encoder_male.onnx",
        minimumRamMb = 384,
        minimumStorageMb = 250,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "text_ids",
            outputTensorName = "waveform",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            sampleRate = 22050,
            vocabFileName = "config.yaml",
            decoderType = "BHASHINI_FS2_HIFIGAN"
        ),
        description = "Bhashini IIT-Madras FastSpeech2-HS + HiFi-GAN ONNX neural model for deep, articulate, and natural Bengali male voice synthesis.",
        licenseSource = "Bhashini / IIT Madras (MIT)",
        runtimeRequirements = "ONNX Runtime Mobile (FastSpeech2-HS Encoder, Decoder + HiFi-GAN 22050Hz)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "en_decoder_male.onnx",
                downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Male/en_decoder_male.onnx",
                expectedSizeBytes = 78_776_078L,
                sha256 = "57006994c0acbb46af71b49e9be55df15c76a5e4dacac687c6e243fbb454a56a"
            ),
            ModelAuxiliaryFile(
                fileName = "hifigan_male.onnx",
                downloadUrl = "https://media.githubusercontent.com/media/Henil21/Bhashini-TTS/main/Bengali/Bengali_Male/hifigan_male.onnx",
                expectedSizeBytes = 55_736_915L,
                sha256 = "c7317dffc2fb2be2c06b6e4eb72c3aeb936c58318151f157700285c746145ea8"
            ),
            ModelAuxiliaryFile(
                fileName = "feats_stats.npz",
                downloadUrl = "https://raw.githubusercontent.com/Henil21/Bhashini-TTS/main/Bengali/Bengali_Male/feats_stats.npz",
                expectedSizeBytes = 1402L,
                sha256 = "cc564e7dbf6feb83ce81a81ddf1e5b88b86f7de313c5b864820db0d35a639f3a"
            ),
            ModelAuxiliaryFile(
                fileName = "config.yaml",
                downloadUrl = "https://raw.githubusercontent.com/Henil21/Bhashini-TTS/main/Bengali/Bengali_Male/config.yaml",
                expectedSizeBytes = 5180L,
                sha256 = "09ff407b77cbf3780088481f6837c6d262723f0d1118bd62325c5b32c622989c"
            )
        )
    )

    // Active default Bangla voice TTS model (Bhashini Female)
    val BANGLA_VOICE_TTS = BHASHINI_BANGLA_FEMALE_TTS

    // Required models for the current English -> Bangla Dubbing pipeline
    val REQUIRED_MODELS: List<ModelInfo>
        get() = listOf(
            ENGLISH_ASR,
            ENGLISH_TO_BANGLA_TRANSLATION,
            BHASHINI_BANGLA_FEMALE_TTS,
            BHASHINI_BANGLA_MALE_TTS
        )

    val ALL_MODELS: List<ModelInfo>
        get() = listOf(
            ENGLISH_ASR,
            ENGLISH_TO_BANGLA_TRANSLATION,
            BHASHINI_BANGLA_FEMALE_TTS,
            BHASHINI_BANGLA_MALE_TTS
        )

    fun configureTranslationReleaseUrl(url: String) {
        // No-op: Google ML Kit manages translation model downloads directly via on-device SDK
    }
}
