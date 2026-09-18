package com.example.storage

import android.content.Context
import java.io.File

class StorageManager(private val context: Context) {

    val rootProjectsDir: File
        get() {
            val dir = File(context.filesDir, "AI_Dubbing/Projects")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val tempDir: File
        get() {
            val dir = File(context.cacheDir, "dubbing_temp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getProjectDir(projectId: String): File {
        val dir = File(rootProjectsDir, projectId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getProjectSegmentsDir(projectId: String): File {
        val dir = File(getProjectDir(projectId), "segments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun clearTemporaryFiles(): Long {
        val freed = deleteDirectoryContents(tempDir)
        // Also clear segment audio files in projects
        val projects = rootProjectsDir.listFiles() ?: emptyArray()
        var segmentsFreed = 0L
        for (proj in projects) {
            val segDir = File(proj, "segments")
            if (segDir.exists()) {
                segmentsFreed += deleteDirectoryContents(segDir)
            }
        }
        return freed + segmentsFreed
    }

    fun getStorageBreakdown(): Triple<Long, Long, Long> {
        val modelsDir = File(context.filesDir, "models")
        val modelsSize = calculateDirectorySize(modelsDir)
        val dubsSize = calculateDirectorySize(rootProjectsDir)
        val tempSize = calculateDirectorySize(tempDir)
        return Triple(modelsSize, dubsSize, tempSize)
    }

    private fun deleteDirectoryContents(dir: File): Long {
        if (!dir.exists()) return 0L
        var bytesDeleted = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            bytesDeleted += if (file.isDirectory) {
                deleteDirectoryContents(file) + if (file.delete()) 0L else 0L
            } else {
                val len = file.length()
                if (file.delete()) len else 0L
            }
        }
        return bytesDeleted
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) calculateDirectorySize(f) else f.length()
        }
        return total
    }
}
