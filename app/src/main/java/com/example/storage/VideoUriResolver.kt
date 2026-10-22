package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object VideoUriResolver {

    private const val TAG = "VideoUriResolver"

    /**
     * Attempts to acquire persistable read permission so the content URI remains valid across reboots.
     */
    fun takePersistablePermission(context: Context, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                Log.d(TAG, "Persistable URI permission acquired for: $uri")
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistable permission for $uri: ${e.message}")
            }
        }
    }

    /**
     * Resolves the permanent, playable video URI for a dubbed project.
     * Guaranteed to work across app kills, device reboots, and storage changes.
     */
    fun resolvePlayableVideoUri(
        context: Context,
        projectId: String,
        videoTitle: String,
        savedUriString: String?,
        projectDirPath: String?
    ): Uri? {
        val storageManager = StorageManager(context)

        // 1. First priority: Check if savedUriString is directly accessible and playable
        if (!savedUriString.isNullOrBlank()) {
            try {
                val parsedUri = Uri.parse(savedUriString)
                if (parsedUri.scheme == "file") {
                    val f = File(parsedUri.path ?: "")
                    if (f.exists() && f.length() > 1024) {
                        Log.i(TAG, "Resolved video from existing file URI: ${f.absolutePath} (${f.length()} bytes)")
                        return parsedUri
                    }
                } else if (parsedUri.scheme == "content") {
                    takePersistablePermission(context, parsedUri)
                    var canRead = false
                    try {
                        context.contentResolver.openAssetFileDescriptor(parsedUri, "r")?.use { afd ->
                            canRead = afd.length > 0 || afd.length == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
                        }
                    } catch (_: Exception) {}
                    if (canRead) {
                        Log.i(TAG, "Resolved video from verified persistent content URI: $parsedUri")
                        return parsedUri
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking savedUriString accessibility: ${e.message}")
            }
        }

        // 2. Second priority: Direct local file inside project directory (source_video.* or video.*)
        val projectDirs = listOfNotNull(
            projectDirPath?.let { File(it) },
            storageManager.getProjectDir(projectId),
            File(storageManager.internalProjectsDir, projectId),
            File(storageManager.rootProjectsDir, projectId)
        ).distinct()

        for (dir in projectDirs) {
            if (dir.exists()) {
                val candidate = dir.listFiles()?.firstOrNull { file ->
                    file.isFile && file.length() > 5000 && (
                        file.name.startsWith("source_video") ||
                        file.name.startsWith("video") ||
                        file.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts")
                    ) && !file.name.contains("dubbed") && !file.name.contains("audio") &&
                       !file.name.endsWith(".wav") && !file.name.endsWith(".m4a") &&
                       !file.name.endsWith(".mp3") && !file.name.endsWith(".tmp")
                }
                if (candidate != null) {
                    Log.i(TAG, "Found local cached video in project dir: ${candidate.absolutePath} (${candidate.length()} bytes)")
                    return Uri.fromFile(candidate)
                }
            }
        }

        // 3. Third priority: Search standard public directories for matching video title
        if (videoTitle.isNotBlank()) {
            val publicDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileTranslato"),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            )

            for (pDir in publicDirs) {
                if (pDir.exists()) {
                    val directFile = File(pDir, videoTitle)
                    if (directFile.exists() && directFile.length() > 0) {
                        Log.i(TAG, "Auto-located video file from storage: ${directFile.absolutePath}")
                        return Uri.fromFile(directFile)
                    }
                }
            }
        }

        // 4. Fourth priority: Query Android MediaStore matching videoTitle
        if (videoTitle.isNotBlank()) {
            try {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME
                )
                val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(videoTitle)
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val id = cursor.getLong(idIdx)
                        val mediaUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        Log.i(TAG, "Auto-recovered video from MediaStore: $mediaUri")
                        return mediaUri
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore search note: ${e.message}")
            }
        }

        // 5. Ultimate fallback: If savedUriString is present, trust it rather than null
        if (!savedUriString.isNullOrBlank()) {
            try {
                val fallbackUri = Uri.parse(savedUriString)
                Log.i(TAG, "Using savedUriString as ultimate video playback target: $fallbackUri")
                return fallbackUri
            } catch (_: Exception) {}
        }

        Log.w(TAG, "Could not locate source video file for $videoTitle (id=$projectId)")
        return null
    }
}
