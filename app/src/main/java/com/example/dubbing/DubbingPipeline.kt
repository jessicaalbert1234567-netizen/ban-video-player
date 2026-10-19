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
import com.example.storage.VideoUriResolver
import com.example.subtitle.SubtitleExtractor
import com.example.subtitle.SubtitleGenerator
import com.example.translation.BanglaNaturalizer
import com.example.translation.EnglishToBanglaTranslator
import com.example.translation.OfflineDialogueTranslator
import com.example.translation.SubtitleBatchTranslator
import com.example.tts.BanglaTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        providedSubtitleCues: List<com.example.subtitle.SubtitleCue>? = null,
        onProgressUpdate: ((PipelineProgress) -> Unit)? = null
    ): Result<DubbingProject> = withContext(Dispatchers.Default) {
        activeJobCancelled = false
        val projectDir = storageManager.getProjectDir(projectId)
        val segmentsDir = storageManager.getProjectSegmentsDir(projectId)

        val rawAudioFile = File(projectDir, "source_audio.wav")
        val transcriptJsonFile = File(projectDir, "transcript.json")
        val srtFile = File(projectDir, "subtitle_bn.srt")
        val finalDubbedAudioFile = File(projectDir, "dubbed_bn.wav")

        VideoUriResolver.takePersistablePermission(context, videoUri)

        val rawExt = videoTitle.substringAfterLast('.', "mp4").lowercase()
        val videoExt = if (rawExt.isEmpty() || rawExt.length > 5 || rawExt.contains("/")) "mp4" else rawExt
        val localVideoFile = File(projectDir, "source_video.$videoExt")

        var workingVideoUri = videoUri
        if (localVideoFile.exists() && localVideoFile.length() > 1024) {
            workingVideoUri = Uri.fromFile(localVideoFile)
        } else {
            try {
                withContext(Dispatchers.IO) {
                    val tempCopyFile = File(projectDir, "source_video.tmp")
                    if (videoUri.scheme == "file") {
                        val srcFile = File(videoUri.path ?: "")
                        if (srcFile.exists() && srcFile.canonicalPath != localVideoFile.canonicalPath) {
                            srcFile.copyTo(tempCopyFile, overwrite = true)
                        }
                    } else {
                        context.contentResolver.openInputStream(videoUri)?.use { input ->
                            tempCopyFile.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                                output.flush()
                            }
                        }
                    }
                    if (tempCopyFile.exists() && tempCopyFile.length() > 1024) {
                        if (localVideoFile.exists()) {
                            localVideoFile.delete()
                        }
                        val renamed = tempCopyFile.renameTo(localVideoFile)
                        if (!renamed) {
                            tempCopyFile.copyTo(localVideoFile, overwrite = true)
                            tempCopyFile.delete()
                        }
                    }
                }
                if (localVideoFile.exists() && localVideoFile.length() > 1024) {
                    workingVideoUri = Uri.fromFile(localVideoFile)
                    Log.i(TAG, "Successfully cached source video to project storage: ${localVideoFile.absolutePath} (${localVideoFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not cache source video locally: ${e.message}, falling back to original URI", e)
            }
        }

        val resolvedInitial = VideoUriResolver.resolvePlayableVideoUri(
            context = context,
            projectId = projectId,
            videoTitle = videoTitle,
            savedUriString = workingVideoUri.toString(),
            projectDirPath = projectDir.absolutePath
        )
        if (resolvedInitial != null) {
            workingVideoUri = resolvedInitial
        }

        var project = repository.findProject(projectId) ?: DubbingProject(
            id = projectId,
            title = videoTitle,
            videoUriString = workingVideoUri.toString(),
            projectDirPath = projectDir.absolutePath,
            originalAudioPath = rawAudioFile.absolutePath,
            transcriptJsonPath = transcriptJsonFile.absolutePath,
            subtitleSrtPath = srtFile.absolutePath,
            dubbedAudioPath = finalDubbedAudioFile.absolutePath
        )
        if (project.videoUriString != workingVideoUri.toString()) {
            project = project.copy(videoUriString = workingVideoUri.toString())
        }
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

            // STAGE 1: Extract Audio (0% - 15%) - BYPASSED when subtitle cues are already provided
            val hasProvidedSubtitles = providedSubtitleCues != null && providedSubtitleCues.isNotEmpty()
            if (hasProvidedSubtitles) {
                checkCancelled()
                reportStage(
                    projectId,
                    ProcessingStage.EXTRACT_AUDIO,
                    100,
                    15,
                    "⚡ MKV Subtitle Track Ready (Instant)",
                    onProgressUpdate
                )

                // Instantly obtain video duration from metadata without slow audio decoding
                val retrievedDurationMs = try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, workingVideoUri)
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    durStr?.toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L }

                val maxCueEndMs = providedSubtitleCues.maxOfOrNull { it.endMs } ?: 0L
                val calculatedDuration = maxOf(retrievedDurationMs, maxCueEndMs + 1000L)

                project = project.copy(
                    durationMs = calculatedDuration
                )
                repository.saveProject(project)
                Log.i(TAG, "Fast dubbing mode: skipped audio extraction, detected video duration = $calculatedDuration ms")
            } else if (startingStage.ordinal <= ProcessingStage.EXTRACT_AUDIO.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.EXTRACT_AUDIO, 0, 0, "Extracting audio from video...", onProgressUpdate)

                val extractor = MediaCodecAudioExtractor(context)
                val extractResult = extractor.extractAudio(workingVideoUri, rawAudioFile) { progress ->
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

            val totalDurationMs = if (project.durationMs > 0) {
                project.durationMs
            } else if (hasProvidedSubtitles) {
                (providedSubtitleCues?.maxOfOrNull { it.endMs } ?: 0L) + 1000L
            } else {
                WavUtils.getWavDurationMs(rawAudioFile)
            }

            // STAGE 2: Transcribe English Speech (15% - 40%) OR Use Extracted Subtitle Cues (Instant)
            var segments: List<TranscriptSegmentEntity>
            if (providedSubtitleCues != null && providedSubtitleCues.isNotEmpty()) {
                checkCancelled()
                reportStage(
                    projectId,
                    ProcessingStage.TRANSCRIBE,
                    100,
                    40,
                    "⚡ Extracted ${providedSubtitleCues.size} subtitle cues (ASR skipped)",
                    onProgressUpdate
                )
                segments = providedSubtitleCues.mapIndexed { index, cue ->
                    TranscriptSegmentEntity(
                        projectId = projectId,
                        index = index,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        sourceText = cue.text,
                        confidence = 1.0f
                    )
                }
                repository.deleteSegmentsForProject(projectId)
                repository.saveSegments(segments)

                val sourceSrtFile = File(projectDir, "subtitle_en_source.srt")
                com.example.subtitle.SubtitleExtractor.exportToSrt(providedSubtitleCues, sourceSrtFile)

                // Save English subtitle in device Downloads folder as well
                val baseVideoTitle = videoTitle.substringBeforeLast(".")
                com.example.subtitle.SubtitleExtractor.saveSrtToPublicDownloads(
                    context,
                    "${baseVideoTitle}_English",
                    sourceSrtFile.readText()
                )
                Log.i(TAG, "Successfully bypassed ASR using ${providedSubtitleCues.size} extracted subtitle cues")
            } else if (startingStage.ordinal <= ProcessingStage.TRANSCRIBE.ordinal) {
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

            // STAGE 3: Translate to Bangla (40% - 55%) via GTX API with Offline ML Kit Fallback & Normalization
            if (startingStage.ordinal <= ProcessingStage.TRANSLATE.ordinal) {
                checkCancelled()

                val isAlreadyBangla = segments.any { seg -> seg.sourceText.any { ch -> ch in '\u0980'..'\u09FF' } }
                if (isAlreadyBangla) {
                    reportStage(projectId, ProcessingStage.TRANSLATE, 100, 55, "✓ সাবটাইটেল ইতোমধ্যে বাংলায় রয়েছে, নর্মালাইজেশন সম্পন্ন...", onProgressUpdate)
                    segments = segments.map { seg -> seg.copy(translatedText = BanglaNaturalizer.naturalize(seg.sourceText)) }
                    repository.saveSegments(segments)
                } else {
                    var gtxSuccess = false

                    // Tier 1: Try Fast Online Google GTX API
                    try {
                        reportStage(projectId, ProcessingStage.TRANSLATE, 0, 40, "অনলাইন Google Translate API দিয়ে বাংলায় অনুবাদ শুরু হচ্ছে...", onProgressUpdate)

                        val translationStartTime = System.currentTimeMillis()
                        val gtxItems = segments.map { seg ->
                            val sTime = SubtitleExtractor.formatSrtTimestamp(seg.startMs)
                            val eTime = SubtitleExtractor.formatSrtTimestamp(seg.endMs)
                            com.example.translation.SubtitleItem(
                                index = seg.index + 1,
                                startTimeStr = sTime,
                                endTimeStr = eTime,
                                startTimeMs = seg.startMs,
                                endTimeMs = seg.endMs,
                                originalText = seg.sourceText
                            )
                        }

                        var lastReportedOverall = 40
                        com.example.translation.GtxSubtitleTranslator.translateSubtitlesToBangla(gtxItems) { current, total ->
                            checkCancelled()
                            val stageProg = (current.toFloat() / total.toFloat())
                            val overall = maxOf(lastReportedOverall, 40 + (stageProg * 15).toInt())
                            lastReportedOverall = overall
                            val sample = gtxItems.getOrNull(current - 1)?.translatedText?.take(22) ?: ""
                            reportStage(
                                projectId,
                                ProcessingStage.TRANSLATE,
                                (stageProg * 100).toInt(),
                                overall,
                                "অনলাইন অনুবাদ (GTX): $current/$total (\"$sample...\")",
                                onProgressUpdate
                            )
                        }

                        // Verify that GTX actually produced authentic Bengali text
                        val banglaCount = gtxItems.count { it.translatedText.any { ch -> ch in '\u0980'..'\u09FF' } }
                        if (banglaCount > 0) {
                            gtxSuccess = true
                            // Apply rule-based BanglaNaturalizer normalization to all lines!
                            segments = segments.mapIndexed { idx, seg ->
                                val rawTrans = gtxItems.getOrNull(idx)?.translatedText?.trim()?.takeIf { it.isNotEmpty() } ?: seg.sourceText
                                val natural = BanglaNaturalizer.naturalize(rawTrans)
                                seg.copy(translatedText = natural)
                            }
                            repository.saveSegments(segments)

                            val transElapsed = System.currentTimeMillis() - translationStartTime
                            Log.i(TAG, "GTX Translation & Normalization Complete in ${transElapsed}ms for ${segments.size} cues")
                            reportStage(
                                projectId,
                                ProcessingStage.TRANSLATE,
                                100,
                                55,
                                "✓ অনলাইন অনুবাদ ও নর্মালাইজেশন সম্পন্ন (${segments.size} সংলাপ - GTX API)",
                                onProgressUpdate
                            )
                        } else {
                            Log.w(TAG, "GTX API did not return Bengali text, falling back to offline ML Kit")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "GTX online translation failed: ${e.message}, switching to offline translation")
                    }

                    // Tier 2: Offline ML Kit Fallback with Rule-Based Normalization!
                    if (!gtxSuccess) {
                        checkCancelled()
                        reportStage(projectId, ProcessingStage.TRANSLATE, 0, 40, "অনলাইন API কাজ করেনি, অফলাইন ML Kit মডেলে অনুবাদ ও নর্মালাইজেশন শুরু হচ্ছে...", onProgressUpdate)

                        val offlineTranslator = SubtitleBatchTranslator(context)
                        try {
                            val prepared = offlineTranslator.prepareModel()
                            if (prepared || offlineTranslator.isModelDownloaded()) {
                                var lastReportedOverall = 40
                                segments = offlineTranslator.translateSegments(segments) { current, total, sample ->
                                    checkCancelled()
                                    val stageProg = (current.toFloat() / total.toFloat())
                                    val overall = maxOf(lastReportedOverall, 40 + (stageProg * 12).toInt())
                                    lastReportedOverall = overall
                                    reportStage(
                                        projectId,
                                        ProcessingStage.TRANSLATE,
                                        (stageProg * 100).toInt(),
                                        overall,
                                        "অফলাইন অনুবাদ (ML Kit + নর্মালাইজেশন): $current/$total (\"${sample.take(20)}...\")",
                                        onProgressUpdate
                                    )
                                }
                                repository.saveSegments(segments)
                                Log.i(TAG, "Offline ML Kit translation pass complete")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Offline ML Kit translation note: ${e.message}")
                        } finally {
                            offlineTranslator.close()
                        }
                    }

                    // Tier 3: Guaranteed Offline Dialogue Engine + BanglaNaturalizer
                    val unTranslatedCount = segments.count { it.translatedText.isNullOrBlank() }
                    if (unTranslatedCount > 0) {
                        checkCancelled()
                        reportStage(projectId, ProcessingStage.TRANSLATE, 0, 48, "অফলাইন ডায়ালগ ইঞ্জিন ও নর্মালাইজেশন বিকল্প কার্যকর করা হচ্ছে...", onProgressUpdate)
                        segments = segments.mapIndexed { idx, seg ->
                            checkCancelled()
                            val text = if (!seg.translatedText.isNullOrBlank()) {
                                seg.translatedText!!
                            } else {
                                OfflineDialogueTranslator.translate(seg.sourceText)
                            }
                            val natural = BanglaNaturalizer.naturalize(text)
                            seg.copy(translatedText = natural)
                        }
                        repository.saveSegments(segments)
                        reportStage(
                            projectId,
                            ProcessingStage.TRANSLATE,
                            100,
                            55,
                            "✓ অফলাইন ডায়ালগ ইঞ্জিন ও নর্মালাইজেশন সম্পন্ন (${segments.size} সংলাপ)",
                            onProgressUpdate
                        )
                        Log.i(TAG, "OfflineDialogueTranslator finished for $unTranslatedCount segments")
                    } else {
                        reportStage(
                            projectId,
                            ProcessingStage.TRANSLATE,
                            100,
                            55,
                            "✓ অনুবাদ ও নর্মালাইজেশন সম্পন্ন (${segments.size} সংলাপ)",
                            onProgressUpdate
                        )
                    }
                }
            }

            // STAGE 4: Generate Bangla Subtitles & JSON transcript (55% - 65%)
            if (startingStage.ordinal <= ProcessingStage.GENERATE_SUBTITLE.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.GENERATE_SUBTITLE, 0, 55, "Generating Bangla SRT and structured transcript...", onProgressUpdate)

                SubtitleGenerator.generateSrt(segments, srtFile)
                SubtitleGenerator.generateTranscriptJson("en", "bn", segments, transcriptJsonFile)

                // Save translated Bengali SRT directly to device public Download/FileTranslato folder
                val baseVideoTitle = videoTitle.substringBeforeLast(".")
                val bnSrtContent = srtFile.readText()
                val downloadedFile = com.example.subtitle.SubtitleExtractor.saveSrtToPublicDownloads(
                    context,
                    "${baseVideoTitle}_bangla",
                    bnSrtContent
                )
                val statusMsg = if (downloadedFile != null) {
                    "✓ Bangla SRT saved to Download/FileTranslato (${downloadedFile.name})"
                } else {
                    "Bangla subtitles generated."
                }
                reportStage(projectId, ProcessingStage.GENERATE_SUBTITLE, 100, 65, statusMsg, onProgressUpdate)
                Log.i(TAG, "Translated Bangla subtitle generated and exported to FileTranslato: ${downloadedFile?.absolutePath}")
            }

            // STAGE 5: Bangla Voice Synthesis (TTS) (65% - 85%)
            if (startingStage.ordinal <= ProcessingStage.GENERATE_TTS.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.GENERATE_TTS, 0, 65, "Preparing Android Bengali TextToSpeech...", onProgressUpdate)

                val ttsStartTime = System.currentTimeMillis()
                val ttsEngine = BanglaTtsEngine(context)
                try {
                    val initResult = ttsEngine.ensureInitialized()
                    if (initResult.isFailure) {
                        val reason = initResult.exceptionOrNull()?.message ?: BanglaTtsEngine.ERROR_VOICE_NOT_INSTALLED
                        reportStage(projectId, ProcessingStage.GENERATE_TTS, 0, 65, "Failed: $reason", onProgressUpdate)
                        throw IllegalStateException(reason)
                    }

                    val ttsSegments = mutableListOf<TranscriptSegmentEntity>()
                    var successfulTtsSegments = 0
                    var failedTtsSegments = 0

                    for (i in segments.indices) {
                        checkCancelled()
                        val seg = segments[i]
                        val segAudioFile = File(segmentsDir, "tts_seg_${seg.index}.wav")

                        // Resume capability: skip if already synthesized on disk from earlier run
                        if (segAudioFile.exists() && segAudioFile.length() > 44L) {
                            ttsSegments.add(seg.copy(audioSegmentPath = segAudioFile.absolutePath))
                            successfulTtsSegments++
                            val stageProg = ((i + 1).toFloat() / segments.size)
                            val overall = 65 + (stageProg * 20).toInt()
                            reportStageSync(projectId, ProcessingStage.GENERATE_TTS, (stageProg * 100).toInt(), overall, "Synthesized (${i + 1}/${segments.size})", onProgressUpdate)
                            continue
                        }

                        // Use already naturalized translated text directly (no redundant naturalization)
                        val rawText = seg.translatedText?.trim()?.ifEmpty { null } ?: ""
                        val textToSpeak = rawText.filter { it != '\u0000' && !it.isISOControl() || it == '\n' || it == '\t' }

                        if (textToSpeak.isBlank()) {
                            val segDuration = maxOf(300L, seg.endMs - seg.startMs)
                            WavUtils.createSilenceWav(segAudioFile, segDuration)
                        } else {
                            try {
                                ttsEngine.synthesize(textToSpeak, segAudioFile)
                                successfulTtsSegments++
                            } catch (e: Exception) {
                                failedTtsSegments++
                                Log.w(TAG, "Bangla TTS failed for segment ${seg.index + 1}: ${e.message}")
                                val segDuration = maxOf(300L, seg.endMs - seg.startMs)
                                WavUtils.createSilenceWav(segAudioFile, segDuration)
                            }
                        }
                        ttsSegments.add(seg.copy(audioSegmentPath = segAudioFile.absolutePath))

                        // Periodically save segment audio paths to database every 10 items
                        if ((i + 1) % 10 == 0 || i == segments.size - 1) {
                            repository.saveSegments(ttsSegments)
                        }

                        // Stage timing diagnostics: Every 25 subtitles, log elapsed time & avg ms/segment
                        if ((i + 1) % 25 == 0 || i == segments.size - 1) {
                            val elapsed = System.currentTimeMillis() - ttsStartTime
                            val avg = elapsed.toDouble() / (i + 1)
                            Log.i(TAG, "TTS Synthesis Progress: ${i + 1}/${segments.size}. Elapsed: ${elapsed}ms, Avg: ${String.format("%.1f", avg)}ms/seg, Failures: $failedTtsSegments")
                        }

                        val stageProg = ((i + 1).toFloat() / segments.size)
                        val overall = 65 + (stageProg * 20).toInt()
                        reportStageSync(projectId, ProcessingStage.GENERATE_TTS, (stageProg * 100).toInt(), overall, "Synthesizing (${i + 1}/${segments.size})", onProgressUpdate)
                    }

                    segments = ttsSegments
                    repository.saveSegments(segments)
                    val ttsTotalElapsed = System.currentTimeMillis() - ttsStartTime
                    Log.i(TAG, "TTS Stage Complete in ${ttsTotalElapsed}ms. Synthesized ${successfulTtsSegments}/${segments.size} segments (Failures: $failedTtsSegments)")
                    reportStage(
                        projectId,
                        ProcessingStage.GENERATE_TTS,
                        100,
                        85,
                        "✓ Voice synthesis complete: ${segments.size} dialogue segments",
                        onProgressUpdate
                    )
                } finally {
                    ttsEngine.close()
                }
                com.example.models.MemoryDiagnostics.logHeapSnapshot("PIPELINE", "TTS Stage Complete")
                System.gc()
            }

            // STAGE 6: Audio Synchronization (85% - 95%)
            var effectiveDubbedAudioFile = finalDubbedAudioFile
            if (startingStage.ordinal <= ProcessingStage.SYNC_AUDIO.ordinal) {
                checkCancelled()
                reportStage(projectId, ProcessingStage.SYNC_AUDIO, 0, 85, "Synchronizing dubbed audio with video...", onProgressUpdate)

                val syncStartTime = System.currentTimeMillis()
                val synchronizer = AudioSynchronizer(context)
                val syncResult = synchronizer.synchronizeAndMux(
                    segments = segments,
                    totalDurationMs = totalDurationMs,
                    outputFile = finalDubbedAudioFile,
                    backgroundAudioFile = rawAudioFile.takeIf { it.exists() && it.length() > 44 }
                ) { prog ->
                    val overall = 85 + (prog * 10).toInt()
                    reportStageSync(projectId, ProcessingStage.SYNC_AUDIO, (prog * 100).toInt(), overall, "Synchronizing audio: ${(prog * 100).toInt()}%", onProgressUpdate)
                }

                if (syncResult.isSuccess) {
                    effectiveDubbedAudioFile = syncResult.getOrThrow()
                    try {
                        val baseVideoTitle = videoTitle.substringBeforeLast(".")
                        val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val fileTranslatoDir = File(publicDownloads, "FileTranslato").apply { if (!exists()) mkdirs() }
                        val publicDubbedAudio = File(fileTranslatoDir, "${baseVideoTitle}_bangla_dubbed_audio.wav")
                        effectiveDubbedAudioFile.copyTo(publicDubbedAudio, overwrite = true)
                        Log.i(TAG, "Exported dubbed audio to Download/FileTranslato: ${publicDubbedAudio.absolutePath}")

                        if (rawAudioFile.exists() && rawAudioFile.length() > 44) {
                            val publicOriginalAudio = File(fileTranslatoDir, "${baseVideoTitle}_original_audio.wav")
                            rawAudioFile.copyTo(publicOriginalAudio, overwrite = true)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not mirror audio to FileTranslato: ${e.message}")
                    }
                } else {
                    WavUtils.createSilenceWav(finalDubbedAudioFile, maxOf(1000L, totalDurationMs))
                    effectiveDubbedAudioFile = finalDubbedAudioFile
                }
                val syncElapsed = System.currentTimeMillis() - syncStartTime
                Log.i(TAG, "Audio Synchronization Complete in ${syncElapsed}ms, output: ${effectiveDubbedAudioFile.length()} bytes")
                reportStage(projectId, ProcessingStage.SYNC_AUDIO, 100, 95, "✓ Synchronized audio ready", onProgressUpdate)
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

            val resolvedFinalVideoUri = when {
                localVideoFile.exists() && localVideoFile.length() > 1024 -> Uri.fromFile(localVideoFile)
                workingVideoUri.toString().isNotBlank() -> workingVideoUri
                else -> VideoUriResolver.resolvePlayableVideoUri(
                    context = context,
                    projectId = projectId,
                    videoTitle = videoTitle,
                    savedUriString = workingVideoUri.toString(),
                    projectDirPath = projectDir.absolutePath
                ) ?: workingVideoUri
            }

            project = project.copy(
                videoUriString = resolvedFinalVideoUri.toString(),
                currentStage = ProcessingStage.COMPLETE,
                progressPercent = 100,
                statusMessage = "Offline AI Dubbing Complete",
                originalAudioPath = if (rawAudioFile.exists()) rawAudioFile.absolutePath else null,
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

    private suspend fun determineResumeStage(
        project: DubbingProject,
        rawAudio: File,
        transcriptJson: File,
        srtFile: File,
        dubbedAudio: File
    ): ProcessingStage {
        val existingSegments = repository.findSegments(project.id)
        if (existingSegments.isNotEmpty()) {
            val allTranslated = existingSegments.all { !it.translatedText.isNullOrBlank() }
            if (dubbedAudio.exists() && dubbedAudio.length() > 1000) return ProcessingStage.COMPLETE
            if (srtFile.exists() && srtFile.length() > 0) return ProcessingStage.GENERATE_TTS
            if (allTranslated) return ProcessingStage.GENERATE_SUBTITLE
            return ProcessingStage.TRANSLATE
        }

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
        val safeOverall = if (stage == ProcessingStage.FAILED || stage == ProcessingStage.CANCELLED) {
            overallProg
        } else {
            maxOf(_pipelineState.value?.overallProgressPercent ?: 0, overallProg)
        }

        val progress = PipelineProgress(
            projectId = projectId,
            stage = stage,
            stageProgressPercent = stageProg,
            overallProgressPercent = safeOverall,
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
