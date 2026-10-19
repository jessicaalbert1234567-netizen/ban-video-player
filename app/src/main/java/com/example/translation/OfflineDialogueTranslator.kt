package com.example.translation

import android.util.Log
import java.util.Locale

/**
 * High-Quality Offline Movie Dialogue Translation Engine.
 *
 * Serves as the robust offline fallback when cloud translation API (translate.googleapis.com)
 * is unavailable or returns an error, ensuring that subtitle translation and TTS dubbing
 * NEVER fail even with zero network connectivity.
 *
 * Combines an extensive conversational dialogue phrase dictionary with spoken Bengali
 * grammar collocations, vocabulary mapping, and post-processed by BanglaNaturalizer.
 */
object OfflineDialogueTranslator {

    private const val TAG = "OfflineDialogueTranslator"

    // Ordered list of conversational patterns (longest matching phrases first)
    private val DIALOGUE_PHRASES: List<Pair<Regex, String>> = listOf(
        // Greetings & Well-wishes
        Regex("""(?i)\b(good\s+morning)\b""") to "শুভ সকাল",
        Regex("""(?i)\b(good\s+afternoon)\b""") to "শুভ দুপুর",
        Regex("""(?i)\b(good\s+evening)\b""") to "শুভ সন্ধ্যা",
        Regex("""(?i)\b(good\s+night)\b""") to "শুভ রাত্রি",
        Regex("""(?i)\b(how\s+are\s+you\s+doing\??)\b""") to "কেমন আছো?",
        Regex("""(?i)\b(how\s+are\s+you\??)\b""") to "কেমন আছো?",
        Regex("""(?i)\b(how\s+have\s+you\s+been\??)\b""") to "কেমন ছিলে?",
        Regex("""(?i)\b(nice\s+to\s+meet\s+you)\b""") to "তোমার সাথে দেখা হয়ে ভালো লাগলো",
        Regex("""(?i)\b(glad\s+to\s+see\s+you)\b""") to "তোমাকে দেখে খুব ভালো লাগলো",
        Regex("""(?i)\b(welcome\s+back)\b""") to "স্বাগতম",
        Regex("""(?i)\b(see\s+you\s+later)\b""") to "পরে দেখা হবে",
        Regex("""(?i)\b(see\s+you\s+soon)\b""") to "শীঘ্রই দেখা হবে",
        Regex("""(?i)\b(take\s+care)\b""") to "ভালো থেকো",
        Regex("""(?i)\b(take\s+care\s+of\s+yourself)\b""") to "নিজের যত্ন নিও",

        // Reactions & Exclamations
        Regex("""(?i)\b(what\s+the\s+hell\??)\b""") to "কী হচ্ছে এসব?",
        Regex("""(?i)\b(what\s+happened\??)\b""") to "কী হয়েছে?",
        Regex("""(?i)\b(what's\s+happening\??)\b""") to "কী হচ্ছে?",
        Regex("""(?i)\b(what\s+is\s+happening\??)\b""") to "কী হচ্ছে?",
        Regex("""(?i)\b(what's\s+going\s+on\??)\b""") to "কী হচ্ছে এখানে?",
        Regex("""(?i)\b(what\s+are\s+you\s+doing\??)\b""") to "তুমি কী করছো?",
        Regex("""(?i)\b(what\s+are\s+you\s+talking\s+about\??)\b""") to "তুমি কীসের কথা বলছো?",
        Regex("""(?i)\b(what\s+do\s+you\s+mean\??)\b""") to "তুমি কী বোঝাতে চাচ্ছ?",
        Regex("""(?i)\b(what\s+do\s+you\s+want\??)\b""") to "তুমি কী চাও?",
        Regex("""(?i)\b(what\s+are\s+we\s+going\s+to\s+do\??)\b""") to "আমরা এখন কী করব?",
        Regex("""(?i)\b(what\s+should\s+we\s+do\??)\b""") to "আমাদের এখন কী করা উচিত?",
        Regex("""(?i)\b(why\s+are\s+you\s+doing\s+this\??)\b""") to "তুমি এটা কেন করছো?",
        Regex("""(?i)\b(how\s+could\s+you\s+do\s+this\??)\b""") to "তুমি এমনটা কীভাবে করতে পারলে?",
        Regex("""(?i)\b(where\s+are\s+you\s+going\??)\b""") to "তুমি কোথায় যাচ্ছ?",
        Regex("""(?i)\b(where\s+have\s+you\s+been\??)\b""") to "তুমি কোথায় ছিলে?",
        Regex("""(?i)\b(who\s+are\s+you\??)\b""") to "তুমি কে?",
        Regex("""(?i)\b(who\s+is\s+that\??)\b""") to "ওটা কে?",
        Regex("""(?i)\b(is\s+anyone\s+there\??)\b""") to "সেখানে কি কেউ আছেন?",

        // Commands & Urgency
        Regex("""(?i)\b(get\s+out\s+of\s+here!*)\b""") to "এখান থেকে চলে যাও!",
        Regex("""(?i)\b(let's\s+get\s+out\s+of\s+here!*)\b""") to "চলো এখান থেকে পালাই!",
        Regex("""(?i)\b(we\s+have\s+to\s+get\s+out\s+of\s+here)\b""") to "আমাদের এখান থেকে পালাতে হবে",
        Regex("""(?i)\b(we\s+have\s+to\s+go)\b""") to "আমাদের যেতে হবে",
        Regex("""(?i)\b(i\s+have\s+to\s+go)\b""") to "আমাকে যেতে হবে",
        Regex("""(?i)\b(let's\s+go!*)\b""") to "চলো যাই!",
        Regex("""(?i)\b(come\s+on!*)\b""") to "চলে এসো!",
        Regex("""(?i)\b(come\s+with\s+me)\b""") to "আমার সাথে এসো",
        Regex("""(?i)\b(follow\s+me)\b""") to "আমার পেছনে এসো",
        Regex("""(?i)\b(hurry\s+up!*)\b""") to "তাড়াতাড়ি করো!",
        Regex("""(?i)\b(fast!*)\b""") to "দ্রুত!",
        Regex("""(?i)\b(stop\s+it!*)\b""") to "থামো!",
        Regex("""(?i)\b(hold\s+on!*)\b""") to "একটু দাঁড়াও!",
        Regex("""(?i)\b(wait\s+for\s+me)\b""") to "আমার জন্য অপেক্ষা করো",
        Regex("""(?i)\b(wait\s+a\s+minute)\b""") to "এক মিনিট অপেক্ষা করো",
        Regex("""(?i)\b(just\s+a\s+moment)\b""") to "এক মুহূর্ত",
        Regex("""(?i)\b(listen\s+to\s+me)\b""") to "আমার কথা শোনো",
        Regex("""(?i)\b(look\s+at\s+me)\b""") to "আমার দিকে তাকাও",
        Regex("""(?i)\b(look\s+out!*)\b""") to "সাবধান!",
        Regex("""(?i)\b(watch\s+out!*)\b""") to "সাবধানে থাকো!",
        Regex("""(?i)\b(be\s+careful)\b""") to "সাবধানে থেকো",
        Regex("""(?i)\b(calm\s+down)\b""") to "শান্ত হও",
        Regex("""(?i)\b(don't\s+move)\b""") to "একদম নড়বে না",
        Regex("""(?i)\b(stay\s+here)\b""") to "এখানেই থাকো",
        Regex("""(?i)\b(stay\s+with\s+me)\b""") to "আমার সাথেই থাকো",
        Regex("""(?i)\b(don't\s+leave\s+me)\b""") to "আমাকে ছেড়ে যেও না",
        Regex("""(?i)\b(leave\s+me\s+alone)\b""") to "আমাকে একা থাকতে দাও",
        Regex("""(?i)\b(get\s+down!*)\b""") to "নিচে নামো!",
        Regex("""(?i)\b(wake\s+up!*)\b""") to "জেগে ওঠো!",

        // Emotions & Conversational Affirmations
        Regex("""(?i)\b(i\s+love\s+you)\b""") to "আমি তোমাকে ভালোবাসি",
        Regex("""(?i)\b(i\s+miss\s+you)\b""") to "আমি তোমাকে মিস করছি",
        Regex("""(?i)\b(i\s+hate\s+you)\b""") to "আমি তোমাকে ঘৃণা করি",
        Regex("""(?i)\b(i\s+trust\s+you)\b""") to "আমি তোমাকে বিশ্বাস করি",
        Regex("""(?i)\b(trust\s+me)\b""") to "আমাকে বিশ্বাস করো",
        Regex("""(?i)\b(believe\s+me)\b""") to "আমার কথা বিশ্বাস করো",
        Regex("""(?i)\b(i\s+promise)\b""") to "আমি কথা দিচ্ছি",
        Regex("""(?i)\b(i\s+swear)\b""") to "আমি কসম খাচ্ছি",
        Regex("""(?i)\b(i\s+understand)\b""") to "আমি বুঝতে পেরেছি",
        Regex("""(?i)\b(i\s+know)\b""") to "আমি জানি",
        Regex("""(?i)\b(i\s+don't\s+know)\b""") to "আমি জানি না",
        Regex("""(?i)\b(i\s+have\s+no\s+idea)\b""") to "আমার কোনো ধারণা নেই",
        Regex("""(?i)\b(i\s+don't\s+understand)\b""") to "আমি বুঝতে পারছি না",
        Regex("""(?i)\b(i\s+can't\s+believe\s+this)\b""") to "আমি এটা বিশ্বাস করতে পারছি না",
        Regex("""(?i)\b(i\s+don't\s+believe\s+you)\b""") to "আমি তোমাকে বিশ্বাস করি না",
        Regex("""(?i)\b(i'm\s+sorry)\b""") to "আমি দুঃখিত",
        Regex("""(?i)\b(i\s+am\s+sorry)\b""") to "আমি দুঃখিত",
        Regex("""(?i)\b(excuse\s+me)\b""") to "মাফ করবেন",
        Regex("""(?i)\b(forgive\s+me)\b""") to "আমাকে ক্ষমা করো",
        Regex("""(?i)\b(thank\s+you\s+very\s+much)\b""") to "তোমাকে অনেক ধন্যবাদ",
        Regex("""(?i)\b(thank\s+you)\b""") to "ধন্যবাদ",
        Regex("""(?i)\b(thanks\s+a\s+lot)\b""") to "অনেক ধন্যবাদ",
        Regex("""(?i)\b(you're\s+welcome)\b""") to "স্বাগতম",
        Regex("""(?i)\b(no\s+problem)\b""") to "কোনো সমস্যা নেই",
        Regex("""(?i)\b(don't\s+worry\s+about\s+it)\b""") to "এটা নিয়ে চিন্তা কোরো না",
        Regex("""(?i)\b(don't\s+worry)\b""") to "চিন্তা কোরো না",
        Regex("""(?i)\b(it's\s+okay)\b""") to "সব ঠিক আছে",
        Regex("""(?i)\b(it's\s+alright)\b""") to "ঠিক আছে",
        Regex("""(?i)\b(everything\s+is\s+going\s+to\s+be\s+okay)\b""") to "সব ঠিক হয়ে যাবে",
        Regex("""(?i)\b(everything\s+will\s+be\s+fine)\b""") to "সবকিছু ঠিক হয়ে যাবে",
        Regex("""(?i)\b(i'm\s+fine)\b""") to "আমি ঠিক আছি",
        Regex("""(?i)\b(are\s+you\s+okay\??)\b""") to "তুমি কি ঠিক আছো?",
        Regex("""(?i)\b(are\s+you\s+hurt\??)\b""") to "তুমি কি আঘাত পেয়েছো?",
        Regex("""(?i)\b(are\s+you\s+ready\??)\b""") to "তুমি কি প্রস্তুত?",
        Regex("""(?i)\b(are\s+you\s+sure\??)\b""") to "তুমি কি নিশ্চিত?",
        Regex("""(?i)\b(are\s+you\s+crazy\??)\b""") to "তুমি কি পাগল?",
        Regex("""(?i)\b(i\s+need\s+your\s+help)\b""") to "আমার তোমার সাহায্য প্রয়োজন",
        Regex("""(?i)\b(help\s+me!*)\b""") to "আমাকে সাহায্য করো!",
        Regex("""(?i)\b(can\s+you\s+help\s+me\??)\b""") to "তুমি কি আমাকে সাহায্য করতে পারবে?",
        Regex("""(?i)\b(can\s+you\s+hear\s+me\??)\b""") to "তুমি কি আমার কথা শুনতে পাচ্ছ?",
        Regex("""(?i)\b(i\s+can\s+hear\s+you)\b""") to "আমি শুনতে পাচ্ছি",
        Regex("""(?i)\b(i\s+can't\s+hear\s+you)\b""") to "আমি শুনতে পাচ্ছি না",
        Regex("""(?i)\b(tell\s+me\s+the\s+truth)\b""") to "আমাকে সত্যিটা বলো",
        Regex("""(?i)\b(are\s+you\s+telling\s+the\s+truth\??)\b""") to "তুমি কি সত্যি বলছো?",
        Regex("""(?i)\b(i'm\s+telling\s+the\s+truth)\b""") to "আমি সত্যি বলছি",
        Regex("""(?i)\b(i\s+did\s+not\s+do\s+it)\b""") to "আমি এটা করিনি",
        Regex("""(?i)\b(i\s+didn't\s+do\s+it)\b""") to "আমি এটা করিনি",
        Regex("""(?i)\b(it\s+was\s+an\s+accident)\b""") to "এটা একটা দুর্ঘটনা ছিল",
        Regex("""(?i)\b(it's\s+not\s+my\s+fault)\b""") to "এটা আমার দোষ নয়",
        Regex("""(?i)\b(it's\s+too\s+late)\b""") to "অনেক দেরি হয়ে গেছে",
        Regex("""(?i)\b(we\s+are\s+out\s+of\s+time)\b""") to "আমাদের সময় শেষ",
        Regex("""(?i)\b(we\s+need\s+more\s+time)\b""") to "আমাদের আরও সময় লাগবে",
        Regex("""(?i)\b(give\s+me\s+some\s+time)\b""") to "আমাকে একটু সময় দাও",
        Regex("""(?i)\b(let\s+me\s+explain)\b""") to "আমাকে বুঝিয়ে বলতে দাও",
        Regex("""(?i)\b(i\s+have\s+an\s+idea)\b""") to "আমার একটা বুদ্ধি আছে",
        Regex("""(?i)\b(i\s+will\s+be\s+right\s+back)\b""") to "আমি এখুনি ফিরছি",
        Regex("""(?i)\b(i'll\s+be\s+right\s+back)\b""") to "আমি এখুনি ফিরে আসছি",
        Regex("""(?i)\b(never\s+give\s+up)\b""") to "কখনও হাল ছেড়ে দিও না",
        Regex("""(?i)\b(i\s+can't\s+do\s+this\s+anymore)\b""") to "আমি আর এটা করতে পারছি না",
        Regex("""(?i)\b(i\s+am\s+proud\s+of\s+you)\b""") to "আমি তোমাকে নিয়ে গর্বিত",
        Regex("""(?i)\b(i'm\s+proud\s+of\s+you)\b""") to "আমি তোমাকে নিয়ে গর্বিত",
        Regex("""(?i)\b(it\s+doesn't\s+matter)\b""") to "তাতে কিছু যায় আসে না"
    )

    // Common individual conversational words and vocabulary
    private val VOCABULARY: Map<String, String> = mapOf(
        "yes" to "হ্যাঁ",
        "no" to "না",
        "okay" to "ঠিক আছে",
        "ok" to "ঠিক আছে",
        "please" to "দয়া করে",
        "hello" to "হ্যালো",
        "hi" to "হাই",
        "bye" to "বিদায়",
        "goodbye" to "বিদায়",
        "thanks" to "ধন্যবাদ",
        "sorry" to "দুঃখিত",
        "sure" to "নিশ্চয়ই",
        "right" to "ঠিক",
        "true" to "সত্য",
        "false" to "মিথ্যা",
        "why" to "কেন",
        "what" to "কী",
        "who" to "কে",
        "where" to "কোথায়",
        "when" to "কখন",
        "how" to "কীভাবে",
        "here" to "এখানে",
        "there" to "সেখানে",
        "now" to "এখন",
        "then" to "তখন",
        "today" to "আজ",
        "tomorrow" to "আগামীকাল",
        "yesterday" to "গতকাল",
        "always" to "সবসময়",
        "never" to "কখনও না",
        "maybe" to "হয়তো",
        "really" to "সত্যি",
        "friend" to "বন্ধু",
        "family" to "পরিবার",
        "father" to "বাবা",
        "mother" to "মা",
        "brother" to "ভাই",
        "sister" to "বোন",
        "son" to "ছেলে",
        "daughter" to "মেয়ে",
        "doctor" to "ডাক্তার",
        "police" to "পুলিশ",
        "car" to "গাড়ি",
        "home" to "বাড়ি",
        "house" to "বাড়ি",
        "room" to "ঘর",
        "door" to "দরজা",
        "money" to "টাকা",
        "time" to "সময়",
        "life" to "জীবন",
        "water" to "পানি",
        "food" to "খাবার"
    )

    /**
     * Translates English dialogue into authentic spoken Bangla.
     * Uses phrase-level match, vocabulary translation, and normalizes with BanglaNaturalizer.
     */
    fun translate(englishText: String): String {
        val trimmed = englishText.trim()
        if (trimmed.isEmpty()) return ""

        // If the text is already primarily in Bengali Unicode, just normalize it
        val hasBengali = trimmed.any { it in '\u0980'..'\u09FF' }
        if (hasBengali) {
            return BanglaNaturalizer.naturalize(trimmed)
        }

        var working = trimmed

        // 1. Match multi-word movie dialogues & expressions
        for ((pattern, banglaReplacement) in DIALOGUE_PHRASES) {
            if (pattern.containsMatchIn(working)) {
                working = pattern.replace(working, banglaReplacement)
            }
        }

        // 2. If the text has already turned substantially into Bengali, post-process
        val bengaliCharCount = working.count { it in '\u0980'..'\u09FF' }
        if (bengaliCharCount > 0 && working.length < 50) {
            return BanglaNaturalizer.naturalize(working)
        }

        // 3. Fallback word-level translation for remaining English tokens
        val tokens = working.split(Regex("(?<=\\b|\\s|(?=[.,!?]))|(?<=[.,!?])"))
        val sb = StringBuilder()
        for (token in tokens) {
            val clean = token.lowercase(Locale.ENGLISH).trim()
            val mapped = VOCABULARY[clean]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append(token)
            }
        }

        val result = sb.toString().trim()
        return BanglaNaturalizer.naturalize(if (result.isNotBlank()) result else trimmed)
    }
}
