package com.example.tts

/**
 * Phonemizer & Tokenizer for Bhashini Indic FastSpeech2-HS models.
 * Implements IIT Madras Bengali phonetic rules and character-to-token mappings.
 */
object BhashiniTokenizer {

    private val DIGIT_WORDS = mapOf(
        '০' to "শূন্য", '১' to "এক", '২' to "দুই", '৩' to "তিন", '৪' to "চার",
        '৫' to "পাঁচ", '৬' to "ছয়", '৭' to "সাত", '৮' to "আট", '৯' to "নয়",
        '0' to "শূন্য", '1' to "এক", '2' to "দুই", '3' to "তিন", '4' to "চার",
        '5' to "পাঁচ", '6' to "ছয়", '7' to "সাত", '8' to "আট", '9' to "নয়"
    )

    // Bengali character to Bhashini IIT Madras token string mapping
    private val CHAR_MAP = mapOf(
        // Vowels
        'অ' to "a",
        'আ' to "A",
        'ই' to "i",
        'ঈ' to "I",
        'উ' to "u",
        'ঊ' to "U",
        'ঋ' to "R",
        'এ' to "E",
        'ঐ' to "ऐ",
        'ও' to "o",
        'ঔ' to "औ",

        // Matras
        'া' to "A",
        'ি' to "i",
        'ী' to "I",
        'ু' to "u",
        'ূ' to "U",
        'ৃ' to "R",
        'ে' to "E",
        'ৈ' to "ऐ",
        'ো' to "o",
        'ৌ' to "औ",

        // Consonants
        'ক' to "k",
        'খ' to "ख",
        'গ' to "g",
        'ঘ' to "घ",
        'ঙ' to "ङ",
        'চ' to "c",
        'ছ' to "C",
        'জ' to "j",
        'ঝ' to "J",
        'ঞ' to "ञ",
        'ট' to "ट",
        'ঠ' to "ठ",
        'ড' to "ड",
        'ঢ' to "ढ",
        'ণ' to "ण",
        'ত' to "t",
        'থ' to "थ",
        'দ' to "d",
        'ধ' to "D",
        'ন' to "n",
        'প' to "p",
        'ফ' to "P",
        'ব' to "b",
        'ভ' to "B",
        'ম' to "m",
        'য' to "j",
        'র' to "r",
        'ল' to "l",
        'শ' to "श",
        'ষ' to "ष",
        'স' to "s",
        'হ' to "h",
        'ড়' to "ड",
        'ঢ়' to "ढ",
        'য়' to "y",
        'ৎ' to "t",

        // Modifiers
        'ং' to "q",
        'ঃ' to "h",
        'ঁ' to "M"
    )

    private val CONSONANTS = setOf(
        'ক', 'খ', 'গ', 'ঘ', 'ঙ',
        'চ', 'ছ', 'জ', 'ঝ', 'ঞ',
        'ট', 'ঠ', 'ড', 'ঢ', 'ণ',
        'ত', 'থ', 'দ', 'ধ', 'ন',
        'প', 'ফ', 'ব', 'ভ', 'ম',
        'য', 'র', 'ল', 'শ', 'ষ',
        'স', 'হ', 'ড়', 'ঢ়', 'য়', 'ৎ'
    )

    private val MATRAS = setOf(
        'া', 'ি', 'ী', 'ু', 'ূ', 'ৃ', 'ে', 'ৈ', 'ো', 'ৌ'
    )

    private const val HASANT = '্'

    /**
     * Converts a Bengali text string into a sequence of Bhashini token IDs.
     */
    fun textToTokenIds(text: String): LongArray {
        val normalized = normalizeBengali(text)
        if (normalized.isBlank()) return longArrayOf(BhashiniConstants.TOKEN_START_ID, BhashiniConstants.TOKEN_END_ID)

        val tokens = mutableListOf<Long>()
        tokens.add(BhashiniConstants.TOKEN_START_ID) // "$" prefix

        val words = normalized.split("\\s+".toRegex())
        for (wIndex in words.indices) {
            val word = words[wIndex]
            if (word.isEmpty()) continue

            var i = 0
            val len = word.length
            while (i < len) {
                val c = word[i]

                when {
                    c == ',' -> {
                        if (tokens.lastOrNull() != BhashiniConstants.TOKEN_COMMA_ID) {
                            tokens.add(BhashiniConstants.TOKEN_COMMA_ID)
                        }
                        i++
                    }
                    c == '.' || c == '।' || c == '!' || c == '?' -> {
                        if (tokens.lastOrNull() != BhashiniConstants.TOKEN_END_ID) {
                            tokens.add(BhashiniConstants.TOKEN_END_ID)
                        }
                        i++
                    }
                    CONSONANTS.contains(c) -> {
                        val tokenStr = CHAR_MAP[c] ?: "k"
                        val id = BhashiniConstants.TOKEN_TO_ID[tokenStr] ?: BhashiniConstants.TOKEN_UNK_ID
                        tokens.add(id)

                        val nextChar = if (i + 1 < len) word[i + 1] else null
                        if (nextChar == HASANT) {
                            // Virama suppresses inherent vowel
                            i += 2
                        } else if (nextChar != null && MATRAS.contains(nextChar)) {
                            // Followed by matra
                            val matraStr = CHAR_MAP[nextChar] ?: "a"
                            val matraId = BhashiniConstants.TOKEN_TO_ID[matraStr] ?: BhashiniConstants.TOKEN_UNK_ID
                            tokens.add(matraId)
                            i += 2
                        } else {
                            // Inherent vowel (Bangla: 'a' or 'o')
                            // Natural Bengali phonology: word-final or intermediate
                            val inherentVowel = if (i == len - 1 && len > 2) {
                                // Final consonant often drops inherent vowel in modern Bengali (অ-কার লোপ)
                                null
                            } else {
                                BhashiniConstants.TOKEN_TO_ID["a"] ?: 3L
                            }
                            if (inherentVowel != null) {
                                tokens.add(inherentVowel)
                            }
                            i++
                        }
                    }
                    else -> {
                        val tokenStr = CHAR_MAP[c]
                        if (tokenStr != null) {
                            val id = BhashiniConstants.TOKEN_TO_ID[tokenStr] ?: BhashiniConstants.TOKEN_UNK_ID
                            tokens.add(id)
                        } else if (c in 'a'..'z' || c in 'A'..'Z') {
                            val id = BhashiniConstants.TOKEN_TO_ID[c.toString()] ?: BhashiniConstants.TOKEN_UNK_ID
                            tokens.add(id)
                        }
                        i++
                    }
                }
            }

            // Word boundary separator (slight pause token between words if needed)
            if (wIndex < words.size - 1 && tokens.lastOrNull() != BhashiniConstants.TOKEN_COMMA_ID) {
                // Keep token flow natural
            }
        }

        if (tokens.lastOrNull() != BhashiniConstants.TOKEN_END_ID) {
            tokens.add(BhashiniConstants.TOKEN_END_ID) // "." suffix
        }
        return tokens.toLongArray()
    }

    private fun normalizeBengali(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            val digitWord = DIGIT_WORDS[c]
            if (digitWord != null) {
                sb.append(" ").append(digitWord).append(" ")
            } else if (c == '।' || c == '?' || c == '!') {
                sb.append(" . ")
            } else if (c == ',') {
                sb.append(" , ")
            } else if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(" ")
            } else {
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }
}
