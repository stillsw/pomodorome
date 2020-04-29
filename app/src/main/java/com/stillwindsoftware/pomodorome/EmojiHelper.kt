package com.stillwindsoftware.pomodorome

import android.content.Context
import androidx.core.provider.FontRequest
import android.util.Log
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.FontRequestEmojiCompatConfig

/**
 * Initialises emojis for the activities just once when the app starts up
 */
class EmojiHelper {

    companion object {
        const val LOG_TAG = "EmojiHelper"
        var fontsRequested = false

        /**
         * Download fonts only once while the app is up
         */
        fun initEmojis(context: Context) {
            if (fontsRequested) {
                Log.d(LOG_TAG, "initEmojis: fonts already requested")
                return
            }

            fontsRequested = true

            val fontRequest = FontRequest("com.google.android.gms.fonts",
                "com.google.android.gms", "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs)

            FontRequestEmojiCompatConfig.ExponentialBackoffRetryPolicy(60L * 1000 * 5) // 5 mins

            val config = FontRequestEmojiCompatConfig(context.applicationContext, fontRequest)
                .setReplaceAll(true)
                .registerInitCallback(object: EmojiCompat.InitCallback() {
                    override fun onInitialized() {
                        Log.d(LOG_TAG, "initEmojis: initialized")
                    }

                    override fun onFailed(throwable: Throwable?) {
                        fontsRequested = false // try again next time in to the activity
                        Log.e(LOG_TAG, "initEmojis: failed", throwable)
                    }
                })

            EmojiCompat.init(config)
        }
    }


}