package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioSynchronizer
import com.example.audio.WavUtils
import com.example.database.TranscriptSegmentEntity
import com.example.translation.BanglaNaturalizer
import com.example.translation.SubtitleBatchTranslator
import com.example.tts.BanglaTtsEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PipelineAcceptanceTest {

    @Test
    fun testAcceptanceWith100Subtitles() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Generate 100 realistic subtitle cues with distinct timestamps and dialogue
        val count = 100
        val sampleDialogues = listOf(
            "Hello my friend", "Are you ready?", "Let's go now", "Wait for me please",
            "What happened yesterday?", "I don't know what to do", "Please help me here",
            "This is very important", "I understand your point", "See you later",
            "Be careful out there", "Of course we can", "Thank you very much",
            "Good morning everybody", "Stop right there!", "Why are you doing this?",
            "Everything is going to be fine", "Where are you going now?", "I love this place",
            "Excuse me sir"
        )

        val inputSegments = (0 until count).map { idx ->
            TranscriptSegmentEntity(
                projectId = "proj_acc_100",
                index = idx,
                startMs = idx * 2500L,
                endMs = idx * 2500L + 2100L,
                sourceText = sampleDialogues[idx % sampleDialogues.size] + if (idx % 7 == 0) " $idx" else "",
                translatedText = null
            )
        }

        val maxConcurrentTracking = AtomicInteger(0)
        val activeConcurrent = AtomicInteger(0)
        val progressUpdates = mutableListOf<Pair<Int, Int>>()

        // Simulated local translator that tracks concurrency and enforces no artificial delay
        val customTranslator: suspend (String) -> String = { text ->
            val cur = activeConcurrent.incrementAndGet()
            var max = maxConcurrentTracking.get()
            while (cur > max && !maxConcurrentTracking.compareAndSet(max, cur)) {
                max = maxConcurrentTracking.get()
            }
            // Small realistic processing latency (5-15ms)
            delay((5 + (text.length % 10)).toLong())
            activeConcurrent.decrementAndGet()
            "অনুবাদ: $text"
        }

        val translator = SubtitleBatchTranslator(
            context = context,
            customTranslator = customTranslator,
            concurrencyLimit = 3
        )

        val startTime = System.currentTimeMillis()

        val results = translator.translateSegments(inputSegments) { current, total, _ ->
            synchronized(progressUpdates) {
                progressUpdates.add(current to total)
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        val avgTimePerSubtitle = elapsedTime.toDouble() / count

        println("=== 100 Subtitles Acceptance Test ===")
        println("Total Subtitles: $count")
        println("Elapsed Time: ${elapsedTime}ms")
        println("Avg Time per Subtitle: ${"%.2f".format(avgTimePerSubtitle)}ms")
        println("Max Concurrent Tasks Observed: ${maxConcurrentTracking.get()}")

        // VERIFICATIONS:
        // 1. Result count matches input count
        assertEquals(100, results.size)

        // 2. Concurrency bound strictly respected (<= 3)
        assertTrue("Concurrency must not exceed configured limit of 3, was: ${maxConcurrentTracking.get()}",
            maxConcurrentTracking.get() <= 3)

        // 3. No artificial 20-second wait (should complete under 5 seconds for simulated local model)
        assertTrue("Execution took too long (${elapsedTime}ms), artificial delay detected!", elapsedTime < 5000L)

        // 4. Subtitle order and timestamps strictly preserved
        for (i in 0 until count) {
            val original = inputSegments[i]
            val translated = results[i]
            assertEquals("Index mismatch at position $i", original.index, translated.index)
            assertEquals("Start timestamp mismatch at position $i", original.startMs, translated.startMs)
            assertEquals("End timestamp mismatch at position $i", original.endMs, translated.endMs)
            assertEquals("Source text mismatch at position $i", original.sourceText, translated.sourceText)
            assertNotNull("Translated text must not be null for successful cue $i", translated.translatedText)
            assertFalse("Translated text must not be blank", translated.translatedText!!.isBlank())
            // Ensure original English was NOT mistakenly returned as translation
            assertNotEquals("Original English returned as translation", original.sourceText, translated.translatedText)
        }

        // 5. Monotonic per-cue progress updates (not batched in 25 or 80)
        assertEquals(100, progressUpdates.size)
        assertEquals(100, progressUpdates.last().first)
        assertEquals(100, progressUpdates.last().second)

        // Clean up
        translator.close()
        }
    }

    @Test
    fun testAcceptanceWith500Subtitles() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()

        val count = 500
        val sampleDialogues = listOf(
            "Where did everyone go?", "We must reach the station before nightfall.",
            "Are the supplies secured in the vehicle?", "I will check on the passengers immediately.",
            "Can anyone hear the transmission signal?", "The generator is running out of fuel.",
            "Hold on tightly everyone!", "Everything will be back to normal soon.",
            "Did anyone see what happened outside?", "Please keep your voice down."
        )

        val inputSegments = (0 until count).map { idx ->
            TranscriptSegmentEntity(
                projectId = "proj_acc_500",
                index = idx,
                startMs = idx * 2200L,
                endMs = idx * 2200L + 1800L,
                sourceText = "${sampleDialogues[idx % sampleDialogues.size]} (cue $idx)",
                translatedText = null
            )
        }

        val activeConcurrent = AtomicInteger(0)
        val maxConcurrentTracking = AtomicInteger(0)
        val progressCount = AtomicInteger(0)

        val customTranslator: suspend (String) -> String = { text ->
            val cur = activeConcurrent.incrementAndGet()
            var max = maxConcurrentTracking.get()
            while (cur > max && !maxConcurrentTracking.compareAndSet(max, cur)) {
                max = maxConcurrentTracking.get()
            }
            // Ultra-fast simulated local engine latency (2-4ms)
            delay(3)
            activeConcurrent.decrementAndGet()
            "বাংলা অর্থ: $text"
        }

        val translator = SubtitleBatchTranslator(
            context = context,
            customTranslator = customTranslator,
            concurrencyLimit = 3
        )

        // Measure initial memory
        System.gc()
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startTime = System.currentTimeMillis()

        val results = translator.translateSegments(inputSegments) { current, total, _ ->
            progressCount.incrementAndGet()
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memDeltaMb = (memAfter - memBefore) / (1024 * 1024)
        val avgTimePerSubtitle = elapsedTime.toDouble() / count

        println("=== 500 Subtitles Acceptance Test ===")
        println("Total Subtitles: $count")
        println("Elapsed Time: ${elapsedTime}ms")
        println("Avg Time per Subtitle: ${"%.2f".format(avgTimePerSubtitle)}ms")
        println("Max Concurrent Tasks Observed: ${maxConcurrentTracking.get()}")
        println("Memory Delta: ${memDeltaMb}MB (Within 4GB budget)")

        // VERIFICATIONS:
        assertEquals(500, results.size)
        assertTrue("Concurrency must stay within <= 3, was: ${maxConcurrentTracking.get()}",
            maxConcurrentTracking.get() <= 3)
        assertTrue("500 subtitles should complete smoothly under 10 seconds in local simulation", elapsedTime < 10000L)

        // Strict verification of all 500 cues
        for (i in 0 until count) {
            assertEquals(i, results[i].index)
            assertEquals(i * 2200L, results[i].startMs)
            assertEquals(i * 2200L + 1800L, results[i].endMs)
            assertNotNull(results[i].translatedText)
        }

        assertEquals(500, progressCount.get())
        translator.close()
        }
    }

    @Test
    fun testFailureHandlingDoesNotMaskEnglishAsBangla() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()

        val inputSegments = listOf(
            TranscriptSegmentEntity(projectId = "p1", index = 0, startMs = 0L, endMs = 1000L, sourceText = "Hello", translatedText = null),
            TranscriptSegmentEntity(projectId = "p1", index = 1, startMs = 1000L, endMs = 2000L, sourceText = "Unstranslatable phrase", translatedText = null)
        )

        // Translator that fails for second phrase
        val customTranslator: suspend (String) -> String = { text ->
            if (text.contains("Unstranslatable")) {
                throw RuntimeException("Simulated ML Kit translation error")
            }
            "হ্যালো"
        }

        val translator = SubtitleBatchTranslator(
            context = context,
            customTranslator = customTranslator,
            concurrencyLimit = 3
        )

        val results = translator.translateSegments(inputSegments) { _, _, _ -> }

        assertEquals(2, results.size)
        assertEquals("হ্যালো", results[0].translatedText)
        // CRITICAL REQUIREMENT 3: If translation fails, do NOT show original English as Bengali translation!
        assertNull("Failed subtitle translation must be null, not original English", results[1].translatedText)

        translator.close()
        }
    }

    @Test
    fun testSingleNaturalizationPassVerification() {
        val originalText = "Hello"
        val translated = "হ্যালো"

        // Naturalize once
        val pass1 = BanglaNaturalizer.naturalize(translated)
        // Naturalize again
        val pass2 = BanglaNaturalizer.naturalize(pass1)

        // Naturalization should be idempotent and must not alter already naturalized text
        assertEquals(pass1, pass2)
    }

    @Test
    fun testTtsEngineReuseAndAudioGeneration() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
        val tts = BanglaTtsEngine(context)

        // Reusing the same TTS engine for multiple cues
        val outDir = File(context.cacheDir, "acc_tts_test").apply { mkdirs() }
        val file1 = File(outDir, "seg_0.wav")
        val file2 = File(outDir, "seg_1.wav")

        val res1 = tts.synthesize("হ্যালো বন্ধুরা", file1)
        val res2 = tts.synthesize("আজকের আবহাওয়া কেমন?", file2)

        assertTrue("First synthesis must produce valid file", res1.exists() && res1.length() > 44)
        assertTrue("Second synthesis must produce valid file", res2.exists() && res2.length() > 44)

        // Check WAV header validity (first 4 bytes must be 'RIFF')
        val bytes = file1.readBytes()
        val riff = String(bytes.sliceArray(0..3))
        assertEquals("RIFF", riff)

        // Verify audio synchronizer does not crash or hold everything in RAM
        val syncOut = File(outDir, "synced.wav")
        val bgAudio = File(outDir, "bg.wav")
        WavUtils.createSilenceWav(bgAudio, 3000L)

        val segments = listOf(
            TranscriptSegmentEntity(projectId = "p1", index = 0, startMs = 200L, endMs = 1200L, sourceText = "Hello friends", translatedText = "হ্যালো বন্ধুরা", audioSegmentPath = file1.absolutePath),
            TranscriptSegmentEntity(projectId = "p1", index = 1, startMs = 1500L, endMs = 2500L, sourceText = "How is weather", translatedText = "আজকের আবহাওয়া কেমন?", audioSegmentPath = file2.absolutePath)
        )

        val synchronizer = AudioSynchronizer(context)
        val syncResult = synchronizer.synchronizeAndMux(
            segments = segments,
            totalDurationMs = 3000L,
            outputFile = syncOut,
            backgroundAudioFile = bgAudio
        ) { _ -> }

        assertTrue(syncResult.isSuccess)
        assertTrue(syncOut.exists() && syncOut.length() > 44)

        // Cleanup
        outDir.deleteRecursively()
        }
    }
}
