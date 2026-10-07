package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object AudioExporter {

    private const val TAG = "AudioExporter"

    /**
     * Saves dubbed audio file into the mobile device's public Download folder (Environment.DIRECTORY_DOWNLOADS)
     * so the user can easily find, share, listen to, or use it anywhere.
     */
    fun saveAudioToPublicDownloads(context: Context, fileName: String, sourceAudioFile: File): File? {
        if (!sourceAudioFile.exists() || sourceAudioFile.length() <= 44) {
            Log.w(TAG, "Source audio file does not exist or is empty: ${sourceAudioFile.absolutePath}")
            return null
        }

        val extension = if (sourceAudioFile.name.endsWith(".m4a", ignoreCase = true)) ".m4a" else ".wav"
        val cleanName = if (fileName.endsWith(extension, ignoreCase = true)) fileName else "$fileName$extension"
        val mimeType = if (extension == ".m4a") "audio/mp4" else "audio/x-wav"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BanglaDubbing")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outStream ->
                        FileInputStream(sourceAudioFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    Log.i(TAG, "Saved dubbed audio to public Downloads via MediaStore: $cleanName")
                }
            }

            // Also always write to public Downloads directory or app external files directory as direct File
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val subDir = File(publicDownloads, "BanglaDubbing").apply { if (!exists()) mkdirs() }
            val targetFile = File(subDir, cleanName)
            sourceAudioFile.copyTo(targetFile, overwrite = true)
            Log.i(TAG, "Saved dubbed audio directly to file: ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not save to public Downloads, falling back to app external files dir: ${e.message}")
            try {
                val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val fallbackFile = File(extDir, cleanName)
                sourceAudioFile.copyTo(fallbackFile, overwrite = true)
                return fallbackFile
            } catch (ex: Exception) {
                Log.e(TAG, "Failed fallback audio export: ${ex.message}")
                return null
            }
        }
    }
}
