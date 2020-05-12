package com.stillwindsoftware.pomodorome.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase

/**
 * Superclass for the view models
 */
open class PomodoromeViewModel(application: Application) : AndroidViewModel(application) {

    // The ViewModel maintains a reference to the repository to get data.
    val repository: PomodoromeRepository =
        PomodoromeRepository(application.applicationContext, PomodoromeDatabase.getDatabase(application, viewModelScope))

}