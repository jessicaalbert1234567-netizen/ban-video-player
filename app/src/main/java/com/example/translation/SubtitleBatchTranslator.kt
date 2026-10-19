package com.example.translation

import android.content.Context
import android.util.Log
import com.example.database.TranscriptSegmentEntity
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ultra-Fast, Resilient Offline Subtitle Translation Engine.
 *
 * Optimized for 4GB RAM Android devices:
 * 1. Single Reused Translator: Reuses a single Google ML Kit Translator instance for the entire job.
 * 2. Strictly Bounded Concurrency: Uses kotlinx.coroutines.sync.Semaphore(3) (up to 4) to eliminate
 *    memory spikes and ANRs.
 * 3. 100% Offline: Operates purely on-device via Google ML Kit Bengali neural model.
 *    No network endpoints or remote fallbacks (translate.googleapis.com completely removed).
 * 4. Zero Unnecessary Delay: No artificial delay() or batch completion freezes.
 * 5. Monotonic Instant Progress: Emits progress callback immediately as each individual subtitle resolves.
 * 6. Preserves Subtitle Integrity: Subtitle timestamps, indices, and chronological order are strictly retained.
 * 7. Single Naturalization Pass: BanglaNaturalizer runs exactly once per translated dialogue.
 * 8. Cache-Accelerated: In-memory phrase cache instantly resolves conversational phrases without ML Kit overhead.
 */
class SubtitleBatchTranslator(
    private val context: Context,
    private val customTranslator: (suspend (String) -> String)? = null,
    private val concurrencyLimit: Int = 3
) : AutoCloseable {

    companion object {
        private const val TAG = "SubtitleBatchTranslator"

        // In-memory cache for repeated conversational subtitle dialogue (0ms latency)
        val phraseCache = ConcurrentHashMap<String, String>()

        init {
            // Seed common subtitle dialogue for instantaneous zero-latency translation
            phraseCache["yes"] = "হ্যাঁ"
            phraseCache["yes."] = "হ্যাঁ।"
            phraseCache["no"] = "না"
            phraseCache["no."] = "না।"
            phraseCache["okay"] = "ঠিক আছে"
            phraseCache["okay."] = "ঠিক আছে।"
            phraseCache["ok"] = "ঠিক আছে"
            phraseCache["ok."] = "ঠিক আছে।"
            phraseCache["hello"] = "হ্যালো"
            phraseCache["hello."] = "হ্যালো।"
            phraseCache["hi"] = "হাই"
            phraseCache["hi."] = "হাই।"
            phraseCache["thank you"] = "ধন্যবাদ"
            phraseCache["thank you."] = "ধন্যবাদ।"
            phraseCache["thanks"] = "ধন্যবাদ"
            phraseCache["thanks."] = "ধন্যবাদ।"
            phraseCache["please"] = "দয়া করে"
            phraseCache["please."] = "দয়া করে।"
            phraseCache["come on"] = "চলে আসো"
            phraseCache["come on!"] = "চলে আসো!"
            phraseCache["let's go"] = "চলো যাই"
            phraseCache["let's go!"] = "চলো যাই!"
            phraseCache["stop"] = "থামো"
            phraseCache["stop!"] = "থামো!"
            phraseCache["wait"] = "অপেক্ষা করো"
            phraseCache["wait!"] = "অপেক্ষা করো!"
            phraseCache["wait for me"] = "আমার জন্য অপেক্ষা করো"
            phraseCache["what?"] = "কী?"
            phraseCache["what!"] = "কী!"
            phraseCache["why?"] = "কেন?"
            phraseCache["who?"] = "কে?"
            phraseCache["where?"] = "কোথায়?"
            phraseCache["when?"] = "কখন?"
            phraseCache["how?"] = "কীভাবে?"
            phraseCache["goodbye"] = "বিদায়"
            phraseCache["goodbye."] = "বিদায়।"
            phraseCache["bye"] = "বিদায়"
            phraseCache["bye."] = "বিদায়।"
            phraseCache["help"] = "সাহায্য করো"
            phraseCache["help!"] = "সাহায্য করো!"
            phraseCache["help me"] = "আমাকে সাহায্য করো"
            phraseCache["i know"] = "আমি জানি"
            phraseCache["i know."] = "আমি জানি।"
            phraseCache["i don't know"] = "আমি জানি না"
            phraseCache["i don't know."] = "আমি জানি না।"
            phraseCache["i understand"] = "আমি বুঝতে পেরেছি"
            phraseCache["sorry"] = "দুঃখিত"
            phraseCache["sorry."] = "দুঃখিত।"
            phraseCache["i am sorry"] = "আমি দুঃখিত"
            phraseCache["i'm sorry"] = "আমি দুঃখিত"
            phraseCache["excuse me"] = "মাফ করবেন"
            phraseCache["are you ready?"] = "তুমি কি প্রস্তুত?"
            phraseCache["be careful"] = "সাবধানে থেকো"
            phraseCache["be careful!"] = "সাবধানে থেকো!"
            phraseCache["look out"] = "সাবধান"
            phraseCache["look out!"] = "সাবধান!"
            phraseCache["let me see"] = "আমাকে দেখতে দাও"
            phraseCache["of course"] = "অবশ্যই"
            phraseCache["of course."] = "অবশ্যই।"
            phraseCache["sure"] = "নিশ্চয়ই"
            phraseCache["really?"] = "সত্যি?"
            phraseCache["right"] = "ঠিক"
            phraseCache["all right"] = "সব ঠিক আছে"
            phraseCache["good morning"] = "শুভ সকাল"
            phraseCache["good night"] = "শুভ রাত্রি"
            phraseCache["see you soon"] = "শীঘ্রই দেখা হবে"
            phraseCache["see you later"] = "পরে দেখা হবে"
            phraseCache["what happened?"] = "কী হয়েছে?"
            phraseCache["what are you doing?"] = "তুমি কী করছো?"
            phraseCache["where are you going?"] = "তুমি কোথায় যাচ্ছ?"
            phraseCache["i love you"] = "আমি তোমাকে ভালোবাসি"
            phraseCache["i love you."] = "আমি তোমাকে ভালোবাসি।"
        }

        private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            addOnCanceledListener { if (cont.isActive) cont.cancel() }
        }
    }

    private var mlKitTranslator: com.google.mlkit.nl.translate.Translator? = null
    private var isMlKitInitialized = false
    private var isClosed = false

    private fun getMlKitTranslator(): com.google.mlkit.nl.translate.Translator {
        if (mlKitTranslator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.BENGALI)
                .build()
            mlKitTranslator = Translation.getClient(options)
        }
        return mlKitTranslator!!
    }

    /**
     * Checks if the Google ML Kit offline Bengali translation model is installed on device.
     */
    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        if (customTranslator != null) return@withContext true
        try {
            val modelManager = RemoteModelManager.getInstance()
            val bengaliModel = TranslateRemoteModel.Builder(TranslateLanguage.BENGALI).build()
            modelManager.isModelDownloaded(bengaliModel).awaitTask()
        } catch (e: Throwable) {
            Log.w(TAG, "Notice checking ML Kit model download status: ${e.message}")
            false
        }
    }

    /**
     * Pre-warms or verifies the offline ML Kit translation model.
     */
    suspend fun prepareModel(): Boolean = withContext(Dispatchers.IO) {
        if (customTranslator != null) {
            isMlKitInitialized = true
            return@withContext true
        }
        try {
            val isDownloaded = isModelDownloaded()
            if (isDownloaded) {
                isMlKitInitialized = true
                return@withContext true
            }
            // If not downloaded, trigger download
            val translator = getMlKitTranslator()
            val conditions = DownloadConditions.Builder().build()
            val downloadTask = translator.downloadModelIfNeeded(conditions)
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                downloadTask.addOnSuccessListener {
                    isMlKitInitialized = true
                    if (cont.isActive) cont.resume(true)
                }
                downloadTask.addOnFailureListener { ex ->
                    Log.w(TAG, "ML Kit Bengali model download failed: ${ex.message}")
                    if (cont.isActive) cont.resume(false)
                }
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "prepareModel note: ${e.message}")
            false
        }
    }

    /**
     * Translates a list of transcript segments into Bengali with monotonic progress callbacks.
     *
     * Concurrency: Controlled via Semaphore(concurrencyLimit) (default 3 concurrent ML Kit tasks).
     * Order: Strictly retains original segment order and timestamps.
     * Deduplication: Skips already-translated segments and uses phraseCache.
     * Offline: Runs 100% locally with zero internet dependency.
     */
    suspend fun translateSegments(
        segments: List<TranscriptSegmentEntity>,
        onProgress: suspend (current: Int, total: Int, currentText: String) -> Unit
    ): List<TranscriptSegmentEntity> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        val total = segments.size
        val results = arrayOfNulls<TranscriptSegmentEntity>(total)
        val completedCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val actualConcurrency = concurrencyLimit.coerceIn(1, 4)
        val semaphore = Semaphore(actualConcurrency)
        val startTimeMs = System.currentTimeMillis()

        Log.i(TAG, "Beginning offline translation of $total subtitle cues (concurrency=$actualConcurrency)")

        coroutineScope {
            segments.forEachIndexed { idx, seg ->
                launch {
                    // 1. Skip already translated segments (preserves prior progress on resume)
                    val existing = seg.translatedText?.trim()
                    if (!existing.isNullOrBlank()) {
                        results[idx] = seg
                        val count = completedCount.incrementAndGet()
                        onProgress(count, total, existing)
                        return@launch
                    }

                    // 2. Skip empty / blank source dialogue
                    val cleanSource = seg.sourceText.trim()
                    if (cleanSource.isBlank()) {
                        val emptySeg = seg.copy(translatedText = "")
                        results[idx] = emptySeg
                        val count = completedCount.incrementAndGet()
                        onProgress(count, total, "")
                        return@launch
                    }

                    // 3. Fast-path: dialogue phrase cache (0ms latency)
                    val cached = phraseCache[cleanSource.lowercase()]
                    if (cached != null) {
                        val cachedSeg = seg.copy(translatedText = cached)
                        results[idx] = cachedSeg
                        val count = completedCount.incrementAndGet()
                        onProgress(count, total, cached)
                        return@launch
                    }

                    // 4. ML Kit Translation governed by Semaphore
                    semaphore.withPermit {
                        // Secondary check in case an identical phrase was just resolved concurrently
                        val recheck = phraseCache[cleanSource.lowercase()]
                        if (recheck != null) {
                            val cachedSeg = seg.copy(translatedText = recheck)
                            results[idx] = cachedSeg
                            val count = completedCount.incrementAndGet()
                            onProgress(count, total, recheck)
                            return@withPermit
                        }

                        var translatedText: String? = null
                        var attempts = 0
                        while (attempts < 2 && translatedText == null) {
                            try {
                                translatedText = if (customTranslator != null) {
                                    customTranslator.invoke(cleanSource)
                                } else {
                                    translateViaMlKit(cleanSource)
                                }
                            } catch (e: Exception) {
                                attempts++
                                if (attempts >= 2) {
                                    Log.w(TAG, "ML Kit translation failed for cue $idx ('${cleanSource.take(24)}'): ${e.message}")
                                }
                            }
                        }

                        val finalSeg = if (translatedText != null && translatedText.isNotBlank()) {
                            // Run Rule-based Bangla Naturalizer EXACTLY ONCE here
                            val natural = BanglaNaturalizer.naturalize(translatedText.trim())
                            phraseCache[cleanSource.lowercase()] = natural
                            seg.copy(translatedText = natural)
                        } else {
                            val offlineCandidate = if (customTranslator == null) {
                                OfflineDialogueTranslator.translate(cleanSource).takeIf { it.isNotBlank() }
                            } else null

                            if (offlineCandidate != null) {
                                val natural = BanglaNaturalizer.naturalize(offlineCandidate)
                                phraseCache[cleanSource.lowercase()] = natural
                                seg.copy(translatedText = natural)
                            } else {
                                failureCount.incrementAndGet()
                                seg.copy(translatedText = null)
                            }
                        }

                        results[idx] = finalSeg
                        val count = completedCount.incrementAndGet()
                        val sample = finalSeg.translatedText ?: ""
                        onProgress(count, total, sample)

                        // Requirement 7: Periodically log elapsed time, average ms/cue, and failures every 25 cues
                        if (count % 25 == 0 || count == total) {
                            val elapsed = System.currentTimeMillis() - startTimeMs
                            val avgMs = elapsed.toDouble() / count
                            Log.i(TAG, "Translation Progress: $count/$total cues processed. Elapsed: ${elapsed}ms, Avg: ${String.format("%.1f", avgMs)}ms/cue, Failures: ${failureCount.get()}")
                        }
                    }
                }
            }
        }

        val totalElapsed = System.currentTimeMillis() - startTimeMs
        val avgTotal = if (total > 0) totalElapsed.toDouble() / total else 0.0
        Log.i(TAG, "Subtitle Translation Complete: $total cues in ${totalElapsed}ms (Avg ${String.format("%.1f", avgTotal)}ms/cue, Failures: ${failureCount.get()})")

        results.mapIndexed { i, seg -> seg ?: segments[i] }
    }

    /**
     * Translates a list of raw texts individually using concurrency limit and phrase cache.
     * Preserves input order strictly.
     */
    suspend fun translateBatch(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        val dummySegments = texts.mapIndexed { index, text ->
            TranscriptSegmentEntity(
                projectId = "batch_trans",
                index = index,
                startMs = index * 1000L,
                endMs = (index + 1) * 1000L,
                sourceText = text
            )
        }
        val translated = translateSegments(dummySegments) { _, _, _ -> }
        translated.map { it.translatedText ?: "" }
    }

    /**
     * Translates a single line of text on-device with limited retry.
     * Strictly offline; does not use network or fall back to remote HTTP.
     */
    suspend fun translateSingleWithFallback(text: String): String = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext ""

        phraseCache[clean.lowercase()]?.let { return@withContext it }

        var raw: String? = null
        var attempts = 0
        while (attempts < 2 && raw == null) {
            try {
                raw = if (customTranslator != null) customTranslator.invoke(clean) else translateViaMlKit(clean)
            } catch (_: Exception) {
                attempts++
            }
        }

        if (raw != null && raw.isNotBlank()) {
            val natural = BanglaNaturalizer.naturalize(raw.trim())
            phraseCache[clean.lowercase()] = natural
            natural
        } else {
            val offline = if (customTranslator == null) {
                OfflineDialogueTranslator.translate(clean).takeIf { it.isNotBlank() }
            } else null
            if (offline != null) {
                val natural = BanglaNaturalizer.naturalize(offline)
                phraseCache[clean.lowercase()] = natural
                natural
            } else {
                ""
            }
        }
    }

    private suspend fun translateViaMlKit(text: String): String = suspendCancellableCoroutine { cont ->
        if (isClosed) {
            cont.resumeWithException(IllegalStateException("SubtitleBatchTranslator is closed"))
            return@suspendCancellableCoroutine
        }
        val translator = getMlKitTranslator()
        val task = translator.translate(text)
        task.addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }
        task.addOnFailureListener { ex ->
            if (cont.isActive) cont.resumeWithException(ex)
        }
        task.addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            try {
                mlKitTranslator?.close()
                mlKitTranslator = null
            } catch (_: Exception) {}
        }
    }
}
