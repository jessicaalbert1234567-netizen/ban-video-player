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

    // Model 2: English -> Bangla Translation (MarianMT Seq2Seq INT8 ONNX)
    // Production Distribution Package: translation_en_bn_v1.0.zip (~150.4 MB compressed, ~221.0 MB uncompressed)
    // Published via GitHub Actions workflow as a GitHub Release asset.
    const val DEFAULT_TRANSLATION_RELEASE_URL =
        "https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases/download/v1.0.0-translation-model/translation_en_bn_v1.0.zip"

    @Volatile
    var translationReleaseUrl: String = DEFAULT_TRANSLATION_RELEASE_URL

    fun configureTranslationReleaseUrl(url: String) {
        translationReleaseUrl = if (url.isBlank()) DEFAULT_TRANSLATION_RELEASE_URL else url.trim()
    }

    val ENGLISH_TO_BANGLA_TRANSLATION: ModelInfo
        get() = ModelInfo(
            id = "en_bn_translation_onnx",
            name = "English → Bangla Translation",
            version = "1.0.0",
            sourceLanguage = "en",
            targetLanguage = "bn",
            type = ModelType.TRANSLATION,
            downloadUrl = translationReleaseUrl,
            sizeBytes = 231_754_408L, // Real verified uncompressed package size ~221.0 MB
            archiveSizeBytes = 157_681_950L, // Real distribution zip archive size: 157,681,950 bytes
            sha256 = "ff8b97888c8413c4ba2509e39a50234d040c6b894ab0a005f39bbd47595c0e27", // SHA-256 of translation_en_bn_v1.0.zip
            format = ModelFormat.BINARY_ARCHIVE,
            archiveName = "translation_en_bn_v1.0.zip",
            minimumRamMb = 512,
            minimumStorageMb = 450,
            onnxMetadata = OnnxMetadata(
                inputTensorName = "input_ids",
                outputTensorName = "last_hidden_state",
                inputShape = listOf(1L, -1L),
                dataType = "INT64",
                vocabFileName = "vocab.json",
                decoderType = "SEQ2SEQ_MARIAN"
            ),
            description = "Real offline English → Bangla neural translation model (shhossain/opus-mt-en-to-bn MarianMT Seq2Seq INT8 ONNX). Official GitHub Release package.",
            licenseSource = "Helsinki-NLP / shhossain (Apache-2.0)",
            runtimeRequirements = "ONNX Runtime Mobile (Encoder + Decoder Seq2Seq INT8, SentencePiece tokenizer)",
            auxiliaryFiles = listOf(
                ModelAuxiliaryFile(
                    fileName = "encoder_model.onnx",
                    downloadUrl = "",
                    expectedSizeBytes = 51_062_030L,
                    sha256 = "ddb11a17b599458d736b4f1f65b8c69ea778e32348316705193c1c9226e2f2a8"
                ),
                ModelAuxiliaryFile(
                    fileName = "decoder_model.onnx",
                    downloadUrl = "",
                    expectedSizeBytes = 89_507_041L,
                    sha256 = "02c6143216641cbb1c7ac171931bb4ee65d2864b60049292aef044dfd773b0b9"
                ),
                ModelAuxiliaryFile(
                    fileName = "decoder_with_past_model.onnx",
                    downloadUrl = "",
                    expectedSizeBytes = 86_295_092L,
                    sha256 = "f86fd21ae96746ce3cce2c72f0e3a3f81daf30902284164a806efd86e7ebf165"
                ),
                ModelAuxiliaryFile(
                    fileName = "source.spm",
                    downloadUrl = "",
                    expectedSizeBytes = 801_944L,
                    sha256 = "42579d7e10efa316d46046d93b32ec58575741d43e86cd54edd5382e7c0ebcfb"
                ),
                ModelAuxiliaryFile(
                    fileName = "target.spm",
                    downloadUrl = "",
                    expectedSizeBytes = 969_253L,
                    sha256 = "e6c0bae089a84b8d524ba3fa274388c09aec3c492fd74cb13c12ae8dbd1379de"
                ),
                ModelAuxiliaryFile(
                    fileName = "vocab.json",
                    downloadUrl = "",
                    expectedSizeBytes = 2_049_060L,
                    sha256 = "5419d2b1ce532f694971b3f1c651064b01f48c203a2e94a4fb3a468ad692d383"
                ),
                ModelAuxiliaryFile(
                    fileName = "source_pieces.json",
                    downloadUrl = "",
                    expectedSizeBytes = 1_067_467L,
                    sha256 = "f2ee3f64dbacf9ef768388da197dcab26e35dde695277bb60c3eb0e5bee43cd4"
                ),
                ModelAuxiliaryFile(
                    fileName = "model_manifest.json",
                    downloadUrl = "",
                    expectedSizeBytes = 3_114L,
                    sha256 = "06960a09ec46487e651e7f607c3365fa316d56314f5ec9832791550c606cb87b"
                )
            )
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
}
