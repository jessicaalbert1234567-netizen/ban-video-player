package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.database.TranscriptSegmentEntity
import com.example.subtitle.SubtitleGenerator
import com.example.translation.EnglishToBanglaTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Offline AI Dubbing Player", appName)
    }

    @Test
    fun `test subtitle timestamp formatting`() {
        val formatted = SubtitleGenerator.formatSrtTimestamp(3200L)
        assertEquals("00:00:03,200", formatted)
        val formattedLong = SubtitleGenerator.formatSrtTimestamp(3665123L)
        assertEquals("01:01:05,123", formattedLong)
    }

    @Test
    fun `test srt generation with unicode bangla`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testSrt = File(context.cacheDir, "test_sample.srt")
        val segments = listOf(
            TranscriptSegmentEntity(
                projectId = "test_p",
                index = 1,
                startMs = 3200L,
                endMs = 5800L,
                sourceText = "How are you?",
                translatedText = "কেমন আছো?"
            )
        )
        SubtitleGenerator.generateSrt(segments, testSrt)
        assertTrue(testSrt.exists())
        val content = testSrt.readText(Charsets.UTF_8)
        assertTrue(content.contains("00:00:03,200 --> 00:00:05,800"))
        assertTrue(content.contains("কেমন আছো?"))
        testSrt.delete()
    }

    @Test
    fun testOfflineTranslationEngineThrowsWhenModelUninstalled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val translator = EnglishToBanglaTranslator(context)
        try {
            runBlocking {
                translator.translate("How are you?")
            }
            org.junit.Assert.fail("Should have thrown IllegalStateException when translation model is not installed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("translation model is not downloaded") == true || e.message?.contains("Translation failed") == true)
        } finally {
            translator.close()
        }
    }

    @Test
    fun `test translation model package catalog info`() {
        val model = com.example.models.ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION
        assertEquals("English → Bangla", model.name)
        assertEquals(com.example.models.ModelFormat.BINARY_ARCHIVE, model.format)
        org.junit.Assert.assertTrue(model.isSourceConfigured)
    }

    @Test
    fun `test model offline verification fails cleanly when file missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val verification = com.example.models.ModelInstaller.verifyModelOffline(
            context,
            com.example.models.ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION
        )
        org.junit.Assert.assertFalse(verification.isReadyForOfflineUse)
        org.junit.Assert.assertNotNull(verification.failureReason)
        assertTrue(verification.failureReason!!.contains("translation model is not downloaded"))
    }

    @Test
    fun `test native bangla tts engine initialization and text chunking`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ttsEngine = com.example.tts.BanglaTtsEngine(context)
        try {
            // Verify engine chunking and safety on the required sentences
            val phrase1 = "আজ আমরা অফলাইন এআই ভিডিও ডাবিং প্রদর্শন করছি।"
            val phrase2 = "বাংলাদেশ একটি সুন্দর দেশ। এখানে অনেক মানুষ বাংলা ভাষায় কথা বলে।"

            val out1 = File(context.cacheDir, "test_phrase1.wav")
            val out2 = File(context.cacheDir, "test_phrase2.wav")

            runBlocking {
                ttsEngine.synthesize(phrase1, out1)
                ttsEngine.synthesize(phrase2, out2)
            }

            assertTrue(out1.exists())
            assertTrue(out1.length() > 44L)
            assertTrue(out2.exists())
            assertTrue(out2.length() > 44L)

            out1.delete()
            out2.delete()
        } finally {
            ttsEngine.close()
        }
    }

    @Test
    fun `test wav utils concatenation and silence generator`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav1 = File(context.cacheDir, "test_part1.wav")
        val wav2 = File(context.cacheDir, "test_part2.wav")
        val combined = File(context.cacheDir, "test_combined.wav")

        com.example.audio.WavUtils.createSilenceWav(wav1, 500L)
        com.example.audio.WavUtils.createSilenceWav(wav2, 700L)
        com.example.audio.WavUtils.concatenateWavFiles(listOf(wav1, wav2), combined)

        assertTrue(combined.exists())
        val combinedDuration = com.example.audio.WavUtils.getWavDurationMs(combined)
        assertTrue(combinedDuration >= 1150L)

        wav1.delete()
        wav2.delete()
        combined.delete()
    }
}
