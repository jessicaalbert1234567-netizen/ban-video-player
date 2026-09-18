package com.example.models

object ModelCatalog {

    const val MODEL_URL_NOT_CONFIGURED = "MODEL_URL_NOT_CONFIGURED"

    // Model 1: English Speech Recognition (ONNX Mobile)
    val ENGLISH_ASR = ModelInfo(
        id = "english_asr_onnx",
        name = "English Speech Recognition",
        version = "1.2.0",
        sourceLanguage = "en",
        targetLanguage = null,
        type = ModelType.ASR,
        // Public HuggingFace ONNX Sherpa/Whisper-tiny quantization repository
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en.onnx",
        sizeBytes = 75_000_000L, // ~75 MB
        sha256 = "b84e1b8b8ef03ad295a7042562be55c70bf002f2324dc2c6bbf8e411b4231ff0",
        format = ModelFormat.ONNX,
        archiveName = "english_asr_model.onnx",
        minimumRamMb = 512,
        minimumStorageMb = 120,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "audio_pcm",
            outputTensorName = "logits",
            inputShape = listOf(1L, -1L),
            dataType = "FLOAT32",
            sampleRate = 16000,
            blankTokenId = 0,
            vocabFileName = "tokens.txt",
            decoderType = "CTC_GREEDY"
        ),
        description = "Offline English Automatic Speech Recognition engine optimized for Android ONNX Runtime Mobile."
    )

    // Model 2: English -> Bangla Translation (ONNX Seq2Seq / MarianMT)
    val ENGLISH_TO_BANGLA_TRANSLATION = ModelInfo(
        id = "en_bn_translation_onnx",
        name = "English → Bangla Translation",
        version = "1.1.0",
        sourceLanguage = "en",
        targetLanguage = "bn",
        type = ModelType.TRANSLATION,
        // Public HuggingFace MarianMT en-bn INT8 ONNX checkpoint
        downloadUrl = "https://huggingface.co/Helsinki-NLP/opus-mt-en-mul/resolve/main/model.onnx",
        sizeBytes = 68_000_000L, // ~68 MB
        sha256 = "6caefca442750e41cf77dd18e87adbc3b1b9e54a5a770281b95fbb0e2a4f494a",
        format = ModelFormat.ONNX,
        archiveName = "en_bn_translator.onnx",
        minimumRamMb = 512,
        minimumStorageMb = 100,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "input_ids",
            outputTensorName = "logits",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            vocabFileName = "source.spm",
            decoderType = "SEQ2SEQ_BEAM"
        ),
        description = "Offline English to Bengali neural translation model preserving subtitle segment timing."
    )

    // Model 3: Bangla Voice / Speech Synthesis (ONNX VITS/Piper)
    val BANGLA_VOICE_TTS = ModelInfo(
        id = "bn_voice_vits_onnx",
        name = "Bangla Voice (TTS)",
        version = "1.0.4",
        sourceLanguage = "bn",
        targetLanguage = null,
        type = ModelType.TTS,
        // Public HuggingFace Piper / Sherpa-onnx Bangla acoustic voice checkpoint
        downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/bn/bn_BD/rokeya/medium/bn_BD-rokeya-medium.onnx",
        sizeBytes = 55_000_000L, // ~55 MB
        sha256 = "c088f19da3f9c63a56291a2e379ff96a4da499e1957c5cefdbaee4a7d6560938",
        format = ModelFormat.ONNX,
        archiveName = "bangla_tts_model.onnx",
        minimumRamMb = 384,
        minimumStorageMb = 90,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "phoneme_ids",
            outputTensorName = "audio_wav",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            sampleRate = 22050,
            vocabFileName = "phonemes.json",
            decoderType = "VITS_SYNTHESIZER"
        ),
        description = "Natural offline Bangla neural voice synthesizer generating synchronized speech for video dubbing."
    )

    // Required models for the current English -> Bangla Dubbing pipeline
    val REQUIRED_MODELS = listOf(
        ENGLISH_ASR,
        ENGLISH_TO_BANGLA_TRANSLATION,
        BANGLA_VOICE_TTS
    )

    // Future Language Packs (Architecture extensibility)
    val FUTURE_TARGET_LANGUAGE_PACKS = listOf(
        "Hindi (en_hi)" to "English → Hindi Neural Translation & Voice",
        "Arabic (en_ar)" to "English → Arabic Neural Translation & Voice",
        "Japanese (en_ja)" to "English → Japanese Neural Translation & Voice",
        "Chinese (en_zh)" to "English → Chinese Neural Translation & Voice",
        "Spanish (en_es)" to "English → Spanish Neural Translation & Voice",
        "French (en_fr)" to "English → French Neural Translation & Voice"
    )

    val FUTURE_SOURCE_LANGUAGES = listOf(
        "hi" to "Hindi Speech Recognition",
        "ar" to "Arabic Speech Recognition",
        "ja" to "Japanese Speech Recognition",
        "zh" to "Chinese Speech Recognition"
    )
}
