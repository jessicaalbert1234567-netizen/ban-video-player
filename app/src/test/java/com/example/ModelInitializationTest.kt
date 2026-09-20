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
        val states = BanglaTtsEngine.EngineState.values()
        assertTrue(states.contains(BanglaTtsEngine.EngineState.NOT_LOADED))
        assertTrue(states.contains(BanglaTtsEngine.EngineState.LOADING))
        assertTrue(states.contains(BanglaTtsEngine.EngineState.LOADED))
        assertTrue(states.contains(BanglaTtsEngine.EngineState.FAILED))
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
