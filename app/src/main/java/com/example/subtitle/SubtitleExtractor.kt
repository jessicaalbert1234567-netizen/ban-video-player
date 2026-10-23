package com.example.subtitle

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.Inflater

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
     * Inspects a video file (MKV, MP4, WebM) using streaming Matroska EBML parser and MediaExtractor
     * to discover all embedded subtitle tracks accurately without loading large files into RAM.
     */
    suspend fun inspectSubtitleTracks(context: Context, videoUri: Uri): List<SubtitleTrackInfo> =
        withContext(Dispatchers.IO) {
            // 1. First probe via streaming Matroska (.mkv / .webm) EBML header parser
            try {
                val mkvTracks = inspectMatroskaTracks(context, videoUri)
                if (mkvTracks.isNotEmpty()) {
                    Log.i(TAG, "Discovered ${mkvTracks.size} MKV subtitle track(s) via EBML header parser")
                    return@withContext mkvTracks
                }
            } catch (e: Exception) {
                Log.w(TAG, "Matroska EBML track inspection note: ${e.message}")
            }

            // 2. Probe via Android MediaExtractor (for MP4, WebM, 3GP, or fallback)
            val tracks = mutableListOf<SubtitleTrackInfo>()
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

            tracks
        }

    /**
     * Extracts all subtitle cues from a specified track in a video file.
     * Uses zero-OOM streaming EBML extraction for MKV files and MediaExtractor for MP4/other containers.
     */
    suspend fun extractCues(
        context: Context,
        videoUri: Uri,
        trackIndex: Int
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        // 1. Try fast streaming Matroska (.mkv) EBML extractor first if the file is an MKV container
        try {
            val mkvCues = extractMatroskaCues(context, videoUri, trackIndex)
            if (mkvCues.isNotEmpty()) {
                Log.i(TAG, "Extracted ${mkvCues.size} subtitle cues from MKV track $trackIndex via streaming EBML parser")
                return@withContext adjustCueTimestamps(mkvCues)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Streaming Matroska extraction note: ${e.message}")
        }

        // 2. Try Android MediaExtractor ONLY if the target track is genuinely a subtitle track
        val cues = mutableListOf<SubtitleCue>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val trackCount = extractor.trackCount
            var effectiveSubTrack = -1

            if (trackIndex in 0 until trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (isSubtitleMime(mime)) {
                    effectiveSubTrack = trackIndex
                }
            }

            if (effectiveSubTrack == -1) {
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (isSubtitleMime(mime)) {
                        effectiveSubTrack = i
                        break
                    }
                }
            }

            if (effectiveSubTrack != -1) {
                extractor.selectTrack(effectiveSubTrack)
                val buffer = ByteBuffer.allocateDirect(256 * 1024)
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
            Log.e(TAG, "MediaExtractor subtitle extraction failed: ${e.message}", e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        if (cues.isEmpty()) {
            try {
                val ffmpegCues = extractCuesViaFfmpeg(context, videoUri, trackIndex)
                if (ffmpegCues.isNotEmpty()) {
                    return@withContext adjustCueTimestamps(ffmpegCues)
                }
            } catch (e: Exception) {
                Log.w(TAG, "FFmpeg subtitle extraction fallback failed: ${e.message}")
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
        val cleanContent = content.trim().removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
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
        var validIdx = 1
        for (cue in cues) {
            val text = cue.text.trim()
            if (text.isBlank()) continue

            val startTimeStr = formatSrtTimestamp(cue.startMs)
            val endTimeStr = formatSrtTimestamp(cue.endMs)

            sb.append(validIdx++).append("\n")
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

            val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val subDir = File(publicDownloads, "FileTranslato").apply { if (!exists()) mkdirs() }
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
        val safeMs = ms.coerceAtLeast(0L)
        val hours = safeMs / 3600000
        val minutes = (safeMs % 3600000) / 60000
        val seconds = (safeMs % 60000) / 1000
        val millis = safeMs % 1000
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
            "mal", "ml" -> "Malayalam"
            "tam", "ta" -> "Tamil"
            "tel", "te" -> "Telugu"
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

    private val MKV_ASS_BLOCK_PREFIX_REGEX = Regex("^\\d+,\\s*\\d*,[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,")

    private fun cleanSubtitleText(raw: String, isAssCodec: Boolean = false): String {
        var t = raw.trim().removePrefix("\uFEFF")
        if (isAssCodec || MKV_ASS_BLOCK_PREFIX_REGEX.containsMatchIn(t)) {
            t = t.replaceFirst(MKV_ASS_BLOCK_PREFIX_REGEX, "")
        }
        t = t.replace(Regex("\\{[^}]*\\}"), "")
        t = t.replace(Regex("<[^>]+>"), "")
        t = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        t = t.replace("♪", "").replace("♫", "")
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
    }

    private fun parsePacketText(packetText: String, defaultStartMs: Long, indexOffset: Int): List<SubtitleCue> {
        if (packetText.startsWith("Dialogue:") || packetText.contains("\nDialogue:")) {
            return parseAssSubtitle(packetText)
        }

        if (packetText.contains("-->")) {
            val cues = parseVttOrSrtSubtitle(packetText)
            if (cues.isNotEmpty()) return cues
        }

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

            if (nextStart > cur.startMs && endMs > nextStart) {
                endMs = maxOf(cur.startMs + 400L, nextStart - 40L)
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

    private fun parseVttOrSrtSubtitle(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("\n\n+"))
        var index = 1

        val timestampRegex = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{1,3}|\\d{2}:\\d{2}[,\\.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{1,3}|\\d{2}:\\d{2}[,\\.]\\d{1,3})")

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

    private fun parseAssSubtitle(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines()
        var index = 1

        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Dialogue:")) continue

            val parts = trimmed.substringAfter("Dialogue:").split(",", limit = 10)
            if (parts.size >= 10) {
                val startStr = parts[1].trim()
                val endStr = parts[2].trim()
                val rawText = parts[9].trim()

                val startMs = parseAssTimestamp(startStr)
                val endMs = parseAssTimestamp(endStr)
                val clean = cleanSubtitleText(rawText, isAssCodec = true)

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
    // Streaming Zero-OOM Matroska (.mkv) EBML Container Parser
    // -------------------------------------------------------------

    private fun extractCuesViaFfmpeg(context: Context, videoUri: Uri, trackIndex: Int): List<SubtitleCue> {
        val directFile = if (videoUri.scheme == "file" && videoUri.path != null) {
            File(videoUri.path!!)
        } else null
        if (directFile == null || !directFile.exists()) return emptyList()

        return try {
            val srtOutput = StringBuilder()
            val cmd = arrayOf(
                "ffmpeg", "-y",
                "-i", directFile.absolutePath,
                "-map", "0:s:0",
                "-f", "srt",
                "-"
            )
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(false)
                .start()
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            var line = reader.readLine()
            while (line != null) {
                srtOutput.append(line).append("\n")
                line = reader.readLine()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0 && srtOutput.isNotBlank()) {
                parseSubtitleContent(srtOutput.toString())
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun openEbmlReader(context: Context, videoUri: Uri): FastEbmlStream? {
        try {
            if (videoUri.scheme == "file" && !videoUri.path.isNullOrBlank()) {
                val file = File(videoUri.path!!)
                if (file.exists() && file.length() > 16) {
                    val fis = FileInputStream(file)
                    return FastEbmlStream(fis, fis.channel, null)
                }
            }
            val pfd: ParcelFileDescriptor? = try {
                context.contentResolver.openFileDescriptor(videoUri, "r")
            } catch (_: Exception) {
                null
            }
            if (pfd != null) {
                val fis = FileInputStream(pfd.fileDescriptor)
                return FastEbmlStream(fis, fis.channel, pfd)
            }
            val ins = context.contentResolver.openInputStream(videoUri) ?: return null
            return FastEbmlStream(BufferedInputStream(ins, 64 * 1024), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open EBML stream for $videoUri: ${e.message}")
            return null
        }
    }

    private fun inspectMatroskaTracks(context: Context, videoUri: Uri): List<SubtitleTrackInfo> {
        val stream = openEbmlReader(context, videoUri) ?: return emptyList()
        return stream.use { ebml ->
            MatroskaStreamingParser(ebml).findSubtitleTracks()
        }
    }

    private fun extractMatroskaCues(context: Context, videoUri: Uri, trackIndex: Int): List<SubtitleCue> {
        val stream = openEbmlReader(context, videoUri) ?: return emptyList()
        return stream.use { ebml ->
            MatroskaStreamingParser(ebml).extractCuesForTrack(trackIndex)
        }
    }

    private class FastEbmlStream(
        private val inputStream: InputStream,
        private val fileChannel: FileChannel?,
        private val pfd: ParcelFileDescriptor?
    ) : AutoCloseable {

        private val buffer = ByteArray(64 * 1024)
        private var bufPos = 0
        private var bufLimit = 0
        private var baseFileOffset = 0L
        var isEof = false
            private set

        val position: Long
            get() = baseFileOffset + bufPos

        private fun refill(): Boolean {
            if (isEof) return false
            baseFileOffset += bufLimit
            bufPos = 0
            bufLimit = 0

            val read = if (fileChannel != null) {
                val bb = ByteBuffer.wrap(buffer)
                fileChannel.read(bb)
            } else {
                inputStream.read(buffer, 0, buffer.size)
            }

            if (read <= 0) {
                isEof = true
                return false
            }
            bufLimit = read
            return true
        }

        fun readByte(): Int {
            if (bufPos >= bufLimit && !refill()) return -1
            return buffer[bufPos++].toInt() and 0xFF
        }

        fun readSignedShort(): Short {
            val b0 = readByte()
            val b1 = readByte()
            if (b0 < 0 || b1 < 0) return 0
            return ((b0 shl 8) or b1).toShort()
        }

        fun readBytes(length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val safeLen = length.coerceAtMost(2 * 1024 * 1024)
            val out = ByteArray(safeLen)
            var offset = 0
            while (offset < safeLen) {
                if (bufPos >= bufLimit && !refill()) break
                val avail = (bufLimit - bufPos).coerceAtMost(safeLen - offset)
                System.arraycopy(buffer, bufPos, out, offset, avail)
                bufPos += avail
                offset += avail
            }
            return if (offset == safeLen) out else out.copyOf(offset)
        }

        fun skipTo(targetPosition: Long) {
            val currentPos = position
            if (targetPosition <= currentPos) return
            val delta = targetPosition - currentPos

            if (bufPos + delta <= bufLimit) {
                bufPos += delta.toInt()
                return
            }

            if (fileChannel != null) {
                try {
                    fileChannel.position(targetPosition)
                    baseFileOffset = targetPosition
                    bufPos = 0
                    bufLimit = 0
                    return
                } catch (_: Exception) {}
            }

            var remaining = delta - (bufLimit - bufPos)
            baseFileOffset += bufLimit
            bufPos = 0
            bufLimit = 0

            while (remaining > 0 && !isEof) {
                val skipped = inputStream.skip(remaining)
                if (skipped > 0) {
                    baseFileOffset += skipped
                    remaining -= skipped
                } else {
                    val b = inputStream.read()
                    if (b < 0) {
                        isEof = true
                        break
                    }
                    baseFileOffset += 1
                    remaining -= 1
                }
            }
        }

        fun readElementId(): Long {
            val b0 = readByte()
            if (b0 <= 0) return -1L
            var numBytes = 1
            var mask = 0x80
            while ((b0 and mask) == 0 && numBytes <= 4) {
                mask = mask ushr 1
                numBytes++
            }
            if (numBytes > 4) return -1L
            var value = b0.toLong()
            for (i in 1 until numBytes) {
                val bx = readByte()
                if (bx < 0) return -1L
                value = (value shl 8) or bx.toLong()
            }
            return value
        }

        fun readVintSize(): Pair<Long, Boolean> {
            val b0 = readByte()
            if (b0 <= 0) return Pair(-1L, false)
            var numBytes = 1
            var mask = 0x80
            while ((b0 and mask) == 0 && numBytes <= 8) {
                mask = mask ushr 1
                numBytes++
            }
            if (numBytes > 8) return Pair(-1L, false)
            var value = (b0 and (mask - 1)).toLong()
            var allOnes = (value == (mask - 1).toLong())
            for (i in 1 until numBytes) {
                val bx = readByte()
                if (bx < 0) return Pair(-1L, false)
                if (bx != 0xFF) allOnes = false
                value = (value shl 8) or bx.toLong()
            }
            return Pair(value, allOnes)
        }

        fun readUnsignedInt(size: Long): Long {
            val len = size.toInt().coerceIn(0, 8)
            var v = 0L
            for (i in 0 until len) {
                val b = readByte()
                if (b < 0) break
                v = (v shl 8) or b.toLong()
            }
            return v
        }

        fun readAsciiOrUtf8String(size: Long): String {
            val bytes = readBytes(size.toInt().coerceIn(0, 65536))
            var validLen = bytes.size
            while (validLen > 0 && bytes[validLen - 1] == 0.toByte()) {
                validLen--
            }
            return String(bytes, 0, validLen, Charsets.UTF_8).trim()
        }

        override fun close() {
            try { inputStream.close() } catch (_: Exception) {}
            try { fileChannel?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private data class MkvTrackMeta(
        var trackNumber: Int = 0,
        var trackType: Int = 0,
        var codecId: String = "",
        var language: String = "eng",
        var languageBcp47: String = "",
        var name: String = "",
        var isDefault: Boolean = true,
        var isForced: Boolean = false,
        var isCompressed: Boolean = false,
        var compAlgo: Int = -1,
        var compHeaderStripBytes: ByteArray? = null
    ) {
        val effectiveLanguage: String
            get() = languageBcp47.ifBlank { language.ifBlank { "eng" } }

        val isSubtitleTrack: Boolean
            get() = trackType == 0x11 ||
                    codecId.startsWith("S_TEXT", ignoreCase = true) ||
                    codecId.contains("SUBRIP", ignoreCase = true) ||
                    codecId.contains("WEBVTT", ignoreCase = true) ||
                    codecId.contains("ASS", ignoreCase = true) ||
                    codecId.contains("SSA", ignoreCase = true)

        val isAssOrSsa: Boolean
            get() = codecId.contains("ASS", ignoreCase = true) || codecId.contains("SSA", ignoreCase = true)
    }

    private class MatroskaStreamingParser(private val stream: FastEbmlStream) {

        private var timecodeScaleNs: Long = 1_000_000L
        private val subtitleTracks = mutableListOf<MkvTrackMeta>()
        private var pendingTopElement: Triple<Long, Long, Boolean>? = null

        fun findSubtitleTracks(): List<SubtitleTrackInfo> {
            parseHeaderAndTracks(stopAtFirstCluster = true)
            return subtitleTracks.map { meta ->
                val mime = when {
                    meta.isAssOrSsa -> "text/x-ssa"
                    meta.codecId.contains("WEBVTT", ignoreCase = true) -> "text/vtt"
                    else -> "application/x-subrip"
                }
                val formatName = getFriendlyFormatName(mime)
                val displayLang = getFriendlyLanguage(meta.effectiveLanguage)
                val label = if (meta.name.isNotBlank()) {
                    "${meta.name} ($displayLang)"
                } else {
                    "$displayLang Subtitle (#${meta.trackNumber})"
                }
                SubtitleTrackInfo(
                    trackIndex = meta.trackNumber,
                    mimeType = mime,
                    language = meta.effectiveLanguage,
                    displayLanguage = displayLang,
                    title = label,
                    formatName = formatName,
                    isDefault = meta.isDefault,
                    isForced = meta.isForced
                )
            }
        }

        fun extractCuesForTrack(requestedTrackIndex: Int): List<SubtitleCue> {
            val rawCues = mutableListOf<SubtitleCue>()
            if (!verifyEbmlHeader()) return emptyList()

            var segmentEnd = Long.MAX_VALUE
            var foundSegment = false
            while (!stream.isEof) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, unknown) = stream.readVintSize()
                if (size < 0) break
                if (id == 0x18538067L) {
                    foundSegment = true
                    segmentEnd = if (unknown) Long.MAX_VALUE else stream.position + size
                    break
                } else {
                    stream.skipTo(stream.position + size)
                }
            }
            if (!foundSegment) return emptyList()

            var resolvedTargetTrack: MkvTrackMeta? = null

            while (!stream.isEof && stream.position < segmentEnd) {
                val currentElem = pendingTopElement
                val id: Long
                val size: Long
                val unknown: Boolean
                if (currentElem != null) {
                    id = currentElem.first
                    size = currentElem.second
                    unknown = currentElem.third
                    pendingTopElement = null
                } else {
                    id = stream.readElementId()
                    if (id < 0) break
                    val szPair = stream.readVintSize()
                    size = szPair.first
                    unknown = szPair.second
                    if (size < 0) break
                }

                val elemEnd = if (unknown) segmentEnd else (stream.position + size)

                when (id) {
                    0x1549A966L -> parseInfoElement(elemEnd)
                    0x1654AE6BL -> {
                        parseTracksElement(elemEnd)
                        resolvedTargetTrack = resolveTargetSubtitleTrack(requestedTrackIndex)
                    }
                    0x1F43B675L -> {
                        if (resolvedTargetTrack == null) {
                            resolvedTargetTrack = resolveTargetSubtitleTrack(requestedTrackIndex)
                        }
                        val target = resolvedTargetTrack ?: return emptyList()
                        parseClusterElement(elemEnd, unknown, target, rawCues)
                    }
                    else -> {
                        if (!unknown) {
                            stream.skipTo(elemEnd)
                        }
                    }
                }
            }

            return rawCues
        }

        private fun resolveTargetSubtitleTrack(requestedTrackIndex: Int): MkvTrackMeta? {
            if (subtitleTracks.isEmpty()) return null
            subtitleTracks.firstOrNull { it.trackNumber == requestedTrackIndex }?.let { return it }
            if (requestedTrackIndex in subtitleTracks.indices) {
                return subtitleTracks[requestedTrackIndex]
            }
            return subtitleTracks.firstOrNull {
                it.effectiveLanguage.startsWith("en", ignoreCase = true)
            } ?: subtitleTracks.firstOrNull { it.isDefault } ?: subtitleTracks.first()
        }

        private fun verifyEbmlHeader(): Boolean {
            val firstId = stream.readElementId()
            if (firstId != 0x1A45DFA3L) return false
            val (headerSize, _) = stream.readVintSize()
            if (headerSize < 0) return false
            stream.skipTo(stream.position + headerSize)
            return true
        }

        private fun parseHeaderAndTracks(stopAtFirstCluster: Boolean) {
            if (!verifyEbmlHeader()) return

            while (!stream.isEof) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, unknown) = stream.readVintSize()
                if (size < 0) break
                if (id == 0x18538067L) {
                    val segmentEnd = if (unknown) Long.MAX_VALUE else stream.position + size
                    while (!stream.isEof && stream.position < segmentEnd) {
                        val subId = stream.readElementId()
                        if (subId < 0) break
                        val (subSize, subUnknown) = stream.readVintSize()
                        if (subSize < 0) break
                        val subEnd = if (subUnknown) segmentEnd else stream.position + subSize

                        when (subId) {
                            0x1549A966L -> parseInfoElement(subEnd)
                            0x1654AE6BL -> {
                                parseTracksElement(subEnd)
                                if (stopAtFirstCluster && subtitleTracks.isNotEmpty()) return
                            }
                            0x1F43B675L -> {
                                if (stopAtFirstCluster) return
                                stream.skipTo(subEnd)
                            }
                            else -> {
                                if (!subUnknown) stream.skipTo(subEnd)
                            }
                        }
                    }
                    return
                } else {
                    stream.skipTo(stream.position + size)
                }
            }
        }

        private fun parseInfoElement(infoEnd: Long) {
            while (!stream.isEof && stream.position < infoEnd) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, _) = stream.readVintSize()
                if (size < 0) break
                val childEnd = stream.position + size
                if (id == 0x2AD7B1L) {
                    val scale = stream.readUnsignedInt(size)
                    if (scale > 0) timecodeScaleNs = scale
                }
                stream.skipTo(childEnd)
            }
        }

        private fun parseTracksElement(tracksEnd: Long) {
            subtitleTracks.clear()
            while (!stream.isEof && stream.position < tracksEnd) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, _) = stream.readVintSize()
                if (size < 0) break
                val entryEnd = stream.position + size
                if (id == 0xAEL) {
                    val meta = parseTrackEntry(entryEnd)
                    if (meta.trackNumber > 0 && meta.isSubtitleTrack) {
                        subtitleTracks.add(meta)
                    }
                }
                stream.skipTo(entryEnd)
            }
        }

        private fun parseTrackEntry(entryEnd: Long): MkvTrackMeta {
            val meta = MkvTrackMeta()
            while (!stream.isEof && stream.position < entryEnd) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, _) = stream.readVintSize()
                if (size < 0) break
                val propEnd = stream.position + size

                when (id) {
                    0xD7L -> meta.trackNumber = stream.readUnsignedInt(size).toInt()
                    0x83L -> meta.trackType = stream.readUnsignedInt(size).toInt()
                    0x86L -> meta.codecId = stream.readAsciiOrUtf8String(size)
                    0x22B59CL -> meta.language = stream.readAsciiOrUtf8String(size)
                    0x22B59DL -> meta.languageBcp47 = stream.readAsciiOrUtf8String(size)
                    0x536EL -> meta.name = stream.readAsciiOrUtf8String(size)
                    0x88L -> meta.isDefault = stream.readUnsignedInt(size) != 0L
                    0x55AAL -> meta.isForced = stream.readUnsignedInt(size) != 0L
                    0x6D80L -> parseContentEncodings(propEnd, meta)
                }
                stream.skipTo(propEnd)
            }
            return meta
        }

        private fun parseContentEncodings(encodingsEnd: Long, meta: MkvTrackMeta) {
            while (!stream.isEof && stream.position < encodingsEnd) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, _) = stream.readVintSize()
                if (size < 0) break
                val childEnd = stream.position + size
                if (id == 0x6240L) {
                    while (!stream.isEof && stream.position < childEnd) {
                        val ceId = stream.readElementId()
                        if (ceId < 0) break
                        val (ceSize, _) = stream.readVintSize()
                        if (ceSize < 0) break
                        val ceEnd = stream.position + ceSize
                        if (ceId == 0x5034L) {
                            meta.isCompressed = true
                            meta.compAlgo = 0
                            while (!stream.isEof && stream.position < ceEnd) {
                                val ccId = stream.readElementId()
                                if (ccId < 0) break
                                val (ccSize, _) = stream.readVintSize()
                                if (ccSize < 0) break
                                val ccEnd = stream.position + ccSize
                                if (ccId == 0x4254L) {
                                    meta.compAlgo = stream.readUnsignedInt(ccSize).toInt()
                                } else if (ccId == 0x4255L) {
                                    meta.compHeaderStripBytes = stream.readBytes(ccSize.toInt())
                                }
                                stream.skipTo(ccEnd)
                            }
                        }
                        stream.skipTo(ceEnd)
                    }
                }
                stream.skipTo(childEnd)
            }
        }

        private fun parseClusterElement(
            clusterEnd: Long,
            clusterUnknownSize: Boolean,
            targetTrack: MkvTrackMeta,
            outCues: MutableList<SubtitleCue>
        ) {
            var clusterTimecode = 0L

            while (!stream.isEof && stream.position < clusterEnd) {
                val id = stream.readElementId()
                if (id < 0) break
                val (size, unknown) = stream.readVintSize()
                if (size < 0) break

                // If Cluster had unknown size and we reached the next top-level Segment element,
                // save it in pendingTopElement so the outer Segment loop processes it without losing a single byte!
                if (clusterUnknownSize && (
                        id == 0x1F43B675L ||
                        id == 0x1C53BB6BL ||
                        id == 0x1254C367L ||
                        id == 0x1043A770L ||
                        id == 0x1941A469L ||
                        id == 0x1654AE6BL ||
                        id == 0x1549A966L
                    )
                ) {
                    pendingTopElement = Triple(id, size, unknown)
                    return
                }

                val elemEnd = if (unknown) clusterEnd else (stream.position + size)

                when (id) {
                    0xE7L -> { // Cluster Timecode
                        clusterTimecode = stream.readUnsignedInt(size)
                        stream.skipTo(elemEnd)
                    }
                    0xA3L -> { // SimpleBlock
                        val (blockTrackNum, _) = stream.readVintSize()
                        if (blockTrackNum.toInt() != targetTrack.trackNumber) {
                            stream.skipTo(elemEnd)
                        } else {
                            val relTime = stream.readSignedShort().toLong()
                            val flags = stream.readByte()
                            val payloadLen = (elemEnd - stream.position).toInt()
                            if (payloadLen in 1..65536 && (flags and 0x06) == 0) {
                                val rawBytes = stream.readBytes(payloadLen)
                                val decodedBytes = decodePayloadBytes(rawBytes, targetTrack)
                                val rawStr = String(decodedBytes, Charsets.UTF_8)
                                val clean = cleanSubtitleText(rawStr, targetTrack.isAssOrSsa)
                                if (clean.isNotBlank()) {
                                    val startMs = maxOf(0L, ((clusterTimecode + relTime) * timecodeScaleNs) / 1_000_000L)
                                    val durMs = estimateCueDuration(clean)
                                    outCues.add(
                                        SubtitleCue(
                                            index = outCues.size + 1,
                                            startMs = startMs,
                                            endMs = startMs + durMs,
                                            text = clean
                                        )
                                    )
                                }
                            }
                            stream.skipTo(elemEnd)
                        }
                    }
                    0xA0L -> { // BlockGroup
                        var blockRelTime = 0L
                        var blockPayload: ByteArray? = null
                        var blockDurationTicks = -1L
                        var isMatchingTrack = false

                        while (!stream.isEof && stream.position < elemEnd) {
                            val bgId = stream.readElementId()
                            if (bgId < 0) break
                            val (bgSize, _) = stream.readVintSize()
                            if (bgSize < 0) break
                            val bgChildEnd = stream.position + bgSize

                            when (bgId) {
                                0xA1L -> { // Block
                                    val (bTrackNum, _) = stream.readVintSize()
                                    if (bTrackNum.toInt() != targetTrack.trackNumber) {
                                        stream.skipTo(elemEnd)
                                        break
                                    } else {
                                        isMatchingTrack = true
                                        blockRelTime = stream.readSignedShort().toLong()
                                        stream.readByte() // flags
                                        val pLen = (bgChildEnd - stream.position).toInt()
                                        if (pLen in 1..65536) {
                                            blockPayload = stream.readBytes(pLen)
                                        }
                                        stream.skipTo(bgChildEnd)
                                    }
                                }
                                0x9BL -> { // BlockDuration
                                    blockDurationTicks = stream.readUnsignedInt(bgSize)
                                    stream.skipTo(bgChildEnd)
                                }
                                else -> {
                                    stream.skipTo(bgChildEnd)
                                }
                            }
                        }

                        if (isMatchingTrack && blockPayload != null) {
                            val decodedBytes = decodePayloadBytes(blockPayload!!, targetTrack)
                            val rawStr = String(decodedBytes, Charsets.UTF_8)
                            val clean = cleanSubtitleText(rawStr, targetTrack.isAssOrSsa)
                            if (clean.isNotBlank()) {
                                val startMs = maxOf(0L, ((clusterTimecode + blockRelTime) * timecodeScaleNs) / 1_000_000L)
                                val durationMs = if (blockDurationTicks > 0L) {
                                    ((blockDurationTicks * timecodeScaleNs) / 1_000_000L).coerceIn(300L, 15000L)
                                } else {
                                    estimateCueDuration(clean)
                                }
                                outCues.add(
                                    SubtitleCue(
                                        index = outCues.size + 1,
                                        startMs = startMs,
                                        endMs = startMs + durationMs,
                                        text = clean
                                    )
                                )
                            }
                        }
                        stream.skipTo(elemEnd)
                    }
                    else -> {
                        if (!unknown) {
                            stream.skipTo(elemEnd)
                        }
                    }
                }
            }
        }

        private fun decodePayloadBytes(raw: ByteArray, track: MkvTrackMeta): ByteArray {
            if (!track.isCompressed) return raw
            return try {
                when (track.compAlgo) {
                    0 -> {
                        val inflater = Inflater()
                        inflater.setInput(raw)
                        val bos = ByteArrayOutputStream(raw.size * 2)
                        val buf = ByteArray(1024)
                        while (!inflater.finished() && !inflater.needsInput()) {
                            val count = inflater.inflate(buf)
                            if (count <= 0) break
                            bos.write(buf, 0, count)
                        }
                        inflater.end()
                        bos.toByteArray()
                    }
                    3 -> {
                        val prefix = track.compHeaderStripBytes
                        if (prefix != null && prefix.isNotEmpty()) {
                            prefix + raw
                        } else raw
                    }
                    else -> raw
                }
            } catch (_: Exception) {
                raw
            }
        }
    }
}
