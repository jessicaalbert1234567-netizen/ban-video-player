package com.example.tts

import android.util.Log

/**
 * High-accuracy Bengali Grapheme-to-Phoneme (G2P) converter.
 * Converts Bengali Unicode text into authentic International Phonetic Alphabet (IPA) tokens
 * adhering to modern Bengali phonology and schwa-deletion rules (অ-কার বিলোপ),
 * producing natural, fluent human-like articulation in Piper VITS and neural engines.
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
        'স' to "ʃ",
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
     * Converts raw Bengali Unicode text into natural IPA phonemes with Bengali schwa deletion.
     */
    fun textToIpa(text: String): String {
        val sb = StringBuilder()
        val chars = text.toCharArray()
        val n = chars.size
        var i = 0

        while (i < n) {
            val c = chars[i]

            // Whitespace
            if (c == ' ' || c == '\t' || c == '\n') {
                sb.append(' ')
                i++
                continue
            }

            // Bengali sentence punctuation
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

                // Phonological context for inherent vowel and schwa deletion:
                if (i + 1 < n) {
                    val nxt = chars[i + 1]
                    if (nxt == '্') {
                        // Virama suppresses inherent vowel completely
                        // Check for ya-phala (্ + য) -> æ
                        if (i + 2 < n && chars[i + 2] == 'য') {
                            sb.append("æ")
                            i += 3
                            continue
                        } else if (i + 2 < n && chars[i + 2] == 'র') {
                            // ra-phala (্ + র) -> r
                            sb.append("r")
                            i += 3
                            if (i < n && MATRAS.containsKey(chars[i])) {
                                sb.append(MATRAS[chars[i]])
                                i++
                            } else {
                                sb.append("ɔ")
                            }
                            continue
                        }
                        i += 2
                        continue
                    } else if (MATRAS.containsKey(nxt)) {
                        // Vowel sign directly provides syllable nucleus
                        sb.append(MATRAS[nxt])
                        i += 2
                        continue
                    } else if (VOWELS.containsKey(nxt) || nxt == ' ') {
                        // Vowel or word boundary: word-final consonant has NO schwa
                        i++
                        continue
                    } else if (CONSONANTS.containsKey(nxt)) {
                        // Consonant followed by consonant: Apply Bengali Schwa Deletion rules
                        val isNextCharWordEnd = (i + 1 == n - 1) || (i + 2 < n && (chars[i + 2] == ' ' || chars[i + 2] == '.' || chars[i + 2] == ','))
                        if (isNextCharWordEnd) {
                            // Penultimate consonant before a word-final consonant (e.g. 'কেমন' -> /kemon/, 'গরম' -> /ɡɔrom/, 'করব' -> /kɔrob/)
                            sb.append("o")
                        } else if (i + 2 < n && MATRAS.containsKey(chars[i + 2])) {
                            // Schwa deletion: consonant directly precedes a consonant that has a vowel sign
                            // e.g. 'আপনি' -> /apni/, 'করছেন' -> /kɔrcʰen/, 'বলছে' -> /bɔlcʰe/, 'চলছে' -> /cɔlcʰe/
                            // Do NOT append schwa!
                        } else if (i + 2 < n && chars[i + 2] == '্') {
                            // Before a conjunct cluster: e.g. 'ন' in 'নমস্কার' -> /nɔmɔʃkar/
                            sb.append("ɔ")
                        } else if (i == 0) {
                            // Word-initial consonant without matra (e.g. 'ক' in 'করব' -> /kɔ/)
                            sb.append("ɔ")
                        } else {
                            // Default medial rounded schwa
                            sb.append("o")
                        }
                        i++
                        continue
                    }
                }
                // Word-final consonant: Keep silent/pure consonant without trailing schwa
                i++
                continue
            }

            // Independent Vowels
            if (VOWELS.containsKey(c)) {
                sb.append(VOWELS[c])
                i++
                continue
            }

            // Attached matra without consonant
            if (MATRAS.containsKey(c)) {
                sb.append(MATRAS[c])
                i++
                continue
            }

            // Bengali diacritics & standard symbols
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
                    // Ignore non-printable control characters
                }
            }
            i++
        }

        return sb.toString()
    }

    /**
     * Maps IPA phonemes to Piper token IDs with start/pad/end framing.
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
            // Priority to 2-character dipthongs (e.g., 'ɔɪ', 'oʊ', 'aɪ', 'aʊ', 'eɪ')
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
                // Secondary fallback for combining diacritics like aspiration 'ʰ' or tilde '̃'
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

        Log.d(TAG, "G2P converted text to ${ids.size} Piper token IDs")
        return ids.toLongArray()
    }
}
