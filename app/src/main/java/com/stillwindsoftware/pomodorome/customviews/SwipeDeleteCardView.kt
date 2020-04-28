package com.stillwindsoftware.pomodorome.customviews

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import androidx.cardview.widget.CardView

class SwipeDeleteCardView : CardView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    companion object {
        private const val LOG_TAG = "SwipeDeleteCardView"
    }

    override fun onDraw(canvas: Canvas?) {
        //if (translationX != 0f)
//            Log.d(LOG_TAG, "onDraw: x/y=${x}/$y")
        super.onDraw(canvas)
    }
}