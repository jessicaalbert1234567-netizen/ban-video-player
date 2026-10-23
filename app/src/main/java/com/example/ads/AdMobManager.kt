package com.example.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.security.AntiTamperGuard
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AdMobManager {

    private const val TAG = "AdMobManager"
    private var rewardedAd: RewardedAd? = null
    private var isLoadingRewarded = false
    var isInitialized = false
        private set

    /**
     * Initializes Google Mobile Ads SDK on a background coroutine as recommended
     * by Google to prevent main-thread jank and isolate Chromium variations seed loaders.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Remove any stale or unsigned variations seed file from app_webview
                purgeCorruptVariationsSeed(appContext)

                // Configure request settings with emulator test device support
                val requestConfig = RequestConfiguration.Builder()
                    .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                    .build()
                MobileAds.setRequestConfiguration(requestConfig)

                MobileAds.initialize(appContext) { status ->
                    Log.i(TAG, "AdMob MobileAds initialized successfully: $status")
                    isInitialized = true
                    // Preload rewarded ad once initialization is complete
                    loadRewardedAd(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Non-fatal AdMob initialization warning: ${t.message}")
            }
        }
    }

    private fun purgeCorruptVariationsSeed(context: Context) {
        try {
            val dataDir = java.io.File(context.applicationInfo.dataDir)
            val webviewDir = java.io.File(dataDir, "app_webview")
            if (webviewDir.exists()) {
                val seedFiles = listOf(
                    java.io.File(webviewDir, "variations_seed"),
                    java.io.File(webviewDir, "variations_seed_new")
                )
                for (file in seedFiles) {
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun loadRewardedAd(context: Context) {
        if (rewardedAd != null || isLoadingRewarded) return
        isLoadingRewarded = true

        val unitId = AntiTamperGuard.getRewardAdUnitId()
        val adRequest = AdRequest.Builder().build()

        try {
            RewardedAd.load(
                context,
                unitId,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        Log.i(TAG, "Rewarded ad loaded successfully: $unitId")
                        rewardedAd = ad
                        isLoadingRewarded = false
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(TAG, "Rewarded ad failed to load: ${loadAdError.message} (code=${loadAdError.code})")
                        rewardedAd = null
                        isLoadingRewarded = false
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Non-fatal rewarded ad load exception: ${t.message}")
            isLoadingRewarded = false
        }
    }

    /**
     * Shows the rewarded video ad prior to video playback.
     * Guaranteed to call onFinished() even if the ad is not ready, fails, or is dismissed,
     * so user video playback is never blocked.
     */
    fun showRewardedAd(activity: Activity, onFinished: () -> Unit) {
        val currentAd = rewardedAd
        if (currentAd == null) {
            Log.i(TAG, "Rewarded ad not loaded yet, proceeding directly to video playback.")
            loadRewardedAd(activity.applicationContext)
            onFinished()
            return
        }

        try {
            currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d(TAG, "Rewarded ad dismissed, starting video playback.")
                    rewardedAd = null
                    loadRewardedAd(activity.applicationContext)
                    onFinished()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
                    rewardedAd = null
                    loadRewardedAd(activity.applicationContext)
                    onFinished()
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "Rewarded ad showed full screen.")
                }
            }

            currentAd.show(activity) { rewardItem ->
                Log.i(TAG, "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Non-fatal error showing rewarded ad, bypassing to playback: ${t.message}")
            onFinished()
        }
    }
}

/**
 * Clean Jetpack Compose wrapper for Google AdMob Banner Ads.
 * Includes defensive AdListener for offline / failed load states.
 */
@Composable
fun AdMobBanner(
    adUnitId: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                try {
                    AdView(context).apply {
                        setAdSize(AdSize.BANNER)
                        this.adUnitId = adUnitId
                        adListener = object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                Log.d("AdMobBanner", "Banner ad failed to load (offline or no fill): ${error.message}")
                            }
                        }
                        if (AdMobManager.isInitialized) {
                            loadAd(AdRequest.Builder().build())
                        } else {
                            AdMobManager.initialize(context)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("AdMobBanner", "Failed to instantiate AdView: ${t.message}")
                    android.view.View(context)
                }
            }
        )
    }
}
