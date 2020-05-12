package com.stillwindsoftware.pomodorome.ads

import android.annotation.SuppressLint
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.ads.consent.ConsentInfoUpdateListener
import com.google.ads.consent.ConsentInformation
import com.google.ads.consent.ConsentStatus
import com.google.ads.consent.ConsentForm
import com.google.ads.consent.ConsentFormListener
import com.google.ads.consent.DebugGeography
import com.google.android.gms.ads.*
import com.stillwindsoftware.pomodorome.BuildConfig
import com.stillwindsoftware.pomodorome.MainActivity
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.SettingsActivity
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.URL
import java.security.NoSuchAlgorithmException

/**
 * Handles the admob stuff
 * To test eu consent, set isDevTestingEuSpoofing to true and check which device it's set up for in isTestSpoofingEu()
 */
class AdmobLoader(private val activity: AppCompatActivity, private val adView: AdView? = null, private val display: Display? = null) {

    companion object {
        private const val LOG_TAG = "AdmobLoader"
        private const val NEXUS_7_DEVICE = "E590725CEA6AC3E836A5E8D767A86882"
        private const val GALAXY_NEXUS_DEVICE = "F830B9C9F5CFF00A9D43B83572557041"
        private const val SWSW_DATA_CONSENT_POLICY_URL = "https://sites.google.com/view/stillwindswag/data-policy"
        private const val ADMOB_PUB_ID = "pub-1327712413378636"

        private var isAdsInitialised = false
        private var isAwaitingConsentInfo = true
        private var hasConsentInfoBeenRequested = false
        private var hasConsentRequestFailed = false
        private var isConsentToShowAdsNeeded = true // will be unset if location not in eea
        private var isConsentForPersonalizedAds = false
        private var isConsentForNonPersonalizedAds = false

        // set when consent is needed and checks are all good, so can go ahead and show the form when the opportunity presents
        // (see isTriggerConsentForm())
        private var isReadyToShowConsentForm = false

        // for testing eu consent
        private var isDevTestingEuSpoofing = false
    }

    /**
     * Called from mainActivity.onCreate()
     */
    fun initialize() {
        gatherConsentForAdsIfNeeded(isInitializing = true)
    }

    /**
     * Recheck consent (it's possible user has just changed consent for personalized ads, say they went to prefs and changed it)
     * Called from mainActivity.onResume()
     */
    fun onActivityResume() {
        gatherConsentForAdsIfNeeded(isInitializing = false)
    }

    /**
     * Called from the activity whenever a convenient place to interrupt the user happens and seems
     * reasonable to check for consent
     * Returns true if the consent form is shown so the caller can react accordingly (eg. cancel what it's about to do)
     */
    fun isTriggerConsentForm(activeTimer: ActiveTimer): Boolean {
        if (!isConsentToShowAdsNeeded
            || !isReadyToShowConsentForm) // set when established consent is needed
            return false

        // should only call this method from some kind of started state
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.e(LOG_TAG, "isTriggerConsentForm: invalid state: activity not started")
            return false
        }

        if (activeTimer.isPlaying()) {
            Log.e(LOG_TAG, "isTriggerConsentForm: invalid state: not allowed while playing timer")
            return false
        }

        // retest it's needed (could've been handled in the settings screen)

        return if (testConsentStatus(ConsentInformation.getInstance(activity.baseContext).consentStatus)) {
            showAdsConsentForm(null)
            true
        } else {
            Log.d(LOG_TAG, "isTriggerConsentForm: form not needed now, consent has happened ")
            false
        }
    }


    /**
     * Called when consent is gained or not needed
     */
    private fun loadAd() {

        if (!isAdsInitialised) {
            MobileAds.initialize(activity)
            isAdsInitialised = true
        }

        DisplayMetrics().also { metrics ->
            display!!.getMetrics(metrics)
            adView!!.adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, (metrics.widthPixels / metrics.density).toInt())
        }

        @Suppress("ConstantConditionIf")
        if (!BuildConfig.SHOW_REAL_ADS) { // ie. debug build, include test devices
            val testDevices = ArrayList<String>().apply {
                    add(AdRequest.DEVICE_ID_EMULATOR)
                    add(NEXUS_7_DEVICE)
                    add(GALAXY_NEXUS_DEVICE)
                }

           val requestConfig = RequestConfiguration.Builder()
                    .setTestDeviceIds(testDevices)
                    .build()
            MobileAds.setRequestConfiguration(requestConfig)
        }

        adView!!.loadAd(AdRequest.Builder().build())

        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {

                // reset the size of the parent frame layout for the ad now it's loaded

                (adView.parent as FrameLayout).apply {
                    val lp = layoutParams
                    lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                    layoutParams = lp
                }
            }
        }
    }

    /**
     * Looks up consent and sets up either not needed can start generating ads or
     * have to wait for consent
     * Called from mainActivity.onCreate() via initialize(), and onActivityResume()
     */
    private fun gatherConsentForAdsIfNeeded(isInitializing: Boolean) {

        ConsentInformation.getInstance(activity.baseContext).also {consentInformation ->

            // set up properties for the rest of the testing
            testConsentStatus(consentInformation.consentStatus)

            // before value of personalized consent, so can react if it's been changed since last tested
            val hadPersonalizedConsent = !isInitializing && isConsentForPersonalizedAds // would only be set on a previous call

            if (isConsentToShowAdsNeeded) {
                if (!isReadyToShowConsentForm) {

                    Log.d(LOG_TAG, "gatherConsentForAdsIfNeeded: consent needed, test if need to go for background update or show form")
                    updateConsent(consentInformation, onResume = !isInitializing)
                }
                else {
                    Log.d(LOG_TAG, "gatherConsentForAdsIfNeeded: consent needed, but about to show form already")
                }
            }
            else if (isInitializing ||  // either first time in, or consent for personalized ads changed
                (hadPersonalizedConsent && !isConsentForPersonalizedAds)) {
                Log.d(LOG_TAG, "gatherConsentForAdsIfNeeded: initializing or consent changed, (re)load ads")
                loadAd()
            }
            else {
                Log.d(LOG_TAG, "gatherConsentForAdsIfNeeded: consent not needed")
            }
        }
    }

    /**
     * Called from gatherConsentForAdsIfNeeded(), checked already that needs to get it
     */
    private fun updateConsent(consentInformation: ConsentInformation, onResume: Boolean = false) {

        when {
            hasConsentInfoBeenRequested && isAwaitingConsentInfo -> {
                // consent has been requested but still awaiting result
                Log.d(LOG_TAG, "updateConsent: waiting for consent info which should complete and invoke the next procedure")
            }
            hasConsentRequestFailed || !hasConsentInfoBeenRequested -> {

                // consent has not been requested yet or it has been requested but failed, get an info update on a non-UI thread

                MainScope().launch {
                    requestConsentInfoUpdate(consentInformation)
                }
            }
            onResume -> {
                Log.d(LOG_TAG, "updateConsent: presume form previously cancelled when activity was out of view, try again")
                isReadyToShowConsentForm = true
            }
            else -> Log.w(LOG_TAG, "updateConsent: consent already requested, should not have fallen through to here")
        }
    }

    /**
     * Called from updateConsent() because probably need to launch the form to get consent, but the update needs to be off the UI thread
     */
    private suspend fun requestConsentInfoUpdate(consentInformation: ConsentInformation) {

        withContext(Dispatchers.IO) {

            hasConsentInfoBeenRequested = true  // just do this once unless failed before
            isAwaitingConsentInfo = true
            hasConsentRequestFailed = false

            Log.d(LOG_TAG, "requestConsentInfoUpdate: background task started for consent and init ads")

            @Suppress("ConstantConditionIf")
            if (!BuildConfig.SHOW_REAL_ADS) { // ie. debug build
                consentInformation.addTestDevice(AdRequest.DEVICE_ID_EMULATOR)
                consentInformation.addTestDevice(NEXUS_7_DEVICE)
                consentInformation.addTestDevice(GALAXY_NEXUS_DEVICE)

                if (isTestSpoofingEu()) {
                    Log.d(LOG_TAG, "requestConsentInfoUpdate: spoofing in EU for consent processing")
                    consentInformation.debugGeography = DebugGeography.DEBUG_GEOGRAPHY_EEA
                    Log.d(LOG_TAG, "updateConsent: spoof set") // W/O THIS LINE THE PREVIOUS LINE CAUSES AN UNCAUGHT CAST EXCEPTION (EVEN WITH TRY/CATCH)
                    // REGARDLESS(!!) OF NOT BEING ZTE_DEVICE
                } else {
                    Log.d(LOG_TAG, "requestConsentInfoUpdate: not spoofing in EU on device=$deviceId")
                }
            }

            consentInformation.requestConsentInfoUpdate(arrayOf(ADMOB_PUB_ID), object : ConsentInfoUpdateListener {
                override fun onConsentInfoUpdated(consentStatus: ConsentStatus) {

                    isAwaitingConsentInfo = false

                    if (!testConsentStatus(consentStatus)) {
                        Log.d(LOG_TAG, "requestConsentInfoUpdate.onConsentInfoUpdated: consent not needed, prepare to show ads")
                        loadAd()
                    }
                    else { // means it came from the background thread that checked for consent first time around
                        Log.d(LOG_TAG, "requestConsentInfoUpdate.onConsentInfoUpdated: can't show ads, put the form up after delay")
                        isReadyToShowConsentForm = true
                    }
                }

                override fun onFailedToUpdateConsentInfo(s: String) {
                    isAwaitingConsentInfo = false // User's consent state failed to update, maybe there's no network... don't bother now, wait for next try (activity restart)
                    hasConsentRequestFailed = true
                    Log.e(LOG_TAG, "requestConsentInfoUpdate.onFailedToUpdateConsentInfo: Error querying consent status = $s")
                }
            })
        }
    }

    /**
     * Evaluates the consent status, whether needed at all, and if so if it's for personalized ads or not
     */
    private fun testConsentStatus(consentStatus: ConsentStatus): Boolean {
        val consentInformation = ConsentInformation.getInstance(activity)

        isConsentForPersonalizedAds = consentStatus == ConsentStatus.PERSONALIZED
        isConsentForNonPersonalizedAds = consentStatus == ConsentStatus.NON_PERSONALIZED
        val isConsentUnknown = consentStatus == ConsentStatus.UNKNOWN
        val isLocationEeaOrUnknown = consentInformation.isRequestLocationInEeaOrUnknown

        Log.d(LOG_TAG, "testConsentStatus: personal=$isConsentForPersonalizedAds, non-personal=$isConsentForNonPersonalizedAds, unknown=$isConsentUnknown, location eea or unknown=$isLocationEeaOrUnknown")

        isConsentToShowAdsNeeded = !isConsentForPersonalizedAds && !isConsentForNonPersonalizedAds && (isLocationEeaOrUnknown || isTestSpoofingEu())

        return isConsentToShowAdsNeeded // for convenience
    }

    /**
     * Called from settingsActivity to set up the consent preference
     */
    fun evaluateConsent(func: (isPersonalized: Boolean, isNonPersonalized: Boolean, isEaaOrUnknown: Boolean) -> Unit) {
        val consentInformation = ConsentInformation.getInstance(activity.baseContext)
        val consentStatus = consentInformation.consentStatus
        func(consentStatus == ConsentStatus.PERSONALIZED,
            consentStatus == ConsentStatus.NON_PERSONALIZED,
            consentInformation.isRequestLocationInEeaOrUnknown || isTestSpoofingEu())
    }

    /**
     * Called as part of the gathering consent process, but also by preferences fragment in Settings Activity
     * which is looking for values for setting the status of the preference and passes a function to achieve that
     */
    fun showAdsConsentForm(f: ((isPersonalizedConsent: Boolean) -> Unit)?) {
        try {
            var consentForm: ConsentForm? = null

            ConsentForm.Builder(activity, URL(SWSW_DATA_CONSENT_POLICY_URL))
                .also {
                    it.withListener(object : ConsentFormListener() {
                        override fun onConsentFormLoaded() {
                            Log.d(LOG_TAG, "showAdsConsentForm.onConsentFormLoaded: loaded successfully, attempt to show it")
                            consentForm?.show()
                        }

                        override fun onConsentFormOpened() {}

                        override fun onConsentFormClosed(consentStatus: ConsentStatus?, userPrefersAdFree: Boolean?) {

                            if (activity is SettingsActivity) {
                                f?.invoke(consentStatus == ConsentStatus.PERSONALIZED)
                                return
                            }

                            // following only for when in main activity, ie. init loading

                            (activity as MainActivity).onConsentFormClosed()

                            val prevHadConsent = !isConsentToShowAdsNeeded
                            val prevHadNonPersonalizedConsent = prevHadConsent && isConsentForNonPersonalizedAds
                            Log.d(LOG_TAG, "showAdsConsentForm.onConsentFormClosed: prevHadConsent=$prevHadConsent")

                            testConsentStatus(consentStatus!!)

                            if (!prevHadConsent // didn't have consent before, or did but now it's changed
                                || prevHadConsent && isConsentForNonPersonalizedAds != prevHadNonPersonalizedConsent) {
                                Log.d(LOG_TAG, "showAdsConsentForm.onConsentFormClosed: consent changed, loadAd() regardless if already have")
                                loadAd()
                            }
                        }

                        override fun onConsentFormError(errorDescription: String?) {
                            Log.e(LOG_TAG, "showAdsConsentForm.onConsentFormError: not able to process form error=$errorDescription")

                            if (activity is SettingsActivity) {
                                Toast.makeText(activity, R.string.consent_form_show_error, Toast.LENGTH_LONG).show()
                            }
                            else {
                                (activity as MainActivity).onConsentFormClosed()
                            }
                        }
                    })

                    it.withPersonalizedAdsOption()
                    it.withNonPersonalizedAdsOption()

                    consentForm = it.build().apply { load() }
                }

        } catch (e: MalformedURLException) {
            Log.e(LOG_TAG, "showAdsConsentForm: ", e)
        }
    }

    // for testing/spoofing eu device
    private var deviceId: String? = null

    /**
     * Testing only
     */
    @SuppressLint("HardwareIds", "DefaultLocale")
    private fun isTestSpoofingEu(): Boolean {
        fun md5(s: String): String {
            try {
                // Create MD5 Hash
                val digest = java.security.MessageDigest.getInstance("MD5")
                digest.update(s.toByteArray())
                val messageDigest = digest.digest()

                // Create Hex String
                val hexString = StringBuffer()
                for (i in messageDigest.indices) {
                    var h = Integer.toHexString((0xFF and messageDigest[i].toInt()))
                    while (h.length < 2)
                        h = "0$h"
                    hexString.append(h)
                }
                return hexString.toString()

            } catch (e: NoSuchAlgorithmException) {}

            return ""
        }

        @Suppress("ConstantConditionIf")
        if (BuildConfig.SHOW_REAL_ADS || !isDevTestingEuSpoofing) {
            return false
        }

        if (deviceId == null) deviceId = md5(Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)).toUpperCase()

        return deviceId == GALAXY_NEXUS_DEVICE
    }
}