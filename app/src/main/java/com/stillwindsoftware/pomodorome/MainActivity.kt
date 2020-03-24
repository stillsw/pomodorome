package com.stillwindsoftware.pomodorome

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import com.stillwindsoftware.pomodorome.databinding.ActivityMainBinding
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import com.stillwindsoftware.pomodorome.viewmodels.PomodoromeRepository
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOG_TAG = "MainActivity"
    }

    private val viewModel by lazy { ViewModelProvider(this)[ActiveTimerViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PomodoromeRepository.initEmojis(applicationContext)

        val binding : ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewmodel = viewModel

        setSupportActionBar(toolbar)

        time_picker_circle.timerWidgets[TimePickerCircle.WORK].timePickerTextView = work_time
        time_picker_circle.timerWidgets[TimePickerCircle.REST].timePickerTextView = rest_time

    }

    fun callbackChangeToTimer(acceptInput: Boolean) {
        if (acceptInput) {
            work_emoji.visibility = View.INVISIBLE
            rest_emoji.visibility = View.INVISIBLE
        }
        else {
            work_emoji.visibility = View.VISIBLE
            rest_emoji.visibility = View.VISIBLE
        }
    }

    fun playClicked(view: View) {
        time_picker_circle.startTiming()
    }

    fun editTimers(view: View) {
        time_picker_circle.editTimers()
    }

    fun restartTimers(view: View) {
        //TODO
    }

}
