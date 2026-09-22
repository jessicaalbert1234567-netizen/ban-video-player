package com.example.dubbing

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.example.asr.EnglishAsrEngine
import com.example.audio.AudioSynchronizer
import com.example.audio.MediaCodecAudioExtractor
import com.example.audio.WavUtils
import com.example.database.DubbingProject
import com.example.database.ProjectRepository
import com.example.database.TranscriptSegmentEntity
import com.example.settings.ProcessingMode
import com.example.settings.SettingsManager
import com.example.storage.StorageManager
import com.example.subtitle.SubtitleGenerator
import com.example.translation.EnglishToBanglaTranslator
import com.example.tts.BanglaTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class PipelineProgress(
    val projectId: String,
    val stage: ProcessingStage,
    val stageProgressPercent: Int,
    val overallProgressPercent: Int,
    val statusMessage: String,
    val isRunning: Boolean = true,
    val errorMessage: String? = null
)

class DubbingPipeline(
    private val context: Context,
    private val repository: ProjectRepository,
    private val storageManager: StorageManager,
    private val settingsManager: SettingsManager
) {
    private val TAG = "DubbingPipeline"

    private val _pipelineState = MutableStateFlow<PipelineProgress?>(null)
    val pipelineState: StateFlow<PipelineProgress?> = _pipelineState.asStateFlow()

    private var activeJobCancelled = false

    fun cancelActivePipeline() {
        activeJobCancelled = true
    }

    suspend fun executePipeline(
        projectId: String,
        videoUri: Uri,
        videoTitle: String,
        isReDubOnly: Boolean = false,
        onProgressUpdate: ((PipelineProgress) -> Unit)? = null
    ): Result<DubbingProject> = withContext(Dispatchers.Default) {
        activeJobCancelled = false
        val projectDir = storageManager.getProjectDir(projectId)
        val segmentsDir = storageManager.getProjectSegmentsDir(projectId)

        val rawAudioFile = File(projectDir, "source_audio.wav")
        val transcriptJsonFile = File(projectDir, "transcript.json")
        val srtFile = File(projectDir, "subtitle_bn.srt")
        val finalDubbedAudioFile = File(projectDir, "dubbed_bn.m4a")

        var project = repository.findProject(projectId) ?: DubbingProject(
            id = projectId,
            title = videoTitle,
            videoUriString = videoUri.toString(),
            projectDirPath = projectDir.absolutePath,
            originalAudioPath = rawAudioFile.absolutePath,
            transcriptJsonPath = transcriptJsonFile.absolutePath,
            subtitleSrtPath = srtFile.absolutePath,
            dubbedAudioPath = finalDubbedAudioFile.absolutePath
        )
        repository.saveProject(project)

        try {
            val mode = settingsManager.processingMode.value
            Log.d(TAG, "Starting pipeline for $projectId in mode: $mode, reDubOnly=$isReDubOnly")

            // Determine resumption stage
            val startingStage = if (isReDubOnly) {
                ProcessingStage.TRANSLATE
            } else {
                determineResumeStage(project, rawAudioFile, transcriptJsonFile, srtFile, finalDubbedAudioFile)
            }

            // STAGE 1: Extract Audio (0% - 15%)
            if (startingStage.ordinal <= ProcessingStage.EXTRACT_AUDIO.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.EXTRACT_AUDIO, 0, 0, "Extracting audio from video...", onProgressUpdate)

                val extractor = MediaCodecAudioExtractor(context)
                val extractResult = extractor.extractAudio(videoUri, rawAudioFile) { progress ->
                    val overall = (progress * 15).toInt()
                    reportStageSync(projectId, ProcessingStage.EXTRACT_AUDIO, (progress * 100).toInt(), overall, "Extracting audio: ${(progress * 100).toInt()}%", onProgressUpdate)
                }

                if (extractResult.isFailure) {
                    throw extractResult.exceptionOrNull() ?: IllegalStateException("Audio extraction failed")
                }
                project = project.copy(
                    originalAudioPath = rawAudioFile.absolutePath,
                    durationMs = WavUtils.getWavDurationMs(rawAudioFile)
                )
                repository.saveProject(project)
            }

            val totalDurationMs = if (project.durationMs > 0) project.durationMs else WavUtils.getWavDurationMs(rawAudioFile)

            // STAGE 2: Transcribe English Speech (15% - 40%)
            var segments: List<TranscriptSegmentEntity>
            if (startingStage.ordinal <= ProcessingStage.TRANSCRIBE.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.TRANSCRIBE, 0, 15, "English Speech Recognition (ASR)...", onProgressUpdate)

                val asrEngine = EnglishAsrEngine(context) { seg, chunkProgress ->
                    val overall = 15 + (chunkProgress * 25).toInt()
                    reportStageSync(projectId, ProcessingStage.TRANSCRIBE, (chunkProgress * 100).toInt(), overall, "Transcribing: \"${seg.sourceText.take(24)}...\"", onProgressUpdate)
                }
                val rawSegments = asrEngine.transcribe(rawAudioFile)
                asrEngine.close()

                segments = rawSegments.mapIndexed { index, seg ->
                    TranscriptSegmentEntity(
                        projectId = projectId,
                        index = index,
                        startMs = seg.startMs,
                        endMs = seg.endMs,
                        sourceText = seg.sourceText,
                        confidence = seg.confidence
                    )
                }
                repository.deleteSegmentsForProject(projectId)
                repository.saveSegments(segments)

                com.example.models.MemoryDiagnostics.logHeapSnapshot("PIPELINE", "ASR Stage Complete, released ASR session")
                System.gc()
            } else {
                segments = repository.findSegments(projectId)
            }

            if (segments.isEmpty()) {
                throw IllegalStateException("Transcription failed: No English speech detected in the audio track.")
            }

            // STAGE 3: Translate to Bangla (40% - 55%)
            if (startingStage.ordinal <= ProcessingStage.TRANSLATE.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.TRANSLATE, 0, 40, "Preparing on-device English → Bangla translation...", onProgressUpdate)

                val isModelDownloaded = EnglishToBanglaTranslator.isModelDownloaded()
                if (!isModelDownloaded) {
                    reportStage(projectId, ProcessingStage.TRANSLATE, 5, 41, "Downloading Google ML Kit English → Bangla model...", onProgressUpdate)
                    val dlTranslator = EnglishToBanglaTranslator(context)
                    dlTranslator.downloadModel(requireWifi = false)
                    dlTranslator.close()
                }

                val translator = EnglishToBanglaTranslator(context)
                val translatedSegments = mutableListOf<TranscriptSegmentEntity>()

                for (i in segments.indices) {
                    checkCancelled()
                    val seg = segments[i]
                    val bnText = translator.translate(seg.sourceText)
                    translatedSegments.add(seg.copy(translatedText = bnText))

                    val stageProg = ((i + 1).toFloat() / segments.size)
                    val overall = 40 + (stageProg * 15).toInt()
                    reportStage(projectId, ProcessingStage.TRANSLATE, (stageProg * 100).toInt(), overall, "Translating segment ${i + 1}/${segments.size}", onProgressUpdate)
                }
                segments = translatedSegments
                repository.saveSegments(segments)
                translator.close()
                com.example.models.MemoryDiagnostics.logHeapSnapshot("PIPELINE", "Translation Stage Complete, released translation sessions")
                System.gc()
            }

            // STAGE 4: Generate Bangla Subtitles & JSON transcript (55% - 65%)
            if (startingStage.ordinal <= ProcessingStage.GENERATE_SUBTITLE.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.GENERATE_SUBTITLE, 0, 55, "Generating Bangla SRT and structured transcript...", onProgressUpdate)

                SubtitleGenerator.generateSrt(segments, srtFile)
                SubtitleGenerator.generateTranscriptJson("en", "bn", segments, transcriptJsonFile)
                reportStage(projectId, ProcessingStage.GENERATE_SUBTITLE, 100, 65, "Subtitles generated.", onProgressUpdate)
            }

            // STAGE 5: Bangla Voice Synthesis (TTS) (65% - 85%)
            if (startingStage.ordinal <= ProcessingStage.GENERATE_TTS.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.GENERATE_TTS, 0, 65, "Preparing Bangla AI Voice Synthesis...", onProgressUpdate)

                val isMmsInstalled = com.example.models.ModelInstaller.isModelInstalled(context, com.example.models.ModelCatalog.MMS_BANGLA_TTS)
                if (!isMmsInstalled) {
                    val downloader = com.example.models.ModelDownloader(context)
                    if (downloader.isNetworkAvailable()) {
                        reportStage(projectId, ProcessingStage.GENERATE_TTS, 5, 66, "Downloading MMS Bangla Voice model...", onProgressUpdate)
                        downloader.downloadAndInstall(com.example.models.ModelCatalog.MMS_BANGLA_TTS) { prog ->
                            val p = (prog.progressPercent * 0.15f).toInt()
                            reportStageSync(
                                projectId,
                                ProcessingStage.GENERATE_TTS,
                                prog.progressPercent,
                                65 + p,
                                prog.verificationStatus ?: "Downloading MMS Bangla model (${prog.progressPercent}%)...",
                                onProgressUpdate
                            )
                        }
                    }
                }

                val ttsEngine = BanglaTtsEngine(context)
                val ttsSegments = mutableListOf<TranscriptSegmentEntity>()

                for (i in segments.indices) {
                    checkCancelled()
                    val seg = segments[i]
                    val textToSpeak = seg.translatedText ?: seg.sourceText
                    val segAudioFile = File(segmentsDir, "tts_seg_${seg.index}.wav")

                    try {
                        ttsEngine.synthesize(textToSpeak, segAudioFile)
                    } catch (e: Exception) {
                        Log.w(TAG, "Segment ${seg.index} voice synthesis exception: ${e.message}, falling back to duration-padded audio")
                        val fallbackDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)
                        com.example.audio.WavUtils.createSilenceWav(segAudioFile, fallbackDurationMs)
                    }
                    ttsSegments.add(seg.copy(audioSegmentPath = segAudioFile.absolutePath))

                    val stageProg = ((i + 1).toFloat() / segments.size)
                    val overall = 65 + (stageProg * 20).toInt()
                    reportStage(projectId, ProcessingStage.GENERATE_TTS, (stageProg * 100).toInt(), overall, "Synthesizing segment ${i + 1}/${segments.size}", onProgressUpdate)
                }
                segments = ttsSegments
                repository.saveSegments(segments)
                ttsEngine.close()
                com.example.models.MemoryDiagnostics.logHeapSnapshot("PIPELINE", "TTS Stage Complete, released TTS session")
                System.gc()
            }

            // STAGE 6: Audio Synchronization (85% - 95%)
            var effectiveDubbedAudioFile = finalDubbedAudioFile
            if (startingStage.ordinal <= ProcessingStage.SYNC_AUDIO.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.SYNC_AUDIO, 0, 85, "Synchronizing dubbed audio with video...", onProgressUpdate)

                val synchronizer = AudioSynchronizer(context)
                val syncResult = synchronizer.synchronizeAndMux(segments, totalDurationMs, finalDubbedAudioFile) { prog ->
                    val overall = 85 + (prog * 10).toInt()
                    reportStageSync(projectId, ProcessingStage.SYNC_AUDIO, (prog * 100).toInt(), overall, "Synchronizing audio tracks...", onProgressUpdate)
                }

                if (syncResult.isFailure) {
                    throw syncResult.exceptionOrNull() ?: IllegalStateException("Audio synchronization failed")
                }
                effectiveDubbedAudioFile = syncResult.getOrThrow()
            }

            // STAGE 7: Finalize & Complete (95% - 100%)
            reportStage(projectId, ProcessingStage.FINALIZE, 50, 95, "Verifying output files...", onProgressUpdate)

            // Strict Output Verification: verify existence, size, and validity of every output artifact
            if (!transcriptJsonFile.exists() || transcriptJsonFile.length() == 0L) {
                throw IllegalStateException("Output verification failed: English/Bangla transcript JSON file is missing or empty (Stage: Subtitle & Transcript Generation).")
            }

            if (!srtFile.exists() || srtFile.length() == 0L) {
                throw IllegalStateException("Output verification failed: Bangla subtitle SRT file is missing or empty (Stage: Subtitle Generation).")
            }

            val srtContent = srtFile.readText()
            if (!srtContent.contains("-->") || srtContent.isBlank()) {
                throw IllegalStateException("Output verification failed: Bangla subtitle SRT file does not contain valid subtitle timestamp blocks.")
            }

            val dubbedAudioToUse = when {
                effectiveDubbedAudioFile.exists() && effectiveDubbedAudioFile.length() >= 1000L -> effectiveDubbedAudioFile
                finalDubbedAudioFile.exists() && finalDubbedAudioFile.length() >= 1000L -> finalDubbedAudioFile
                else -> {
                    val fallbackWav = File(projectDir, "dubbed_bn.wav")
                    if (fallbackWav.exists() && fallbackWav.length() >= 1000L) fallbackWav
                    else throw IllegalStateException("Output verification failed: Bangla dubbed audio file is missing or contains insufficient data (Stage: Audio Synchronization).")
                }
            }

            project = project.copy(
                currentStage = ProcessingStage.COMPLETE,
                progressPercent = 100,
                statusMessage = "Offline AI Dubbing Complete",
                originalAudioPath = rawAudioFile.absolutePath,
                transcriptJsonPath = transcriptJsonFile.absolutePath,
                subtitleSrtPath = srtFile.absolutePath,
                dubbedAudioPath = dubbedAudioToUse.absolutePath,
                durationMs = totalDurationMs,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveProject(project)

            if (settingsManager.autoCleanTempFiles.value) {
                storageManager.clearTemporaryFiles()
            }

            reportStage(projectId, ProcessingStage.COMPLETE, 100, 100, "Dubbing ready to play!", onProgressUpdate)
            Result.success(project)

        } catch (e: CancellationException) {
            Log.w(TAG, "Dubbing cancelled by user")
            val cancelledState = project.copy(
                currentStage = ProcessingStage.CANCELLED,
                statusMessage = "Dubbing cancelled by user"
            )
            repository.saveProject(cancelledState)
            reportStage(projectId, ProcessingStage.CANCELLED, 0, 0, "Cancelled", onProgressUpdate)
            Result.failure(e)
        } catch (e: Throwable) {
            Log.e(TAG, "Dubbing pipeline failed", e)
            val failedState = project.copy(
                currentStage = ProcessingStage.FAILED,
                statusMessage = "Error: ${e.localizedMessage ?: "Unknown error"}",
                errorMessage = e.localizedMessage
            )
            repository.saveProject(failedState)
            reportStage(projectId, ProcessingStage.FAILED, 0, 0, e.localizedMessage ?: "Processing error", onProgressUpdate)
            Result.failure(e)
        }
    }

    private fun determineResumeStage(
        project: DubbingProject,
        rawAudio: File,
        transcriptJson: File,
        srtFile: File,
        dubbedAudio: File
    ): ProcessingStage {
        return when {
            dubbedAudio.exists() && dubbedAudio.length() > 1000 -> ProcessingStage.COMPLETE
            srtFile.exists() && srtFile.length() > 0 -> ProcessingStage.GENERATE_TTS
            transcriptJson.exists() && transcriptJson.length() > 0 -> ProcessingStage.TRANSLATE
            rawAudio.exists() && rawAudio.length() > 44 -> ProcessingStage.TRANSCRIBE
            else -> ProcessingStage.EXTRACT_AUDIO
        }
    }

    private fun checkCancelled() {
        if (activeJobCancelled) {
            throw CancellationException("Pipeline cancelled by user")
        }
    }

    private fun reportStageSync(
        projectId: String,
        stage: ProcessingStage,
        stageProg: Int,
        overallProg: Int,
        message: String,
        callback: ((PipelineProgress) -> Unit)?
    ) {
        val progress = PipelineProgress(
            projectId = projectId,
            stage = stage,
            stageProgressPercent = stageProg,
            overallProgressPercent = overallProg,
            statusMessage = message,
            isRunning = stage != ProcessingStage.COMPLETE && stage != ProcessingStage.FAILED && stage != ProcessingStage.CANCELLED
        )
        _pipelineState.value = progress
        callback?.invoke(progress)
    }

    private suspend fun reportStage(
        projectId: String,
        stage: ProcessingStage,
        stageProg: Int,
        overallProg: Int,
        message: String,
        callback: ((PipelineProgress) -> Unit)?
    ) {
        reportStageSync(projectId, stage, stageProg, overallProg, message, callback)
        repository.updateProjectProgress(projectId, stage, overallProg, message)
    }
}
