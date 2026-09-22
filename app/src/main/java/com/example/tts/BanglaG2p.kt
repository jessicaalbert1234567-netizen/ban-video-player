package com.example.tts

import android.util.Log

/**
 * Bengali Grapheme-to-Phoneme (G2P) converter for Piper VITS TTS.
 * Maps Bengali Unicode text into International Phonetic Alphabet (IPA) tokens
 * expected by Piper's Bengali voice models (e.g. bn_BD-google-medium).
 */
object BanglaG2p {

    private const val TAG = "BanglaG2p"

    private val CONSONANTS = mapOf(
        'ক' to "k",
        'খ' to "kʰ",
        'গ' to "ɡ",
        'ঘ' to "ɡʰ",
        'ঙ' to "ŋ",
        'চ' to "c",
        'ছ' to "cʰ",
        'জ' to "ɟ",
        'ঝ' to "ɟʰ",
        'ঞ' to "n",
        'ট' to "ʈ",
        'ঠ' to "ʈʰ",
        'ড' to "ɖ",
        'ঢ' to "ɖʰ",
        'ণ' to "n",
        'ত' to "t",
        'থ' to "tʰ",
        'দ' to "d",
        'ধ' to "dʰ",
        'ন' to "n",
        'প' to "p",
        'ফ' to "f",
        'ব' to "b",
        'ভ' to "v",
        'ম' to "m",
        'য' to "ɟ",
        'র' to "r",
        'ল' to "l",
        'শ' to "ʃ",
        'ষ' to "ʃ",
        'স' to "s",
        'হ' to "h",
        'ড়' to "ɽ",
        'ঢ়' to "ɽʰ",
        'য়' to "e",
        'ৎ' to "t"
    )

    private val VOWELS = mapOf(
        'অ' to "ɔ",
        'আ' to "a",
        'ই' to "i",
        'ঈ' to "i",
        'উ' to "u",
        'ঊ' to "u",
        'ঋ' to "ri",
        'এ' to "e",
        'ঐ' to "ɔɪ",
        'ও' to "o",
        'ঔ' to "oʊ"
    )

    private val MATRAS = mapOf(
        'া' to "a",
        'ি' to "i",
        'ী' to "i",
        'ু' to "u",
        'ূ' to "u",
        'ৃ' to "ri",
        'ে' to "e",
        'ৈ' to "ɔɪ",
        'ো' to "o",
        'ৌ' to "oʊ"
    )

    private val DIGITS = mapOf(
        '০' to "0", '১' to "1", '২' to "2", '৩' to "3", '৪' to "4",
        '৫' to "5", '৬' to "6", '৭' to "7", '৮' to "8", '৯' to "9"
    )

    /**
     * Converts raw Bengali Unicode text into an IPA phoneme string.
     */
    fun textToIpa(text: String): String {
        val sb = StringBuilder()
        val chars = text.toCharArray()
        val n = chars.size
        var i = 0

        while (i < n) {
            val c = chars[i]

            // Space
            if (c == ' ' || c == '\t' || c == '\n') {
                sb.append(' ')
                i++
                continue
            }

            // Bengali sentence terminators
            if (c == '।' || c == '॥') {
                sb.append('.')
                i++
                continue
            }

            // Consonants
            if (CONSONANTS.containsKey(c)) {
                // Conjunct: ক্ষ (ক + ্ + ষ) -> kʰ
                if (c == 'ক' && i + 2 < n && chars[i + 1] == '্' && chars[i + 2] == 'ষ') {
                    sb.append("kʰ")
                    i += 3
                    if (i < n && MATRAS.containsKey(chars[i])) {
                        sb.append(MATRAS[chars[i]])
                        i++
                    } else if (i < n && CONSONANTS.containsKey(chars[i])) {
                        sb.append("ɔ")
                    }
                    continue
                }

                // Conjunct: জ্ঞ (জ + ্ + ঞ) -> ɡæ
                if (c == 'জ' && i + 2 < n && chars[i + 1] == '্' && chars[i + 2] == 'ঞ') {
                    sb.append("ɡæ")
                    i += 3
                    if (i < n && MATRAS.containsKey(chars[i])) {
                        sb.append(MATRAS[chars[i]])
                        i++
                    }
                    continue
                }

                sb.append(CONSONANTS[c])

                // Examine next character to decide on inherent vowel or suppression
                if (i + 1 < n) {
                    val nxt = chars[i + 1]
                    if (nxt == '্') {
                        // Hasanta / Virama suppresses the inherent vowel
                        // Check for ya-phala (্ + য) -> æ
                        if (i + 2 < n && chars[i + 2] == 'য') {
                            sb.append("æ")
                            i += 3
                            continue
                        }
                        i += 2
                        continue
                    } else if (MATRAS.containsKey(nxt)) {
                        // Attached vowel sign replaces inherent vowel
                        sb.append(MATRAS[nxt])
                        i += 2
                        continue
                    } else if (VOWELS.containsKey(nxt) || nxt == ' ') {
                        i++
                        continue
                    } else if (CONSONANTS.containsKey(nxt)) {
                        // Inherent vowel 'ɔ' between consonants
                        sb.append("ɔ")
                        i++
                        continue
                    }
                }
                i++
                continue
            }

            // Independent Vowels
            if (VOWELS.containsKey(c)) {
                sb.append(VOWELS[c])
                i++
                continue
            }

            // Stray matra (without preceding consonant)
            if (MATRAS.containsKey(c)) {
                sb.append(MATRAS[c])
                i++
                continue
            }

            // Special Bengali diacritics
            when (c) {
                'ং' -> sb.append("ŋ")
                'ঃ' -> sb.append("h")
                'ঁ' -> sb.append("̃")
                in DIGITS -> sb.append(DIGITS[c])
                in "!?,.-;:()\"\'" -> sb.append(c)
                in 'a'..'z' -> sb.append(c)
                in 'A'..'Z' -> sb.append(c.lowercaseChar())
                in '0'..'9' -> sb.append(c)
                else -> {
                    // Ignore unrecognized non-printable markers
                }
            }
            i++
        }

        return sb.toString()
    }

    /**
     * Converts IPA phoneme string into Piper token IDs using the model's phoneme_id_map.
     * Interleaves pad tokens (0 / '_') between phonemes as required by Piper VITS.
     */
    fun ipaToTokenIds(ipaText: String, phonemeIdMap: Map<String, Long>): LongArray {
        val ids = mutableListOf<Long>()
        val startTokenId = phonemeIdMap["^"] ?: 1L
        val endTokenId = phonemeIdMap["$"] ?: 2L
        val padTokenId = phonemeIdMap["_"] ?: 0L
        val spaceTokenId = phonemeIdMap[" "] ?: 3L

        ids.add(startTokenId)

        var j = 0
        val len = ipaText.length
        while (j < len) {
            // Check for 2-character phoneme tokens first (e.g., 'ɔɪ', 'oʊ', 'aɪ', 'aʊ', 'eɪ')
            if (j + 1 < len) {
                val pair = ipaText.substring(j, j + 2)
                val pairId = phonemeIdMap[pair]
                if (pairId != null) {
                    ids.add(pairId)
                    ids.add(padTokenId)
                    j += 2
                    continue
                }
            }

            val single = ipaText[j].toString()
            val singleId = phonemeIdMap[single]
            if (singleId != null) {
                ids.add(singleId)
                ids.add(padTokenId)
            } else if (ipaText[j] == ' ') {
                ids.add(spaceTokenId)
                ids.add(padTokenId)
            } else {
                // For combining characters like aspiration 'ʰ' or tilde '̃'
                val code = ipaText[j].toString()
                val fallbackId = phonemeIdMap[code]
                if (fallbackId != null) {
                    ids.add(fallbackId)
                    ids.add(padTokenId)
                }
            }
            j++
        }

        ids.add(endTokenId)

        Log.d(TAG, "G2P converted text (len ${ipaText.length}) to ${ids.size} Piper token IDs")
        return ids.toLongArray()
    }
}
