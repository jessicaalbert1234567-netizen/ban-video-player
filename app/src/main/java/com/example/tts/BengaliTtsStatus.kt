package com.example.tts

sealed class BengaliTtsStatus {
    object Checking : BengaliTtsStatus()
    data class Available(val engineName: String?, val localeDisplayName: String) : BengaliTtsStatus()
    data class NotInstalled(val message: String = "Bengali TTS voice is not installed on this device.") : BengaliTtsStatus()

    val isAvailable: Boolean
        get() = this is Available
}
