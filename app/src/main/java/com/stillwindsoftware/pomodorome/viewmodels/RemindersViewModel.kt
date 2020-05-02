package com.stillwindsoftware.pomodorome.viewmodels

import android.app.Application
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.stillwindsoftware.pomodorome.ReminderDeleteIconState
import com.stillwindsoftware.pomodorome.databinding.ReminderListItemBinding
import com.stillwindsoftware.pomodorome.db.Reminder
import kotlinx.coroutines.launch

/**
 * View model provides non-blocking scope for updates.
 * ViewModels have a coroutine scope based on their lifecycle called viewModelScope
 */
class RemindersViewModel(application: Application) : PomodoromeViewModel(application) {
    companion object {
        const val LOG_TAG = "RemindersViewModel"
    }

    private fun insert(reminder: Reminder) = viewModelScope.launch {
        repository.insert(reminder)
    }

    private fun update(reminder: Reminder) = viewModelScope.launch {
        repository.update(reminder)
    }

    fun delete(reminder: Reminder) = viewModelScope.launch {
        repository.delete(reminder)
    }

    fun toggleSelection(reminder: Reminder) {
        reminder.selected = !reminder.selected
        update(reminder)
    }

    fun addReminder(text: String) {
        insert(Reminder(null, text, true))
    }

    fun getReminderText(): String? {
        return repository.getNextReminder(getApplication())?.text
    }
}

/**
 * View holder for the recycler view
 * Also stores some data about the swiping (see SwipeToRevealCallback.onChildDraw()
 * in RemindersListFragment)
 */
class RemindersItemViewHolder private constructor(val binding: ReminderListItemBinding): RecyclerView.ViewHolder(binding.root) {

    var backgroundRect = RectF()
    var buttonRect = RectF()
    internal var buttonState = ReminderDeleteIconState.GONE

    companion object {
        fun from(parent: ViewGroup): RemindersItemViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = ReminderListItemBinding.inflate(layoutInflater, parent, false)
            return RemindersItemViewHolder(binding)
        }
    }

    fun bind(item: Reminder, clickListener: RemindersClickListener) {
        buttonState = ReminderDeleteIconState.GONE
        binding.reminder = item
        binding.clickListener = clickListener
        binding.executePendingBindings()
    }

    fun storeButtonAndBackRect(cardView: CardView, dX: Float = 0f) {
        backgroundRect.set((cardView.left + cardView.paddingLeft).toFloat(),
            (cardView.top + cardView.paddingTop).toFloat(),
            (cardView.right - cardView.paddingRight).toFloat(),
            (cardView.bottom - cardView.paddingBottom).toFloat())

        buttonRect.set(if (dX < 0f || buttonState == ReminderDeleteIconState.RIGHT_VISIBLE) backgroundRect.right - backgroundRect.height() else backgroundRect.left,
            backgroundRect.top,
            if (dX < 0f || buttonState == ReminderDeleteIconState.RIGHT_VISIBLE) backgroundRect.right else backgroundRect.left + backgroundRect.height(),
            backgroundRect.bottom)
    }
}

/**
 * Adapter for the recycler view
 */
class RemindersAdapter(private val clickListener: RemindersClickListener):
    ListAdapter<Reminder, RemindersItemViewHolder>(RemindersDiffCallback()) {

    override fun onBindViewHolder(holder: RemindersItemViewHolder, position: Int) {
        holder.bind(getItem(position), clickListener)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemindersItemViewHolder {
        return RemindersItemViewHolder.from(parent)
    }
}

/**
 * Callback to allow efficient updates to the list
 * Not needed for such a small list, but it is the recommended way to implement
 */
class RemindersDiffCallback : DiffUtil.ItemCallback<Reminder>() {
    override fun areItemsTheSame(oldItem: Reminder, newItem: Reminder): Boolean {
        return oldItem.reminderId == newItem.reminderId
    }

    override fun areContentsTheSame(oldItem: Reminder, newItem: Reminder): Boolean {
        return oldItem == newItem
    }

}

/**
 * Listen for click on selection and also on delete button
 * The reminders list fragment handles the clicks by calling the view model (above)
 */
class RemindersClickListener(val clickListener: (reminder: Reminder) -> Unit) {
    fun onSelectionClicked(reminder: Reminder) = clickListener(reminder)
}