package com.stillwindsoftware.pomodorome

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms
import java.util.*
import android.app.TimePickerDialog.OnTimeSetListener as OnTimeSetListener

/**
 * The settings activity plus the fragments that make up the individual screens
 * within
 */
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    companion object {
        private const val LOG_TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader,pref.fragment)
        fragment.arguments = args
        fragment.setTargetFragment(caller, 0)
        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
                .replace(R.id.settings, fragment)
                .addToBackStack(null)
                .commit()
        return true
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
         */
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.root_preferences)

            Alarms(context!!).also { alarms ->

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

        //todo add other prefs

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

                                 Alarms(context!!).getPreferredRingtoneUri(timerType)?.let {ringtoneUri ->
                                     Log.d(LOG_TAG, "onPreferenceTreeClick: found existing = $ringtoneUri")
                                     putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtoneUri)

                                 } ?: Log.d(LOG_TAG, "onPreferenceTreeClick: found no existing ringtone")

                                 startActivityForResult(this, timerType.requestCode)
                             }

                     }

                     true   // return value
                }
                /*todo
                preference.key == getString(R.string.consent_pref_key) -> {
                    flavourImplementor.showAdsConsentForm { isPersonalizedConsent ->
                        preference.summary = getString(if (isPersonalizedConsent) R.string.consent_pref_summary_personalized else R.string.consent_pref_summary_non_personalized)
                    }
                    true
                }*/
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
     * 2nd level fragment for just the settings related to auto-start of the app
     */
    class SettingsAutoStartFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.timed_preferences)

            val timeFormat = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("GMT") }

            with(PreferenceManager.getDefaultSharedPreferences(context)) {

                // the key is a string resource

                context!!.getString(R.string.auto_start_time_pref_key).also {key ->

                    Log.d(LOG_TAG, "key = $key")

                    // try to get the preference, default to 9am

                    getLong(key, GregorianCalendar(TimeZone.getTimeZone("GMT")).onlyTimeMillis(9, 0))
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
                            findPreference<Preference>(key)?.apply { summary = timeFormat.format(prefValue) }
                        }
                }
            }
        }

        /**
         * Tapping a time brings up a time picker dialog
         */
        override fun onPreferenceTreeClick(preference: Preference): Boolean {

            //todo refactor this and the previous methods, and add the other time field

            return when (preference.key) {
                context!!.getString(R.string.auto_start_time_pref_key) -> {
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .also{sharedPrefs ->
                            with(sharedPrefs.getLong(preference.key, -1L)) {
                                val hours = this / 1000L / 60L / 60L
                                val minutes = (this / 1000L / 60L) - (hours * 60L)

                                TimePickerDialog(context, { _, hour, minute ->              // listener for setting time
                                    GregorianCalendar(TimeZone.getTimeZone("GMT"))
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
}

fun GregorianCalendar.onlyTimeMillis(hours: Int, minutes: Int): Long {
    clear()
    set(Calendar.HOUR_OF_DAY, hours)
    set(Calendar.MINUTE, minutes)
    return timeInMillis
}