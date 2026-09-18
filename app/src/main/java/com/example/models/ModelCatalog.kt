package com.example.models

object ModelCatalog {

    const val MODEL_URL_NOT_CONFIGURED = ""

    // Model 1: English Speech Recognition (Citrinet-512 CTC ONNX Mobile)
    val ENGLISH_ASR = ModelInfo(
        id = "english_asr_citrinet",
        name = "English Speech Recognition (Citrinet-512)",
        version = "1.0.0",
        sourceLanguage = "en",
        targetLanguage = null,
        type = ModelType.ASR,
        // Real HuggingFace ONNX Sherpa / NeMo Citrinet-512 INT8 model
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-citrinet-512/resolve/main/model.int8.onnx",
        sizeBytes = 38_048_112L, // ~38 MB
        sha256 = "fc42a69b0c113c3188a15487652b10564584255dbcb093b3d543334c898af1eb",
        format = ModelFormat.ONNX,
        archiveName = "model.int8.onnx",
        minimumRamMb = 512,
        minimumStorageMb = 100,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "audio_signal",
            outputTensorName = "logprobs",
            inputShape = listOf(1L, 80L, -1L),
            dataType = "FLOAT32",
            sampleRate = 16000,
            blankTokenId = 1024,
            vocabFileName = "tokens.txt",
            decoderType = "CTC_GREEDY"
        ),
        description = "Real quantized English CTC acoustic model for on-device automatic speech recognition.",
        licenseSource = "NVIDIA NeMo / Sherpa-ONNX (Apache-2.0)",
        runtimeRequirements = "ONNX Runtime Mobile (16kHz mono PCM, 80-bin mel fbank, tokens.txt)",
        auxiliaryFiles = listOf(
            ModelAuxiliaryFile(
                fileName = "tokens.txt",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-en-citrinet-512/raw/main/tokens.txt",
                expectedSizeBytes = 11022L,
                sha256 = "ec47e32278739a7423a610ec8127b55ead3ac7c6"
            )
        )
    )

    // Model 2: English -> Bangla Translation
    // Explicitly unconfigured per prompt Part 14 & Part 1 since no verified public single ONNX exists
    val ENGLISH_TO_BANGLA_TRANSLATION = ModelInfo(
        id = "en_bn_translation_onnx",
        name = "English → Bangla Translation",
        version = "1.0.0",
        sourceLanguage = "en",
        targetLanguage = "bn",
        type = ModelType.TRANSLATION,
        downloadUrl = "", // Explicitly unconfigured
        sizeBytes = 0L,
        sha256 = "",
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
        description = "Offline English to Bangla neural translation model. Model source not configured.",
        licenseSource = "Not configured: No public Android-compatible ONNX English→Bangla model available",
        runtimeRequirements = "Seq2Seq ONNX model + SentencePiece / BPE tokenizer",
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
    val REQUIRED_MODELS = listOf(
        ENGLISH_ASR,
        ENGLISH_TO_BANGLA_TRANSLATION,
        BANGLA_VOICE_TTS
    )
}
