package com.example.translation

interface Translator {
    suspend fun translate(
        text: String
    ): String
}
