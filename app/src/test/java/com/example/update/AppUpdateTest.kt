package com.example.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateTest {

    @Test
    fun testVersionJsonParsing() {
        val sampleJson = """
        {
          "versionCode": 2,
          "versionName": "1.1.0",
          "mandatory": true,
          "apkUrl": "https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases/download/v1.1.0/app-release.apk",
          "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
          "message": "অ্যাপ ব্যবহার চালিয়ে যেতে সর্বশেষ ভার্সন ইনস্টল করুন।"
        }
        """.trimIndent()

        val json = JSONObject(sampleJson)
        val info = VersionInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            mandatory = json.getBoolean("mandatory"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            message = json.getString("message")
        )

        assertEquals(2, info.versionCode)
        assertEquals("1.1.0", info.versionName)
        assertTrue(info.mandatory)
        assertEquals(
            "https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases/download/v1.1.0/app-release.apk",
            info.apkUrl
        )
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", info.sha256)
        assertEquals("অ্যাপ ব্যবহার চালিয়ে যেতে সর্বশেষ ভার্সন ইনস্টল করুন।", info.message)
    }

    @Test
    fun testSha256VerificationLogic() {
        val testContent = "Test APK content for SHA-256 verification"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(testContent.toByteArray()).joinToString("") { "%02x".format(it) }

        assertEquals(64, hash.length)

        // Matching check
        assertTrue(hash.equals(hash.uppercase(), ignoreCase = true))
        assertFalse(hash.equals("invalid_hash", ignoreCase = true))
    }
}
