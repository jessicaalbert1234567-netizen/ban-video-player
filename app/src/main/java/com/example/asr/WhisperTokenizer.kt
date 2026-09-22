package com.example.asr

import android.util.Base64
import android.util.Log
import java.io.File

/**
 * High-performance tokenizer for OpenAI Whisper English models (tiny.en).
 * Reads the Sherpa-ONNX formatted tokens.txt (base64_encoded_token token_id)
 * and decodes token sequences into clean English text.
 */
class WhisperTokenizer {

    companion object {
        private const val TAG = "WhisperTokenizer"
        const val SOT = 50257L
        const val EOT = 50256L
        const val TRANSCRIBE = 50358L
        const val NO_TIMESTAMPS = 50362L
        const val VOCAB_SIZE = 51864
    }

    private val idToToken = arrayOfNulls<String>(VOCAB_SIZE + 1)
    private var isLoaded = false

    fun load(tokensFile: File): Boolean {
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            Log.e(TAG, "Tokens file does not exist or is empty: ${tokensFile.absolutePath}")
            return false
        }

        try {
            var count = 0
            tokensFile.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty()) {
                    val spaceIdx = line.indexOf(' ')
                    if (spaceIdx > 0) {
                        val b64 = line.substring(0, spaceIdx).trim()
                        val idStr = line.substring(spaceIdx + 1).trim()
                        val id = idStr.toIntOrNull()
                        if (id != null && id in 0..VOCAB_SIZE) {
                            val decoded = try {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                String(bytes, Charsets.UTF_8)
                            } catch (_: Exception) {
                                try {
                                    val bytes = java.util.Base64.getDecoder().decode(b64)
                                    String(bytes, Charsets.UTF_8)
                                } catch (_: Exception) {
                                    ""
                                }
                            }
                            idToToken[id] = decoded
                            count++
                        }
                    }
                }
            }
            isLoaded = count > 1000
            Log.d(TAG, "Loaded $count tokens from ${tokensFile.name}")
            return isLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokens: ${e.message}", e)
            return false
        }
    }

    /**
     * Decodes a list of token IDs into clean, normalized English text.
     * Special tokens (IDs >= 50256 or < 0) are excluded from the output.
     */
    fun decode(tokens: List<Long>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            val id = token.toInt()
            // Skip special tokens (SOT, EOT, timestamps, etc.)
            if (id < 0 || id >= 50256) {
                continue
            }
            val piece = idToToken.getOrNull(id)
            if (piece != null) {
                sb.append(piece)
            }
        }

        return normalizeDecodedText(sb.toString())
    }

    private fun normalizeDecodedText(text: String): String {
        return text
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
