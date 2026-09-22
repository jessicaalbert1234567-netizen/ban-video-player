package com.example.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.audio.WavUtils
import com.example.models.MemoryDiagnostics
import com.example.models.ModelCatalog
import com.example.models.ModelInfo
import com.example.models.ModelInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * High-quality Bhashini FastSpeech2-HS + HiFi-GAN ONNX TTS Engine.
 * Supports both Female and Male Bengali neural voices trained on native Indic prosody.
 * Reference: https://github.com/Henil21/Bhashini-TTS
 */
class BhashiniTtsEngine(
    private val context: Context
) : AutoCloseable {

    companion object {
        private const val TAG = "BhashiniTTS"
    }

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var hifiganSession: OrtSession? = null
    private var currentModelId: String? = null

    private val sessionLock = Any()

    fun isModelReady(isFemale: Boolean): Boolean {
        val model = if (isFemale) ModelCatalog.BHASHINI_BANGLA_FEMALE_TTS else ModelCatalog.BHASHINI_BANGLA_MALE_TTS
        return ModelInstaller.isModelInstalled(context, model)
    }

    suspend fun loadModel(isFemale: Boolean) = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            val targetModel = if (isFemale) ModelCatalog.BHASHINI_BANGLA_FEMALE_TTS else ModelCatalog.BHASHINI_BANGLA_MALE_TTS
            if (currentModelId == targetModel.id && encoderSession != null && decoderSession != null && hifiganSession != null) {
                return@withContext
            }

            closeSessionsInternal()

            val dir = ModelInstaller.getModelDirectory(context, targetModel)
            val encFileName = if (isFemale) "bengali_encoder_female.onnx" else "en_encoder_male.onnx"
            val decFileName = if (isFemale) "bengali_decoder_female.onnx" else "en_decoder_male.onnx"
            val vocFileName = if (isFemale) "hifigan_female.onnx" else "hifigan_male.onnx"

            val encFile = File(dir, encFileName).let { if (it.exists() && it.length() > 0) it else File(dir, if (isFemale) "bengali_encoder_female_int8.onnx" else "en_encoder_male_int8.onnx") }
            val decFile = File(dir, decFileName).let { if (it.exists() && it.length() > 0) it else File(dir, if (isFemale) "bengali_decoder_female_int8.onnx" else "en_decoder_male_int8.onnx") }
            val vocFile = File(dir, vocFileName).let { if (it.exists() && it.length() > 0) it else File(dir, if (isFemale) "hifigan_female_int8.onnx" else "hifigan_male_int8.onnx") }

            if (!encFile.exists() || !decFile.exists() || !vocFile.exists()) {
                throw IllegalStateException("Bhashini ONNX model files missing in ${dir.absolutePath} (needed: $encFileName, $decFileName, $vocFileName)")
            }

            Log.i(TAG, "Loading Bhashini FastSpeech2-HS + HiFi-GAN for ${if (isFemale) "FEMALE" else "MALE"} voice...")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            MemoryDiagnostics.trackModelLoad("Bhashini_Encoder", encFile.name, encFile) {
                encoderSession = env.createSession(encFile.absolutePath, sessionOptions)
            }
            MemoryDiagnostics.trackModelLoad("Bhashini_Decoder", decFile.name, decFile) {
                decoderSession = env.createSession(decFile.absolutePath, sessionOptions)
            }
            MemoryDiagnostics.trackModelLoad("Bhashini_HiFiGAN", vocFile.name, vocFile) {
                hifiganSession = env.createSession(vocFile.absolutePath, sessionOptions)
            }

            currentModelId = targetModel.id
            Log.i(TAG, "Bhashini TTS engine loaded successfully for ${targetModel.name}")
        }
    }

    suspend fun synthesize(
        text: String,
        outputFile: File,
        isFemale: Boolean = true,
        speedRatio: Float = 1.0f
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            WavUtils.createSilenceWav(outputFile, 300L)
            return@withContext true
        }

        synchronized(sessionLock) {
            try {
                if (currentModelId == null || encoderSession == null || decoderSession == null || hifiganSession == null) {
                    runBlockingModelLoad(isFemale)
                }

                val env = ortEnv ?: throw IllegalStateException("OrtEnvironment is null")
                val enc = encoderSession ?: throw IllegalStateException("Encoder session is null")
                val dec = decoderSession ?: throw IllegalStateException("Decoder session is null")
                val voc = hifiganSession ?: throw IllegalStateException("Vocoder session is null")

                // Step 1: Tokenize Bengali text into Bhashini Indic token IDs
                val tokenIds = BhashiniTokenizer.textToTokenIds(cleanText)
                val seqLen = tokenIds.size.toLong()

                // Step 2: Encoder inference (text_ids -> hidden_states, durations)
                val textTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, seqLen))
                val encInputName = enc.inputNames.firstOrNull() ?: "text_ids"
                val encResult = enc.run(mapOf(encInputName to textTensor))
                textTensor.close()

                val hsTensor = encResult[0] as OnnxTensor
                val dLogTensor = encResult[1] as OnnxTensor

                val hsBuffer = hsTensor.floatBuffer
                val dLogBuffer = dLogTensor.floatBuffer

                val durations = IntArray(seqLen.toInt())
                var totalFrames = 0
                val effectiveSpeed = speedRatio.coerceIn(0.7f, 1.4f)

                for (i in 0 until seqLen.toInt()) {
                    val logDur = dLogBuffer.get(i).toDouble()
                    val frameCount = (Math.round(Math.exp(logDur)) / effectiveSpeed).toInt().coerceIn(1, 40)
                    durations[i] = frameCount
                    totalFrames += frameCount
                }

                if (totalFrames <= 0) totalFrames = 1

                // Step 3: Length regulation expansion
                val expandedHs = FloatArray(totalFrames * BhashiniConstants.HIDDEN_DIM)
                var outOffset = 0
                val frameVector = FloatArray(BhashiniConstants.HIDDEN_DIM)

                for (i in 0 until seqLen.toInt()) {
                    val dur = durations[i]
                    hsBuffer.position(i * BhashiniConstants.HIDDEN_DIM)
                    hsBuffer.get(frameVector)
                    for (d in 0 until dur) {
                        System.arraycopy(frameVector, 0, expandedHs, outOffset, BhashiniConstants.HIDDEN_DIM)
                        outOffset += BhashiniConstants.HIDDEN_DIM
                    }
                }

                encResult.close()

                // Step 4: Decoder inference (hidden_states -> mel_spectrogram [1, 80, totalFrames])
                val hsExpTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(expandedHs),
                    longArrayOf(1, totalFrames.toLong(), BhashiniConstants.HIDDEN_DIM.toLong())
                )
                val decInputName = dec.inputNames.firstOrNull() ?: "hidden_states"
                val decResult = dec.run(mapOf(decInputName to hsExpTensor))
                hsExpTensor.close()

                val melTensor = decResult[0] as OnnxTensor
                val melBuffer = melTensor.floatBuffer
                val melScaled = FloatArray(BhashiniConstants.NUM_MEL_BINS * totalFrames)

                val meanStats = if (isFemale) BhashiniConstants.FEMALE_MEAN else BhashiniConstants.MALE_MEAN
                val stdStats = if (isFemale) BhashiniConstants.FEMALE_STD else BhashiniConstants.MALE_STD

                // Step 5: Mel denormalization and scale factor
                for (b in 0 until BhashiniConstants.NUM_MEL_BINS) {
                    val m = meanStats[b]
                    val s = stdStats[b]
                    for (t in 0 until totalFrames) {
                        val idx = b * totalFrames + t
                        val normVal = if (idx < melBuffer.capacity()) melBuffer.get(idx) else 0.0f
                        melScaled[idx] = (normVal * s + m) * BhashiniConstants.MEL_SCALE_FACTOR
                    }
                }
                decResult.close()

                // Step 6: HiFi-GAN Vocoder inference (mel_spectrogram [1, 80, totalFrames] -> waveform)
                val melScaledTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(melScaled),
                    longArrayOf(1, BhashiniConstants.NUM_MEL_BINS.toLong(), totalFrames.toLong())
                )
                val vocInputName = voc.inputNames.firstOrNull() ?: "mel_spectrogram"
                val vocResult = voc.run(mapOf(vocInputName to melScaledTensor))
                melScaledTensor.close()

                val wavTensor = vocResult[0] as OnnxTensor
                val wavBuffer = wavTensor.floatBuffer
                val numSamples = wavBuffer.remaining()
                val floatSamples = FloatArray(numSamples)
                wavBuffer.get(floatSamples)
                vocResult.close()

                if (numSamples == 0) {
                    Log.w(TAG, "HiFiGAN produced 0 samples")
                    return@withContext false
                }

                // Step 7: Write 16-bit PCM @ 22050 Hz to WAV
                var maxVal = 0.0f
                for (s in floatSamples) {
                    val abs = Math.abs(s)
                    if (abs > maxVal) maxVal = abs
                }
                val scale = if (maxVal > 0.05f) {
                    (28000.0f / maxVal).coerceIn(12000.0f, 32000.0f)
                } else {
                    28000.0f
                }

                val pcmBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in floatSamples) {
                    val sampleShort = (s * scale).toInt().coerceIn(-32767, 32767).toShort()
                    pcmBuffer.putShort(sampleShort)
                }

                outputFile.parentFile?.mkdirs()
                writeWavHeader(outputFile, pcmBuffer.array(), BhashiniConstants.SAMPLE_RATE, 1)
                Log.i(TAG, "Bhashini TTS successfully synthesized ${numSamples} samples (${(numSamples.toFloat() / BhashiniConstants.SAMPLE_RATE)}s) to ${outputFile.name}")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Bhashini synthesis failed for text '$text': ${e.message}", e)
                return@withContext false
            }
        }
    }

    private fun runBlockingModelLoad(isFemale: Boolean) {
        val targetModel = if (isFemale) ModelCatalog.BHASHINI_BANGLA_FEMALE_TTS else ModelCatalog.BHASHINI_BANGLA_MALE_TTS
        val dir = ModelInstaller.getModelDirectory(context, targetModel)
        val encFileName = if (isFemale) "bengali_encoder_female_int8.onnx" else "en_encoder_male_int8.onnx"
        val decFileName = if (isFemale) "bengali_decoder_female_int8.onnx" else "en_decoder_male_int8.onnx"
        val vocFileName = if (isFemale) "hifigan_female_int8.onnx" else "hifigan_male_int8.onnx"

        val encFile = File(dir, encFileName)
        val decFile = File(dir, decFileName)
        val vocFile = File(dir, vocFileName)

        if (!encFile.exists() || !decFile.exists() || !vocFile.exists()) {
            throw IllegalStateException("Bhashini ONNX model files missing in ${dir.absolutePath}")
        }

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }

        encoderSession = env.createSession(encFile.absolutePath, sessionOptions)
        decoderSession = env.createSession(decFile.absolutePath, sessionOptions)
        hifiganSession = env.createSession(vocFile.absolutePath, sessionOptions)
        currentModelId = targetModel.id
    }

    private fun writeWavHeader(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        FileOutputStream(file).use { fos ->
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * channels * 2

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // Subchunk1Size (16 for PCM)
                putShort(1.toShort()) // AudioFormat (1 for PCM)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * 2).toShort()) // BlockAlign
                putShort(16.toShort()) // BitsPerSample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }

            fos.write(header.array())
            fos.write(pcmData)
        }
    }

    private fun closeSessionsInternal() {
        try {
            encoderSession?.close()
        } catch (_: Exception) {}
        encoderSession = null

        try {
            decoderSession?.close()
        } catch (_: Exception) {}
        decoderSession = null

        try {
            hifiganSession?.close()
        } catch (_: Exception) {}
        hifiganSession = null

        currentModelId = null
    }

    override fun close() {
        synchronized(sessionLock) {
            closeSessionsInternal()
        }
    }
}
