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
    // Package verified from shhossain/opus-mt-en-to-bn INT8 conversion (~221.0 MB total)
    val ENGLISH_TO_BANGLA_TRANSLATION = ModelInfo(
        id = "en_bn_translation_onnx",
        name = "English → Bangla Translation",
        version = "1.0.0",
        sourceLanguage = "en",
        targetLanguage = "bn",
        type = ModelType.TRANSLATION,
        downloadUrl = "", // Explicitly unconfigured per Step 9: "If the converted model has not yet been uploaded to a stable public location, keep the status: 'Model source not configured' instead of pretending it can be downloaded."
        sizeBytes = 231_754_408L, // Real verified package size ~221.0 MB (NOT fake ~64 MB)
        archiveSizeBytes = 51_062_030L, // encoder_model.onnx size
        sha256 = "ddb11a17b599458d736b4f1f65b8c69ea778e32348316705193c1c9226e2f2a8", // encoder_model.onnx SHA-256
        format = ModelFormat.ONNX,
        archiveName = "encoder_model.onnx",
        minimumRamMb = 512,
        minimumStorageMb = 350,
        onnxMetadata = OnnxMetadata(
            inputTensorName = "input_ids",
            outputTensorName = "last_hidden_state",
            inputShape = listOf(1L, -1L),
            dataType = "INT64",
            vocabFileName = "vocab.json",
            decoderType = "SEQ2SEQ_MARIAN"
        ),
        description = "Real offline English → Bangla neural translation model (shhossain/opus-mt-en-to-bn MarianMT Seq2Seq INT8 ONNX). Model source not configured on public CDN.",
        licenseSource = "Helsinki-NLP / shhossain (Apache-2.0)",
        runtimeRequirements = "ONNX Runtime Mobile (Encoder + Decoder Seq2Seq INT8, SentencePiece tokenizer)",
        auxiliaryFiles = listOf(
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
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/source.spm",
                expectedSizeBytes = 801_944L,
                sha256 = "42579d7e10efa316d46046d93b32ec58575741d43e86cd54edd5382e7c0ebcfb"
            ),
            ModelAuxiliaryFile(
                fileName = "target.spm",
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/target.spm",
                expectedSizeBytes = 969_253L,
                sha256 = "e6c0bae089a84b8d524ba3fa274388c09aec3c492fd74cb13c12ae8dbd1379de"
            ),
            ModelAuxiliaryFile(
                fileName = "vocab.json",
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/vocab.json",
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
                fileName = "config.json",
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/config.json",
                expectedSizeBytes = 1_311L,
                sha256 = "0ba3582247e8a092f41b2e2ed04496160e3a43ab8ed20858784e8e51593e3ec7"
            ),
            ModelAuxiliaryFile(
                fileName = "generation_config.json",
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/generation_config.json",
                expectedSizeBytes = 288L,
                sha256 = "d70086da7b4a5c423963297dbd43dbbbbd03e7c915fe8cfbeac86c3de85d3667"
            ),
            ModelAuxiliaryFile(
                fileName = "tokenizer_config.json",
                downloadUrl = "https://huggingface.co/shhossain/opus-mt-en-to-bn/resolve/main/tokenizer_config.json",
                expectedSizeBytes = 848L,
                sha256 = "7516a3e0b141a004383ce6f618915a5a43f304e6ff123b32a4d59f3d2014735f"
            ),
            ModelAuxiliaryFile(
                fileName = "model_manifest.json",
                downloadUrl = "",
                expectedSizeBytes = 3_150L,
                sha256 = ""
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
    val REQUIRED_MODELS = listOf(
        ENGLISH_ASR,
        ENGLISH_TO_BANGLA_TRANSLATION,
        BANGLA_VOICE_TTS
    )
}
