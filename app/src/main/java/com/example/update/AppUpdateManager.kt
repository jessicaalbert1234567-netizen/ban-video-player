package com.example.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Metadata received from GitHub Releases version.json
 */
data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    val mandatory: Boolean,
    val apkUrl: String,
    val sha256: String,
    val message: String
)

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val versionInfo: VersionInfo? = null) : UpdateUiState()
    data class UpdateRequired(val versionInfo: VersionInfo) : UpdateUiState()
    data class Downloading(
        val versionInfo: VersionInfo,
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateUiState()
    data class ReadyToInstall(val versionInfo: VersionInfo, val apkFile: File) : UpdateUiState()
    data class Error(val errorMessage: String, val canRetry: Boolean = true) : UpdateUiState()
}

/**
 * Manages App Update checks against GitHub Releases,
 * streaming APK download with on-the-fly SHA-256 verification, and Android Package Installer launch.
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val UPDATE_METADATA_URL =
            "https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases/latest/download/version.json"
    }

    // Standard client for APK downloads
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Fast client for lightweight version.json checks
    private val fastCheckClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _latestServerVersion = MutableStateFlow<VersionInfo?>(null)
    val latestServerVersion: StateFlow<VersionInfo?> = _latestServerVersion.asStateFlow()

    private var activeDownloadedApk: File? = null

    /**
     * Checks if the device has an active internet connection.
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks for updates from GitHub Releases metadata URL on every app launch.
     * If device is offline or network fails/times out, immediately defaults to UpToDate
     * allowing the user to enter and use the app without any block.
     * When a new version is detected, sets UpdateRequired (strictly mandatory).
     */
    suspend fun checkForUpdates(forceCheck: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateUiState.Downloading && !forceCheck) return@withContext

        // 1. Instant check: If offline, bypass immediately so user can use the app without waiting
        if (!isNetworkAvailable()) {
            Log.i(TAG, "Device is offline. Bypassing update check and allowing immediate app access.")
            _updateState.value = UpdateUiState.UpToDate(_latestServerVersion.value)
            return@withContext
        }

        _updateState.value = UpdateUiState.Checking

        try {
            val request = Request.Builder()
                .url(UPDATE_METADATA_URL)
                .header("User-Agent", "BanVideoPlayer-Android/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            fastCheckClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    Log.i(TAG, "Update check server returned HTTP $code. Allowing app access.")
                    _updateState.value = UpdateUiState.UpToDate(_latestServerVersion.value)
                    return@withContext
                }

                val bodyString = response.body?.string() ?: ""
                if (bodyString.isBlank()) {
                    Log.i(TAG, "Empty response body from update server. Allowing app access.")
                    _updateState.value = UpdateUiState.UpToDate(_latestServerVersion.value)
                    return@withContext
                }

                val json = JSONObject(bodyString)
                val serverVersionCode = json.optInt("versionCode", 0)
                val serverVersionName = json.optString("versionName", "")
                val isMandatory = json.optBoolean("mandatory", true)
                val apkUrl = json.optString("apkUrl", "")
                val expectedSha256 = json.optString("sha256", "").trim()
                val serverMessage = json.optString(
                    "message",
                    "অ্যাপ ব্যবহার চালিয়ে যেতে সর্বশেষ ভার্সন ইনস্টল করুন।"
                )

                val versionInfo = VersionInfo(
                    versionCode = serverVersionCode,
                    versionName = serverVersionName,
                    mandatory = isMandatory,
                    apkUrl = apkUrl,
                    sha256 = expectedSha256,
                    message = serverMessage
                )

                _latestServerVersion.value = versionInfo

                val installedVersionCode = BuildConfig.VERSION_CODE
                Log.i(TAG, "Update check result: installed=$installedVersionCode, server=$serverVersionCode")

                if (serverVersionCode > installedVersionCode) {
                    // Update is strictly mandatory
                    val cachedApk = getCachedApkFile(versionInfo)
                    if (cachedApk.exists() && cachedApk.length() > 1024 && verifyFileSha256(cachedApk, expectedSha256)) {
                        activeDownloadedApk = cachedApk
                        _updateState.value = UpdateUiState.ReadyToInstall(versionInfo, cachedApk)
                    } else {
                        _updateState.value = UpdateUiState.UpdateRequired(versionInfo)
                    }
                } else {
                    _updateState.value = UpdateUiState.UpToDate(versionInfo)
                }
            }
        } catch (e: Exception) {
            // Offline / timeout / connection failure: Allow app entry immediately without blocking
            Log.i(TAG, "Update check network issue (${e.message}). Allowing app access.")
            _updateState.value = UpdateUiState.UpToDate(_latestServerVersion.value)
        }
    }

    /**
     * Downloads APK file directly to internal cache directory, calculates SHA-256 on the fly,
     * and validates file integrity.
     */
    suspend fun downloadAndVerifyApk(versionInfo: VersionInfo) = withContext(Dispatchers.IO) {
        if (versionInfo.apkUrl.isBlank()) {
            _updateState.value = UpdateUiState.Error("সঠিক APK ডাউনলোড লিঙ্ক পাওয়া যায়নি।")
            return@withContext
        }

        val targetApk = getCachedApkFile(versionInfo)
        _updateState.value = UpdateUiState.Downloading(versionInfo, 0, 0L, 0L)

        try {
            val request = Request.Builder()
                .url(versionInfo.apkUrl)
                .header("User-Agent", "BanVideoPlayer-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _updateState.value = UpdateUiState.Error("APK ডাউনলোড ব্যর্থ হয়েছে (HTTP ${response.code})।")
                    return@withContext
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalBytes = body.contentLength()
                val tempFile = File(targetApk.parentFile, "${targetApk.name}.tmp")
                tempFile.parentFile?.mkdirs()

                val digest = MessageDigest.getInstance("SHA-256")
                var bytesCopied = 0L

                body.byteStream().use { input: InputStream ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var lastReportedPercent = -1

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesCopied += read

                            val percent = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt() else 0
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                _updateState.value = UpdateUiState.Downloading(
                                    versionInfo = versionInfo,
                                    progressPercent = percent,
                                    bytesDownloaded = bytesCopied,
                                    totalBytes = totalBytes
                                )
                            }
                        }
                        output.flush()
                    }
                }

                val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                Log.i(TAG, "Downloaded ${bytesCopied} bytes. Calculated SHA-256: $calculatedSha256, Expected: ${versionInfo.sha256}")

                // Strict SHA-256 Integrity Verification
                if (versionInfo.sha256.isNotBlank() &&
                    !versionInfo.sha256.equals("REPLACE_WITH_REAL_SHA256", ignoreCase = true) &&
                    !calculatedSha256.equals(versionInfo.sha256, ignoreCase = true)
                ) {
                    tempFile.delete()
                    _updateState.value = UpdateUiState.Error(
                        "নিরাপত্তা সতর্কতা: ডাউনলোড করা APK-এর SHA-256 মেলেনি! ফাইলটি ক্ষতিগ্রস্ত বা বিকৃত হতে পারে।"
                    )
                    return@withContext
                }

                if (targetApk.exists()) targetApk.delete()
                tempFile.renameTo(targetApk)
                targetApk.setReadable(true, false)
                activeDownloadedApk = targetApk
                _updateState.value = UpdateUiState.ReadyToInstall(versionInfo, targetApk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            _updateState.value = UpdateUiState.Error("ডাউনলোডে ত্রুটি: ${e.localizedMessage ?: "অজানা সমস্যা"}। পুনরায় চেষ্টা করুন।")
        }
    }

    /**
     * Allows testing or re-downloading the latest release directly from within the app.
     */
    suspend fun forceDownloadAndTestUpdate() {
        val info = _latestServerVersion.value ?: VersionInfo(
            versionCode = 3,
            versionName = "1.2.0",
            mandatory = false,
            apkUrl = "https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases/download/v1.2.0/app-release.apk",
            sha256 = "a706257ac3d05fa23ad790bb24d4b83e9cb4e1105169121b40096555b04eb6f3",
            message = "ইন-অ্যাপ আপডেট পরীক্ষা: সর্বশেষ রিলিজ APK ডাউনলোড ও ইনস্টলেশন।"
        )
        downloadAndVerifyApk(info)
    }

    /**
     * Prompts the standard Android Package Installer to install the verified APK.
     * Handles unknown sources permission on Android 8.0+.
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() < 1024) {
            _updateState.value = UpdateUiState.Error("ইনস্টলযোগ্য APK ফাইল পাওয়া যায়নি। অনুগ্রহ করে পুনরায় ডাউনলোড করুন।")
            return
        }

        try {
            // Check unknown apps install permission on Android 8.0+ (Oreo, API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        activity,
                        "অ্যাপ আপডেট করতে অনুগ্রহ করে 'Allow from this source' চালু করুন",
                        Toast.LENGTH_LONG
                    ).show()
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(settingsIntent)
                    return
                }
            }

            apkFile.setReadable(true, false)

            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resInfoList = activity.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                activity.grantUriPermission(pkgName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Package installer invocation failed: ${e.message}", e)
            _updateState.value = UpdateUiState.Error("ইনস্টলার খুলতে সমস্যা হয়েছে: ${e.message}")
        }
    }

    private fun getCachedApkFile(versionInfo: VersionInfo): File {
        val updatesDir = File(context.cacheDir, "apk_updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        return File(updatesDir, "app-release-v${versionInfo.versionName}-${versionInfo.versionCode}.apk")
    }

    private fun verifyFileSha256(file: File, expectedHash: String): Boolean {
        if (expectedHash.isBlank() || expectedHash.equals("REPLACE_WITH_REAL_SHA256", ignoreCase = true)) {
            return true
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            calculated.equals(expectedHash, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}
