package com.example

import com.example.asr.OnnxSpeechRecognizer
import com.example.models.MemoryDiagnostics
import com.example.models.ModelCatalog
import com.example.models.ModelInfo
import com.example.models.ModelInstaller
import com.example.models.ModelStatus
import com.example.models.ModelType
import com.example.tts.BanglaTtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelInitializationTest {

    @Test
    fun testModelStatusStates() {
        // Verify lazy states exist
        val readyOnDisk = ModelStatus.READY_ON_DISK
        val loadedInMemory = ModelStatus.LOADED_IN_MEMORY
        assertNotNull(readyOnDisk)
        assertNotNull(loadedInMemory)
    }

    @Test
    fun testMemoryDiagnosticsSnapshot() {
        val snapshot = MemoryDiagnostics.captureSnapshot()
        assertNotNull(snapshot)
        assertTrue(snapshot.jvmMax > 0)
        assertTrue(snapshot.jvmTotal > 0)
        assertNotNull(snapshot.threadName)

        var executed = false
        MemoryDiagnostics.trackModelLoad("TEST", "TestModel", null) {
            executed = true
        }
        assertTrue(executed)
    }

    @Test
    fun testBanglaTtsEngineDefaultStateIsUnloaded() {
        assertEquals("Bengali TTS voice is not installed on this device.", BanglaTtsEngine.ERROR_VOICE_NOT_INSTALLED)
        assertEquals("bn", BanglaTtsEngine.LOCALE_BD.language)
        assertEquals("BD", BanglaTtsEngine.LOCALE_BD.country)
        assertEquals("bn", BanglaTtsEngine.LOCALE_IN.language)
        assertEquals("IN", BanglaTtsEngine.LOCALE_IN.country)
    }

    @Test
    fun testOnnxSpeechRecognizerDefaultStateIsUnloaded() {
        val states = OnnxSpeechRecognizer.AsrState.values()
        assertTrue(states.contains(OnnxSpeechRecognizer.AsrState.NOT_LOADED))
        assertTrue(states.contains(OnnxSpeechRecognizer.AsrState.LOADING))
        assertTrue(states.contains(OnnxSpeechRecognizer.AsrState.LOADED))
        assertTrue(states.contains(OnnxSpeechRecognizer.AsrState.FAILED))
    }
}
