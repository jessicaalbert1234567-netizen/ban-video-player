package com.example.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProcessingMode(val displayName: String, val description: String) {
    FAST("Fast", "High speed, keeps models cached in RAM for instant inference"),
    BALANCED("Balanced", "Optimized balance of RAM usage and processing speed"),
    MEMORY_SAVER("Memory Saver", "Designed for 3-4 GB RAM phones. Uses smaller chunks and unloads models between stages.")
}

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("dubbing_settings", Context.MODE_PRIVATE)

    private val _processingMode = MutableStateFlow(loadProcessingMode())
    val processingMode: StateFlow<ProcessingMode> = _processingMode.asStateFlow()

    private val _autoCleanTempFiles = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CLEAN, true))
    val autoCleanTempFiles: StateFlow<Boolean> = _autoCleanTempFiles.asStateFlow()

    fun setProcessingMode(mode: ProcessingMode) {
        prefs.edit().putString(KEY_PROCESSING_MODE, mode.name).apply()
        _processingMode.value = mode
    }

    fun setAutoCleanTempFiles(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEAN, enabled).apply()
        _autoCleanTempFiles.value = enabled
    }

    private fun loadProcessingMode(): ProcessingMode {
        val name = prefs.getString(KEY_PROCESSING_MODE, ProcessingMode.BALANCED.name) ?: ProcessingMode.BALANCED.name
        return try {
            ProcessingMode.valueOf(name)
        } catch (e: Exception) {
            ProcessingMode.BALANCED
        }
    }

    companion object {
        private const val KEY_PROCESSING_MODE = "key_processing_mode"
        private const val KEY_AUTO_CLEAN = "key_auto_clean"
    }
}
