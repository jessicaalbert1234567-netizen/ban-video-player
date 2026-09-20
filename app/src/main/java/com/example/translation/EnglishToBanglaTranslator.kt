package com.example.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.models.ModelCatalog
import com.example.models.ModelInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Real on-device English to Bangla Neural Machine Translation engine using ONNX Runtime Mobile.
 * Uses MarianMT Seq2Seq Transformer architecture (Encoder + Autoregressive Decoder).
 * Strictly performs real inference; throws clean, descriptive exceptions if unconfigured or uninstalled.
 */
typealias TranslationModel = EnglishToBanglaTranslator

class EnglishToBanglaTranslator(
    private val context: Context
) : Translator, AutoCloseable {

    private val TAG = "EnBnTranslator"

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private val tokenToId = HashMap<String, Long>()
    private val idToToken = HashMap<Long, String>()
    private val pieceScores = HashMap<String, Float>()

    private val eosTokenId = 0L
    private val padTokenId = 61759L
    private val decoderStartTokenId = 61759L

    @Volatile
    private var isInitialized = false

    @Synchronized
    fun initialize() {
        if (isInitialized) return

        val model = ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION
        val modelDir = ModelInstaller.getModelDirectory(context, model)
        val encoderFile = File(modelDir, "encoder_model.onnx")
        val decoderFile = File(modelDir, "decoder_model.onnx")

        // Step 11: If translation ONNX files are missing:
        // show: "English → Bangla translation model is not installed."
        Log.i(TAG, """
            |==================================================
            |MODEL AUDIT:
            |MODEL NAME: ${model.name}
            |EXPECTED PATH: ${encoderFile.absolutePath}
            |ACTUAL PATH: ${encoderFile.absolutePath}
            |EXPECTED FILENAME: encoder_model.onnx
            |ACTUAL FILENAME: ${encoderFile.name}
            |ACTUAL BYTE SIZE: ${if (encoderFile.exists()) encoderFile.length() else 0L}
            |EXISTS: ${encoderFile.exists()}, IS_FILE: ${encoderFile.isFile}, CAN_READ: ${encoderFile.canRead()}
            |==================================================
        """.trimMargin())

        if (!encoderFile.exists() || encoderFile.length() <= 0L || !decoderFile.exists() || decoderFile.length() <= 0L) {
            throw IllegalStateException("English → Bangla translation model is not installed.")
        }

        // Step 11: If tokenizer is missing:
        // show: "Translation tokenizer files are missing."
        val vocabFile = File(modelDir, "vocab.json")
        val piecesFile = File(modelDir, "source_pieces.json")
        val spmFile = File(modelDir, "source.spm")
        if (!vocabFile.exists() || vocabFile.length() <= 0L || (!piecesFile.exists() && !spmFile.exists())) {
            throw IllegalStateException("Translation tokenizer files are missing.")
        }

        try {
            loadTokenizer(vocabFile, piecesFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load tokenizer", e)
            throw IllegalStateException("Translation tokenizer files are missing.", e)
        }

        // Step 11: If ONNX Runtime cannot load:
        // show: "Translation model failed to initialize."
        try {
            com.example.models.MemoryDiagnostics.trackModelLoad(
                com.example.models.MemoryDiagnostics.TAG_TRANSLATION,
                "MarianMT Translation Seq2Seq",
                encoderFile
            ) {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                encoderSession = env.createSession(encoderFile.absolutePath, sessionOptions)
                decoderSession = env.createSession(decoderFile.absolutePath, sessionOptions)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX Runtime initialization failed on '${Thread.currentThread().name}'", e)
            close()
            throw IllegalStateException("Translation model failed to initialize.", e)
        }

        isInitialized = true
        Log.i(TAG, "Real Seq2Seq English → Bangla Translation model initialized successfully on '${Thread.currentThread().name}'.")
    }

    private fun loadTokenizer(vocabFile: File, piecesFile: File) {
        tokenToId.clear()
        idToToken.clear()
        pieceScores.clear()

        // 1. Load vocabulary
        val vocabJson = JSONObject(vocabFile.readText(Charsets.UTF_8))
        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val id = vocabJson.getLong(token)
            tokenToId[token] = id
            idToToken[id] = token
        }

        // 2. Load unigram piece scores
        if (piecesFile.exists() && piecesFile.length() > 0L) {
            val piecesJson = JSONObject(piecesFile.readText(Charsets.UTF_8))
            val pKeys = piecesJson.keys()
            while (pKeys.hasNext()) {
                val piece = pKeys.next()
                pieceScores[piece] = piecesJson.getDouble(piece).toFloat()
            }
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        val cleanInput = text.trim()
        if (cleanInput.isEmpty()) return@withContext ""

        if (!isInitialized) {
            initialize()
        }

        val env = ortEnv
            ?: throw IllegalStateException("Translation model failed to initialize.")
        val encSession = encoderSession
            ?: throw IllegalStateException("Translation model failed to initialize.")
        val decSession = decoderSession
            ?: throw IllegalStateException("Translation model failed to initialize.")

        try {
            // Step 10: tokenization -> ONNX inference -> autoregressive decoding -> detokenization -> Bangla text
            val tokenIds = tokenizeEnglish(cleanInput)
            if (tokenIds.isEmpty()) {
                throw IllegalStateException("Translation inference failed.")
            }

            // 1. Encoder inference
            val seqLen = tokenIds.size.toLong()
            val shape = longArrayOf(1, seqLen)
            val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)
            val attentionMask = LongArray(tokenIds.size) { 1L }
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)

            val encInputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor
            )
            val encResult = encSession.run(encInputs)

            @Suppress("UNCHECKED_CAST")
            val lastHiddenState = encResult[0].value as Array<Array<FloatArray>>
            val hiddenDim = lastHiddenState[0][0].size

            val hiddenFlat = FloatArray(seqLen.toInt() * hiddenDim)
            var hIdx = 0
            for (i in 0 until seqLen.toInt()) {
                val row = lastHiddenState[0][i]
                for (j in 0 until hiddenDim) {
                    hiddenFlat[hIdx++] = row[j]
                }
            }
            val hiddenShape = longArrayOf(1, seqLen, hiddenDim.toLong())
            val hiddenTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(hiddenFlat), hiddenShape)

            // 2. Autoregressive Decoder Loop
            val generatedTokens = mutableListOf<Long>()
            val currentDecoderTokens = mutableListOf(decoderStartTokenId)
            val maxLen = 128

            for (step in 0 until maxLen) {
                val decSeqLen = currentDecoderTokens.size.toLong()
                val decShape = longArrayOf(1, decSeqLen)
                val decInputTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(currentDecoderTokens.toLongArray()),
                    decShape
                )

                val decInputs = mapOf(
                    "encoder_attention_mask" to maskTensor,
                    "input_ids" to decInputTensor,
                    "encoder_hidden_states" to hiddenTensor
                )

                val decResult = decSession.run(decInputs)

                @Suppress("UNCHECKED_CAST")
                val logits = decResult[0].value as Array<Array<FloatArray>>
                val stepLogits = logits[0][decSeqLen.toInt() - 1]

                // Argmax
                var bestId = 0L
                var maxScore = Float.NEGATIVE_INFINITY
                for (v in stepLogits.indices) {
                    if (stepLogits[v] > maxScore) {
                        maxScore = stepLogits[v]
                        bestId = v.toLong()
                    }
                }

                decInputTensor.close()
                decResult.close()

                if (bestId == eosTokenId) {
                    break
                }
                generatedTokens.add(bestId)
                currentDecoderTokens.add(bestId)
            }

            inputTensor.close()
            maskTensor.close()
            hiddenTensor.close()
            encResult.close()

            if (generatedTokens.isEmpty()) {
                throw IllegalStateException("Translation inference failed.")
            }

            // 3. Detokenize to Bangla text
            val banglaText = detokenizeBangla(generatedTokens)
            if (banglaText.isBlank()) {
                throw IllegalStateException("Translation inference failed.")
            }

            banglaText
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Translation inference failed: ${e.message}", e)
            throw IllegalStateException("Translation inference failed.", e)
        }
    }

    private fun tokenizeEnglish(text: String): LongArray {
        // SentencePiece Unigram tokenization
        val norm = "\u2581" + text.trim().replace("\\s+".toRegex(), "\u2581")
        val n = norm.length
        val dp = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(n + 1) { -1 }
        val bestPiece = Array(n + 1) { "" }
        dp[0] = 0f

        for (i in 0 until n) {
            if (dp[i] == Float.NEGATIVE_INFINITY) continue
            val maxL = minOf(32, n - i)
            for (l in 1..maxL) {
                val sub = norm.substring(i, i + l)
                val score = pieceScores[sub]
                if (score != null) {
                    val candidate = dp[i] + score
                    if (candidate > dp[i + l]) {
                        dp[i + l] = candidate
                        bestPrev[i + l] = i
                        bestPiece[i + l] = sub
                    }
                }
            }
            if (dp[i + 1] == Float.NEGATIVE_INFINITY) {
                dp[i + 1] = dp[i] - 100f
                bestPrev[i + 1] = i
                bestPiece[i + 1] = norm.substring(i, i + 1)
            }
        }

        var curr = n
        val pieces = mutableListOf<String>()
        while (curr > 0) {
            pieces.add(bestPiece[curr])
            curr = bestPrev[curr]
        }
        pieces.reverse()

        val ids = mutableListOf<Long>()
        for (p in pieces) {
            val id = tokenToId[p] ?: tokenToId["\u2581$p"] ?: tokenToId["<unk>"] ?: 0L
            ids.add(id)
        }
        ids.add(eosTokenId)
        return ids.toLongArray()
    }

    private fun detokenizeBangla(tokenIds: List<Long>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            val piece = idToToken[id] ?: ""
            sb.append(piece)
        }
        return sb.toString()
            .replace("\u2581", " ")
            .replace("  ", " ")
            .trim()
    }

    override fun close() {
        try {
            encoderSession?.close()
            decoderSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing translator", e)
        } finally {
            encoderSession = null
            decoderSession = null
            ortEnv = null
            isInitialized = false
        }
    }
}
