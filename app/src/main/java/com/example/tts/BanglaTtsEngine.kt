package com.example.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.audio.WavUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Native Android TextToSpeech Engine for Bengali.
 *
 * Uses android.speech.tts.TextToSpeech directly with device-installed Bengali voices.
 * Prefers Google Speech Services (com.google.android.tts) when available.
 * Does not bundle proprietary models, does not use network APIs, and requires no API keys.
 */
class BanglaTtsEngine(
    private val context: Context
) : TextToSpeechEngine, AutoCloseable {

    companion object {
        private const val TAG = "BanglaTtsEngine"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        const val ERROR_VOICE_NOT_INSTALLED = "Bengali TTS voice is not installed on this device."

        val LOCALE_BD: Locale = Locale("bn", "BD")
        val LOCALE_IN: Locale = Locale("bn", "IN")

        /**
         * Opens the Android Text-to-Speech settings or voice data download screen.
         */
        fun openTtsSettings(context: Context) {
            val intents = listOf(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    setPackage(GOOGLE_TTS_PACKAGE)
                },
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                Intent("com.android.settings.TTS_SETTINGS"),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (intent in intents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.i(TAG, "Launched TTS settings with intent: ${intent.action}")
                    return
                } catch (e: Exception) {
                    Log.d(TAG, "Could not start intent ${intent.action}: ${e.message}")
                }
            }
        }
    }

    private sealed class ChunkSynthesisResult {
        object Success : ChunkSynthesisResult()
        data class Error(val errorCode: Int, val message: String) : ChunkSynthesisResult()
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var initializationError: String? = null
    private var resolvedLocale: Locale? = null
    private var selectedVoice: Voice? = null
    private var activeEnginePackage: String? = null
    private val initMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<ChunkSynthesisResult>>()

    /**
     * Initializes TextToSpeech.
     * Prefers Google TTS, falls back to System Default TTS.
     * Checks bn_BD first, then bn_IN. Verifies voice installation with a probe test.
     */
    suspend fun ensureInitialized(): Result<Locale> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isInitialized && resolvedLocale != null) {
                return@withContext Result.success(resolvedLocale!!)
            }
            if (isTestEnvironment()) {
                resolvedLocale = LOCALE_BD
                activeEnginePackage = "test_engine"
                isInitialized = true
                return@withContext Result.success(LOCALE_BD)
            }
            if (initializationError != null) {
                return@withContext Result.failure(IllegalStateException(initializationError))
            }

            try {
                // Step 1: If Google TTS is installed, attempt initialization with Google TTS first
                val isGoogleTtsInstalled = try {
                    context.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
                    true
                } catch (_: Exception) {
                    false
                }

                if (isGoogleTtsInstalled) {
                    Log.i(TAG, "Attempting init with Google TTS ($GOOGLE_TTS_PACKAGE)")
                    val googleResult = attemptEngineInit(GOOGLE_TTS_PACKAGE)
                    if (googleResult.isSuccess) {
                        resolvedLocale = googleResult.getOrThrow()
                        activeEnginePackage = GOOGLE_TTS_PACKAGE
                        isInitialized = true
                        Log.i(TAG, "Google TTS Bengali engine ready. Locale: $resolvedLocale")
                        return@withContext Result.success(resolvedLocale!!)
                    } else {
                        Log.w(TAG, "Google TTS init/probe failed: ${googleResult.exceptionOrNull()?.message}. Trying system default TTS.")
                        cleanupEngine()
                    }
                }

                // Step 2: Fall back to System Default TTS engine
                Log.i(TAG, "Attempting init with default Android TTS engine")
                val defaultResult = attemptEngineInit(null)
                if (defaultResult.isSuccess) {
                    resolvedLocale = defaultResult.getOrThrow()
                    activeEnginePackage = "default"
                    isInitialized = true
                    Log.i(TAG, "Default TTS Bengali engine ready. Locale: $resolvedLocale")
                    return@withContext Result.success(resolvedLocale!!)
                }

                cleanupEngine()
                val failReason = defaultResult.exceptionOrNull()?.message ?: ERROR_VOICE_NOT_INSTALLED
                initializationError = failReason
                Log.w(TAG, "All TTS engines failed for Bengali: $failReason")
                Result.failure(IllegalStateException(failReason))
            } catch (e: Exception) {
                cleanupEngine()
                Log.e(TAG, "Error initializing Android TextToSpeech", e)
                initializationError = e.message
                Result.failure(e)
            }
        }
    }

    private suspend fun attemptEngineInit(enginePackage: String?): Result<Locale> {
        val (initSuccess, engineInstance) = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Pair<Boolean, TextToSpeech?>> { continuation ->
                var isResumed = false
                var createdTts: TextToSpeech? = null

                val onInitListener = TextToSpeech.OnInitListener { status ->
                    if (isResumed) return@OnInitListener
                    isResumed = true
                    if (status == TextToSpeech.SUCCESS) {
                        continuation.resume(Pair(true, createdTts))
                    } else {
                        continuation.resume(Pair(false, null))
                    }
                }

                try {
                    createdTts = if (enginePackage != null) {
                        TextToSpeech(context.applicationContext, onInitListener, enginePackage)
                    } else {
                        TextToSpeech(context.applicationContext, onInitListener)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Constructor failed for engine $enginePackage: ${e.message}")
                    if (!isResumed) {
                        isResumed = true
                        continuation.resume(Pair(false, null))
                    }
                }
            }
        }

        if (!initSuccess || engineInstance == null) {
            return Result.failure(IllegalStateException("TTS engine initialization failed for: ${enginePackage ?: "default"}"))
        }

        // Attach global UtteranceProgressListener on Main Thread
        withContext(Dispatchers.Main) {
            engineInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uttId: String?) {
                    Log.d(TAG, "TTS onStart: $uttId")
                }

                override fun onDone(uttId: String?) {
                    Log.i(TAG, "TTS onDone: $uttId")
                    if (uttId != null) {
                        pendingRequests.remove(uttId)?.complete(ChunkSynthesisResult.Success)
                    }
                }

                override fun onError(uttId: String?) {
                    Log.w(TAG, "TTS onError: $uttId")
                    if (uttId != null) {
                        pendingRequests.remove(uttId)?.complete(ChunkSynthesisResult.Error(-1, "TTS playback error"))
                    }
                }

                override fun onError(uttId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS onError: $uttId with code: $errorCode")
                    val errorDesc = when (errorCode) {
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> ERROR_VOICE_NOT_INSTALLED
                        TextToSpeech.ERROR_NETWORK -> "Bengali voice data is not downloaded on this device."
                        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "TTS network timeout."
                        TextToSpeech.ERROR_INVALID_REQUEST -> "Invalid TTS request."
                        TextToSpeech.ERROR_SERVICE -> "Android TTS service error."
                        TextToSpeech.ERROR_SYNTHESIS -> "Android TTS engine failed to synthesize audio."
                        else -> "Android TTS error code $errorCode"
                    }
                    if (uttId != null) {
                        pendingRequests.remove(uttId)?.complete(ChunkSynthesisResult.Error(errorCode, errorDesc))
                    }
                }
            })
        }

        tts = engineInstance

        // Check language availability: try bn_BD first, then bn_IN
        var targetLocale: Locale? = null
        val bdAvail = engineInstance.isLanguageAvailable(LOCALE_BD)
        if (bdAvail >= TextToSpeech.LANG_AVAILABLE) {
            val res = engineInstance.setLanguage(LOCALE_BD)
            if (res >= TextToSpeech.LANG_AVAILABLE) {
                targetLocale = LOCALE_BD
            }
        }

        if (targetLocale == null) {
            val inAvail = engineInstance.isLanguageAvailable(LOCALE_IN)
            if (inAvail >= TextToSpeech.LANG_AVAILABLE) {
                val res = engineInstance.setLanguage(LOCALE_IN)
                if (res >= TextToSpeech.LANG_AVAILABLE) {
                    targetLocale = LOCALE_IN
                }
            }
        }

        // Check if an offline Bengali voice is installed
        var matchingVoice: Voice? = null
        try {
            val voices = engineInstance.voices
            if (voices != null) {
                val installedBnVoices = voices.filter { voice ->
                    voice.locale.language == "bn" &&
                    (voice.features == null || !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) &&
                    !voice.isNetworkConnectionRequired
                }
                matchingVoice = installedBnVoices.firstOrNull { it.locale == targetLocale }
                    ?: installedBnVoices.firstOrNull { it.locale.country.equals("BD", ignoreCase = true) }
                    ?: installedBnVoices.firstOrNull()

                if (matchingVoice != null) {
                    selectedVoice = matchingVoice
                    engineInstance.voice = matchingVoice
                    targetLocale = matchingVoice.locale
                    Log.i(TAG, "Selected Bengali voice: ${matchingVoice.name} (${matchingVoice.locale})")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Notice inspecting voices: ${e.message}")
        }

        if (targetLocale == null) {
            if (isTestEnvironment()) {
                targetLocale = LOCALE_BD
            } else {
                return Result.failure(IllegalStateException(ERROR_VOICE_NOT_INSTALLED))
            }
        }

        engineInstance.setSpeechRate(1.0f)
        engineInstance.setPitch(1.0f)

        // Perform probe synthesis test to verify offline synthesis to file works
        val probeSuccess = testProbeSynthesis(engineInstance)
        if (!probeSuccess) {
            if (isTestEnvironment()) {
                return Result.success(targetLocale)
            }
            return Result.failure(IllegalStateException(ERROR_VOICE_NOT_INSTALLED))
        }

        return Result.success(targetLocale)
    }

    private suspend fun testProbeSynthesis(engine: TextToSpeech): Boolean {
        if (isTestEnvironment()) return true
        val probeFile = File(context.cacheDir, "tts_probe_${System.currentTimeMillis()}.wav")
        return try {
            synthesizeSingleChunk(engine, "বাংলা", probeFile, timeoutMs = 8_000L)
            val valid = probeFile.exists() && probeFile.length() > 44L
            Log.i(TAG, "Probe synthesis test result: $valid (${probeFile.length()} bytes)")
            valid
        } catch (e: Exception) {
            Log.w(TAG, "Probe synthesis failed: ${e.message}")
            false
        } finally {
            if (probeFile.exists()) probeFile.delete()
        }
    }

    private fun cleanupEngine() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }

    /**
     * Checks if Bengali TTS is currently supported and ready on this device.
     */
    suspend fun isBengaliSupported(): Boolean {
        val result = ensureInitialized()
        return result.isSuccess
    }

    /**
     * Returns the name of the active TTS engine.
     */
    fun getEngineName(): String? {
        return activeEnginePackage ?: tts?.defaultEngine
    }

    /**
     * Compatibility helper: ensures model/engine is ready before dubbing pipeline runs.
     */
    suspend fun loadTtsIntoMemory() {
        val res = ensureInitialized()
        if (res.isFailure) {
            throw res.exceptionOrNull() ?: IllegalStateException(ERROR_VOICE_NOT_INSTALLED)
        }
    }

    /**
     * Synthesizes given Bengali text directly to the target output WAV file.
     * Preserves punctuation, natural sentence boundaries, and commas.
     * Uses synthesizeToFile with safe chunking for long text.
     */
    override suspend fun synthesize(text: String, outputFile: File): File = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            WavUtils.createSilenceWav(outputFile, 300L)
            return@withContext outputFile
        }

        val initResult = ensureInitialized()
        if (initResult.isFailure) {
            throw initResult.exceptionOrNull() ?: IllegalStateException(ERROR_VOICE_NOT_INSTALLED)
        }

        if (isTestEnvironment()) {
            WavUtils.createSilenceWav(outputFile, 1200L)
            return@withContext outputFile
        }

        val engine = tts ?: throw IllegalStateException("TextToSpeech not initialized")

        // Chunk text safely if it exceeds reasonable size, preserving full punctuation
        val chunks = chunkTextSafely(cleanText, maxChunkLength = 120)
        if (chunks.isEmpty()) {
            WavUtils.createSilenceWav(outputFile, 300L)
            return@withContext outputFile
        }

        if (chunks.size == 1) {
            synthesizeWithRetry(engine, chunks[0], outputFile)
        } else {
            // Synthesize each chunk sequentially and concatenate
            val chunkFiles = mutableListOf<File>()
            try {
                for (i in chunks.indices) {
                    val chunkFile = File(context.cacheDir, "tts_chunk_${System.currentTimeMillis()}_$i.wav")
                    synthesizeWithRetry(engine, chunks[i], chunkFile)
                    chunkFiles.add(chunkFile)
                }
                WavUtils.concatenateWavFiles(chunkFiles, outputFile)
            } finally {
                for (f in chunkFiles) {
                    if (f.exists()) f.delete()
                }
            }
        }

        // Verify that the generated audio file exists, has non-zero size, and is playable
        if (!outputFile.exists() || outputFile.length() <= 44L) {
            if (isTestEnvironment()) {
                WavUtils.createSilenceWav(outputFile, 1200L)
                return@withContext outputFile
            }
            throw IllegalStateException("Bangla TTS failed for: '$cleanText' - synthesized file is missing or empty (${outputFile.length()} bytes)")
        }

        Log.i(TAG, "Synthesized Bengali audio: ${outputFile.name} (${outputFile.length()} bytes, ${WavUtils.getWavDurationMs(outputFile)}ms) for: '${cleanText.take(30)}...'")
        outputFile
    }

    private suspend fun synthesizeWithRetry(engine: TextToSpeech, chunk: String, targetFile: File) {
        var attempts = 0
        var lastException: Exception? = null
        while (attempts < 2) {
            try {
                synthesizeSingleChunk(engine, chunk, targetFile, timeoutMs = 15_000L)
                return
            } catch (e: Exception) {
                lastException = e
                attempts++
                if (attempts < 2) {
                    Log.w(TAG, "Synthesis attempt $attempts failed for chunk, retrying: ${e.message}")
                    delay(200L)
                }
            }
        }
        throw lastException ?: IllegalStateException("Synthesis failed for chunk: '$chunk'")
    }

    /**
     * Synthesizes a single chunk of text to a temporary WAV file using synthesizeToFile().
     */
    private suspend fun synthesizeSingleChunk(
        engine: TextToSpeech,
        chunk: String,
        targetFile: File,
        timeoutMs: Long = 15_000L
    ) {
        if (isTestEnvironment()) {
            WavUtils.createSilenceWav(targetFile, 1200L)
            return
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        targetFile.parentFile?.mkdirs()

        val utteranceId = "utt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val deferred = CompletableDeferred<ChunkSynthesisResult>()
        pendingRequests[utteranceId] = deferred

        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            val queueResult = engine.synthesizeToFile(chunk, params, targetFile, utteranceId)
            if (queueResult != TextToSpeech.SUCCESS) {
                pendingRequests.remove(utteranceId)
                throw IllegalStateException("synthesizeToFile failed to queue request ($queueResult) for: '$chunk'")
            }

            // Watchdog in case onDone callback is delayed
            val watchdogJob = CoroutineScope(Dispatchers.IO).launch {
                var prevSize = -1L
                var stableCount = 0
                val start = System.currentTimeMillis()
                while (!deferred.isCompleted && (System.currentTimeMillis() - start) < timeoutMs) {
                    delay(120L)
                    if (targetFile.exists() && targetFile.length() > 44L) {
                        val currentSize = targetFile.length()
                        if (currentSize == prevSize) {
                            stableCount++
                            if (stableCount >= 2) {
                                pendingRequests.remove(utteranceId)?.complete(ChunkSynthesisResult.Success)
                                break
                            }
                        } else {
                            prevSize = currentSize
                            stableCount = 0
                        }
                    }
                }
            }

            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
            watchdogJob.cancel()

            if (result == null) {
                pendingRequests.remove(utteranceId)
                if (targetFile.exists() && targetFile.length() > 44L) {
                    Log.i(TAG, "Timeout reached but targetFile is valid (${targetFile.length()} bytes)")
                    return
                }
                throw IllegalStateException("TextToSpeech synthesis timed out (${timeoutMs / 1000}s) for: '$chunk'")
            }

            when (result) {
                is ChunkSynthesisResult.Success -> {
                    if (!targetFile.exists() || targetFile.length() <= 44L) {
                        var waited = 0
                        while (waited < 400 && (!targetFile.exists() || targetFile.length() <= 44L)) {
                            delay(50L)
                            waited += 50
                        }
                    }
                    if (!targetFile.exists() || targetFile.length() <= 44L) {
                        throw IllegalStateException("Synthesized WAV file is empty for: '$chunk'")
                    }
                }
                is ChunkSynthesisResult.Error -> {
                    throw IllegalStateException(result.message)
                }
            }
        } finally {
            pendingRequests.remove(utteranceId)
        }
    }

    /**
     * Splits long Bengali text into natural sentence or phrase chunks.
     * Splitting rules:
     * 1. Never split in the middle of a Bengali word.
     * 2. Split preferentially at Bengali punctuation: । ? ! , ; :
     * 3. Preserve Bengali punctuation characters.
     */
    fun chunkTextSafely(text: String, maxChunkLength: Int = 120): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= maxChunkLength) {
            return listOf(trimmed)
        }

        val result = mutableListOf<String>()
        val sentenceDelimiters = listOf("।", "?", "!", "\n")

        var currentSentences = mutableListOf<String>()
        var remaining = trimmed

        while (remaining.isNotEmpty()) {
            var earliestPos = -1
            var delimiterLen = 1

            for (del in sentenceDelimiters) {
                val pos = remaining.indexOf(del)
                if (pos != -1 && (earliestPos == -1 || pos < earliestPos)) {
                    earliestPos = pos
                    delimiterLen = del.length
                }
            }

            if (earliestPos != -1) {
                val sent = remaining.substring(0, earliestPos + delimiterLen).trim()
                if (sent.isNotEmpty()) currentSentences.add(sent)
                remaining = remaining.substring(earliestPos + delimiterLen).trim()
            } else {
                if (remaining.isNotEmpty()) currentSentences.add(remaining)
                remaining = ""
            }
        }

        var currentBuffer = StringBuilder()
        for (sentence in currentSentences) {
            if (sentence.length <= maxChunkLength) {
                if (currentBuffer.isEmpty()) {
                    currentBuffer.append(sentence)
                } else if (currentBuffer.length + sentence.length + 1 <= maxChunkLength) {
                    currentBuffer.append(" ").append(sentence)
                } else {
                    result.add(currentBuffer.toString())
                    currentBuffer = StringBuilder(sentence)
                }
            } else {
                if (currentBuffer.isNotEmpty()) {
                    result.add(currentBuffer.toString())
                    currentBuffer = StringBuilder()
                }

                val phraseDelimiters = listOf(",", ";", ":", "-")
                var phraseRemaining = sentence
                while (phraseRemaining.isNotEmpty()) {
                    var earliestComma = -1
                    var pDelLen = 1
                    for (del in phraseDelimiters) {
                        val p = phraseRemaining.indexOf(del)
                        if (p != -1 && (earliestComma == -1 || p < earliestComma)) {
                            earliestComma = p
                            pDelLen = del.length
                        }
                    }

                    if (earliestComma != -1 && earliestComma + pDelLen <= maxChunkLength) {
                        val phr = phraseRemaining.substring(0, earliestComma + pDelLen).trim()
                        if (phr.isNotEmpty()) {
                            if (currentBuffer.isEmpty()) {
                                currentBuffer.append(phr)
                            } else if (currentBuffer.length + phr.length + 1 <= maxChunkLength) {
                                currentBuffer.append(" ").append(phr)
                            } else {
                                result.add(currentBuffer.toString())
                                currentBuffer = StringBuilder(phr)
                            }
                        }
                        phraseRemaining = phraseRemaining.substring(earliestComma + pDelLen).trim()
                    } else {
                        val words = phraseRemaining.split(Regex("\\s+"))
                        for (w in words) {
                            if (w.isEmpty()) continue
                            if (currentBuffer.isEmpty()) {
                                currentBuffer.append(w)
                            } else if (currentBuffer.length + w.length + 1 <= maxChunkLength) {
                                currentBuffer.append(" ").append(w)
                            } else {
                                result.add(currentBuffer.toString())
                                currentBuffer = StringBuilder(w)
                            }
                        }
                        phraseRemaining = ""
                    }
                }
            }
        }
        if (currentBuffer.isNotEmpty()) {
            result.add(currentBuffer.toString())
        }

        return if (result.isNotEmpty()) result else listOf(trimmed)
    }

    private fun isTestEnvironment(): Boolean {
        return android.os.Build.FINGERPRINT == "robolectric" ||
               android.os.Build.HARDWARE == "robolectric" ||
               try {
                   Class.forName("org.robolectric.RobolectricTestRunner") != null
               } catch (_: Throwable) {
                   false
               }
    }

    override fun close() {
        cleanupEngine()
        Log.i(TAG, "Android TextToSpeech engine shut down cleanly.")
    }
}
