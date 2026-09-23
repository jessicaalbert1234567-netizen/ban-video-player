package com.example.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ModelItemUiState(
    val info: ModelInfo,
    val isInstalled: Boolean,
    val status: ModelStatus = ModelStatus.NOT_INSTALLED,
    val installedSizeBytes: Long = 0L,
    val downloadProgress: DownloadProgress? = null,
    val verification: ModelVerificationResult? = null,
    val isReadyForOfflineUse: Boolean = false,
    val isLoadedInMemory: Boolean = false
)

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "MODEL_MANAGER"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloader = ModelDownloader(context)

    private val _modelsState = MutableStateFlow<List<ModelItemUiState>>(emptyList())
    val modelsState: StateFlow<List<ModelItemUiState>> = _modelsState.asStateFlow()

    private val _isAllRequiredReady = MutableStateFlow(false)
    val isAllRequiredReady: StateFlow<Boolean> = _isAllRequiredReady.asStateFlow()

    private val _storageSummary = MutableStateFlow<Pair<Long, Long>>(Pair(0L, 0L))
    val storageSummary: StateFlow<Pair<Long, Long>> = _storageSummary.asStateFlow()

    // In-memory verification cache to prevent redundant disk I/O and SHA-256 calculations
    private val verificationCache = ConcurrentHashMap<String, ModelVerificationResult>()

    init {
        Log.i(TAG, "Initializing ModelManager on thread '${Thread.currentThread().name}'")
        // Initial model status scan (always run off UI thread on Dispatchers.IO)
        scope.launch {
            refreshModelStatusesInternal(forceDeepCheck = false)
        }

        // Listen to downloader progress updates without triggering disk re-verification
        scope.launch {
            downloader.downloadProgressMap.collect { progressMap ->
                updateWithProgress(progressMap)
            }
        }
    }

    fun refreshModelStatuses(forceDeepCheck: Boolean = false) {
        scope.launch {
            refreshModelStatusesInternal(forceDeepCheck)
        }
    }

    suspend fun refreshModelStatusesSync(forceDeepCheck: Boolean = false) {
        withContext(Dispatchers.IO) {
            refreshModelStatusesInternal(forceDeepCheck)
        }
    }

    private suspend fun refreshModelStatusesInternal(forceDeepCheck: Boolean) = withContext(Dispatchers.IO) {
        val threadName = Thread.currentThread().name
        Log.d(TAG, "Refreshing model statuses (deepCheck=$forceDeepCheck) on thread '$threadName'")

        val updatedList = ModelCatalog.ALL_MODELS.map { model ->
            val verification = if (forceDeepCheck || !verificationCache.containsKey(model.id)) {
                val v = ModelInstaller.verifyModelOffline(context, model, deepCheck = forceDeepCheck)
                verificationCache[model.id] = v
                v
            } else {
                verificationCache[model.id]!!
            }

            val progress = downloader.downloadProgressMap.value[model.id]
            val status = when {
                progress?.status == ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                progress?.status == ModelStatus.VERIFYING -> ModelStatus.VERIFYING
                progress?.status == ModelStatus.EXTRACTING -> ModelStatus.EXTRACTING
                progress?.status == ModelStatus.INSTALLING -> ModelStatus.INSTALLING
                progress?.status == ModelStatus.ERROR -> ModelStatus.ERROR
                verification.isReadyForOfflineUse -> ModelStatus.READY_ON_DISK
                verification.isFilePresent && !verification.onnxLoadSuccess -> ModelStatus.INCOMPATIBLE
                verification.isFilePresent && !verification.sha256Matches -> ModelStatus.ERROR
                !model.isSourceConfigured && !verification.isFilePresent -> ModelStatus.NOT_CONFIGURED
                !verification.isFilePresent -> ModelStatus.NOT_DOWNLOADED
                else -> ModelStatus.ERROR
            }

            ModelItemUiState(
                info = model,
                isInstalled = verification.isReadyForOfflineUse,
                status = status,
                installedSizeBytes = verification.fileSizeBytes,
                downloadProgress = progress,
                verification = verification,
                isReadyForOfflineUse = verification.isReadyForOfflineUse,
                isLoadedInMemory = false
            )
        }

        _modelsState.value = updatedList
        val hasAsr = updatedList.any { it.info.id == ModelCatalog.ENGLISH_ASR.id && it.isReadyForOfflineUse }
        val hasTranslation = updatedList.any { it.info.id == ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION.id && it.isReadyForOfflineUse }
        _isAllRequiredReady.value = hasAsr && hasTranslation

        // Update cached storage summary asynchronously
        val baseDir = File(context.filesDir, "models")
        val availableBytes = if (baseDir.exists()) baseDir.usableSpace else 0L
        val requiredBytes = updatedList
            .filter { !it.isReadyForOfflineUse }
            .sumOf { it.info.sizeBytes + it.info.auxiliaryFiles.sumOf { aux -> aux.expectedSizeBytes } }
        _storageSummary.value = Pair(requiredBytes, availableBytes)

        Log.d(
            TAG,
            "Model statuses updated on thread '$threadName'. All ready: ${_isAllRequiredReady.value} " +
                    "(required missing: ${requiredBytes / (1024 * 1024)}MB, available: ${availableBytes / (1024 * 1024)}MB)"
        )
    }

    /**
     * Efficiently handles download progress updates in memory.
     * NEVER performs disk scans, SHA-256 hashing, or ONNX session checks during progress ticks.
     */
    private fun updateWithProgress(progressMap: Map<String, DownloadProgress>) {
        val currentList = _modelsState.value
        if (currentList.isEmpty() || progressMap.isEmpty()) return

        var anyCompleted = false
        val updated = currentList.map { item ->
            val progress = progressMap[item.info.id] ?: return@map item

            if (progress.status == ModelStatus.READY_ON_DISK || progress.status == ModelStatus.READY) {
                if (!item.isReadyForOfflineUse) {
                    anyCompleted = true
                }
            }

            val newStatus = when (progress.status) {
                ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                ModelStatus.VERIFYING -> ModelStatus.VERIFYING
                ModelStatus.EXTRACTING -> ModelStatus.EXTRACTING
                ModelStatus.INSTALLING -> ModelStatus.INSTALLING
                ModelStatus.READY, ModelStatus.READY_ON_DISK -> ModelStatus.READY_ON_DISK
                ModelStatus.ERROR -> ModelStatus.ERROR
                else -> item.status
            }

            val isInstalledNow = if (progress.status == ModelStatus.READY || progress.status == ModelStatus.READY_ON_DISK) {
                true
            } else {
                item.isInstalled
            }

            item.copy(
                status = newStatus,
                downloadProgress = progress,
                isInstalled = isInstalledNow,
                isReadyForOfflineUse = isInstalledNow
            )
        }

        _modelsState.value = updated
        val hasAsr = updated.any { it.info.id == ModelCatalog.ENGLISH_ASR.id && it.isReadyForOfflineUse }
        val hasTranslation = updated.any { it.info.id == ModelCatalog.ENGLISH_TO_BANGLA_TRANSLATION.id && it.isReadyForOfflineUse }
        _isAllRequiredReady.value = hasAsr && hasTranslation

        // When a model finishes installation, invalidate its cache and refresh status on Dispatchers.IO
        if (anyCompleted) {
            scope.launch {
                verificationCache.clear()
                refreshModelStatusesInternal(forceDeepCheck = false)
            }
        }
    }

    fun downloadModel(model: ModelInfo) {
        scope.launch {
            Log.i(TAG, "Starting download for ${model.name} on thread '${Thread.currentThread().name}'")
            downloader.downloadAndInstall(model)
            verificationCache.remove(model.id)
            refreshModelStatusesInternal(forceDeepCheck = false)
        }
    }

    fun downloadAllRequiredModels() {
        scope.launch {
            Log.i(TAG, "Starting download for all required models on thread '${Thread.currentThread().name}'")
            for (model in ModelCatalog.REQUIRED_MODELS) {
                if (model.isSourceConfigured) {
                    val verified = verificationCache[model.id]
                        ?: ModelInstaller.verifyModelOffline(context, model, deepCheck = false)
                    if (!verified.isReadyForOfflineUse) {
                        downloader.downloadAndInstall(model)
                        verificationCache.remove(model.id)
                    }
                }
            }
            refreshModelStatusesInternal(forceDeepCheck = false)
        }
    }

    fun deleteModel(model: ModelInfo): Boolean {
        verificationCache.remove(model.id)
        if (model.type == ModelType.TRANSLATION) {
            scope.launch {
                try {
                    com.example.translation.EnglishToBanglaTranslator.deleteModel()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to delete ML Kit translation model", e)
                }
                refreshModelStatusesInternal(forceDeepCheck = false)
            }
            return true
        }
        val file = ModelInstaller.getInstalledModelFile(context, model)
        val deleted = if (file.exists()) file.delete() else true
        for (aux in model.auxiliaryFiles) {
            val auxFile = ModelInstaller.getAuxiliaryFile(context, model, aux.fileName)
            if (auxFile.exists()) {
                auxFile.delete()
            }
        }
        scope.launch {
            refreshModelStatusesInternal(forceDeepCheck = false)
        }
        return deleted
    }

    fun verifyAllModelsOffline(): Map<String, ModelVerificationResult> {
        verificationCache.clear()
        val results = mutableMapOf<String, ModelVerificationResult>()
        for (model in ModelCatalog.REQUIRED_MODELS) {
            val v = ModelInstaller.verifyModelOffline(context, model, deepCheck = true)
            verificationCache[model.id] = v
            results[model.id] = v
        }
        scope.launch {
            refreshModelStatusesInternal(forceDeepCheck = false)
        }
        return results
    }

    /**
     * Non-blocking getter returning pre-computed storage summary.
     */
    fun getStorageSummary(): Pair<Long, Long> {
        return _storageSummary.value
    }

    fun getModelsDirectoryTotalSize(): Long {
        val baseDir = File(context.filesDir, "models")
        return calculateDirectorySize(baseDir)
    }

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        var length = 0L
        val files = directory.listFiles() ?: return 0L
        for (file in files) {
            length += if (file.isFile) file.length() else calculateDirectorySize(file)
        }
        return length
    }
}
