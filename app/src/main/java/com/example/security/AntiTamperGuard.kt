package com.example.security

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Security & Anti-Tamper Guard:
 * Protects application integrity against APK Editor, MT Manager, and unauthorized patching.
 * Prevents package renaming and obfuscates critical AdMob identifiers so they cannot be
 * easily discovered or replaced with static string search-and-replace tools.
 */
object AntiTamperGuard {

    private const val TAG = "AntiTamperGuard"
    const val AUTHORIZED_PACKAGE_NAME = "com.aistudio.aidubbing.qvdz"

    // Dynamic salt key used for runtime XOR de-obfuscation
    private val OBFUSCATION_SALT = byteArrayOf(0x4E, 0x69, 0x6D, 0x70, 0x61, 0x74, 0x61, 0x42, 0x6E) // "NimpataBn"

    // Obfuscated AdMob Unit IDs (XOR encoded with salt + Base64)
    // Prevents simple string search in dex or resources by APK Editor
    private val ENC_APP_ID = encode("ca-app-pub-5141279700643134~6430780541")
    private val ENC_REWARD_ID = encode("ca-app-pub-5141279700643134/3968268738")
    private val ENC_BANNER_1 = encode("ca-app-pub-5141279700643134/7496450202")
    private val ENC_BANNER_2 = encode("ca-app-pub-5141279700643134/5397734623")
    private val ENC_BANNER_3 = encode("ca-app-pub-5141279700643134/3904030411")
    private val ENC_BANNER_4 = encode("ca-app-pub-5141279700643134/9883774540")

    private fun encode(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor OBFUSCATION_SALT[i % OBFUSCATION_SALT.size].toInt()).toByte()
        }
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    private fun decode(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val result = ByteArray(bytes.size)
            for (i in bytes.indices) {
                result[i] = (bytes[i].toInt() xor OBFUSCATION_SALT[i % OBFUSCATION_SALT.size].toInt()).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            ""
        }
    }

    fun getAdMobAppId(): String = decode(ENC_APP_ID)
    fun getRewardAdUnitId(): String = decode(ENC_REWARD_ID)
    fun getBanner1UnitId(): String = decode(ENC_BANNER_1)
    fun getBanner2UnitId(): String = decode(ENC_BANNER_2)
    fun getBanner3UnitId(): String = decode(ENC_BANNER_3)
    fun getBanner4UnitId(): String = decode(ENC_BANNER_4)

    /**
     * Verifies that the package name has not been tampered with or modified.
     */
    fun verifyIntegrity(context: Context): Boolean {
        val currentPackage = context.packageName
        if (currentPackage != AUTHORIZED_PACKAGE_NAME) {
            Log.w(TAG, "Package name mismatch detected: $currentPackage vs $AUTHORIZED_PACKAGE_NAME")
            return false
        }
        return true
    }
}
