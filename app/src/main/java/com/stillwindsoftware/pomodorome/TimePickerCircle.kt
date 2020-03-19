package com.stillwindsoftware.pomodorome

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Observer
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.TimerStateType
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import java.lang.Math.toDegrees
import java.lang.Math.toRadians
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlin.properties.Delegates

/**
 * Adaptation of the view class first made for StoveMultiTimer
 * There's no need for things like hiding the keyboard here as the interface is just for ticking over the timer
 *
 * Other views which form part of this are below
 */
class TimePickerCircle : AppCompatImageView, View.OnTouchListener{

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs)
    }

    companion object {
        private const val LOG_TAG = "TimePickerCircle"
        private const val MINUTES = 60f
        const val WORK = 0
        const val REST = 1
        private const val FULL_CIRCLE = 360f
        private const val QUARTER_CIRCLE = 90f
        private const val TWELVE_OCLOCK = 270f
        private const val PADDED_WIDTH_PERCENT = .038f
        private const val RINGS_WIDTH_PERCENT = .055f
        private const val DIVISIONS_STROKE_WIDTH_PERCENT = .005f
        private const val DEGREES_PER_MINUTE = 6f

        // subscripts into the floats array for storing drawing points for the thumbs
        private const val THUMB_DEGREES = 0
        private const val THUMB_X = 1
        private const val THUMB_Y = 2
        private const val THUMB_POINT_TO_X = 3
        private const val THUMB_POINT_TO_Y = 4
    }

    private var activity: AppCompatActivity? = null
    private var backgroundColour = 0
    private var bezelColour = 0
    private var shadowColour = 0
    private var divisionsColour = 0
    private var divisionsBackgroundColour = 0
    private var ringsWidth = 1f
    private var divisionsStrokeWidth = 1f
    private var shadow = 0
    private var paddedWidth = 0f
    private var thumbSize = 0f
    private var thumbWidth = 0f
    private var totalRadius = 1f
    private var drawnPickerRadius = 1f
    private var centreX: Float = 0f
    private var centreY: Float = 0f
    private var minutesRingOuterRadius: Float = 0f
    private var minutesRingInnerRadius = 0f
    private var hoursRingInnerRadius = 0f
    private val divisionsPoints = FloatArray(240) // drawn points for lines at the minutes
    private var currentInput = WORK
    private var acceptInput = true
    private var motionEventsPointer: Int = -1
    private var preMotionValue = -1
    private lateinit var paint: Paint
    private val minutesRingRect = RectF()
    private val innerCircleRect = RectF()

    inner class TimerWidget { // inner class can access outer class's members
        private var timeInMillis = 0L
        var minutesDrawnSweepAngle = -1f
        var minutesDrawnSweepAngleStart = TWELVE_OCLOCK // only rest resets this
        var colour = 0
        val thumbFloats = floatArrayOf(0f, 0f, 0f, 0f, 0f)

        var minutes: Int by Delegates.observable(0) { _, _, new ->

            val isWorkTime = timerWidgets.indexOf(this) == WORK

            calcSweepAngles(new)
            calcThumbDegrees(new, isWorkTime)

            timeInMillis = (new * 60L) * 1000L
            if (acceptInput) timePickerTextView?.setTime(timeInMillis)

            Log.v(LOG_TAG, "minutes.observed: value=$new drawnSweepAngle=$minutesDrawnSweepAngle for work=$isWorkTime")
            invalidate() // cause a redraw
        }

        var timePickerTextView: TimePickerTextView? = null

        /**
         * For rest time have to take into account the work time.
         * Doing it here is better than re-calculating in onDraw()
         */
        private fun calcSweepAngles(newMins: Int) {
            minutesDrawnSweepAngle = newMins / MINUTES * FULL_CIRCLE

            // start of rest changes depending on work, for safety always re-calc here
            timerWidgets[REST].minutesDrawnSweepAngleStart = TWELVE_OCLOCK + timerWidgets[WORK].minutesDrawnSweepAngle
        }

        /**
         * Like sweep angle, for rest time have to take into account the work time
         * but this has extra complication, thumbs may want to overlap, so both may have
         * adjust position to accommodate the other
         */
        private fun calcThumbDegrees(newMins: Int, isWorkTime: Boolean) {
            thumbFloats[THUMB_DEGREES] = newMins * DEGREES_PER_MINUTE

            if (isWorkTime) {    // calc own degrees, but then also recurse for rest
                thumbFloats[THUMB_DEGREES] += -QUARTER_CIRCLE
                timerWidgets[REST].calcThumbDegrees(timerWidgets[REST].minutes, false)
            }
            else {
                thumbFloats[THUMB_DEGREES] += timerWidgets[WORK].thumbFloats[THUMB_DEGREES]
            }

            val radians = toRadians(thumbFloats[THUMB_DEGREES].toDouble()).toFloat()
            thumbFloats[THUMB_POINT_TO_X] = centreY + (minutesRingInnerRadius * cos(radians))
            thumbFloats[THUMB_POINT_TO_Y] = centreX + (minutesRingInnerRadius * sin(radians))

            thumbFloats[THUMB_X] = centreY + ((hoursRingInnerRadius - thumbSize / 1.5f) * cos(radians))
            thumbFloats[THUMB_Y] = centreX + ((hoursRingInnerRadius - thumbSize / 1.5f) * sin(radians))
        }
    }

    val timerWidgets = arrayOf(TimerWidget(), TimerWidget())

    private fun init(attrs: AttributeSet) {
        getActivity()
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TimePickerCircle, 0, 0)
        timerWidgets[WORK].colour = typedArray.getColor(R.styleable.TimePickerCircle_workColour, resources.getColor(R.color.colorAccent, null))
        timerWidgets[REST].colour = typedArray.getColor(R.styleable.TimePickerCircle_restColour, resources.getColor(R.color.colorAccent, null))
        backgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_backgroundColour, resources.getColor(android.R.color.white, null))
        shadowColour = typedArray.getColor(R.styleable.TimePickerCircle_shadowColour, backgroundColour)
        divisionsBackgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsBackgroundColour, backgroundColour)
        divisionsColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsColour, resources.getColor(android.R.color.black, null))
        bezelColour = typedArray.getColor(R.styleable.TimePickerCircle_bezelColour, resources.getColor(android.R.color.black, null))

        typedArray.recycle()

        paint = Paint()

        setOnTouchListener(this)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val minSize = min(right - left, bottom - top).toFloat()

        shadow = resources.getDimension(R.dimen.time_picker_background_shadow).toInt()
        paddedWidth = minSize * PADDED_WIDTH_PERCENT
        ringsWidth = minSize * RINGS_WIDTH_PERCENT
        thumbSize = ringsWidth * 2f
        thumbWidth = thumbSize
        divisionsStrokeWidth = max(minSize * DIVISIONS_STROKE_WIDTH_PERCENT, 1f)

        totalRadius = minSize / 2f
        drawnPickerRadius = totalRadius - paddedWidth * 1.6f

        centreX = (right - left) / 2f
        centreY = (bottom - top) / 2f

        minutesRingOuterRadius = drawnPickerRadius - paddedWidth
        minutesRingInnerRadius = minutesRingOuterRadius - ringsWidth
        hoursRingInnerRadius = minutesRingInnerRadius - ringsWidth / 2

        // need a rect to be able to draw the arc for minutes
        val minutesRectOffset = totalRadius - minutesRingOuterRadius
        minutesRingRect.set(minutesRectOffset, minutesRectOffset, right - left - minutesRectOffset, bottom - top - minutesRectOffset)
        val innerCircleOffset = minutesRectOffset + ringsWidth * 1.5f
        innerCircleRect.set(innerCircleOffset, innerCircleOffset, right - left - innerCircleOffset, bottom - top - innerCircleOffset)

        // setup the points for the minutes/hours drawn lines
        var i = 0
        for (minute in 0 .. 59) {
            val angle = minute / MINUTES * 360f
            val rads = toRadians((angle + 270).toDouble())
            val outerCx = centreX + (cos(rads) * minutesRingOuterRadius).toFloat()
            val outerCy = centreY + (sin(rads) * minutesRingOuterRadius).toFloat()
            val innerCx = centreX + (cos(rads) * if (minute % 5 == 0) hoursRingInnerRadius else minutesRingInnerRadius).toFloat()
            val innerCy = centreY + (sin(rads) * if (minute % 5 == 0) hoursRingInnerRadius else minutesRingInnerRadius).toFloat()
            divisionsPoints[i++] = outerCx
            divisionsPoints[i++] = outerCy
            divisionsPoints[i++] = innerCx
            divisionsPoints[i++] = innerCy
        }
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {

        if (event == null || !acceptInput) return false

        if (event.action == MotionEvent.ACTION_DOWN) {
            motionEventsPointer = event.getPointerId(0)
        }

        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_UP) {
            val pointerIndex = event.action and MotionEvent.ACTION_POINTER_INDEX_MASK shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
            val pointerId = event.getPointerId(pointerIndex)

            // ignore it as it wasn't the pointer that went down first and flag
            // indicates only interested in first pointer down
            if (pointerId != motionEventsPointer) {
                return true
            }
        }

        // event pos is relative to top/left
        val centreRelX = event.x - totalRadius
        val centreRelY = event.y - totalRadius
        if (centreRelX * centreRelX + centreRelY * centreRelY > totalRadius * totalRadius) {
            Log.v(LOG_TAG, "onTouch: outside $centreRelX/$centreRelY")
            return true
        }

        when (event.action and MotionEvent.ACTION_MASK) {

            MotionEvent.ACTION_DOWN -> {
                preMotionValue = getValueFromAngle(centreRelX, centreRelY)

                //todo decide which timer is appropriate to activate

                Log.v(LOG_TAG, "onTouch: down $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_MOVE -> {
                val newValue = getValueFromAngle(centreRelX, centreRelY)
                if (newValue != timerWidgets[currentInput].minutes) timerWidgets[currentInput].minutes = newValue
                Log.v(LOG_TAG, "onTouch: move $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_UP -> {
                val value = getValueFromAngle(centreRelX, centreRelY)

                //todo update the view model here?

                Log.v(LOG_TAG, "onTouch: up $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_CANCEL -> {
                timerWidgets[WORK].minutes = preMotionValue
                Log.v(LOG_TAG, "onTouch: cancel $centreRelX/$centreRelY")
            }
        }

        invalidate()

        return true
    }

    private fun getValueFromAngle(centreRelX: Float, centreRelY: Float): Int {
        val deg = toDegrees(abs(atan2(centreRelX, centreRelY) - PI)).toFloat()
        val newValue = ((deg / 360) * MINUTES).toInt()
        Log.v(LOG_TAG, "getValueFromAngle: degrees=$deg gives value=$newValue")
        return newValue
    }

    override fun onDraw(canvas: Canvas?) {
        canvas ?: return

        // fill to complete background
        paint.color = shadowColour
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        // shadow off to the bottom/right, but a little smaller than actual size or it'll be cut off
        canvas.drawCircle(centreX + shadow, centreY + shadow * 2f, totalRadius - shadow * 4f, paint)

        paint.color = backgroundColour
        // a little less than full width so the bezel is visible underneath
        canvas.drawCircle(centreX, centreY, drawnPickerRadius, paint)

        // fill for divisions background
        paint.color = divisionsBackgroundColour
        canvas.drawCircle(centreX, centreY, minutesRingOuterRadius, paint)

        // arc for minutes for each timer

        for ((index, timeSetting) in timerWidgets.withIndex()) {

            paint.color = timeSetting.colour

            // work should fill up the whole circle when 0      //Log.d(LOG_TAG, "onDraw: work=${currentInput == WORK} minutes=$minutes")
            if (index == WORK && timerWidgets[WORK].minutes == 0) {
                canvas.drawCircle(centreX, centreY, minutesRingOuterRadius, paint)
            }
            else {
                canvas.drawArc(
                    minutesRingRect,
                    timeSetting.minutesDrawnSweepAngleStart,
                    timeSetting.minutesDrawnSweepAngle,
                    true, paint
                )
            }
            paint.color = divisionsBackgroundColour
            canvas.drawCircle(centreX, centreY, minutesRingInnerRadius, paint)
        }

        // overdraw inner circles
        paint.color = timerWidgets[WORK].colour
        canvas.drawArc(innerCircleRect, 180f, 180f, true, paint)
        paint.color = timerWidgets[REST].colour
        canvas.drawArc(innerCircleRect, 0f, 180f, true, paint)

        // divisions
        paint.color = divisionsColour
        paint.strokeWidth = divisionsStrokeWidth
        canvas.drawLines(divisionsPoints, paint)

        // thumbs for each timer

        if (acceptInput) {
            for (timerWidget in timerWidgets) {

                // a line from the thumb to where the end of the time setting is (for when the thumb can't be right on it)

                paint.color = divisionsColour
                paint.strokeWidth = divisionsStrokeWidth

                canvas.drawLine(timerWidget.thumbFloats[THUMB_X], timerWidget.thumbFloats[THUMB_Y], timerWidget.thumbFloats[THUMB_POINT_TO_X], timerWidget.thumbFloats[THUMB_POINT_TO_Y], paint)

                // fill circle

                paint.style = Paint.Style.FILL
                paint.color = timerWidget.colour

                canvas.drawCircle(timerWidget.thumbFloats[THUMB_X], timerWidget.thumbFloats[THUMB_Y], thumbSize / 2f, paint)

                // outline

                paint.strokeWidth = divisionsStrokeWidth * 3f
                paint.style = Paint.Style.STROKE
                paint.color = bezelColour
                canvas.drawCircle(timerWidget.thumbFloats[THUMB_X], timerWidget.thumbFloats[THUMB_Y], thumbSize / 2f, paint)
            }
        }
    }

    fun setActiveTimerViewModel(activeTimerViewModel: ActiveTimerViewModel) {
        activeTimerViewModel.timer.observe(activity!!, Observer {
            timer ->
            Log.w(LOG_TAG, "setActiveTimerViewModel: got a timer $timer")
            timerWidgets[WORK].minutes = (timer.pomodoroDuration / 60 / 1000).toInt()
            timerWidgets[REST].minutes = (timer.restDuration / 60 / 1000).toInt()
        })
    }

    /**
     * Need the activity to set up the observer, it's a bit messy but works
     */
    private fun getActivity(): AppCompatActivity? {

        if (activity != null) return activity

        var localContext = context
        while (localContext is ContextWrapper) {
            if (localContext is AppCompatActivity) {
                activity = localContext
                break
            }
            localContext = localContext.baseContext
        }

        return activity
    }

}


class TimePickerTextView : TimerStatefulTextView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs)
    }

    companion object {
        const val LOG_TAG = "TimePickerText"
        @SuppressLint("SimpleDateFormat")
        internal val TIMER_FORMATTER = SimpleDateFormat("mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    }

    private var isMixedTypeface = false
    private var boldTypefaceSpan: CustomTypefaceSpan? = null
    private var boldTextBeginAtChar = -1
    private var boldTextEndAtChar = -1
    private var defColour = -1
    private var tickColour = resources.getColor(android.R.color.transparent, null)

    private fun init(attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TimePickerTextView, 0, 0)
        isMixedTypeface = typedArray.getBoolean(R.styleable.TimePickerTextView_supportPartBoldFontStyle, false)
        boldTextBeginAtChar = typedArray.getInteger(R.styleable.TimePickerTextView_boldSpanBeginChar, -1)
        boldTextEndAtChar = typedArray.getInteger(R.styleable.TimePickerTextView_boldSpanEndChar, -1)
        typedArray.recycle()

        if (isMixedTypeface) {
            val defTypedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.fontFamily))
            val fontFamily = defTypedArray.getString(0) ?: "sans-serif"
            defTypedArray.recycle()

            Log.d(LOG_TAG, "init: isMixedTypeFace font family=$fontFamily (text=$text)")

            boldTypefaceSpan = CustomTypefaceSpan(fontFamily, Typeface.create(fontFamily, Typeface.BOLD))
        }

        // store the default colour of the text
        val defTypedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textColor))
        defColour = defTypedArray.getColor(0, -1)
        defTypedArray.recycle()
    }

    private fun isWorkTextTimer() = this.id == R.id.work_time

    fun setTime(timeInMillis: Long) {

        // work timer should show as 60 in the case of user putting it back to 0, more natural that way
        val newText = if (timeInMillis == 0L && isWorkTextTimer()) {"60:00"} else TIMER_FORMATTER.format(timeInMillis)
//        Log.d(LOG_TAG, "setTime: $newText")

        text = if (boldTextBeginAtChar != -1 && newText.isNotEmpty()) {
            val spannable = SpannableString(newText)

            if (boldTextBeginAtChar < newText.length) {
                spannable.setSpan(boldTypefaceSpan, boldTextBeginAtChar, if (boldTextEndAtChar == -1) newText.length else min(boldTextEndAtChar, newText.length), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            spannable
        }
        else {
            newText
        }
    }

    fun setTick(on: Boolean) {
        if ((on && currentTextColor == defColour) || (!on && currentTextColor == tickColour))
            setTextColor(if (on) tickColour else defColour)
    }
}

open class TimerStatefulTextView : AppCompatTextView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    companion object {
        @Suppress("unused")
        const val LOG_TAG = "TimerStateText"
    }

    private var timerStateType: TimerStateType? = null

    override fun onCreateDrawableState(extraSpace: Int): IntArray {

        return if (timerStateType != null) {
            val state = super.onCreateDrawableState(extraSpace + 1)
            View.mergeDrawableStates(state, timerStateType?.styledAttributeName)
            state
        }
        else {
            super.onCreateDrawableState(extraSpace)
        }
    }

    fun testActiveTimerStates(activeTimer: ActiveTimer?) {
        if (timerStateType != activeTimer?.timerState) {
            timerStateType = activeTimer?.timerState
            refreshDrawableState()
        }
    }

    fun clearActiveTimerStates() {
        timerStateType = null
        refreshDrawableState()
    }
}

class CustomTypefaceSpan(family: String, private val newType: Typeface) : TypefaceSpan(family) {

    override fun updateDrawState(ds: TextPaint) {
        applyCustomTypeFace(ds, newType)
    }

    override fun updateMeasureState(paint: TextPaint) {
        applyCustomTypeFace(paint, newType)
    }

    private fun applyCustomTypeFace(paint: Paint, tf: Typeface) {
        val oldStyle: Int
        val old = paint.typeface
        oldStyle = old?.style ?: 0

        val fake = oldStyle and tf.style.inv()
        if (fake and Typeface.BOLD != 0) {
            paint.isFakeBoldText = true
        }

        paint.typeface = tf
    }
}