package com.stillwindsoftware.pomodorome.ads

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.FrameLayout
import com.google.android.gms.ads.*
import com.stillwindsoftware.pomodorome.BuildConfig

/**
 * Handles the admob stuff
 */
class AdmobLoader {
    companion object {
        private const val LOG_TAG = "MainActivity"
        private const val NEXUS_7_DEVICE = "DC0C4EA1C6127311CAC9C8F9ECC1898C"
        private const val GALAXY_NEXUS_DEVICE = "F830B9C9F5CFF00A9D43B83572557041"

        fun loadBanner(context: Context, adView: AdView, display: Display) {

            val metrics = DisplayMetrics()
            display.getMetrics(metrics)

            //Log.d(LOG_TAG, "loadBanner: display metrics=$metrics adviewWidth=${adView.parent as FrameLayout}")

            adView.adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context,
                (metrics.widthPixels / metrics.density).toInt())

            if (!BuildConfig.SHOW_REAL_ADS) { // ie. debug build, include test devices
                ArrayList<String>().apply {
                    add(AdRequest.DEVICE_ID_EMULATOR)
                    add(NEXUS_7_DEVICE)
                    add(GALAXY_NEXUS_DEVICE)

                    val requestConfig = RequestConfiguration.Builder()
                        .setTestDeviceIds(this)
                        .build()
                    MobileAds.setRequestConfiguration(requestConfig)
                }
            }

            adView.loadAd(AdRequest.Builder().build())
        }
    }
}