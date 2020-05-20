package com.stillwindsoftware.pomodorome

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.get
import androidx.core.view.size
import androidx.databinding.DataBindingUtil
import androidx.emoji.widget.EmojiAppCompatEditText
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.stillwindsoftware.pomodorome.RemindersListFragment.Companion.LOG_TAG
import com.stillwindsoftware.pomodorome.databinding.RemindersListBinding
import com.stillwindsoftware.pomodorome.viewmodels.*
import kotlinx.android.synthetic.main.reminders_list.*
import kotlin.math.abs
import kotlin.math.min

/**
 * List of Reminders.
 */
class RemindersListFragment : DialogFragment() {

    companion object {
        const val LOG_TAG = "RemindersListFragment"
    }

    val viewModel by lazy { ViewModelProvider(this)[RemindersViewModel::class.java] }

    // default dialog dimensions aren't satisfactory, the height is either match parent which always fill the screen regardless of how many
    // rows, which is ugly if there are few, or wrap parent which works fine if there are less than the amount to fill the screen, but then
    // shows nothing at all when it goes over that, which is worse still
    // instead in onViewCreated() addOnLayoutChangeListener determines the exact size to make the dialog

    private var isSingleRowHeightConfirmed = false
    private var singleReminderRowHeight = 0
    private var maxDialogHeight = 0
    private var maxRecyclerSpace = 0 // set first time layout changes, since begin with match parent, this dictates the min that should always be allowed for

    /**
     * Set the style to show a title
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_Alert)
    }

    /**
     * Inflates the view from the data binding and ties in the recycler view adapter
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog?.setTitle(getTitleResId())

        (DataBindingUtil.inflate(LayoutInflater.from(context), R.layout.reminders_list, container, true) as RemindersListBinding)
            .apply {
                lifecycleOwner = this@RemindersListFragment
                viewmodel = viewModel
                recyclerView.adapter = RemindersAdapter(RemindersClickListener { reminder ->
                    viewModel.toggleSelection(reminder = reminder)
                })

                viewModel.repository.reminders.observe(requireActivity(), Observer {
                    it?.let { (recyclerView.adapter as RemindersAdapter).submitList(it) }
                })

                okButton.setOnClickListener {
                    dismiss()
                }

                addButton.setOnClickListener {
                    showAddNewReminderDialog()
                }

                // add gesture detection to the list for swipe and reveal delete icon

                SwipeToRevealCallback(requireContext(), this@RemindersListFragment).also {
                    ItemTouchHelper(it).apply { attachToRecyclerView(recyclerView) }
                }

                return root
            }
    }

    /**
     * Used to find the title text view's value as well as to set it, so make sure they're in step
     */
    private fun getTitleResId(): Int {
        return R.string.reminders_title
    }

    /**
     * Adjust layout, default is too narrow when portrait
     */
    override fun onStart() {
        super.onStart()
        windowForListSize(WindowManager.LayoutParams.MATCH_PARENT)
    }

    /**
     * Called on start to set the layout to match parent
     * thereafter whenever the size of the recycler isn't optimal (too small and there's space available to use or too big)
     */
    private fun windowForListSize(height: Int) {

        Log.d(LOG_TAG, "resetWindowForListSize: recycler rows=${recycler_view.size} height=${recycler_view.height} set to match parent")


            resources.displayMetrics.also {
                dialog?.window?.setLayout(it.widthPixels / 7 * if (it.widthPixels < it.heightPixels) 6 else 4, height)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // want the dialog to be just the right size to display the reminders, not bigger
        // detect how many reminders expect to fit on a screen and if it's not showing any
        // it's because the view has shrunk (it happens when layout is wrap_content but there
        // are more rows than can be displayed)

        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->

            viewModel.repository.reminders.value?.let {rems ->

                // if the ok button has no height, then nothing is laid out yet, can't do anything

                if (ok_button.height != 0) {

                    // first time (so height = match parent), init the space required by the system around the dialog
                    if (maxRecyclerSpace == 0) {
                        dialog?.window?.decorView?.let {
                            maxDialogHeight = it.height
                            maxRecyclerSpace = recycler_view.height
                            Log.v(LOG_TAG, "addOnLayoutChangeListener: init dimens window=${resources.displayMetrics.heightPixels} dialog=${it.height} recycler max=$maxRecyclerSpace")
                        }
                    }

                    // need to init the size of a single row, there's a tricky bit to this though
                    // when there's no rows in the recycler because came into the screen empty, there'll be nothing to measure against
                    // so make a default row size to something similar... the ok-button, it then can be corrected when it comes back
                    // through a 2nd time

                    if (!isSingleRowHeightConfirmed) {
                        if (rems.isNotEmpty()) { // get the row height
                            if (recycler_view.size > 0) {
                                singleReminderRowHeight = recycler_view[0].height
                                isSingleRowHeightConfirmed = true
                                Log.v(LOG_TAG, "addOnLayoutChangeListener: recycler row height = $singleReminderRowHeight and confirmed ")
                            }
                            else {
                                singleReminderRowHeight = ok_button.height
                                Log.v(LOG_TAG, "addOnLayoutChangeListener: recycler row height = $singleReminderRowHeight temporarily")
                            }
                        }
                    }

                    val optimalHeightForRows = singleReminderRowHeight * rems.size

                    if (optimalHeightForRows == recycler_view.height) {
                        Log.v(LOG_TAG, "addOnLayoutChangeListener: recycler view is exactly the right height (${recycler_view.height}), nothing to do (rems=${rems.size})")
                    }
                    else {
                        val newWindowSize = maxDialogHeight - maxRecyclerSpace + min(optimalHeightForRows, maxRecyclerSpace)
                        Log.d(LOG_TAG, "addOnLayoutChangeListener: recycler view is adjusted=${min(optimalHeightForRows, maxRecyclerSpace)}")
                        windowForListSize(newWindowSize)
                    }
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply { setCanceledOnTouchOutside(false) }
    }

    /**
     * New reminder is a emoji edit text with a text length indicator to show how much room is left
     */
    @SuppressLint("SetTextI18n")
    private fun showAddNewReminderDialog() {
        (LayoutInflater.from(requireContext()).inflate(R.layout.add_new_reminder, view as ViewGroup, false) as ConstraintLayout)
            .also { viewGroup ->

                AlertDialog.Builder(requireContext())
                    .setView(viewGroup)
                    .create()

                    .apply {
                        setCanceledOnTouchOutside(false) // make buttons only way to get out (or back)
                        setOnDismissListener {

                            // hide the keyboard shown automatically

                            (requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .apply { toggleSoftInput(0, 0) /*hideSoftInputFromWindow(viewGroup.windowToken, 0)*/ }
                        }

                        // attach a TextWatcher to display how many chars have been filled from maximum allowed

                        viewGroup.findViewById<EmojiAppCompatEditText>(R.id.text)
                            .also { reminderText ->
                                setButton(DialogInterface.BUTTON_POSITIVE, getString(android.R.string.ok)) { _,_ ->
                                    validateAndSave(reminderText.text.toString())
                                }

                                viewGroup.findViewById<TextView>(R.id.textLenIndicator)
                                    .also {
                                        val maxLength = resources.getInteger(R.integer.maxReminderLen)
                                        it.text = "0/$maxLength"

                                        reminderText.addTextChangedListener(object: TextWatcher {
                                            override fun afterTextChanged(s: Editable?) {
                                                it.text = "${s?.length}/$maxLength"
                                            }

                                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                        })
                                    }
                            }

                        setButton(DialogInterface.BUTTON_NEGATIVE, getString(android.R.string.cancel)) { _,_ ->
                            dismiss()
                        }

                        show()

                        // have the keyboard show automatically

                        (requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .apply { toggleSoftInput(InputMethodManager.SHOW_FORCED, 0) }
                    }
            }

    }

    private fun validateAndSave(text: String) {
        if (text.isNotEmpty()) {
            viewModel.addReminder(text)
        }
    }

}

/**
 * Thanks to Zachery Osborn, post shows how to swipe to delete
 * https://medium.com/@zackcosborn/step-by-step-recyclerview-swipe-to-delete-and-undo-7bbae1fce27e
 */
class SwipeToRevealCallback(val context: Context, private val fragment: RemindersListFragment)
    : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)!!
    private val background = ColorDrawable(Color.RED)

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

    /**
     * A full swipe removes the reminder
     */
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        deleteReminderWithHolder(viewHolder as RemindersItemViewHolder)
    }

    /**
     * Called by swiping and by delete button, although that won't probably survive
     */
    private fun deleteReminderWithHolder(viewHolder: RemindersItemViewHolder) {

        with(viewHolder.binding.reminder!!) {
            // delete in the db, it'll take a while to propagate
            fragment.viewModel.delete(this)

            // show snack bar for undo
            Snackbar.make(fragment.requireView().findViewById<ConstraintLayout>(R.id.layout),
                R.string.delete_reminder, 5000) // show it for a longer fixed time than LENGTH_LONG
                .apply {
                    setAction(R.string.undo_delete_reminder) { fragment.viewModel.addReminder(this@with.text) }.show()
                }
        }
    }

    private fun drawDeleteIcon(canvas: Canvas, viewHolder: RemindersItemViewHolder) {

        val iconMargin = (viewHolder.buttonRect.height() - icon.intrinsicHeight) / 2
        val iconTop = viewHolder.buttonRect.top + iconMargin
        val iconBottom = viewHolder.buttonRect.bottom - iconMargin
        val iconLeft = viewHolder.buttonRect.left + iconMargin
        val iconRight = viewHolder.buttonRect.right - iconMargin
        icon.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())

        icon.draw(canvas)
    }

    /**
     * Called when swiping and preserved after the view is swiped away
     * so draw the background, and also a delete icon otherwise
     * the one drawn in onDraw() will be drawn over
     */
    override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

        val itemView = viewHolder.itemView as CardView
        (viewHolder as RemindersItemViewHolder).storeButtonAndBackRect(itemView, dX)

        when {
            dX > 0 -> { // Swiping to the right
                background.setBounds(viewHolder.backgroundRect.left.toInt(), viewHolder.backgroundRect.top.toInt(), (viewHolder.backgroundRect.left + dX).toInt(), viewHolder.backgroundRect.bottom.toInt())
            }
            dX < 0 -> { // Swiping to the left
                background.setBounds((viewHolder.backgroundRect.right + dX.toInt()).toInt(), viewHolder.backgroundRect.top.toInt(), viewHolder.backgroundRect.right.toInt(), viewHolder.backgroundRect.bottom.toInt())
            }
            else -> { // view is unSwiped
                background.setBounds(0, 0, 0, 0)
            }
        }

        background.draw(c)
        drawDeleteIcon(c, viewHolder)

        if (abs(dX) < viewHolder.buttonRect.width() && viewHolder.buttonState != ReminderDeleteIconState.GONE) {
            Log.d(LOG_TAG, "onChildDraw: dx is less than button width ${viewHolder.binding.reminder?.text}")
        }
    }
}

@Suppress("unused")
internal enum class ReminderDeleteIconState {
    GONE, LEFT_VISIBLE, RIGHT_VISIBLE
}

