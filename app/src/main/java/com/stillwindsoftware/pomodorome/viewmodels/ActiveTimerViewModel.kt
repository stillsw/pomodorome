package com.stillwindsoftware.pomodorome.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import kotlinx.coroutines.launch

/**
 * View model connects to the repository and provides non-blocking scope for updates.
 * ViewModels have a coroutine scope based on their lifecycle called viewModelScope
 */
class ActiveTimerViewModel(application: Application) : AndroidViewModel(application) {

    // The ViewModel maintains a reference to the repository to get data.
    private val repository: PomodoromeRepository
    val timer: LiveData<ActiveTimer>
//    val

    init {
        val activeTimerDao = PomodoromeDatabase.getDatabase(application, viewModelScope).activeTimerDao()
        repository = PomodoromeRepository(activeTimerDao)
        timer = repository.timer
    }

    /**
     * The implementation of update() in the database is completely hidden from the UI.
     */
    fun update(activeTimer: ActiveTimer) = viewModelScope.launch {
        repository.update(activeTimer)
    }
}