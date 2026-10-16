package com.example.translation

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SubtitleItem(
    val index: Int,
    val startTimeStr: String,
    val endTimeStr: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    var translatedText: String = ""
)

object GtxSubtitleTranslator {

    private const val TAG = "GtxSubtitleTranslator"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getFileTranslatoOutputDir(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadDir, "FileTranslato")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun parseSrtFile(srtFile: File): List<SubtitleItem> = withContext(Dispatchers.IO) {
        val content = srtFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val blocks = content.trim().split(Regex("\n\n+"))
        val items = mutableListOf<SubtitleItem>()
        val timePattern = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

        var indexCounter = 1
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 2) {
                val timeLineIndex = if (lines[0].matches(Regex("^\\d+$"))) 1 else 0
                if (timeLineIndex < lines.size) {
                    val matcher = timePattern.matcher(lines[timeLineIndex])
                    if (matcher.find()) {
                        val startTimeStr = "${matcher.group(1)}:${matcher.group(2)}:${matcher.group(3)},${matcher.group(4)}"
                        val endTimeStr = "${matcher.group(5)}:${matcher.group(6)}:${matcher.group(7)},${matcher.group(8)}"

                        val startMs = timeToMillis(
                            matcher.group(1)!!.toLong(), matcher.group(2)!!.toLong(),
                            matcher.group(3)!!.toLong(), matcher.group(4)!!.toLong()
                        )
                        val endMs = timeToMillis(
                            matcher.group(5)!!.toLong(), matcher.group(6)!!.toLong(),
                            matcher.group(7)!!.toLong(), matcher.group(8)!!.toLong()
                        )

                        val subtitleText = lines.subList(timeLineIndex + 1, lines.size).joinToString("\n")
                        if (subtitleText.isNotBlank()) {
                            items.add(
                                SubtitleItem(
                                    index = indexCounter++,
                                    startTimeStr = startTimeStr,
                                    endTimeStr = endTimeStr,
                                    startTimeMs = startMs,
                                    endTimeMs = endMs,
                                    originalText = subtitleText
                                )
                            )
                        }
                    }
                }
            }
        }
        return@withContext items
    }

    suspend fun translateSubtitlesToBangla(
        items: List<SubtitleItem>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val batchSize = 10
        val batches = items.chunked(batchSize)

        for ((batchIndex, batch) in batches.withIndex()) {
            val combinedText = batch.joinToString("\n") {
                it.originalText.replace("\n", " ").replace(Regex("<[^>]*>"), "").trim()
            }

            val translatedBlock = translateViaGtxApi(combinedText, "en", "bn")
            val translatedLines = translatedBlock.split("\n")

            for (i in batch.indices) {
                batch[i].translatedText = translatedLines.getOrNull(i)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: batch[i].originalText
            }

            val current = minOf((batchIndex + 1) * batchSize, items.size)
            onProgress(current, items.size)
            delay(150)
        }
    }

    fun translateViaGtxApi(text: String, sourceLang: String = "en", targetLang: String = "bn"): String {
        return try {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                    "&sl=$sourceLang&tl=$targetLang&hl=en&dt=t&dj=1&source=icon&tk=467103.467103&q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return text
                val bodyStr = response.body?.string() ?: return text
                val jsonObject = JSONObject(bodyStr)
                val sentencesArray = jsonObject.optJSONArray("sentences") ?: return text
                val sb = StringBuilder()

                for (i in 0 until sentencesArray.length()) {
                    val sentenceObj = sentencesArray.optJSONObject(i)
                    if (sentenceObj != null && sentenceObj.has("trans")) {
                        sb.append(sentenceObj.getString("trans"))
                    }
                }
                val result = sb.toString()
                if (result.isNotBlank()) result else text
            }
        } catch (e: Exception) {
            Log.w(TAG, "GTX translation error: ${e.message}")
            text
        }
    }

    suspend fun saveBanglaSrtFile(items: List<SubtitleItem>, outFile: File) = withContext(Dispatchers.IO) {
        outFile.parentFile?.mkdirs()
        val sb = StringBuilder()
        for (item in items) {
            sb.append(item.index).append("\n")
            sb.append("${item.startTimeStr} --> ${item.endTimeStr}\n")
            sb.append(item.translatedText).append("\n\n")
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    fun timeToMillis(h: Long, m: Long, s: Long, ms: Long): Long =
        (h * 3600000L) + (m * 60000L) + (s * 1000L) + ms
}
