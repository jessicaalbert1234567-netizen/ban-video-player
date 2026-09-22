package com.example.asr

import android.content.Context
import android.util.Log
import com.example.audio.WavUtils
import com.example.models.ModelCatalog
import com.example.models.ModelInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EnglishAsrEngine(
    private val context: Context,
    private val onChunkTranscribed: ((TranscriptSegment, Float) -> Unit)? = null
) : SpeechRecognizer, AutoCloseable {

    private val TAG = "ASR"
    private var activeWhisperRecognizer: WhisperSpeechRecognizer? = null

    companion object {
        const val CHUNK_DURATION_MS = 28_000L // 28 seconds chunk fits Whisper 30s window
        const val OVERLAP_MS = 1_000L         // 1 second overlap
        const val SILENCE_RMS_THRESHOLD = 180.0
    }

    override suspend fun transcribe(audioFile: File): List<TranscriptSegment> = withContext(Dispatchers.Default) {
        val segments = mutableListOf<TranscriptSegment>()
        val totalDurationMs = WavUtils.getWavDurationMs(audioFile)

        Log.d(TAG, "Starting Whisper transcription for ${audioFile.name}, total duration: ${totalDurationMs}ms on thread '${Thread.currentThread().name}'")
        if (totalDurationMs <= 0) {
            return@withContext emptyList()
        }

        val model = ModelCatalog.ENGLISH_ASR
        val verification = ModelInstaller.verifyModelOffline(context, model, deepCheck = false)
        if (!verification.isReadyForOfflineUse) {
            val reason = verification.failureReason ?: "Whisper ASR model is not ready for offline use."
            throw IllegalStateException("ASR model verification failed: $reason")
        }

        val encoderFile = ModelInstaller.getInstalledModelFile(context, model)
        var decoderFile = ModelInstaller.getAuxiliaryFile(context, model, "tiny.en-decoder.int8.onnx")
        if (!decoderFile.exists() && encoderFile.parentFile != null) {
            decoderFile = File(encoderFile.parentFile, "tiny.en-decoder.int8.onnx")
        }

        var tokensFile = ModelInstaller.getAuxiliaryFile(context, model, "tiny.en-tokens.txt")
        if (!tokensFile.exists() && encoderFile.parentFile != null) {
            tokensFile = File(encoderFile.parentFile, "tiny.en-tokens.txt")
        }

        val whisperRecognizer = WhisperSpeechRecognizer(context, encoderFile, decoderFile, tokensFile)
        activeWhisperRecognizer = whisperRecognizer
        whisperRecognizer.initialize()

        val tempChunkFile = File(context.cacheDir, "temp_asr_chunk.wav")

        try {
            var currentStartMs = 0L

            while (currentStartMs < totalDurationMs) {
                val currentEndMs = (currentStartMs + CHUNK_DURATION_MS).coerceAtMost(totalDurationMs)
                val chunkDuration = currentEndMs - currentStartMs

                // 1. Silence check: check if chunk is purely silence
                val isSilent = WavUtils.isSegmentSilent(
                    wavFile = audioFile,
                    startMs = currentStartMs,
                    durationMs = chunkDuration,
                    thresholdRms = SILENCE_RMS_THRESHOLD
                )

                if (!isSilent && chunkDuration > 500) {
                    // Extract chunk into temp file (no RAM overload)
                    WavUtils.extractWavChunk(
                        sourceWav = audioFile,
                        chunkFile = tempChunkFile,
                        startMs = currentStartMs,
                        endMs = currentEndMs
                    )

                    // Transcribe chunk
                    val rawText = whisperRecognizer.transcribeChunk(tempChunkFile)

                    if (rawText.isNotBlank()) {
                        // Split into sentence-level segments for natural subtitle pacing
                        val subSegments = splitIntoSentenceSegments(rawText, currentStartMs, currentEndMs)
                        for (seg in subSegments) {
                            segments.add(seg)
                            val progress = (currentEndMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            onChunkTranscribed?.invoke(seg, progress)
                        }
                    }
                } else {
                    Log.d(TAG, "Skipping silent chunk: ${currentStartMs}ms -> ${currentEndMs}ms")
                }

                currentStartMs += (CHUNK_DURATION_MS - OVERLAP_MS)
            }
        } finally {
            if (tempChunkFile.exists()) {
                tempChunkFile.delete()
            }
            whisperRecognizer.close()
            activeWhisperRecognizer = null
        }

        Log.d(TAG, "Completed transcription: generated ${segments.size} segments")
        segments
    }

    private fun splitIntoSentenceSegments(
        text: String,
        chunkStartMs: Long,
        chunkEndMs: Long
    ): List<TranscriptSegment> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return emptyList()

        if (sentences.size == 1) {
            return listOf(
                TranscriptSegment(
                    startMs = chunkStartMs,
                    endMs = chunkEndMs,
                    sourceText = sentences[0].trim(),
                    confidence = 0.92f
                )
            )
        }

        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val totalMs = chunkEndMs - chunkStartMs
        val result = mutableListOf<TranscriptSegment>()
        var cursorMs = chunkStartMs

        for (i in sentences.indices) {
            val sentence = sentences[i].trim()
            val proportion = sentence.length.toDouble() / totalChars
            val segDuration = (totalMs * proportion).toLong().coerceAtLeast(1200L)
            val segEnd = (cursorMs + segDuration).coerceAtMost(chunkEndMs)

            result.add(
                TranscriptSegment(
                    startMs = cursorMs,
                    endMs = if (i == sentences.lastIndex) chunkEndMs else segEnd,
                    sourceText = sentence,
                    confidence = 0.90f
                )
            )
            cursorMs = segEnd
        }

        return result
    }

    override fun close() {
        try {
            activeWhisperRecognizer?.close()
            activeWhisperRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing EnglishAsrEngine", e)
        }
    }
}
