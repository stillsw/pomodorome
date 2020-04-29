package com.stillwindsoftware.pomodorome.viewmodels

import android.view.View
import androidx.databinding.BindingAdapter

/**
 * See databinding codelab how to create adapters
 * https://codelabs.developers.google.com/codelabs/android-databinding
 *
 * I think the explanation of use is better in this codelab though:
 * https://codelabs.developers.google.com/codelabs/kotlin-android-training-diffutil-databinding/#6
 *
 * This one isn't now used, and using it actually caused build version errors between tools
 */
//@BindingAdapter("app:hideIfZero")
//fun hideIfZero(view: View, number: Long) {
//    view.visibility = if (number == 0L) View.INVISIBLE else View.VISIBLE
//}