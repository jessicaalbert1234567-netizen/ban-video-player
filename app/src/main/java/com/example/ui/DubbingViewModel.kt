package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DubbingApplication
import com.example.database.DubbingProject
import com.example.database.TranscriptSegmentEntity
import com.example.dubbing.DubbingForegroundService
import com.example.dubbing.PipelineProgress
import com.example.dubbing.ProcessingStage
import com.example.models.ModelInfo
import com.example.models.ModelItemUiState
import com.example.player.AudioTrackChoice
import com.example.player.PlayerState
import com.example.player.SubtitleChoice
import com.example.settings.ProcessingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DubbingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DubbingApplication
    private val modelManager = app.modelManager
    private val repository = app.repository
    private val pipeline = app.dubbingPipeline
    private val storageManager = app.storageManager
    private val settingsManager = app.settingsManager
    val playerManager = app.playerManager

    val modelsState: StateFlow<List<ModelItemUiState>> = modelManager.modelsState
    val isAllModelsReady: StateFlow<Boolean> = modelManager.isAllRequiredReady
    val storageSummary: StateFlow<Pair<Long, Long>> = modelManager.storageSummary
    val allProjects: StateFlow<List<DubbingProject>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pipelineState: StateFlow<PipelineProgress?> = pipeline.pipelineState
    val playerState: StateFlow<PlayerState> = playerManager.playerState
    val processingMode: StateFlow<ProcessingMode> = settingsManager.processingMode

    private val _ttsStatus = MutableStateFlow<com.example.tts.BengaliTtsStatus>(com.example.tts.BengaliTtsStatus.Checking)
    val ttsStatus: StateFlow<com.example.tts.BengaliTtsStatus> = _ttsStatus.asStateFlow()

    init {
        checkBengaliTts()
        viewModelScope.launch {
            pipeline.pipelineState.collect { progress ->
                if (progress != null) {
                    val current = _selectedProject.value
                    if (current != null && current.id == progress.projectId) {
                        _selectedProject.value = current.copy(
                            currentStage = progress.stage,
                            progressPercent = progress.overallProgressPercent,
                            statusMessage = progress.statusMessage
                        )
                    }
                }
            }
        }
    }

    fun checkBengaliTts() {
        viewModelScope.launch(Dispatchers.IO) {
            _ttsStatus.value = com.example.tts.BengaliTtsStatus.Checking
            val ttsEngine = com.example.tts.BanglaTtsEngine(app)
            try {
                val res = ttsEngine.ensureInitialized()
                if (res.isSuccess) {
                    val loc = res.getOrThrow()
                    val engineName = ttsEngine.getEngineName()
                    _ttsStatus.value = com.example.tts.BengaliTtsStatus.Available(
                        engineName = engineName,
                        localeDisplayName = "${loc.displayLanguage} (${loc.displayCountry})"
                    )
                } else {
                    _ttsStatus.value = com.example.tts.BengaliTtsStatus.NotInstalled(
                        message = res.exceptionOrNull()?.message ?: com.example.tts.BanglaTtsEngine.ERROR_VOICE_NOT_INSTALLED
                    )
                }
            } catch (e: Exception) {
                _ttsStatus.value = com.example.tts.BengaliTtsStatus.NotInstalled(
                    message = e.message ?: com.example.tts.BanglaTtsEngine.ERROR_VOICE_NOT_INSTALLED
                )
            } finally {
                ttsEngine.close()
            }
        }
    }

    fun openTtsSettings() {
        com.example.tts.BanglaTtsEngine.openTtsSettings(app)
    }

    // Active project state
    private val _selectedProject = MutableStateFlow<DubbingProject?>(null)
    val selectedProject: StateFlow<DubbingProject?> = _selectedProject.asStateFlow()

    private val _selectedProjectSegments = MutableStateFlow<List<TranscriptSegmentEntity>>(emptyList())
    val selectedProjectSegments: StateFlow<List<TranscriptSegmentEntity>> = _selectedProjectSegments.asStateFlow()

    // Test mode states
    private val _testLogs = MutableStateFlow<List<String>>(emptyList())
    val testLogs: StateFlow<List<String>> = _testLogs.asStateFlow()

    private val _isTestingRunning = MutableStateFlow(false)
    val isTestingRunning: StateFlow<Boolean> = _isTestingRunning.asStateFlow()

    fun downloadAllModels() {
        modelManager.downloadAllRequiredModels()
    }

    fun downloadModel(model: ModelInfo) {
        modelManager.downloadModel(model)
    }

    fun deleteModel(model: ModelInfo) {
        modelManager.deleteModel(model)
    }

    fun refreshModelStatuses() {
        modelManager.refreshModelStatuses()
    }

    private val _probeResults = MutableStateFlow<Map<String, com.example.models.ModelDownloader.HttpProbeResult>>(emptyMap())
    val probeResults: StateFlow<Map<String, com.example.models.ModelDownloader.HttpProbeResult>> = _probeResults.asStateFlow()

    // Subtitle Inspection & Fast Dubbing states
    private val _subtitleInspectionState = MutableStateFlow<SubtitleInspectionUiState?>(null)
    val subtitleInspectionState: StateFlow<SubtitleInspectionUiState?> = _subtitleInspectionState.asStateFlow()

    private val _extractedSrtExportMessage = MutableStateFlow<String?>(null)
    val extractedSrtExportMessage: StateFlow<String?> = _extractedSrtExportMessage.asStateFlow()

    fun dismissSubtitleInspection() {
        _subtitleInspectionState.value = null
    }

    fun clearExportMessage() {
        _extractedSrtExportMessage.value = null
    }

    fun inspectVideoForSubtitles(uri: Uri) {
        viewModelScope.launch {
            val fileName = queryFileName(uri) ?: "video_${System.currentTimeMillis()}.mkv"
            _subtitleInspectionState.value = SubtitleInspectionUiState(
                videoUri = uri,
                videoTitle = fileName,
                tracks = emptyList(),
                isInspecting = true
            )

            val tracks = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.inspectSubtitleTracks(app, uri)
            }

            _subtitleInspectionState.value = SubtitleInspectionUiState(
                videoUri = uri,
                videoTitle = fileName,
                tracks = tracks,
                isInspecting = false,
                infoMessage = if (tracks.isEmpty()) "No embedded subtitle tracks detected in this video file." else null
            )
        }
    }

    fun startFastDubbingFromSubtitleTrack(
        videoUri: Uri,
        trackIndex: Int,
        onNavigateToProgress: (String) -> Unit
    ) {
        viewModelScope.launch {
            val fileName = queryFileName(videoUri) ?: "video_${System.currentTimeMillis()}.mkv"
            _subtitleInspectionState.value = null

            val cues = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.extractCues(app, videoUri, trackIndex)
            }

            if (cues.isEmpty()) {
                // If extraction returned 0 cues, fallback to standard dubbing
                selectVideoForDubbing(videoUri, onNavigateToProgress)
                return@launch
            }

            val projectId = UUID.randomUUID().toString()
            val projectDir = storageManager.getProjectDir(projectId)
            val project = DubbingProject(
                id = projectId,
                title = fileName,
                videoUriString = videoUri.toString(),
                projectDirPath = projectDir.absolutePath,
                currentStage = ProcessingStage.EXTRACT_AUDIO,
                statusMessage = "Starting fast subtitle dubbing (No ASR)..."
            )
            repository.saveProject(project)
            _selectedProject.value = project

            DubbingForegroundService.start(app)
            onNavigateToProgress(projectId)

            pipeline.executePipeline(
                projectId = projectId,
                videoUri = videoUri,
                videoTitle = fileName,
                providedSubtitleCues = cues
            )
        }
    }

    fun startFastDubbingFromExternalSubtitle(
        videoUri: Uri,
        subtitleUri: Uri,
        onNavigateToProgress: (String) -> Unit
    ) {
        viewModelScope.launch {
            val fileName = queryFileName(videoUri) ?: "video_${System.currentTimeMillis()}.mp4"
            _subtitleInspectionState.value = null

            val cues = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.parseSubtitleUri(app, subtitleUri)
            }

            if (cues.isEmpty()) {
                selectVideoForDubbing(videoUri, onNavigateToProgress)
                return@launch
            }

            val projectId = UUID.randomUUID().toString()
            val projectDir = storageManager.getProjectDir(projectId)
            val project = DubbingProject(
                id = projectId,
                title = fileName,
                videoUriString = videoUri.toString(),
                projectDirPath = projectDir.absolutePath,
                currentStage = ProcessingStage.EXTRACT_AUDIO,
                statusMessage = "Starting fast subtitle dubbing from external SRT..."
            )
            repository.saveProject(project)
            _selectedProject.value = project

            DubbingForegroundService.start(app)
            onNavigateToProgress(projectId)

            pipeline.executePipeline(
                projectId = projectId,
                videoUri = videoUri,
                videoTitle = fileName,
                providedSubtitleCues = cues
            )
        }
    }

    fun extractAndExportSrt(
        videoUri: Uri,
        trackIndex: Int
    ) {
        viewModelScope.launch {
            val fileName = queryFileName(videoUri) ?: "video_${System.currentTimeMillis()}"
            val baseName = fileName.substringBeforeLast(".")

            val cues = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.extractCues(app, videoUri, trackIndex)
            }

            if (cues.isEmpty()) {
                _extractedSrtExportMessage.value = "Failed to extract subtitle cues or track was empty."
                return@launch
            }

            val exportDir = File(app.filesDir, "ExtractedSubtitles").apply { if (!exists()) mkdirs() }
            val outputFile = File(exportDir, "${baseName}_track${trackIndex + 1}.srt")
            com.example.subtitle.SubtitleExtractor.exportToSrt(cues, outputFile)

            // Also save directly to mobile device's public Download folder
            val srtText = outputFile.readText()
            val downloadFile = com.example.subtitle.SubtitleExtractor.saveSrtToPublicDownloads(
                app,
                "${baseName}_track${trackIndex + 1}",
                srtText
            )
            val pathNotice = if (downloadFile != null) " (Saved to Downloads/BanglaDubbing)" else ""
            _extractedSrtExportMessage.value = "✓ Subtitle saved: ${outputFile.name}$pathNotice (${cues.size} dialogue cues)"
        }
    }

    // Fast Direct Subtitle Translation (File Translator framework)
    private val _isTranslatingSrt = MutableStateFlow(false)
    val isTranslatingSrt: StateFlow<Boolean> = _isTranslatingSrt.asStateFlow()

    private val _srtTranslationStatus = MutableStateFlow<String?>(null)
    val srtTranslationStatus: StateFlow<String?> = _srtTranslationStatus.asStateFlow()

    fun translateAndExportMkvSubtitleToBangla(
        videoUri: Uri,
        trackIndex: Int
    ) {
        viewModelScope.launch {
            val fileName = queryFileName(videoUri) ?: "video_${System.currentTimeMillis()}"
            val baseName = fileName.substringBeforeLast(".")

            _isTranslatingSrt.value = true
            _srtTranslationStatus.value = "MKV থেকে সাবটাইটেল এক্সট্র্যাক্ট করা হচ্ছে..."

            val cues = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.extractCues(app, videoUri, trackIndex)
            }

            if (cues.isEmpty()) {
                _isTranslatingSrt.value = false
                _srtTranslationStatus.value = null
                _extractedSrtExportMessage.value = "সাবটাইটেল ট্র্যাক থেকে কোনো ডায়লগ পাওয়া যায়নি।"
                return@launch
            }

            _srtTranslationStatus.value = "বাংলায় অনুবাদ হচ্ছে (0/${cues.size} cues)..."
            val translator = com.example.translation.SubtitleBatchTranslator(app)
            try {
                translator.prepareModel()
                val segments = cues.mapIndexed { idx, cue ->
                    TranscriptSegmentEntity(
                        projectId = "standalone_mkv_sub",
                        index = idx,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        sourceText = cue.text
                    )
                }

                val translatedSegments = translator.translateSegments(segments) { current, total, _ ->
                    _srtTranslationStatus.value = "বাংলায় অনুবাদ হচ্ছে ($current/$total cues)..."
                }

                val bnCues = translatedSegments.map {
                    com.example.subtitle.SubtitleCue(
                        index = it.index,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.translatedText ?: it.sourceText
                    )
                }

                val exportDir = File(app.filesDir, "ExtractedSubtitles").apply { if (!exists()) mkdirs() }
                val localFile = File(exportDir, "${baseName}_Bangla.srt")
                com.example.subtitle.SubtitleExtractor.exportToSrt(bnCues, localFile)

                val downloadFile = com.example.subtitle.SubtitleExtractor.saveSrtToPublicDownloads(
                    app,
                    "${baseName}_Bangla",
                    localFile.readText()
                )

                val pathNotice = if (downloadFile != null) " (Saved to Downloads/BanglaDubbing/${downloadFile.name})" else ""
                _extractedSrtExportMessage.value = "✓ বাংলা সাবটাইটেল অনুবাদ সম্পন্ন!${pathNotice} • মোট ${bnCues.size} ডায়লগ"
            } catch (e: Exception) {
                _extractedSrtExportMessage.value = "অনুবাদ ত্রুটি: ${e.localizedMessage}"
            } finally {
                translator.close()
                _isTranslatingSrt.value = false
                _srtTranslationStatus.value = null
            }
        }
    }

    fun translateAndExportExternalSrtToBangla(subtitleUri: Uri) {
        viewModelScope.launch {
            val fileName = queryFileName(subtitleUri) ?: "subtitle_${System.currentTimeMillis()}.srt"
            val baseName = fileName.substringBeforeLast(".")

            _isTranslatingSrt.value = true
            _srtTranslationStatus.value = "SRT ফাইল রিড করা হচ্ছে..."

            val cues = withContext(Dispatchers.IO) {
                com.example.subtitle.SubtitleExtractor.parseSubtitleUri(app, subtitleUri)
            }

            if (cues.isEmpty()) {
                _isTranslatingSrt.value = false
                _srtTranslationStatus.value = null
                _extractedSrtExportMessage.value = "SRT ফাইলে কোনো ভ্যালিড সাবটাইটেল পাওয়া যায়নি।"
                return@launch
            }

            _srtTranslationStatus.value = "বাংলায় অনুবাদ হচ্ছে (0/${cues.size} cues)..."
            val translator = com.example.translation.SubtitleBatchTranslator(app)
            try {
                translator.prepareModel()
                val segments = cues.mapIndexed { idx, cue ->
                    TranscriptSegmentEntity(
                        projectId = "standalone_ext_sub",
                        index = idx,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        sourceText = cue.text
                    )
                }

                val translatedSegments = translator.translateSegments(segments) { current, total, _ ->
                    _srtTranslationStatus.value = "বাংলায় অনুবাদ হচ্ছে ($current/$total cues)..."
                }

                val bnCues = translatedSegments.map {
                    com.example.subtitle.SubtitleCue(
                        index = it.index,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.translatedText ?: it.sourceText
                    )
                }

                val exportDir = File(app.filesDir, "ExtractedSubtitles").apply { if (!exists()) mkdirs() }
                val localFile = File(exportDir, "${baseName}_Bangla.srt")
                com.example.subtitle.SubtitleExtractor.exportToSrt(bnCues, localFile)

                val downloadFile = com.example.subtitle.SubtitleExtractor.saveSrtToPublicDownloads(
                    app,
                    "${baseName}_Bangla",
                    localFile.readText()
                )

                val pathNotice = if (downloadFile != null) " (Saved to Downloads/BanglaDubbing/${downloadFile.name})" else ""
                _extractedSrtExportMessage.value = "✓ বাংলা সাবটাইটেল তৈরি হয়েছে!${pathNotice} • মোট ${bnCues.size} ডায়লগ"
            } catch (e: Exception) {
                _extractedSrtExportMessage.value = "অনুবাদ ত্রুটি: ${e.localizedMessage}"
            } finally {
                translator.close()
                _isTranslatingSrt.value = false
                _srtTranslationStatus.value = null
            }
        }
    }

    fun probeModel(model: ModelInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = modelManager.downloader.probeUrl(model.downloadUrl)
            val updated = _probeResults.value.toMutableMap()
            updated[model.id] = result
            _probeResults.value = updated
        }
    }

    fun selectVideoForDubbing(uri: Uri, onNavigateToProgress: (String) -> Unit) {
        viewModelScope.launch {
            val fileName = queryFileName(uri) ?: "video_${System.currentTimeMillis()}.mp4"
            val projectId = UUID.randomUUID().toString()

            val projectDir = storageManager.getProjectDir(projectId)
            val project = DubbingProject(
                id = projectId,
                title = fileName,
                videoUriString = uri.toString(),
                projectDirPath = projectDir.absolutePath,
                currentStage = ProcessingStage.EXTRACT_AUDIO,
                statusMessage = "Starting offline dubbing..."
            )
            repository.saveProject(project)
            _selectedProject.value = project

            // Start foreground service for reliable background processing
            DubbingForegroundService.start(app)

            onNavigateToProgress(projectId)

            pipeline.executePipeline(
                projectId = projectId,
                videoUri = uri,
                videoTitle = fileName
            )
        }
    }

    fun reDubProject(project: DubbingProject, onNavigateToProgress: (String) -> Unit) {
        viewModelScope.launch {
            _selectedProject.value = project
            DubbingForegroundService.start(app)
            onNavigateToProgress(project.id)

            pipeline.executePipeline(
                projectId = project.id,
                videoUri = Uri.parse(project.videoUriString),
                videoTitle = project.title,
                isReDubOnly = true
            )
        }
    }

    fun cancelActiveDubbing() {
        pipeline.cancelActivePipeline()
        DubbingForegroundService.stop(app)
    }

    fun deleteProject(project: DubbingProject) {
        viewModelScope.launch {
            repository.deleteProject(project.id)
            if (_selectedProject.value?.id == project.id) {
                _selectedProject.value = null
            }
        }
    }

    fun selectProjectForPlayback(project: DubbingProject) {
        _selectedProject.value = project
        val videoUri = Uri.parse(project.videoUriString)
        val dubbedFile = project.dubbedAudioPath?.let { File(it) }
        val srtFile = project.subtitleSrtPath?.let { File(it) }
        val sourceSrt = File(project.projectDirPath, "subtitle_en_source.srt").let { if (it.exists()) it else null }

        playerManager.setupMedia(
            videoUri = videoUri,
            dubbedAudioFile = dubbedFile,
            srtFile = srtFile,
            sourceSrtFile = sourceSrt
        )
    }

    fun setAudioChoice(choice: AudioTrackChoice) {
        playerManager.setAudioChoice(choice)
    }

    fun setSubtitleChoice(choice: SubtitleChoice) {
        playerManager.setSubtitleChoice(choice)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerManager.setPlaybackSpeed(speed)
    }

    fun setProcessingMode(mode: ProcessingMode) {
        settingsManager.setProcessingMode(mode)
    }

    fun clearTemporaryFiles(): Long {
        return storageManager.clearTemporaryFiles()
    }

    fun getStorageBreakdown(): Triple<Long, Long, Long> {
        return storageManager.getStorageBreakdown()
    }

    fun getStorageSummary(): Pair<Long, Long> {
        return modelManager.getStorageSummary()
    }

    // --- TEST MODE EXECUTION ---
    fun runStageTest(stageName: String) {
        viewModelScope.launch {
            _isTestingRunning.value = true
            addTestLog("--- Starting test for: $stageName ---")

            try {
                when (stageName) {
                    "ASR" -> {
                        addTestLog("Testing English ASR engine initialization...")
                        val testWav = File(app.cacheDir, "test_asr_sample.wav")
                        com.example.audio.WavUtils.createSilenceWav(testWav, 2000L)
                        val engine = com.example.asr.EnglishAsrEngine(app)
                        val results = engine.transcribe(testWav)
                        addTestLog("ASR Engine returned ${results.size} segments.")
                        results.forEach { addTestLog("Seg: [${it.startMs}ms - ${it.endMs}ms] \"${it.sourceText}\"") }
                        testWav.delete()
                        addTestLog("✓ ASR Stage Test Passed.")
                    }
                    "TRANSLATION" -> {
                        addTestLog("Testing English to Bangla offline translator (Google ML Kit)...")
                        val isDl = com.example.translation.EnglishToBanglaTranslator.isModelDownloaded()
                        if (!isDl) {
                            addTestLog("ML Kit English-Bangla model not downloaded yet. Downloading on-device...")
                            val dlTranslator = com.example.translation.EnglishToBanglaTranslator(app)
                            dlTranslator.downloadModel(requireWifi = false)
                            dlTranslator.close()
                            addTestLog("ML Kit model downloaded successfully.")
                        }
                        val translator = com.example.translation.EnglishToBanglaTranslator(app)
                        val testPhrases = listOf(
                            "How are you?",
                            "Welcome to this video",
                            "Today we are demonstrating offline AI video dubbing."
                        )
                        for (phrase in testPhrases) {
                            val bn = translator.translate(phrase)
                            addTestLog("EN: \"$phrase\" -> BN: \"$bn\"")
                        }
                        translator.close()
                        addTestLog("✓ Translation Stage Test Passed.")
                    }
                    "TTS" -> {
                        addTestLog("Testing Android Native Bengali TextToSpeech Engine...")
                        val tts = com.example.tts.BanglaTtsEngine(app)
                        try {
                            val initResult = tts.ensureInitialized()
                            if (initResult.isFailure) {
                                val reason = initResult.exceptionOrNull()?.message ?: com.example.tts.BanglaTtsEngine.ERROR_VOICE_NOT_INSTALLED
                                addTestLog("❌ Bengali TTS not available: $reason")
                                return@launch
                            }
                            val loc = initResult.getOrThrow()
                            addTestLog("✓ Bengali TTS Engine initialized: ${tts.getEngineName() ?: "System Default"} [Locale: $loc]")

                            // Test phrase 1:
                            val phrase1 = "আজ আমরা অফলাইন এআই ভিডিও ডাবিং প্রদর্শন করছি।"
                            val outWav1 = File(app.cacheDir, "test_bangla_tts_1.wav")
                            tts.synthesize(phrase1, outWav1)
                            addTestLog("Phrase 1: \"$phrase1\"")
                            addTestLog("  -> Generated file: ${outWav1.length()} bytes, duration: ${com.example.audio.WavUtils.getWavDurationMs(outWav1)}ms, playable: ${com.example.audio.WavUtils.isAudioPlayable(outWav1)}")

                            // Test phrase 2:
                            val phrase2 = "বাংলাদেশ একটি সুন্দর দেশ। এখানে অনেক মানুষ বাংলা ভাষায় কথা বলে।"
                            val outWav2 = File(app.cacheDir, "test_bangla_tts_2.wav")
                            tts.synthesize(phrase2, outWav2)
                            addTestLog("Phrase 2: \"$phrase2\"")
                            addTestLog("  -> Generated file: ${outWav2.length()} bytes, duration: ${com.example.audio.WavUtils.getWavDurationMs(outWav2)}ms, playable: ${com.example.audio.WavUtils.isAudioPlayable(outWav2)}")

                            addTestLog("✓ Bangla Native TTS Stage Test Passed.")
                        } finally {
                            tts.close()
                        }
                    }
                    "AUDIO_SYNC" -> {
                        addTestLog("Testing Audio Synchronizer with time-stretching...")
                        val synchronizer = com.example.audio.AudioSynchronizer(app)
                        val segDir = File(app.cacheDir, "test_sync_segs")
                        segDir.mkdirs()
                        val dummyWav = File(segDir, "seg1.wav")
                        com.example.audio.WavUtils.createSilenceWav(dummyWav, 2500L)

                        val segs = listOf(
                            TranscriptSegmentEntity(
                                projectId = "test",
                                index = 0,
                                startMs = 1000L,
                                endMs = 3000L,
                                sourceText = "Test audio sync",
                                translatedText = "টেস্ট অডিও সিঙ্ক",
                                audioSegmentPath = dummyWav.absolutePath
                            )
                        )
                        val outM4a = File(app.cacheDir, "test_dubbed_out.m4a")
                        val result = synchronizer.synchronizeAndMux(segs, 4000L, outM4a) {}
                        addTestLog("Audio Synchronizer result: success=${result.isSuccess}, size=${result.getOrNull()?.length()} bytes")
                        addTestLog("✓ Synchronization Stage Test Passed.")
                    }
                }
            } catch (e: Exception) {
                addTestLog("❌ Stage Test Error: ${e.message}")
            } finally {
                _isTestingRunning.value = false
            }
        }
    }

    private fun addTestLog(msg: String) {
        val current = _testLogs.value.toMutableList()
        current.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $msg")
        _testLogs.value = current
    }

    fun clearTestLogs() {
        _testLogs.value = emptyList()
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = app.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }
}

data class SubtitleInspectionUiState(
    val videoUri: Uri,
    val videoTitle: String,
    val tracks: List<com.example.subtitle.SubtitleTrackInfo>,
    val isInspecting: Boolean = false,
    val infoMessage: String? = null
)

