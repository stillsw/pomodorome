package com.stillwindsoftware.pomodorome

import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.stillwindsoftware.pomodorome.ads.AdmobLoader
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms
import java.text.DateFormatSymbols
import java.util.*
import kotlin.collections.HashSet

/**
 * The settings activity plus the fragments that make up the individual screens
 * within
 */
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    companion object {
        private const val LOG_TAG = "SettingsActivity"
        const val INTENT_EXTRA_DIRECT_TO_ALARMS = "com.stillwindsoftware.pomodorome.events.Alarms.INTENT_EXTRA_DIRECT_TO_ALARMS"
    }

    val admobLoader: AdmobLoader by lazy { AdmobLoader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiHelper.initEmojis(applicationContext)

        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, if (intent.hasExtra(INTENT_EXTRA_DIRECT_TO_ALARMS)) SettingsAutoStartFragment() else SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment)
        fragment.arguments = args
        fragment.setTargetFragment(caller, 0)
        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
                .replace(R.id.settings, fragment)
                .addToBackStack(null)
                .commit()
        return true
    }

    fun showRemindersList() {
        RemindersListFragment().apply {

            supportFragmentManager.beginTransaction().also { transaction ->
                supportFragmentManager.findFragmentByTag("remindersList")?.let {
                    transaction.remove(it)
                }

                transaction.addToBackStack(null)
                show(transaction, "remindersList")
            }
        }
    }

    /**
     * Up button would take us to the parent activity (main) every time, but when
     * in a sub fragment don't want that, just pop it
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return if (item.itemId == android.R.id.home && supportFragmentManager.backStackEntryCount != 0) {
            supportFragmentManager.popBackStackImmediate()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /**
     * The initial fragment shown
     */
    class SettingsFragment : PreferenceFragmentCompat() {

        /**
         * The 2 ringtones used preference summaries come from the ringtone titles
         * Consent in EU only
         */
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.root_preferences)

            Alarms(requireContext()).also { alarms ->

                for (timerType in TimerType.values()) {
                    findPreference<Preference>(timerType.ringToneKey)?.let { pref ->
                        try {
                            pref.summary = RingtoneManager.getRingtone(context, alarms.getPreferredRingtoneUri(timerType)).getTitle(context)
                        }
                        catch (e: Exception) {
                            Log.w(LOG_TAG, "onCreatePreferences: could not load pomodoro ringtone")
                            pref.summary = "?"
                        }
                    }
                }
            }

            findPreference<Preference>(getString(R.string.consent_pref_key))?.let { consentPref ->
                (requireActivity() as SettingsActivity).admobLoader.evaluateConsent { isPersonalized, isNonPersonalized, isEaaOrUnknown ->
                    consentPref.summary =
                        when {
                            isPersonalized -> getString(R.string.consent_pref_summary_personalized)
                            isNonPersonalized -> getString(R.string.consent_pref_summary_non_personalized)
                            isEaaOrUnknown -> getString(R.string.consent_pref_summary_required)
                            else -> getString(R.string.consent_pref_summary_not_required)
                        }

                    if (!isEaaOrUnknown) {
                        consentPref.isVisible = false
                    }
                }
            }
        }

        /**
         * Tapping a ringtone preference brings up the system picker, the result is evaluated below in onActivityResult()
         */
        override fun onPreferenceTreeClick(preference: Preference): Boolean {

            return when (preference.key) {
                getString(R.string.consent_pref_key) -> {
                    (requireActivity() as SettingsActivity).admobLoader.showAdsConsentForm { isPersonalizedConsent ->
                        preference.summary = getString(if (isPersonalizedConsent) R.string.consent_pref_summary_personalized else R.string.consent_pref_summary_non_personalized)
                    }
                    true
                }
                else -> super.onPreferenceTreeClick(preference)
            }
        }
    }

    /**
     * 2nd level fragment for alerts related prefs
     */
    class SettingsAlertsFragment : PreferenceFragmentCompat() {

        /**
         * The 2 ringtones used preference summaries come from the ringtone titles
         * Consent in EU only
         */
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.alerts_preferences)

            Alarms(requireContext()).also { alarms ->

                for (timerType in TimerType.values()) {
                    findPreference<Preference>(timerType.ringToneKey)?.let { pref ->
                        try {
                            pref.summary = RingtoneManager.getRingtone(context, alarms.getPreferredRingtoneUri(timerType)).getTitle(context)
                        }
                        catch (e: Exception) {
                            Log.w(LOG_TAG, "onCreatePreferences: could not load pomodoro ringtone")
                            pref.summary = "?"
                        }
                    }
                }
            }
        }

        /**
         * Tapping a ringtone preference brings up the system picker, the result is evaluated below in onActivityResult()
         */
        override fun onPreferenceTreeClick(preference: Preference): Boolean {

            return when (preference.key) {
                TimerType.POMODORO.ringToneKey, TimerType.REST.ringToneKey -> {

                    TimerType[preference.key]!!.also {timerType ->

                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)

                                Alarms(requireContext()).getPreferredRingtoneUri(timerType)?.let {ringtoneUri ->
                                    Log.d(LOG_TAG, "onPreferenceTreeClick: found existing = $ringtoneUri")
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtoneUri)

                                } ?: Log.d(LOG_TAG, "onPreferenceTreeClick: found no existing ringtone")

                                startActivityForResult(this, timerType.requestCode)
                            }
                    }

                    true   // return value
                }

                else -> super.onPreferenceTreeClick(preference)
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

            when (requestCode) {
                TimerType.POMODORO.requestCode, TimerType.REST.requestCode -> {
                    TimerType[requestCode]!!.also { timerType ->

                        val ringtoneUri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        ringtoneUri?.run {
                            Log.d(LOG_TAG, "onActivityResult: set ringtone uri=$ringtoneUri on $timerType")

                            setPreferredRingtone(timerType, ringtoneUri)

                            findPreference<Preference>(timerType.ringToneKey)?.summary =
                                RingtoneManager.getRingtone(context, ringtoneUri).getTitle(context)

                        } ?: Log.d(LOG_TAG, "onActivityResult ringtone returned is null, do nothing")
                    }
                }

                else -> super.onActivityResult(requestCode, resultCode, data)
            }
        }

        private fun setPreferredRingtone(timerType: TimerType, ringtoneUri: Uri) {

            with(PreferenceManager.getDefaultSharedPreferences(context)) {

                edit().apply {
                    putString(timerType.ringToneKey, ringtoneUri.toString())
                    apply()
                }
            }
        }

    }

    /**
     * 2nd level fragment for just the settings related to auto-start timings of the app
     */
    class SettingsAutoStartFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        /**
         * Listen for changes to preferences, so can update the summary
         * when days of the week changes
         */
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            super.onDestroy()
            PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
        }

        /**
         * The preference was cached when setting up, if it's there, then that fragment was loaded
         * so check for it being changed to set the summary
         */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {

            if (getString(R.string.auto_days_pref_key) == key) {

                findPreference<MultiSelectListPreference>(key)
                    ?.apply {

                    // summary is list of short symbols, get the selected values
                    // from the shared prefs not from the preference on the fragment
                    // because after orientation change it doesn't work that way
                    // see onCreatePreferences() next for more complete use
                    // of MultiSelectListPreference entries/entryValues/values

                    val stored = sharedPreferences.getStringSet(key, HashSet())

                    summary = DateFormatSymbols(Locale.getDefault()).shortWeekdays
                        .filterIndexed { index, _ -> stored!!.contains(index.toString()) }
                        .joinToString(separator = "/")
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

            addPreferencesFromResource(R.xml.timed_preferences)

            val timeFormat = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("GMT") }

            with(PreferenceManager.getDefaultSharedPreferences(context)) {

                // there are 2 preferences for start/stop times, each with a different default value

                for (keyResIdAndDefault in listOf(
                    R.string.auto_start_time_pref_key to GregorianCalendar().onlyTimeMillis(9, 0),
                    R.string.auto_stop_time_pref_key to GregorianCalendar().onlyTimeMillis(17, 0))) {

                        // try to get the preference, or default

                        val key = getString(keyResIdAndDefault.first)

                        getLong(key, keyResIdAndDefault.second)
                            .also { prefValue ->

                                Log.d(LOG_TAG, "key = $key value = $prefValue")

                                // not existing, then set it

                                if (!contains(key)) {
                                    edit().apply {
                                        putLong(key, prefValue)
                                        apply()
                                    }
                                }

                                // set the summary to the formatted value
                                findPreference<Preference>(key)?.apply {
                                    summary = timeFormat.format(prefValue)
                                }
                            }
                }

                // weekdays is a multi-select preference

                requireContext().getString(R.string.auto_days_pref_key)
                    .also {daysKey ->

                        findPreference<MultiSelectListPreference>(daysKey)
                            ?.apply {

                                val symbols = DateFormatSymbols(Locale.getDefault())

                                // days is an array of length 8, the first is blank
                                entries = symbols.weekdays.filter { it.isNotEmpty() }.toTypedArray()
                                entryValues = Array(7) { i -> (i + 1).toString() }

                                // not existing, then set it

                                if (!contains(daysKey)) {

                                    // default to miss out the weekends

                                    HashSet( Array(5) { i -> (i + Calendar.MONDAY).toString() }.toList() )
                                        .also {
                                            edit().apply {
                                                putStringSet(daysKey, it)
                                                apply()
                                            }

                                            values = it
                                        }
                                }

                                // summary is list of short symbols

                                summary = symbols.shortWeekdays
                                    .filterIndexed { index, _ -> values.contains(index.toString()) }
                                    .joinToString(separator = "/")
                            }
                    }
            }
        }

        /**
         * Tapping a time brings up a time picker dialog
         */
        override fun onPreferenceTreeClick(preference: Preference): Boolean {

            return when (preference.key) {
                getString(R.string.auto_start_time_pref_key),
                getString(R.string.auto_stop_time_pref_key) -> {
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .also{sharedPrefs ->
                            with(sharedPrefs.getLong(preference.key, -1L)) {
                                val hours = this / 1000L / 60L / 60L
                                val minutes = (this / 1000L / 60L) - (hours * 60L)

                                TimePickerDialog(context, { _, hour, minute ->              // listener for setting time
                                    GregorianCalendar()
                                        .onlyTimeMillis(hour, minute)
                                        .also {
                                            sharedPrefs.edit().apply {
                                                putLong(preference.key, it)
                                                apply()
                                            }

                                            val timeFormat = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("GMT") }
                                            preference.summary = timeFormat.format(it)
                                        }
                                }, hours.toInt(), minutes.toInt(), false).show()
                            }

                    }
                    true
                }
                else -> super.onPreferenceTreeClick(preference)
            }
        }
    }

    /**
     * 2nd level fragment for just the settings related to rest time reminders
     */
    @Suppress("unused") // actually is used, ide incorrectly decides it isn't
    class SettingsRemindersFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.reminders_preferences, rootKey)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {

            return when (preference.key) {
                getString(R.string.showReminders_list_pref_key) -> {
                    (activity as SettingsActivity).showRemindersList()
                    true
                }
                else -> super.onPreferenceTreeClick(preference)
            }
        }

    }
}

/**
 * Extension function sets the timezone to GMT and clears all times so only dealing with
 * the hours and minutes passed
 */
fun GregorianCalendar.onlyTimeMillis(hours: Int, minutes: Int): Long {
    timeZone = TimeZone.getTimeZone("GMT")
    clear()
    set(Calendar.HOUR_OF_DAY, hours)
    set(Calendar.MINUTE, minutes)
    return timeInMillis
}