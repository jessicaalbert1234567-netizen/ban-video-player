package com.example.translation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device English to Bangla neural translation engine powered by Google ML Kit.
 *
 * Runs 100% offline on the Android device once the language models are downloaded.
 * Does NOT send transcript text to any remote or cloud translation server.
 */
typealias TranslationModel = EnglishToBanglaTranslator

class EnglishToBanglaTranslator(
    private val context: Context? = null
) : Translator, AutoCloseable {

    companion object {
        private const val TAG = "MlKitEnBnTranslator"

        /**
         * Awaits completion of a Google Play Services / ML Kit Task without blocking threads.
         */
        suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result)
            }
            addOnFailureListener { exception ->
                if (cont.isActive) cont.resumeWithException(exception)
            }
            addOnCanceledListener {
                if (cont.isActive) cont.cancel()
            }
        }

        /**
         * Checks if the Google ML Kit Bengali translation model is downloaded and ready for offline use.
         */
        suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
            try {
                val modelManager = RemoteModelManager.getInstance()
                val bengaliModel = TranslateRemoteModel.Builder(TranslateLanguage.BENGALI).build()
                modelManager.isModelDownloaded(bengaliModel).await()
            } catch (e: Throwable) {
                Log.e(TAG, "Error checking if ML Kit Bengali model is downloaded: ${e.message}")
                false
            }
        }

        /**
         * Deletes the downloaded offline Bengali language model.
         */
        suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
            try {
                val modelManager = RemoteModelManager.getInstance()
                val bengaliModel = TranslateRemoteModel.Builder(TranslateLanguage.BENGALI).build()
                modelManager.deleteDownloadedModel(bengaliModel).await()
                Log.i(TAG, "Successfully deleted ML Kit Bengali translation model.")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Error deleting ML Kit Bengali model: ${e.message}")
                false
            }
        }
    }

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.BENGALI)
        .build()

    // Reuse a single client instance for the entire translation job
    private val mlKitTranslator: com.google.mlkit.nl.translate.Translator by lazy {
        Translation.getClient(options)
    }

    @Volatile
    private var isClosed = false

    /**
     * Downloads the English -> Bengali models using Google ML Kit.
     * Respects user network preference (does not force Wi-Fi by default).
     */
    suspend fun downloadModel(requireWifi: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) {
                requireWifi()
            }
        }.build()

        try {
            Log.i(TAG, "Initiating Google ML Kit model download (requireWifi=$requireWifi)...")
            mlKitTranslator.downloadModelIfNeeded(conditions).await()
            Log.i(TAG, "Google ML Kit English -> Bengali model download completed successfully.")
        } catch (e: Throwable) {
            Log.e(TAG, "Google ML Kit translation model download failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Translates a single segment of English text into Bengali on-device.
     * Strictly runs locally and does not perform network operations during inference.
     */
    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (isClosed) {
            throw IllegalStateException("Translator has already been closed.")
        }
        val cleanInput = text.trim()
        if (cleanInput.isEmpty()) return@withContext ""

        try {
            mlKitTranslator.translate(cleanInput).await()
        } catch (e: Throwable) {
            val isDownloaded = try { isModelDownloaded() } catch (_: Throwable) { false }
            if (!isDownloaded) {
                throw IllegalStateException(
                    "Offline translation model is not downloaded. Please download the Google ML Kit English → Bangla model before translating.",
                    e
                )
            }
            throw IllegalStateException("Translation failed: ${e.message}", e)
        }
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            try {
                mlKitTranslator.close()
                Log.i(TAG, "Google ML Kit Translator closed.")
            } catch (e: Throwable) {
                Log.w(TAG, "Error closing Google ML Kit translator: ${e.message}")
            }
        }
    }
}
