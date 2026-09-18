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

    @Test(expected = IllegalStateException::class)
    fun `test offline translation engine throws when model uninstalled`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val translator = EnglishToBanglaTranslator(context)
            try {
                translator.translate("How are you?")
            } finally {
                translator.close()
            }
        }
    }

    @Test
    fun `test model offline verification fails cleanly when file missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val verification = com.example.models.ModelInstaller.verifyModelOffline(
            context,
            com.example.models.ModelCatalog.ENGLISH_ASR
        )
        org.junit.Assert.assertFalse(verification.isReadyForOfflineUse)
        org.junit.Assert.assertNotNull(verification.failureReason)
    }
}
