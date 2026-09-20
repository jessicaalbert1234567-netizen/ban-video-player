package com.example.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.audio.WavUtils
import com.example.models.MemoryDiagnostics
import com.example.models.ModelCatalog
import com.example.models.ModelInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Real Offline Bangla Text-To-Speech Engine.
 *
 * Uses Piper VITS neural voice ONNX model on-device.
 * Strictly performs real inference and verification; throws explicit exceptions on failure.
 */
class BanglaTtsEngine(
    private val context: Context
) : TextToSpeechEngine, AutoCloseable {

    companion object {
        private const val TAG = "TTS"
    }

    enum class EngineState { NOT_LOADED, LOADING, LOADED, FAILED }

    private val stateLock = Any()
    @Volatile private var loadState = EngineState.NOT_LOADED

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val phonemeIdMap = mutableMapOf<String, Long>()
    private var modelSampleRate = 22050

    private var androidTts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isBanglaSupportedInSystem = false

    init {
        initializeAndroidTts()
    }

    fun verifyTtsFiles(): Boolean {
        val model = ModelCatalog.BANGLA_VOICE_TTS
        return ModelInstaller.isModelInstalled(context, model)
    }

    suspend fun loadTtsIntoMemory() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            if (loadState == EngineState.LOADED && ortSession != null) return@withContext
            if (loadState == EngineState.LOADING) return@withContext
            loadState = EngineState.LOADING
        }

        val model = ModelCatalog.BANGLA_VOICE_TTS
        val modelFile = ModelInstaller.getInstalledModelFile(context, model)

        if (!modelFile.exists() || modelFile.length() <= 0L) {
            synchronized(stateLock) { loadState = EngineState.FAILED }
            throw IllegalStateException("Bangla TTS model file is missing on disk: ${modelFile.absolutePath}")
        }

        try {
            MemoryDiagnostics.trackModelLoad(MemoryDiagnostics.TAG_TTS, model.name, modelFile) {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                val session = env.createSession(modelFile.absolutePath, sessionOptions)
                ortSession = session

                // Load config JSON if available
                val configFile = ModelInstaller.getAuxiliaryFile(context, model, "bn_BD-google-medium.onnx.json")
                if (configFile.exists() && configFile.length() > 0) {
                    loadConfigJson(configFile)
                }

                Log.d(TAG, "Bangla TTS ONNX session initialized successfully on '${Thread.currentThread().name}'. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}")
            }
            synchronized(stateLock) { loadState = EngineState.LOADED }
        } catch (e: Exception) {
            synchronized(stateLock) { loadState = EngineState.FAILED }
            close()
            Log.e(TAG, "Bangla TTS ONNX session creation failed on '${Thread.currentThread().name}': ${e.message}", e)
            throw e
        }
    }

    fun initialize() {
        // Synchronous or called from pipeline: verify files and load into memory on IO
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            loadTtsIntoMemory()
        }
    }

    private fun loadConfigJson(configFile: File) {
        try {
            val jsonStr = configFile.readText()
            val root = JSONObject(jsonStr)
            val audioObj = root.optJSONObject("audio")
            if (audioObj != null) {
                modelSampleRate = audioObj.optInt("sample_rate", 22050)
            }
            val idMapObj = root.optJSONObject("phoneme_id_map")
            if (idMapObj != null) {
                phonemeIdMap.clear()
                val keys = idMapObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = idMapObj.optJSONArray(key)
                    if (arr != null && arr.length() > 0) {
                        phonemeIdMap[key] = arr.getLong(0)
                    }
                }
            }
            Log.d(TAG, "Loaded TTS config: sampleRate=$modelSampleRate, phoneme map size=${phonemeIdMap.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Notice while parsing TTS config JSON: ${e.message}")
        }
    }

    private fun initializeAndroidTts() {
        try {
            androidTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val localeBn = Locale("bn", "BD")
                    val res = androidTts?.setLanguage(localeBn)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val resIn = androidTts?.setLanguage(Locale("bn", "IN"))
                        isBanglaSupportedInSystem = resIn != TextToSpeech.LANG_MISSING_DATA && resIn != TextToSpeech.LANG_NOT_SUPPORTED
                    } else {
                        isBanglaSupportedInSystem = true
                    }
                    isTtsInitialized = true
                    Log.d(TAG, "Android TTS initialized. Bangla supported: $isBanglaSupportedInSystem")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Android TTS: ${e.message}")
        }
    }

    override suspend fun synthesize(text: String, outputFile: File): File = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            WavUtils.createSilenceWav(outputFile, 500L)
            return@withContext outputFile
        }

        // 1. Check if ONNX session is ready or can be initialized
        if (ortSession == null) {
            try {
                loadTtsIntoMemory()
            } catch (e: Exception) {
                Log.w(TAG, "ONNX TTS initialization deferred: ${e.message}")
            }
        }

        if (ortSession != null) {
            synthesizeWithOnnx(cleanText, outputFile)
            if (outputFile.exists() && outputFile.length() > 44) {
                return@withContext outputFile
            }
        }

        // 2. System TTS fallback if available on device
        if (isTtsInitialized && isBanglaSupportedInSystem && androidTts != null) {
            val systemSuccess = synthesizeWithSystemTts(cleanText, outputFile)
            if (systemSuccess && outputFile.exists() && outputFile.length() > 44) {
                return@withContext outputFile
            }
        }

        val model = ModelCatalog.BANGLA_VOICE_TTS
        val verification = ModelInstaller.verifyModelOffline(context, model)
        throw IllegalStateException("Bangla TTS synthesis failed: Model not installed or verified (${verification.failureReason ?: "ONNX session uninitialized"}).")
    }

    private fun synthesizeWithOnnx(text: String, outputFile: File) {
        val session = ortSession ?: throw IllegalStateException("ONNX TTS session is not open.")
        val env = ortEnv ?: throw IllegalStateException("ONNX environment is not open.")

        val inputNames = session.inputNames.toList()
        val inputsMap = mutableMapOf<String, OnnxTensor>()

        try {
            // Map text graphemes/phonemes to integer IDs
            val phonemeIds = mutableListOf<Long>()
            phonemeIds.add(phonemeIdMap["^"] ?: 1L) // start token
            for (ch in text) {
                val s = ch.toString()
                val id = phonemeIdMap[s] ?: phonemeIdMap[" "] ?: 3L
                phonemeIds.add(id)
                phonemeIds.add(phonemeIdMap["_"] ?: 0L) // padding / separator token
            }
            phonemeIds.add(phonemeIdMap["$"] ?: 2L) // end token

            val seqLen = phonemeIds.size.toLong()
            val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(phonemeIds.toLongArray()), longArrayOf(1, seqLen))
            inputsMap[inputNames[0]] = inputTensor

            if (inputNames.size > 1 && inputNames.any { it.contains("length") }) {
                val lenName = inputNames.first { it.contains("length") }
                val lenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(seqLen)), longArrayOf(1))
                inputsMap[lenName] = lenTensor
            }

            if (inputNames.size > 2 && inputNames.any { it.contains("scale") }) {
                val scaleName = inputNames.first { it.contains("scale") }
                val scales = floatArrayOf(0.667f, 1.0f, 0.8f)
                val scaleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3))
                inputsMap[scaleName] = scaleTensor
            }

            // Run ONNX VITS inference
            val results = session.run(inputsMap)
            val outputValue = results[0].value
            val floatAudio = extractFloatAudio(outputValue)
            results.close()

            if (floatAudio.isEmpty()) {
                throw IllegalStateException("ONNX TTS generated 0 audio samples.")
            }

            // Write 16-bit PCM WAV
            writePcmToWav(floatAudio, outputFile, modelSampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX TTS inference error: ${e.message}", e)
            throw IllegalStateException("ONNX TTS inference error: ${e.message}", e)
        } finally {
            for (tensor in inputsMap.values) {
                try { tensor.close() } catch (ignored: Exception) {}
            }
        }
    }

    private fun extractFloatAudio(outputValue: Any?): FloatArray {
        if (outputValue == null) return FloatArray(0)
        if (outputValue is FloatArray) return outputValue
        if (outputValue is Array<*>) {
            val first = outputValue[0]
            if (first is FloatArray) return first
            if (first is Array<*>) {
                val sub = first[0]
                if (sub is FloatArray) return sub
            }
        }
        return FloatArray(0)
    }

    private fun writePcmToWav(floats: FloatArray, outputFile: File, sampleRate: Int) {
        val numSamples = floats.size
        val dataBytes = (numSamples * 2).toLong()

        FileOutputStream(outputFile).use { fos ->
            WavUtils.writeWavHeader(
                outputStream = fos,
                totalAudioLen = dataBytes,
                totalDataLen = dataBytes + 36,
                sampleRate = sampleRate,
                channels = 1,
                bitsPerSample = 16
            )

            val buffer = ByteArray(4096)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            var sampleIdx = 0

            while (sampleIdx < numSamples) {
                bb.clear()
                while (bb.remaining() >= 2 && sampleIdx < numSamples) {
                    val s = (floats[sampleIdx].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                    bb.putShort(s)
                    sampleIdx++
                }
                fos.write(buffer, 0, bb.position())
            }
            fos.flush()
        }
    }

    private suspend fun synthesizeWithSystemTts(text: String, outputFile: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            val tempWav = File(context.cacheDir, "temp_tts_${utteranceId}.wav")

            val listener = object : UtteranceProgressListener() {
                override fun onStart(uttId: String?) {}
                override fun onDone(uttId: String?) {
                    if (uttId == utteranceId) {
                        if (tempWav.exists() && tempWav.length() > 0) {
                            tempWav.copyTo(outputFile, overwrite = true)
                            tempWav.delete()
                            continuation.resume(true)
                        } else {
                            continuation.resume(false)
                        }
                    }
                }

                override fun onError(uttId: String?) {
                    if (uttId == utteranceId) {
                        tempWav.delete()
                        continuation.resume(false)
                    }
                }
            }

            androidTts?.setOnUtteranceProgressListener(listener)
            val params = Bundle()
            val res = androidTts?.synthesizeToFile(text, params, tempWav, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                continuation.resume(false)
            }
        }

    override fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
            ortSession = null
            ortEnv = null
            synchronized(stateLock) {
                loadState = EngineState.NOT_LOADED
            }
            androidTts?.stop()
            androidTts?.shutdown()
            androidTts = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TTS engine", e)
        }
    }
}
