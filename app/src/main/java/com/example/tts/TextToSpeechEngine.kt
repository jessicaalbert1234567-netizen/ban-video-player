package com.example.tts

import java.io.File

interface TextToSpeechEngine {
    suspend fun synthesize(
        text: String,
        outputFile: File
    ): File
}
