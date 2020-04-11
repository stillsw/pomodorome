package com.stillwindsoftware.pomodorome

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val LOG_TAG = "SettingsFragment"
        }

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

                edit().apply() {
                    putString(timerType.ringToneKey, ringtoneUri.toString())
                    apply()
                }
            }
        }

    }
}