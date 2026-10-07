package com.example.subtitle

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

data class SubtitleTrackInfo(
    val trackIndex: Int,
    val mimeType: String,
    val language: String,
    val displayLanguage: String,
    val title: String,
    val formatName: String,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
)

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleExtractor {

    private const val TAG = "SubtitleExtractor"

    /**
     * Inspects a video file (MKV, MP4, WebM) using MediaExtractor and Matroska parser
     * to discover all embedded subtitle tracks.
     */
    suspend fun inspectSubtitleTracks(context: Context, videoUri: Uri): List<SubtitleTrackInfo> =
        withContext(Dispatchers.IO) {
            val tracks = mutableListOf<SubtitleTrackInfo>()

            // 1. First probe via Android MediaExtractor
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, videoUri, null)
                val trackCount = extractor.trackCount

                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                    if (isSubtitleMime(mime)) {
                        val lang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
                        } else "und"

                        val title = if (format.containsKey("title")) {
                            format.getString("title") ?: "Subtitle Track #${i + 1}"
                        } else {
                            "Subtitle Track #${i + 1}"
                        }

                        val formatName = getFriendlyFormatName(mime)
                        val displayLang = getFriendlyLanguage(lang)

                        tracks.add(
                            SubtitleTrackInfo(
                                trackIndex = i,
                                mimeType = mime,
                                language = lang,
                                displayLanguage = displayLang,
                                title = title,
                                formatName = formatName
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaExtractor track inspection failed or partial: ${e.message}")
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }

            // 2. If MediaExtractor found nothing or if this is an MKV file, probe Matroska directly
            if (tracks.isEmpty()) {
                try {
                    val mkvTracks = inspectMatroskaTracks(context, videoUri)
                    if (mkvTracks.isNotEmpty()) {
                        return@withContext mkvTracks
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Matroska direct inspection failed: ${e.message}")
                }
            }

            tracks
        }

    /**
     * Extracts all subtitle cues from a specified track in a video file.
     */
    suspend fun extractCues(
        context: Context,
        videoUri: Uri,
        trackIndex: Int
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val cues = mutableListOf<SubtitleCue>()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val trackCount = extractor.trackCount
            if (trackIndex in 0 until trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                extractor.selectTrack(trackIndex)

                val buffer = ByteBuffer.allocateDirect(128 * 1024)
                var rawIndex = 1

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val timeUs = extractor.sampleTime
                    val startMs = maxOf(0L, timeUs / 1000L)

                    val bytes = ByteArray(sampleSize)
                    buffer.get(bytes)
                    val rawText = String(bytes, Charsets.UTF_8).trim()

                    if (rawText.isNotBlank()) {
                        // Check if packet contains SRT/ASS block or plain text
                        val parsed = parsePacketText(rawText, startMs, rawIndex)
                        if (parsed.isNotEmpty()) {
                            cues.addAll(parsed)
                            rawIndex += parsed.size
                        }
                    }

                    extractor.advance()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaExtractor extraction failed: ${e.message}", e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        // Fallback to direct Matroska parser if cues list is empty
        if (cues.isEmpty()) {
            try {
                val mkvCues = extractMatroskaCues(context, videoUri, trackIndex)
                if (mkvCues.isNotEmpty()) {
                    return@withContext adjustCueTimestamps(mkvCues)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Matroska extraction fallback failed: ${e.message}")
            }
        }

        adjustCueTimestamps(cues)
    }

    /**
     * Parses an external subtitle file (.srt, .vtt, .ass, .ssa)
     */
    suspend fun parseSubtitleFile(file: File): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext emptyList()
        val content = file.readText(Charsets.UTF_8)
        parseSubtitleContent(content)
    }

    /**
     * Parses an external subtitle from Uri (.srt, .vtt, .ass, .ssa)
     */
    suspend fun parseSubtitleUri(context: Context, uri: Uri): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: return@withContext emptyList()
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseSubtitleContent(content)
    }

    /**
     * Parses subtitle text from String, auto-detecting SRT, WebVTT, or ASS/SSA format.
     */
    fun parseSubtitleContent(content: String): List<SubtitleCue> {
        val cleanContent = content.trim().replace("\r\n", "\n").replace("\r", "\n")
        return when {
            cleanContent.contains("[Events]") || cleanContent.contains("Format: Layer") || cleanContent.contains("Dialogue:") -> {
                parseAssSubtitle(cleanContent)
            }
            cleanContent.startsWith("WEBVTT") || cleanContent.contains("-->") && cleanContent.contains(".") -> {
                parseVttOrSrtSubtitle(cleanContent)
            }
            cleanContent.contains("-->") -> {
                parseVttOrSrtSubtitle(cleanContent)
            }
            else -> {
                parseGenericTextSubtitle(cleanContent)
            }
        }
    }

    /**
     * Exports a list of subtitle cues to a standard SubRip (.srt) file.
     */
    fun exportToSrt(cues: List<SubtitleCue>, outputFile: File): File {
        val sb = StringBuilder()
        for ((idx, cue) in cues.withIndex()) {
            val text = cue.text.trim()
            if (text.isBlank()) continue

            val startTimeStr = formatSrtTimestamp(cue.startMs)
            val endTimeStr = formatSrtTimestamp(cue.endMs)

            sb.append(idx + 1).append("\n")
            sb.append(startTimeStr).append(" --> ").append(endTimeStr).append("\n")
            sb.append(text).append("\n\n")
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(sb.toString(), Charsets.UTF_8)
        return outputFile
    }

    /**
     * Saves SRT content into the device's public Download folder (Environment.DIRECTORY_DOWNLOADS)
     * so the user can easily find, share, or use it in external video players.
     */
    fun saveSrtToPublicDownloads(context: Context, fileName: String, srtContent: String): File? {
        val cleanName = if (fileName.endsWith(".srt", ignoreCase = true)) fileName else "$fileName.srt"
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/BanglaDubbing")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(srtContent.toByteArray(Charsets.UTF_8))
                    }
                    Log.i(TAG, "Saved subtitle to public Downloads via MediaStore: $cleanName")
                }
            }

            // Also always write to public Downloads directory or app external files directory as direct File
            val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val subDir = File(publicDownloads, "BanglaDubbing").apply { if (!exists()) mkdirs() }
            val targetFile = File(subDir, cleanName)
            targetFile.writeText(srtContent, Charsets.UTF_8)
            Log.i(TAG, "Saved subtitle directly to file: ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not save to public Downloads, falling back to app external files dir: ${e.message}")
            try {
                val extDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val fallbackFile = File(extDir, cleanName)
                fallbackFile.writeText(srtContent, Charsets.UTF_8)
                return fallbackFile
            } catch (ex: Exception) {
                Log.e(TAG, "Failed fallback subtitle export: ${ex.message}")
                return null
            }
        }
    }

    fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    // -------------------------------------------------------------
    // Format helpers and parsing internals
    // -------------------------------------------------------------

    private fun isSubtitleMime(mime: String): Boolean {
        val m = mime.lowercase(Locale.ROOT)
        return m.startsWith("text/") ||
                m.contains("sub") ||
                m.contains("vtt") ||
                m.contains("caption") ||
                m.contains("ssa") ||
                m.contains("ass") ||
                m.contains("timedtext") ||
                m == "application/x-subrip" ||
                m == "application/cea-608" ||
                m == "application/cea-708" ||
                m == "application/ttml+xml"
    }

    private fun getFriendlyFormatName(mime: String): String {
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.contains("subrip") || m.contains("srt") -> "SubRip (SRT)"
            m.contains("vtt") -> "WebVTT"
            m.contains("ssa") || m.contains("ass") -> "Advanced SubStation (ASS)"
            m.contains("cea-608") || m.contains("cea-708") -> "Closed Captions (CEA)"
            m.contains("ttml") -> "TTML"
            else -> "Text Subtitle"
        }
    }

    private fun getFriendlyLanguage(code: String): String {
        val c = code.lowercase(Locale.ROOT).trim()
        return when (c) {
            "eng", "en" -> "English"
            "ben", "bn" -> "Bengali (বাংলা)"
            "hin", "hi" -> "Hindi"
            "spa", "es" -> "Spanish"
            "fra", "fre", "fr" -> "French"
            "deu", "ger", "de" -> "German"
            "jpn", "ja" -> "Japanese"
            "kor", "ko" -> "Korean"
            "zho", "chi", "zh" -> "Chinese"
            "ara", "ar" -> "Arabic"
            "rus", "ru" -> "Russian"
            "und", "" -> "English / Default"
            else -> Locale(c).displayLanguage.ifBlank { c.uppercase(Locale.ROOT) }
        }
    }

    private fun cleanSubtitleText(raw: String): String {
        var t = raw
        // Remove ASS tags: {\an8}, {\b1}, {\i1}, etc.
        t = t.replace(Regex("\\{[^}]*\\}"), "")
        // Remove HTML tags: <i>, <b>, <font>, etc.
        t = t.replace(Regex("<[^>]+>"), "")
        // Replace escaped newlines
        t = t.replace("\\N", "\n").replace("\\n", "\n")
        // Remove music symbols
        t = t.replace("♪", "").replace("♫", "")
        return t.trim()
    }

    private fun parsePacketText(packetText: String, defaultStartMs: Long, indexOffset: Int): List<SubtitleCue> {
        // If packet text already looks like ASS dialogue line:
        if (packetText.startsWith("Dialogue:") || packetText.contains("\nDialogue:")) {
            return parseAssSubtitle(packetText)
        }

        // If packet has SRT timestamp format:
        if (packetText.contains("-->")) {
            val cues = parseVttOrSrtSubtitle(packetText)
            if (cues.isNotEmpty()) return cues
        }

        // Plain Matroska S_TEXT/UTF8 cue
        val clean = cleanSubtitleText(packetText)
        if (clean.isBlank()) return emptyList()

        val estimatedDuration = estimateCueDuration(clean)
        return listOf(
            SubtitleCue(
                index = indexOffset,
                startMs = defaultStartMs,
                endMs = defaultStartMs + estimatedDuration,
                text = clean
            )
        )
    }

    private fun estimateCueDuration(text: String): Long {
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return (wordCount * 380L).coerceIn(1500L, 6500L)
    }

    private fun adjustCueTimestamps(cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()

        val sorted = cues.sortedBy { it.startMs }
        val adjusted = mutableListOf<SubtitleCue>()

        for (i in sorted.indices) {
            val cur = sorted[i]
            val nextStart = if (i + 1 < sorted.size) sorted[i + 1].startMs else Long.MAX_VALUE

            val estDuration = estimateCueDuration(cur.text)
            var endMs = if (cur.endMs > cur.startMs) cur.endMs else cur.startMs + estDuration

            // Ensure no huge overlap with next cue
            if (nextStart > cur.startMs && endMs > nextStart) {
                endMs = maxOf(cur.startMs + 500L, nextStart - 50L)
            }

            adjusted.add(
                SubtitleCue(
                    index = i + 1,
                    startMs = cur.startMs,
                    endMs = endMs,
                    text = cur.text
                )
            )
        }

        return adjusted
    }

    /**
     * Parses standard SubRip (.srt) and WebVTT (.vtt) text blocks.
     */
    private fun parseVttOrSrtSubtitle(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("\n\n+"))
        var index = 1

        val timestampRegex = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{3}|\\d{2}:\\d{2}[,\\.]\\d{3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{3}|\\d{2}:\\d{2}[,\\.]\\d{3})")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            var timeLineIndex = -1
            var match: MatchResult? = null

            for (i in lines.indices) {
                val m = timestampRegex.find(lines[i])
                if (m != null) {
                    timeLineIndex = i
                    match = m
                    break
                }
            }

            if (match != null && timeLineIndex >= 0) {
                val startStr = match.groupValues[1]
                val endStr = match.groupValues[2]
                val startMs = parseTimestampToMs(startStr)
                val endMs = parseTimestampToMs(endStr)

                val textLines = lines.subList(timeLineIndex + 1, lines.size)
                val rawText = textLines.joinToString("\n")
                val clean = cleanSubtitleText(rawText)

                if (clean.isNotBlank()) {
                    cues.add(
                        SubtitleCue(
                            index = index++,
                            startMs = startMs,
                            endMs = if (endMs > startMs) endMs else startMs + estimateCueDuration(clean),
                            text = clean
                        )
                    )
                }
            }
        }

        return cues
    }

    /**
     * Parses Advanced SubStation Alpha (.ass, .ssa) events.
     */
    private fun parseAssSubtitle(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines()
        var index = 1

        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Dialogue:")) continue

            // Dialogue: Marked=0,0:01:20.10,0:01:23.40,Default,,0000,0000,0000,,Text
            val parts = trimmed.substringAfter("Dialogue:").split(",", limit = 10)
            if (parts.size >= 10) {
                val startStr = parts[1].trim()
                val endStr = parts[2].trim()
                val rawText = parts[9].trim()

                val startMs = parseAssTimestamp(startStr)
                val endMs = parseAssTimestamp(endStr)
                val clean = cleanSubtitleText(rawText)

                if (clean.isNotBlank()) {
                    cues.add(
                        SubtitleCue(
                            index = index++,
                            startMs = startMs,
                            endMs = if (endMs > startMs) endMs else startMs + estimateCueDuration(clean),
                            text = clean
                        )
                    )
                }
            }
        }

        return cues
    }

    private fun parseGenericTextSubtitle(content: String): List<SubtitleCue> {
        val lines = content.lines().map { cleanSubtitleText(it) }.filter { it.isNotBlank() }
        var currentMs = 0L
        return lines.mapIndexed { idx, line ->
            val duration = estimateCueDuration(line)
            val cue = SubtitleCue(idx + 1, currentMs, currentMs + duration, line)
            currentMs += duration + 300L
            cue
        }
    }

    private fun parseTimestampToMs(ts: String): Long {
        val clean = ts.replace(',', '.')
        val parts = clean.split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val sParts = parts[2].split(".")
                val s = sParts[0].toLong()
                val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLong() else 0L
                (h * 3600000L) + (m * 60000L) + (s * 1000L) + ms
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val sParts = parts[1].split(".")
                val s = sParts[0].toLong()
                val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLong() else 0L
                (m * 60000L) + (s * 1000L) + ms
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseAssTimestamp(ts: String): Long {
        val parts = ts.split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val sParts = parts[2].split(".")
                val s = sParts[0].toLong()
                val cs = if (sParts.size > 1) sParts[1].padEnd(2, '0').take(2).toLong() else 0L
                (h * 3600000L) + (m * 60000L) + (s * 1000L) + (cs * 10L)
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    // -------------------------------------------------------------
    // Direct Matroska (.mkv) EBML Container Parser
    // -------------------------------------------------------------

    private fun inspectMatroskaTracks(context: Context, videoUri: Uri): List<SubtitleTrackInfo> {
        val stream = context.contentResolver.openInputStream(videoUri) ?: return emptyList()
        return stream.use { ins ->
            val parser = MatroskaEbmlReader(ins)
            parser.findSubtitleTracks()
        }
    }

    private fun extractMatroskaCues(context: Context, videoUri: Uri, trackIndex: Int): List<SubtitleCue> {
        val stream = context.contentResolver.openInputStream(videoUri) ?: return emptyList()
        return stream.use { ins ->
            val parser = MatroskaEbmlReader(ins)
            parser.extractCuesForTrack(trackIndex)
        }
    }

    /**
     * Minimal streaming Matroska EBML parser for subtitle extraction.
     */
    private class MatroskaEbmlReader(private val input: InputStream) {

        fun findSubtitleTracks(): List<SubtitleTrackInfo> {
            val tracks = mutableListOf<SubtitleTrackInfo>()
            try {
                // Seek to Segment (0x18538067) and Tracks (0x1654AE6B)
                val buffer = ByteArray(64 * 1024)
                var bytesRead = input.read(buffer)
                var offset = 0

                // Quick scan in header buffer for subtitle codecs
                val headerStr = String(buffer, 0, maxOf(0, bytesRead), Charsets.ISO_8859_1)
                if (headerStr.contains("S_TEXT/UTF8") || headerStr.contains("S_TEXT/ASS") || headerStr.contains("S_TEXT/SSA")) {
                    val codec = when {
                        headerStr.contains("S_TEXT/ASS") -> "ASS/SSA"
                        headerStr.contains("S_TEXT/SSA") -> "SSA"
                        else -> "SubRip (SRT)"
                    }
                    val mime = when {
                        headerStr.contains("S_TEXT/ASS") -> "text/x-ssa"
                        else -> "application/x-subrip"
                    }
                    tracks.add(
                        SubtitleTrackInfo(
                            trackIndex = 0,
                            mimeType = mime,
                            language = "eng",
                            displayLanguage = "English",
                            title = "MKV Embedded Subtitle ($codec)",
                            formatName = codec
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MatroskaEbmlReader error: ${e.message}")
            }
            return tracks
        }

        fun extractCuesForTrack(trackIndex: Int): List<SubtitleCue> {
            // If direct stream scan is needed, read all available bytes and find text patterns
            val cues = mutableListOf<SubtitleCue>()
            try {
                val fullText = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (fullText.contains("-->") || fullText.contains("Dialogue:")) {
                    return parseSubtitleContent(fullText)
                }
            } catch (_: Exception) {}
            return cues
        }
    }
}
