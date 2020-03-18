package com.stillwindsoftware.pomodorome.viewmodels

import android.content.Context
import android.graphics.Color
import androidx.core.provider.FontRequest
import android.util.Log
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.FontRequestEmojiCompatConfig
import androidx.lifecycle.LiveData
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.ActiveTimerDao
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase

/**
 * Following the suggested best practice for Room, the repository is created by the view model
 * and becomes the go between for database actions
 */
class PomodoromeRepository (private val activeTimerDao: ActiveTimerDao) {

    companion object {
        const val LOG_TAG = "PomodoromeRepository"
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
                "com.google.android.gms",
                "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs)

            FontRequestEmojiCompatConfig.ExponentialBackoffRetryPolicy(60L * 1000 * 5) // 5 mins

            val config = FontRequestEmojiCompatConfig(context.applicationContext, fontRequest)
                .setReplaceAll(true)
                .registerInitCallback(object: EmojiCompat.InitCallback() {
                    override fun onInitialized() {
                        Log.d(PomodoromeRepository.LOG_TAG, "initEmojis: initialized")
                    }

                    override fun onFailed(throwable: Throwable?) {
                        fontsRequested = false // let's it try again next time in to the activity
                        Log.e(LOG_TAG, "initEmojis: failed", throwable)
                    }
                })

            EmojiCompat.init(config)
        }
    }

    val timer: LiveData<ActiveTimer> = activeTimerDao.getTimer()

    suspend fun update(activeTimer: ActiveTimer) {
        activeTimerDao.update(activeTimer)
    }

}

