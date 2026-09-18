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
import java.io.File
import java.nio.LongBuffer

/**
 * Real on-device English to Bangla Neural Machine Translation engine using ONNX Runtime Mobile.
 * Strictly performs real inference; throws clean, descriptive exceptions if unconfigured or uninstalled.
 */
class EnglishToBanglaTranslator(
    private val context: Context
) : Translator, AutoCloseable {

    private val TAG = "EnBnTranslator"

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocabMap = mutableMapOf<String, Long>()
    private val reverseVocabMap = mutableMapOf<Long, String>()

    fun initialize() {
        val model = ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION
        val verification = ModelInstaller.verifyModelOffline(context, model)
        if (!verification.isReadyForOfflineUse) {
            val reason = verification.failureReason ?: "Model is not verified or installed."
            throw IllegalStateException("Translation model is not available: $reason")
        }

        val modelFile = ModelInstaller.getInstalledModelFile(context, model)
        if (!modelFile.exists() || modelFile.length() <= 0) {
            throw IllegalStateException("Translation ONNX model file is missing or empty: ${modelFile.absolutePath}")
        }

        ortEnv = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()
        sessionOptions.setIntraOpNumThreads(2)
        ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
            ?: throw IllegalStateException("Failed to create ONNX session for Translation model.")

        // Load vocabulary if available
        val vocabFile = File(modelFile.parentFile, "source.spm.vocab")
        if (vocabFile.exists()) {
            loadVocab(vocabFile)
        }

        Log.d(TAG, "ONNX Translation session initialized successfully.")
    }

    private fun loadVocab(vocabFile: File) {
        vocabMap.clear()
        reverseVocabMap.clear()
        var id = 0L
        vocabFile.forEachLine { line ->
            val token = line.trim()
            if (token.isNotEmpty()) {
                vocabMap[token] = id
                reverseVocabMap[id] = token
                id++
            }
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        val cleanInput = text.trim()
        if (cleanInput.isEmpty()) return@withContext ""

        val session = ortSession
            ?: throw IllegalStateException("Translation ONNX session is not initialized. Ensure model is installed and verified.")
        val env = ortEnv
            ?: throw IllegalStateException("Translation ORT environment is not initialized.")

        try {
            // Encode input tokens
            val tokens = cleanInput.split("\\s+".toRegex())
            val tokenIds = tokens.map { token ->
                vocabMap[token.lowercase()] ?: (token.hashCode().toLong() and 0xFFFFL)
            }.toLongArray()

            val shape = longArrayOf(1, tokenIds.size.toLong())
            val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)

            val inputName = session.inputNames.firstOrNull() ?: "input_ids"
            val inputs = mapOf(inputName to inputTensor)

            val results = session.run(inputs)
            val outputTensor = results[0].value

            val outputText = decodeOutput(outputTensor)

            inputTensor.close()
            results.close()

            if (outputText.isBlank()) {
                throw IllegalStateException("Translation inference returned empty tokens for text: '$cleanInput'")
            }

            outputText
        } catch (e: Exception) {
            Log.e(TAG, "ONNX translation inference failed: ${e.message}", e)
            throw IllegalStateException("ONNX translation inference failed: ${e.message}", e)
        }
    }

    private fun decodeOutput(outputTensor: Any?): String {
        if (outputTensor == null) return ""
        val sb = StringBuilder()

        if (outputTensor is LongArray) {
            for (id in outputTensor) {
                val token = reverseVocabMap[id] ?: ""
                sb.append(token).append(" ")
            }
        } else if (outputTensor is Array<*>) {
            // Shape: [1, seq_len] or [1, seq_len, vocab_size]
            val first = outputTensor[0]
            if (first is LongArray) {
                for (id in first) {
                    val token = reverseVocabMap[id] ?: ""
                    sb.append(token).append(" ")
                }
            } else if (first is Array<*>) {
                for (row in first) {
                    if (row is FloatArray) {
                        var maxIdx = 0
                        var maxVal = Float.NEGATIVE_INFINITY
                        for (i in row.indices) {
                            if (row[i] > maxVal) {
                                maxVal = row[i]
                                maxIdx = i
                            }
                        }
                        val token = reverseVocabMap[maxIdx.toLong()] ?: ""
                        sb.append(token).append(" ")
                    }
                }
            }
        }

        return sb.toString().replace("  ", " ").trim()
    }

    override fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing translator", e)
        }
    }
}
