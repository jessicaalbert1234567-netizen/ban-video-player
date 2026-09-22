package com.example.tts

import java.text.Normalizer

/**
 * Tokenizer for Meta Massively Multilingual Speech (MMS) Bengali VITS TTS model
 * (naklitechie/mms-tts-bn-ONNX).
 *
 * Implements Hugging Face VitsTokenizer rules:
 * - Character vocabulary mapping (74 tokens)
 * - Bengali digits to ASCII digits
 * - Nukta composite letter decomposition ('ড়', 'ঢ়', 'য়')
 * - Inter-token blank insertion (0L)
 * - Punctuation pause handling
 */
object MmsTokenizer {

    const val PAD_TOKEN_ID: Long = 0L // '6' -> 0 in vocab
    const val SPACE_TOKEN_ID: Long = 57L // ' ' -> 57 in vocab

    // Character to Token ID map matching vocab.json from naklitechie/mms-tts-bn-ONNX
    private val VOCAB: Map<Char, Long> = mapOf(
        '6' to 0L, 'এ' to 1L, 'ৃ' to 2L, '5' to 3L, 'ু' to 4L, 'ঞ' to 5L, 'প' to 6L, 'ঘ' to 7L,
        '8' to 8L, 'ক' to 9L, 'ল' to 10L, '9' to 11L, 'ি' to 12L, 'ঃ' to 13L, 'থ' to 14L, '1' to 15L,
        'ভ' to 16L, 'দ' to 17L, 'ী' to 18L, '_' to 19L, 'স' to 20L, 'ড' to 21L, 'ঢ' to 22L, 'ছ' to 23L,
        'ই' to 24L, 'ং' to 25L, 'ঁ' to 26L, 'আ' to 27L, 'ব' to 28L, 'হ' to 29L, '\'' to 30L, '2' to 31L,
        '—' to 32L, 'ঈ' to 33L, 'ঊ' to 34L, 'ঐ' to 35L, '্' to 36L, 'ে' to 37L, 'য' to 38L, 'খ' to 39L,
        'ূ' to 40L, 'ম' to 41L, 'ত' to 42L, 'ঝ' to 43L, 'ৌ' to 44L, '4' to 45L, 'ষ' to 46L, 'জ' to 47L,
        'শ' to 48L, 'ঔ' to 49L, 'অ' to 50L, 'ণ' to 51L, 'ৎ' to 52L, '7' to 53L, 'ও' to 54L, 'ট' to 55L,
        'ৈ' to 56L, ' ' to 57L, 'ধ' to 58L, 'া' to 59L, 'ন' to 60L, '0' to 61L, '3' to 62L, 'ঋ' to 63L,
        'ফ' to 64L, 'চ' to 65L, 'উ' to 66L, '-' to 67L, '়' to 68L, 'র' to 69L, 'ঙ' to 70L, 'গ' to 71L,
        'ো' to 72L, 'ঠ' to 73L
    )

    /**
     * Normalizes Bengali text for MMS VITS tokenization.
     */
    fun normalize(text: String): String {
        return text
            .replace('০', '0').replace('১', '1').replace('২', '2').replace('৩', '3')
            .replace('৪', '4').replace('৫', '5').replace('৬', '6').replace('৭', '7')
            .replace('৮', '8').replace('৯', '9')
            // Decompose composite nukta letters into base consonant + nukta
            .replace("ড়", "ড\u09BC")
            .replace("ঢ়", "ঢ\u09BC")
            .replace("য়", "য\u09BC")
            // Map sentence terminators and punctuation to speech pauses
            .replace("।", " ")
            .replace(".", " ")
            .replace(",", " ")
            .replace(";", " ")
            .replace(":", " ")
            .replace("?", " ")
            .replace("!", " ")
            .replace("—", " ")
            .replace("–", " ")
            .replace("\"", " ")
            .replace("“", " ")
            .replace("”", " ")
            .replace("(", " ")
            .replace(")", " ")
            .replace("[", " ")
            .replace("]", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Tokenizes Bengali text into input IDs for MMS VITS ONNX model.
     * Inserts blank token (0L) before each character and at the end (add_blank=true).
     */
    fun tokenize(text: String): LongArray {
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return longArrayOf(PAD_TOKEN_ID, SPACE_TOKEN_ID, PAD_TOKEN_ID)
        }

        val tokens = mutableListOf<Long>()
        tokens.add(PAD_TOKEN_ID) // leading blank

        var lastWasSpace = false
        for (c in normalized) {
            val tokenId = VOCAB[c]
            if (tokenId != null) {
                if (tokenId == SPACE_TOKEN_ID) {
                    if (!lastWasSpace) {
                        tokens.add(SPACE_TOKEN_ID)
                        tokens.add(PAD_TOKEN_ID)
                        lastWasSpace = true
                    }
                } else {
                    tokens.add(tokenId)
                    tokens.add(PAD_TOKEN_ID) // blank after every token
                    lastWasSpace = false
                }
            }
        }

        if (tokens.size <= 1) {
            // Fallback for completely unrecognizable input
            tokens.add(SPACE_TOKEN_ID)
            tokens.add(PAD_TOKEN_ID)
        }

        return tokens.toLongArray()
    }
}
