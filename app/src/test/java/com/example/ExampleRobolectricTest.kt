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
    fun `test translation engine failure message when uninstalled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val translator = EnglishToBanglaTranslator(context)
        try {
            translator.initialize()
            org.junit.Assert.fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("English → Bangla translation model is not installed.", e.message)
        } finally {
            translator.close()
        }
    }

    @Test
    fun `test translation model package size and unconfigured status`() {
        val model = com.example.models.ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION
        assertEquals("English → Bangla Translation", model.name)
        // Step 8: Verify real package size is ~221 MB, not fake ~64 MB
        assertEquals(231_754_408L, model.sizeBytes)
        val sizeMb = model.sizeBytes / (1024 * 1024)
        assertEquals(221L, sizeMb)
        // Step 9: Verify download source is unconfigured until uploaded to public CDN
        org.junit.Assert.assertFalse(model.isSourceConfigured)
        assertEquals("", model.downloadUrl)
    }

    @Test
    fun `test translation model manifest assets verification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifestStream = context.assets.open("models/translation_en_bn/model_manifest.json")
        val manifestJson = org.json.JSONObject(manifestStream.bufferedReader().use { it.readText() })
        assertEquals("translation_en_bn", manifestJson.getString("id"))
        assertEquals("en-bn", manifestJson.getString("languagePair"))
        assertEquals("onnxruntime", manifestJson.getString("runtime"))
        assertEquals("onnx", manifestJson.getString("format"))
        assertEquals(231754408L, manifestJson.getLong("totalSizeBytes"))
        val filesArray = manifestJson.getJSONArray("files")
        assertTrue(filesArray.length() >= 10)
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
        assertTrue(verification.failureReason!!.contains("English → Bangla translation model is not installed"))
    }
}
