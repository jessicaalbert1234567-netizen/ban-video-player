package com.example.models

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ModelItemUiState(
    val info: ModelInfo,
    val isInstalled: Boolean,
    val status: ModelStatus = ModelStatus.NOT_INSTALLED,
    val installedSizeBytes: Long = 0L,
    val downloadProgress: DownloadProgress? = null,
    val verification: ModelVerificationResult? = null,
    val isReadyForOfflineUse: Boolean = false
)

class ModelManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val downloader = ModelDownloader(context)

    private val _modelsState = MutableStateFlow<List<ModelItemUiState>>(emptyList())
    val modelsState: StateFlow<List<ModelItemUiState>> = _modelsState.asStateFlow()

    private val _isAllRequiredReady = MutableStateFlow(false)
    val isAllRequiredReady: StateFlow<Boolean> = _isAllRequiredReady.asStateFlow()

    init {
        refreshModelStatuses()
        // Listen to progress updates
        scope.launch {
            downloader.downloadProgressMap.collect { progressMap ->
                updateWithProgress(progressMap)
            }
        }
    }

    fun refreshModelStatuses() {
        val updatedList = ModelCatalog.REQUIRED_MODELS.map { model ->
            val verification = ModelInstaller.verifyModelOffline(context, model)
            val progress = downloader.downloadProgressMap.value[model.id]
            val status = when {
                progress?.status == ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                progress?.status == ModelStatus.VERIFYING -> ModelStatus.VERIFYING
                progress?.status == ModelStatus.INSTALLING -> ModelStatus.INSTALLING
                progress?.status == ModelStatus.ERROR -> ModelStatus.ERROR
                verification.isReadyForOfflineUse -> ModelStatus.READY
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
                isReadyForOfflineUse = verification.isReadyForOfflineUse
            )
        }
        _modelsState.value = updatedList
        _isAllRequiredReady.value = updatedList.all { it.isReadyForOfflineUse }
    }

    private fun updateWithProgress(progressMap: Map<String, DownloadProgress>) {
        val currentList = _modelsState.value
        val updated = currentList.map { item ->
            val progress = progressMap[item.info.id]
            val verification = ModelInstaller.verifyModelOffline(context, item.info)
            val status = when {
                progress?.status == ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                progress?.status == ModelStatus.VERIFYING -> ModelStatus.VERIFYING
                progress?.status == ModelStatus.INSTALLING -> ModelStatus.INSTALLING
                progress?.status == ModelStatus.ERROR -> ModelStatus.ERROR
                verification.isReadyForOfflineUse -> ModelStatus.READY
                verification.isFilePresent && !verification.onnxLoadSuccess -> ModelStatus.INCOMPATIBLE
                verification.isFilePresent && !verification.sha256Matches -> ModelStatus.ERROR
                !item.info.isSourceConfigured && !verification.isFilePresent -> ModelStatus.NOT_CONFIGURED
                !verification.isFilePresent -> ModelStatus.NOT_DOWNLOADED
                else -> ModelStatus.ERROR
            }
            item.copy(
                isInstalled = verification.isReadyForOfflineUse,
                status = status,
                installedSizeBytes = verification.fileSizeBytes,
                downloadProgress = progress,
                verification = verification,
                isReadyForOfflineUse = verification.isReadyForOfflineUse
            )
        }
        _modelsState.value = updated
        _isAllRequiredReady.value = updated.all { it.isReadyForOfflineUse }
    }

    fun downloadModel(model: ModelInfo) {
        scope.launch {
            downloader.downloadAndInstall(model)
            refreshModelStatuses()
        }
    }

    fun downloadAllRequiredModels() {
        scope.launch {
            for (model in ModelCatalog.REQUIRED_MODELS) {
                if (model.isSourceConfigured) {
                    val verified = ModelInstaller.verifyModelOffline(context, model)
                    if (!verified.isReadyForOfflineUse) {
                        downloader.downloadAndInstall(model)
                    }
                }
            }
            refreshModelStatuses()
        }
    }

    fun deleteModel(model: ModelInfo): Boolean {
        val file = ModelInstaller.getInstalledModelFile(context, model)
        var deleted = if (file.exists()) file.delete() else true
        for (aux in model.auxiliaryFiles) {
            val auxFile = ModelInstaller.getAuxiliaryFile(context, model, aux.fileName)
            if (auxFile.exists()) {
                auxFile.delete()
            }
        }
        refreshModelStatuses()
        return deleted
    }

    fun verifyAllModelsOffline(): Map<String, ModelVerificationResult> {
        val results = mutableMapOf<String, ModelVerificationResult>()
        for (model in ModelCatalog.REQUIRED_MODELS) {
            results[model.id] = ModelInstaller.verifyModelOffline(context, model)
        }
        refreshModelStatuses()
        return results
    }

    fun getStorageSummary(): Pair<Long, Long> {
        val baseDir = File(context.filesDir, "models")
        if (!baseDir.exists()) baseDir.mkdirs()
        val availableBytes = baseDir.usableSpace
        val requiredBytes = ModelCatalog.REQUIRED_MODELS
            .filter { model ->
                val v = ModelInstaller.verifyModelOffline(context, model)
                !v.isReadyForOfflineUse
            }
            .sumOf { it.sizeBytes }
        return Pair(requiredBytes, availableBytes)
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

