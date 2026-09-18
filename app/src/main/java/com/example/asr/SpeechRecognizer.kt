package com.example.asr

import java.io.File

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val confidence: Float? = null
)

interface SpeechRecognizer {
    suspend fun transcribe(
        audioFile: File
    ): List<TranscriptSegment>
}
