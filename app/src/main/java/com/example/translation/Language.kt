package com.example.translation

interface SourceLanguage {
    val code: String
    val displayName: String
}

interface TargetLanguage {
    val code: String
    val displayName: String
}

object Languages {
    val ENGLISH = object : SourceLanguage {
        override val code = "en"
        override val displayName = "English"
    }

    val BANGLA = object : TargetLanguage {
        override val code = "bn"
        override val displayName = "বাংলা (Bangla)"
    }

    val HINDI = object : TargetLanguage {
        override val code = "hi"
        override val displayName = "हिन्दी (Hindi)"
    }

    val ARABIC = object : TargetLanguage {
        override val code = "ar"
        override val displayName = "العربية (Arabic)"
    }

    val JAPANESE = object : TargetLanguage {
        override val code = "ja"
        override val displayName = "日本語 (Japanese)"
    }

    val CHINESE = object : TargetLanguage {
        override val code = "zh"
        override val displayName = "中文 (Chinese)"
    }

    val SPANISH = object : TargetLanguage {
        override val code = "es"
        override val displayName = "Español (Spanish)"
    }

    val FRENCH = object : TargetLanguage {
        override val code = "fr"
        override val displayName = "Français (French)"
    }
}
