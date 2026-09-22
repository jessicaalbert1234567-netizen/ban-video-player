package com.example.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.models.MemoryDiagnostics
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device OpenAI Whisper Tiny English (tiny.en) speech recognizer using ONNX Runtime Mobile.
 * Executes quantized encoder and autoregressive decoder sessions with fast KV-cache management.
 */
class WhisperSpeechRecognizer(
    private val context: Context,
    private val encoderFile: File,
    private val decoderFile: File,
    private val tokensFile: File
) : AutoCloseable {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val N_TEXT_LAYER = 4L
        private const val N_TEXT_CTX = 448L
        private const val N_TEXT_STATE = 384L
        private const val VOCAB_SIZE = 51864
        private const val SOT = 50257L
        private const val NO_TIMESTAMPS = 50362L
        private const val EOT = 50256L
        private const val MAX_GENERATION_TOKENS = 160
    }

    private val lock = Any()
    @Volatile private var isInitialized = false

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private val tokenizer = WhisperTokenizer()
    private val featureExtractor = WhisperFeatureExtractor(context)

    fun initialize() {
        synchronized(lock) {
            if (isInitialized && encoderSession != null && decoderSession != null) return
        }

        if (!encoderFile.exists() || encoderFile.length() <= 0L) {
            throw IllegalStateException("Whisper encoder model file is missing or empty: ${encoderFile.absolutePath}")
        }
        if (!decoderFile.exists() || decoderFile.length() <= 0L) {
            throw IllegalStateException("Whisper decoder model file is missing or empty: ${decoderFile.absolutePath}")
        }
        if (!tokensFile.exists() || tokensFile.length() <= 0L) {
            throw IllegalStateException("Whisper tokens file is missing or empty: ${tokensFile.absolutePath}")
        }

        try {
            MemoryDiagnostics.trackModelLoad(
                MemoryDiagnostics.TAG_ASR,
                "Whisper Tiny English",
                encoderFile
            ) {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }

                // 1. Initialize Encoder Session
                encoderSession = env.createSession(encoderFile.absolutePath, sessionOptions)
                Log.d(TAG, "Whisper encoder session loaded. Inputs: ${encoderSession?.inputNames}, Outputs: ${encoderSession?.outputNames}")

                // 2. Initialize Decoder Session
                decoderSession = env.createSession(decoderFile.absolutePath, sessionOptions)
                Log.d(TAG, "Whisper decoder session loaded. Inputs: ${decoderSession?.inputNames}, Outputs: ${decoderSession?.outputNames}")

                // 3. Load Tokenizer
                val tokensLoaded = tokenizer.load(tokensFile)
                if (!tokensLoaded) {
                    throw IllegalStateException("Failed to parse Whisper tokens from ${tokensFile.absolutePath}")
                }
            }

            synchronized(lock) {
                isInitialized = true
            }
            Log.i(TAG, "Whisper Tiny English speech recognizer initialized successfully.")
        } catch (e: Exception) {
            close()
            Log.e(TAG, "Failed to initialize Whisper speech recognizer: ${e.message}", e)
            throw e
        }
    }

    /**
     * Transcribes a 16kHz mono WAV chunk file.
     */
    fun transcribeChunk(chunkPcmFile: File): String {
        val audioSamples = loadNormalizedPcm(chunkPcmFile)
        if (audioSamples.isEmpty() || audioSamples.size < 1600) {
            return ""
        }
        return transcribeSamples(audioSamples)
    }

    /**
     * Transcribes 16kHz mono normalized audio samples [-1.0, 1.0].
     */
    fun transcribeSamples(audioSamples: FloatArray): String {
        val encSession = encoderSession ?: throw IllegalStateException("Whisper encoder session is not initialized.")
        val decSession = decoderSession ?: throw IllegalStateException("Whisper decoder session is not initialized.")
        val env = ortEnv ?: throw IllegalStateException("ORT environment is not initialized.")

        val t0 = System.currentTimeMillis()

        // Step 1: Extract 80-channel log-Mel spectrogram [1, 80, 3000]
        val melFlat = featureExtractor.extract(audioSamples)
        val melShape = longArrayOf(1, 80, 3000)

        var crossK: OnnxTensor? = null
        var crossV: OnnxTensor? = null
        var melTensor: OnnxTensor? = null

        try {
            melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFlat), melShape)

            // Step 2: Run Encoder Forward
            val encInputs = mapOf("mel" to melTensor)
            val encResults = encSession.run(encInputs)

            // Retrieve cross attention keys and values
            val crossKValue = encResults.get("n_layer_cross_k").orElse(encResults.get(0)) as OnnxTensor
            val crossVValue = encResults.get("n_layer_cross_v").orElse(encResults.get(1)) as OnnxTensor

            crossK = crossKValue
            crossV = crossVValue

            val tEncoder = System.currentTimeMillis()

            // Step 3: Autoregressive Decoder Search
            val predictedTokens = runGreedySearchDecoder(env, decSession, crossK, crossV)

            val tDecoder = System.currentTimeMillis()

            // Step 4: Decode token IDs to English text
            val text = tokenizer.decode(predictedTokens)

            Log.d(TAG, "Whisper chunk transcribed: enc=${tEncoder - t0}ms, dec=${tDecoder - tEncoder}ms, tokens=${predictedTokens.size}, text=\"$text\"")
            return text
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription inference error: ${e.message}", e)
            throw IllegalStateException("Whisper transcription inference error: ${e.message}", e)
        } finally {
            melTensor?.close()
            crossK?.close()
            crossV?.close()
        }
    }

    private fun runGreedySearchDecoder(
        env: OrtEnvironment,
        decSession: OrtSession,
        crossK: OnnxTensor,
        crossV: OnnxTensor
    ): List<Long> {
        val predictedTokens = mutableListOf<Long>()

        // Initial prompt: SOT (50257) followed by NO_TIMESTAMPS (50362)
        val initialTokens = longArrayOf(SOT, NO_TIMESTAMPS)
        val kvCacheShape = longArrayOf(N_TEXT_LAYER, 1, N_TEXT_CTX, N_TEXT_STATE)
        val kvCacheSize = (N_TEXT_LAYER * 1 * N_TEXT_CTX * N_TEXT_STATE).toInt()

        // Allocate working buffers for KV caches
        var curKCacheBuffer = FloatBuffer.allocate(kvCacheSize)
        var curVCacheBuffer = FloatBuffer.allocate(kvCacheSize)

        // --- Step 1: Initial Prompt Pass ---
        var curOffset = 0L
        val promptLen = initialTokens.size.toLong()

        val tokensTensor0 = OnnxTensor.createTensor(env, LongBuffer.wrap(initialTokens), longArrayOf(1, promptLen))
        val kCacheTensor0 = OnnxTensor.createTensor(env, curKCacheBuffer, kvCacheShape)
        val vCacheTensor0 = OnnxTensor.createTensor(env, curVCacheBuffer, kvCacheShape)
        val offsetTensor0 = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(curOffset)), longArrayOf(1))

        var lastLogitsRow: FloatArray? = null

        try {
            val decInputs0 = mapOf(
                "tokens" to tokensTensor0,
                "in_n_layer_self_k_cache" to kCacheTensor0,
                "in_n_layer_self_v_cache" to vCacheTensor0,
                "n_layer_cross_k" to crossK,
                "n_layer_cross_v" to crossV,
                "offset" to offsetTensor0
            )

            val decResults0 = decSession.run(decInputs0)
            val logitsTensor = decResults0.get("logits").orElse(decResults0.get(0)) as OnnxTensor
            val outK = decResults0.get("out_n_layer_self_k_cache").orElse(decResults0.get(1)) as OnnxTensor
            val outV = decResults0.get("out_n_layer_self_v_cache").orElse(decResults0.get(2)) as OnnxTensor

            // Copy out updated KV caches
            val kBuf = (outK.value as Array<Array<Array<FloatArray>>>)
            val vBuf = (outV.value as Array<Array<Array<FloatArray>>>)
            curKCacheBuffer = flatten4DArray(kBuf, kvCacheSize)
            curVCacheBuffer = flatten4DArray(vBuf, kvCacheSize)

            // Extract logits for the last prompt token (index promptLen - 1)
            lastLogitsRow = extractLastLogitsRow(logitsTensor.value, (promptLen - 1).toInt())
            decResults0.close()
        } finally {
            tokensTensor0.close()
            kCacheTensor0.close()
            vCacheTensor0.close()
            offsetTensor0.close()
        }

        if (lastLogitsRow == null) {
            return emptyList()
        }

        var nextToken = argmax(lastLogitsRow).toLong()
        if (nextToken == EOT) {
            return emptyList()
        }

        predictedTokens.add(nextToken)
        curOffset = promptLen

        // --- Step 2: Autoregressive Loop for Subsequent Tokens ---
        for (step in 1 until MAX_GENERATION_TOKENS) {
            if (curOffset >= N_TEXT_CTX - 1) {
                break
            }

            val singleTokenArr = longArrayOf(nextToken)
            val tokenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(singleTokenArr), longArrayOf(1, 1))
            val kCacheTensor = OnnxTensor.createTensor(env, curKCacheBuffer, kvCacheShape)
            val vCacheTensor = OnnxTensor.createTensor(env, curVCacheBuffer, kvCacheShape)
            val offsetTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(curOffset)), longArrayOf(1))

            var nextLogitsRow: FloatArray? = null

            try {
                val decInputs = mapOf(
                    "tokens" to tokenTensor,
                    "in_n_layer_self_k_cache" to kCacheTensor,
                    "in_n_layer_self_v_cache" to vCacheTensor,
                    "n_layer_cross_k" to crossK,
                    "n_layer_cross_v" to crossV,
                    "offset" to offsetTensor
                )

                val decResults = decSession.run(decInputs)
                val logitsTensor = decResults.get("logits").orElse(decResults.get(0)) as OnnxTensor
                val outK = decResults.get("out_n_layer_self_k_cache").orElse(decResults.get(1)) as OnnxTensor
                val outV = decResults.get("out_n_layer_self_v_cache").orElse(decResults.get(2)) as OnnxTensor

                val kBuf = (outK.value as Array<Array<Array<FloatArray>>>)
                val vBuf = (outV.value as Array<Array<Array<FloatArray>>>)
                curKCacheBuffer = flatten4DArray(kBuf, kvCacheSize)
                curVCacheBuffer = flatten4DArray(vBuf, kvCacheSize)

                nextLogitsRow = extractLastLogitsRow(logitsTensor.value, 0)
                decResults.close()
            } finally {
                tokenTensor.close()
                kCacheTensor.close()
                vCacheTensor.close()
                offsetTensor.close()
            }

            if (nextLogitsRow == null) {
                break
            }

            nextToken = argmax(nextLogitsRow).toLong()
            curOffset += 1L

            if (nextToken == EOT) {
                break
            }

            predictedTokens.add(nextToken)
        }

        return predictedTokens
    }

    private fun extractLastLogitsRow(logitsVal: Any?, tokenIdx: Int): FloatArray? {
        if (logitsVal == null) return null
        if (logitsVal is Array<*>) {
            val batch = logitsVal[0]
            if (batch is Array<*>) {
                val row = batch.getOrNull(tokenIdx) ?: batch.lastOrNull()
                if (row is FloatArray) {
                    return row
                }
            }
        }
        return null
    }

    private fun flatten4DArray(arr: Array<Array<Array<FloatArray>>>, totalSize: Int): FloatBuffer {
        val buffer = FloatBuffer.allocate(totalSize)
        for (i in arr.indices) {
            val a3 = arr[i]
            for (j in a3.indices) {
                val a2 = a3[j]
                for (k in a2.indices) {
                    buffer.put(a2[k])
                }
            }
        }
        buffer.flip()
        return buffer
    }

    private fun argmax(logits: FloatArray): Int {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        val limit = logits.size.coerceAtMost(VOCAB_SIZE + 50)
        for (i in 0 until limit) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun loadNormalizedPcm(pcmFile: File): FloatArray {
        val length = pcmFile.length().toInt()
        val numSamples = (length - 44) / 2
        if (numSamples <= 0) return FloatArray(0)

        val floats = FloatArray(numSamples)
        FileInputStream(pcmFile).use { fis ->
            fis.skip(44) // skip WAV header
            val buffer = ByteArray(4096)
            var floatIdx = 0
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                val bb = ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 2 && floatIdx < numSamples) {
                    val s = bb.short
                    floats[floatIdx++] = (s / 32768.0f).coerceIn(-1.0f, 1.0f)
                }
            }
        }
        return floats
    }

    override fun close() {
        try {
            encoderSession?.close()
            encoderSession = null
            decoderSession?.close()
            decoderSession = null
            ortEnv?.close()
            ortEnv = null
            synchronized(lock) {
                isInitialized = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Whisper sessions", e)
        }
    }
}
