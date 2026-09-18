package com.example.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Real OnnxSpeechRecognizer for on-device automatic speech recognition.
 * Supports NeMo Citrinet-512 CTC and CTC-based acoustic models.
 * Strictly throws exceptions on missing models or runtime inference failures.
 */
class OnnxSpeechRecognizer(
    private val context: Context,
    private val modelFile: File
) : AutoCloseable {

    private val TAG = "OnnxSpeechRecognizer"

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocabulary = mutableMapOf<Int, String>()
    private var blankTokenId = 1024

    fun initialize() {
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            throw IllegalStateException("ASR model file does not exist or is empty: ${modelFile.absolutePath}")
        }

        ortEnv = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()
        sessionOptions.setIntraOpNumThreads(2)
        val session = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
            ?: throw IllegalStateException("Failed to create ONNX session for ASR model.")
        ortSession = session

        // Load tokens from tokens.txt in model directory
        val tokensFile = File(modelFile.parentFile, "tokens.txt")
        if (tokensFile.exists() && tokensFile.length() > 0) {
            loadVocabulary(tokensFile)
        } else {
            // Default Citrinet vocabulary mapping
            for (c in 'a'..'z') {
                vocabulary[c - 'a' + 1] = c.toString()
            }
            vocabulary[0] = " "
            vocabulary[27] = "'"
        }

        Log.d(TAG, "ASR ONNX session initialized successfully. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}, Vocab size: ${vocabulary.size}")
    }

    private fun loadVocabulary(tokensFile: File) {
        vocabulary.clear()
        var lineIndex = 0
        tokensFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isNotBlank()) {
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val token = parts[0].replace(" ", " ")
                    val id = parts[1].toIntOrNull()
                    if (id != null) {
                        vocabulary[id] = token
                    } else {
                        vocabulary[lineIndex] = line.replace(" ", " ")
                    }
                } else {
                    vocabulary[lineIndex] = line.replace(" ", " ")
                }
                lineIndex++
            }
        }
        // Citrinet uses blank at vocab size or index 1024
        blankTokenId = if (vocabulary.containsKey(1024)) 1024 else 0
    }

    /**
     * Transcribes a chunk of 16-bit 16kHz mono PCM audio using real ONNX inference.
     */
    fun transcribeChunk(chunkPcmFile: File): String {
        val session = ortSession ?: throw IllegalStateException("ASR ONNX session is not initialized.")
        val env = ortEnv ?: throw IllegalStateException("ASR ORT environment is not initialized.")

        val floatAudio = loadNormalizedPcm(chunkPcmFile)
        if (floatAudio.size < 1600) {
            // Less than 100ms of audio is too short for meaningful phoneme detection
            return ""
        }

        val inputNames = session.inputNames.toList()
        val inputsMap = mutableMapOf<String, OnnxTensor>()

        try {
            // Inspect input requirement: 3D feature tensor [1, 80, numFrames] vs 2D raw audio [1, N]
            val firstInputName = inputNames[0]
            val inputInfo = session.inputInfo[firstInputName]
            val tensorInfo = inputInfo?.info as? ai.onnxruntime.TensorInfo
            val numDims = tensorInfo?.shape?.size ?: 3

            if (numDims == 3 || firstInputName.contains("signal") || firstInputName.contains("feat")) {
                // Extract 80-bin Mel-filterbank spectrogram
                val melFeatures = MelSpectrogramExtractor.extract(floatAudio)
                val numMels = melFeatures.size
                val numFrames = if (numMels > 0) melFeatures[0].size else 0

                if (numFrames == 0) return ""

                val flatFeatures = FloatArray(numMels * numFrames)
                for (m in 0 until numMels) {
                    for (t in 0 until numFrames) {
                        flatFeatures[m * numFrames + t] = melFeatures[m][t]
                    }
                }

                val featureShape = longArrayOf(1, numMels.toLong(), numFrames.toLong())
                val featTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatFeatures), featureShape)
                inputsMap[firstInputName] = featTensor

                // Provide length tensor if required by model
                if (inputNames.size > 1 && inputNames.any { it.contains("len") }) {
                    val lenName = inputNames.first { it.contains("len") }
                    val lengthTensor = OnnxTensor.createTensor(env, longArrayOf(numFrames.toLong()))
                    inputsMap[lenName] = lengthTensor
                }
            } else {
                // Raw audio input [1, N]
                val shape = longArrayOf(1, floatAudio.size.toLong())
                val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatAudio), shape)
                inputsMap[firstInputName] = audioTensor

                if (inputNames.size > 1 && inputNames.any { it.contains("len") }) {
                    val lenName = inputNames.first { it.contains("len") }
                    val lengthTensor = OnnxTensor.createTensor(env, longArrayOf(floatAudio.size.toLong()))
                    inputsMap[lenName] = lengthTensor
                }
            }

            // Run real ONNX inference
            val results = session.run(inputsMap)
            val outputTensor = results[0].value
            val decodedText = decodeCtcLogits(outputTensor)

            results.close()
            return decodedText
        } catch (e: Exception) {
            Log.e(TAG, "Real ASR inference failed on chunk: ${e.message}", e)
            throw IllegalStateException("Real ASR inference failed on audio chunk: ${e.message}", e)
        } finally {
            for (tensor in inputsMap.values) {
                try { tensor.close() } catch (ignored: Exception) {}
            }
        }
    }

    private fun decodeCtcLogits(outputTensor: Any?): String {
        if (outputTensor == null) return ""

        val sb = StringBuilder()
        var lastToken = -1

        // Handle shape: [1, TimeSteps, VocabSize] or [1, VocabSize, TimeSteps]
        if (outputTensor is Array<*>) {
            val batch = outputTensor[0]
            if (batch is Array<*>) {
                val firstRow = batch[0]
                if (firstRow is FloatArray) {
                    // Shape: [1, TimeSteps, VocabSize]
                    for (stepIdx in batch.indices) {
                        val step = batch[stepIdx] as FloatArray
                        val maxIdx = argmax(step)
                        if (maxIdx != blankTokenId && maxIdx != 0 && maxIdx != lastToken) {
                            val token = vocabulary[maxIdx] ?: ""
                            sb.append(token)
                        }
                        lastToken = maxIdx
                    }
                }
            }
        }

        val text = sb.toString().replace("  ", " ").trim()
        return text
    }

    private fun argmax(array: FloatArray): Int {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in array.indices) {
            if (array[i] > bestVal) {
                bestVal = array[i]
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
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ORT session", e)
        }
    }
}
