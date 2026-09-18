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
    val installedSizeBytes: Long = 0L,
    val downloadProgress: DownloadProgress? = null
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
            val file = ModelInstaller.getInstalledModelFile(context, model)
            val installed = file.exists() && file.length() > 0
            val size = if (installed) file.length() else 0L
            val progress = downloader.downloadProgressMap.value[model.id]
            ModelItemUiState(
                info = model,
                isInstalled = installed,
                installedSizeBytes = size,
                downloadProgress = progress
            )
        }
        _modelsState.value = updatedList
        _isAllRequiredReady.value = updatedList.all { it.isInstalled }
    }

    private fun updateWithProgress(progressMap: Map<String, DownloadProgress>) {
        val currentList = _modelsState.value
        val updated = currentList.map { item ->
            val progress = progressMap[item.info.id]
            val isNowInstalled = progress?.status == ModelStatus.INSTALLED || ModelInstaller.isModelInstalled(context, item.info)
            val file = ModelInstaller.getInstalledModelFile(context, item.info)
            item.copy(
                isInstalled = isNowInstalled,
                installedSizeBytes = if (isNowInstalled && file.exists()) file.length() else item.installedSizeBytes,
                downloadProgress = progress
            )
        }
        _modelsState.value = updated
        _isAllRequiredReady.value = updated.all { it.isInstalled }
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
                if (!ModelInstaller.isModelInstalled(context, model)) {
                    downloader.downloadAndInstall(model)
                }
            }
            refreshModelStatuses()
        }
    }

    fun deleteModel(model: ModelInfo): Boolean {
        val file = ModelInstaller.getInstalledModelFile(context, model)
        val deleted = if (file.exists()) file.delete() else true
        refreshModelStatuses()
        return deleted
    }

    fun getStorageSummary(): Pair<Long, Long> {
        val baseDir = File(context.filesDir, "models")
        if (!baseDir.exists()) baseDir.mkdirs()
        val availableBytes = baseDir.usableSpace
        val requiredBytes = ModelCatalog.REQUIRED_MODELS
            .filter { !ModelInstaller.isModelInstalled(context, it) }
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
