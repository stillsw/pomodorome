package com.stillwindsoftware.pomodorome.viewmodels

import android.view.View
import androidx.databinding.BindingAdapter

/**
 * See databinding codelab how to create adapters
 * https://codelabs.developers.google.com/codelabs/android-databinding
 */
@BindingAdapter("app:hideIfZero")
fun hideIfZero(view: View, number: Long) {
    view.visibility = if (number == 0L) View.GONE else View.VISIBLE
}