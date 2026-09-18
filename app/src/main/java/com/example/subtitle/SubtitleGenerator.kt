package com.example.subtitle

import com.example.database.TranscriptSegmentEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SubtitleGenerator {

    /**
     * Generates a valid SubRip (.srt) subtitle file using original ASR timestamps
     * and Unicode Bengali text.
     */
    fun generateSrt(
        segments: List<TranscriptSegmentEntity>,
        outputSrtFile: File
    ): File {
        val sb = StringBuilder()
        var index = 1

        for (seg in segments) {
            val text = seg.translatedText ?: seg.sourceText
            if (text.isBlank()) continue

            val startTimeStr = formatSrtTimestamp(seg.startMs)
            val endTimeStr = formatSrtTimestamp(seg.endMs)

            sb.append(index).append("\n")
            sb.append(startTimeStr).append(" --> ").append(endTimeStr).append("\n")
            sb.append(text.trim()).append("\n\n")

            index++
        }

        outputSrtFile.writeText(sb.toString(), Charsets.UTF_8)
        return outputSrtFile
    }

    /**
     * Formats milliseconds into standard SRT format: 00:00:03,200
     */
    fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * Generates structured transcript.json containing metadata, source, translation,
     * start and end times, and confidence.
     */
    fun generateTranscriptJson(
        sourceLanguage: String,
        targetLanguage: String,
        segments: List<TranscriptSegmentEntity>,
        outputJsonFile: File
    ): File {
        val root = JSONObject()
        root.put("sourceLanguage", sourceLanguage)
        root.put("targetLanguage", targetLanguage)
        root.put("generatedAt", System.currentTimeMillis())
        root.put("segmentCount", segments.size)

        val jsonArray = JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("index", seg.index)
            obj.put("startTimeMs", seg.startMs)
            obj.put("endTimeMs", seg.endMs)
            obj.put("startTimeFormatted", formatSrtTimestamp(seg.startMs))
            obj.put("endTimeFormatted", formatSrtTimestamp(seg.endMs))
            obj.put("sourceText", seg.sourceText)
            obj.put("translatedText", seg.translatedText ?: "")
            obj.put("confidence", seg.confidence ?: 1.0)
            jsonArray.put(obj)
        }

        root.put("segments", jsonArray)
        outputJsonFile.writeText(root.toString(2), Charsets.UTF_8)
        return outputJsonFile
    }
}
