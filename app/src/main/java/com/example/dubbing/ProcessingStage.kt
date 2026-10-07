package com.example.dubbing

enum class ProcessingStage(val displayName: String) {
    EXTRACT_AUDIO("Extracting Audio"),
    TRANSCRIBE("English Speech Recognition (ASR)"),
    TRANSLATE("Bangla Translation & Naturalizer"),
    GENERATE_SUBTITLE("Generating Bangla Subtitles"),
    GENERATE_TTS("Synthesizing Bangla Voice"),
    SYNC_AUDIO("Synchronizing Audio"),
    FINALIZE("Finalizing Dubbed Media"),
    COMPLETE("Completed"),
    FAILED("Processing Failed"),
    CANCELLED("Cancelled")
}
