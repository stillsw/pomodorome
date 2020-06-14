package com.stillwindsoftware.pomodorome.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.stillwindsoftware.pomodorome.BuildConfig
import com.stillwindsoftware.pomodorome.events.Alarms

/**
 * A bit of a flaky feature, seems to work more reliably when the device is charging, but it's
 * good to only use if for that anyway because of the draw on the battery.
 * Also uses deprecated methods, they're the most reliable way to get the device to wake up.
 */
class WakeupForReminders(val context: Context) {

    companion object {

        private const val LOG_TAG = "WakeupForReminders"
        private var partialWakeLock: PowerManager.WakeLock? = null
        private var fullWakeLock: PowerManager.WakeLock? = null
        internal var wakeLocksExpireAtMillis = -1L // so the alarm repeating in the background knows not to prevent a notification

        /**
         * Called from AlarmReceiver when rest time begins provided the preference is set to wake up to show
         * reminders
         */
        internal fun createWakeLocks(context: Context, millisToHold: Long): Boolean {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .also {
                    return when {
                        it.isInteractive -> {
                            Log.d(LOG_TAG, "createWakeLocks: not acquiring wake locks, already interactive")
                            false
                        }
                        it.isPowerSaveMode -> {
                            Log.d(LOG_TAG, "createWakeLocks: not acquiring wake locks, in power save mode")
                            false
                        }
                        !isPluggedInOrDebugEmulator(context) -> { // see if can test with emulator
                            Log.d(LOG_TAG, "createWakeLocks: not acquiring wake locks, device must be plugged in")
                            false
                        }
                        else -> {
                            Log.d(LOG_TAG, "createWakeLocks: acquiring wake locks")

                            wakeLocksExpireAtMillis = System.currentTimeMillis() + millisToHold

                            @Suppress("DEPRECATION")
                            fullWakeLock = it.newWakeLock((PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP), "$LOG_TAG:FULL WAKE LOCK")
                                .apply { acquire(millisToHold) }
                            partialWakeLock = it.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOG_TAG:PARTIAL WAKE LOCK")
                                .apply { acquire(millisToHold) }
                            true
                        }
                    }
                }
        }

        /**
         * Plugged in test doesn't seem to ever return true on the emulator
         * Hence hard-coded test id for a particular emulator, good enough for testing
         */
        @SuppressLint("HardwareIds")
        private fun isPluggedInOrDebugEmulator(context: Context): Boolean {
            return ((context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
                    || (BuildConfig.DEBUG &&
                        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) == "764942447402698d"))
        }
    }

    // main activity sets this if wake locks are not held, but for most things they're the same
    // (the wake locks have timeouts too)
    var stayAwakeWhileRestReminders = false
        get() {
            return hasWakeLocks() || field
        }
        set(value) {
            if (field && !value) {
                Log.d(LOG_TAG, "stayAwakeWhileRestReminders: set to false")
                field = value
            }
            else if (!field && value) {
                Log.d(LOG_TAG, "stayAwakeWhileRestReminders: set to true")
                field = value
            }
            else if (!value && !field && hasWakeLocks()) {
                Log.d(LOG_TAG, "stayAwakeWhileRestReminders: release wake locks")
                fullWakeLock?.release()
                partialWakeLock?.release()
                fullWakeLock = null
                partialWakeLock = null
            }
        }

    fun hasWakeLocks()= fullWakeLock != null && fullWakeLock!!.isHeld

    /**
     * When the activity resumes it will call this method to check if it's staying awake
     * just to show reminders while resting
     */
    fun checkForStayAwake(intent: Intent?): Boolean {
        return if (hasWakeLocks()) {
            Log.d(LOG_TAG, "checkForStayAwake: wake locks are held")
            true
        }
        else if (intent != null && intent.getIntExtra(Alarms.REQ_CODE, -1) == Alarms.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT) {
            Log.d(LOG_TAG, "checkForStayAwake: called with full screen intent")
            stayAwakeWhileRestReminders = true
            true
        }
        else {
            false // no other reason to set screen on automatically
        }

    }
}