package com.stillwindsoftware.pomodorome

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
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
        const val LOG_TAG = "TimePickerCircle"
        const val MINUTES = 60f
        private const val WORK = 0;
        private const val REST = 1;
        private const val PADDED_WIDTH_PERCENT = .038f
        private const val RINGS_WIDTH_PERCENT = .055f
        private const val DIVISIONS_STROKE_WIDTH_PERCENT = .005f
    }

    private var workColour = 0
    private var restColour = 0
    private var backgroundColour = 0
    private var shadowColour = 0
    private var divisionsColour = 0
    private var divisionsBackgroundColour = 0
    private var thumbColour = 0
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
    private val divisionsPoints = FloatArray(240) // drawn points
    private var drawnThumbDegrees = 0f // animated when moving between minutes/hours
    private var currentInput = WORK;
    private var acceptInput = true

    var minutes: Int by Delegates.observable(0) { _, _, new ->
        minutesDrawnSweepAngle = new / MINUTES * 360f
        Log.v(LOG_TAG, "minutes.observed: value=$new drawnSweepAngle=$minutesDrawnSweepAngle")
        setDrawnThumbPosition()
        invalidate()
        timeInMillis = (minutes * 60L) /* 60L */ * 1000L

        if (acceptInput) timePickerTextView?.setTime(timeInMillis)
    }

    private var timeInMillis = 0L

    private var minutesDrawnSweepAngle = -1f
    private var motionEventsPointer: Int = -1
    private var preMotionValue = -1

    private lateinit var paint: Paint
    private val minutesRingRect = RectF()
    private val innnerCircleRect = RectF()
    var timePickerTextView: TimePickerTextView? = null
        set(value) {
//todo
/*
            value?.setOnClickListener {
                if (acceptInput) {
                    Log.d(LOG_TAG, "timePickerTextView.set(): timer text clicked, toggle mins/hours")
                    if (divisions == TimePickerCircle.MINUTES) {
                        divisions = HOURS
                        minutes = minutes
                    } else {
                        divisions = MINUTES
                        hours = hours
                    }

                    // switch thumb to other
                    val offsetVals = PropertyValuesHolder.ofFloat("drawnThumbOffset",
                            getThumbOffsetForDivisions(if (divisions == TimePickerCircle.MINUTES) TimePickerCircle.HOURS else TimePickerCircle.MINUTES),
                            getThumbOffsetForDivisions(if (divisions == TimePickerCircle.MINUTES) TimePickerCircle.MINUTES else TimePickerCircle.HOURS))

                    // from minutes to hours or hours to minutes
                    val sourceDegrees = if (divisions == TimePickerCircle.HOURS) minutes * 6f else hours * 30f
                    val destDegrees = if (divisions == TimePickerCircle.MINUTES) minutes * 6f else hours * 30f
                    val diff = destDegrees - sourceDegrees

                    val degreesVals = if (abs(diff) <= 180f)
                        PropertyValuesHolder.ofFloat("drawnThumbDegrees", sourceDegrees, destDegrees) else
                        PropertyValuesHolder.ofFloat("drawnThumbDegrees",
                                if (sourceDegrees < destDegrees) sourceDegrees + 360f else sourceDegrees,
                                if (sourceDegrees < destDegrees) destDegrees else destDegrees + 360f)
                    ObjectAnimator.ofPropertyValuesHolder(this, offsetVals, degreesVals).apply {
                        duration = 300L
                        addUpdateListener {
                            this@TimePickerCircle.invalidate()
                        }
                        start()
                    }
                }
            }
*/

            field = value
        }

    private fun init(attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TimePickerCircle, 0, 0)
        workColour = typedArray.getColor(R.styleable.TimePickerCircle_workColour, resources.getColor(R.color.colorAccent, null))
        restColour = typedArray.getColor(R.styleable.TimePickerCircle_restColour, resources.getColor(R.color.colorAccent, null))
        backgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_backgroundColour, resources.getColor(android.R.color.white, null))
        shadowColour = typedArray.getColor(R.styleable.TimePickerCircle_shadowColour, backgroundColour)
        divisionsBackgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsBackgroundColour, backgroundColour)
        divisionsColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsColour, resources.getColor(android.R.color.black, null))
        thumbColour = typedArray.getColor(R.styleable.TimePickerCircle_thumbColour, workColour)

        typedArray.recycle()

        paint = Paint()

        setOnTouchListener(this)
    }

    private fun setDrawnThumbPosition() {
        drawnThumbDegrees = minutes * 6f
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
//        Log.d(LOG_TAG, "onLayout: left=$left, right=$right, centreX=$centreX, top=$top, bottom=$bottom, centreY=$centreY")
//        Log.d(LOG_TAG, "onLayout: w=${right-left}, paddedw=$paddedWidth, ringsw=$ringsWidth")
        minutesRingOuterRadius = drawnPickerRadius - paddedWidth
        minutesRingInnerRadius = minutesRingOuterRadius - ringsWidth
        hoursRingInnerRadius = minutesRingInnerRadius - ringsWidth / 2

        // need a rect to be able to draw the arc for minutes
        val minutesRectOffset = totalRadius - minutesRingOuterRadius
        minutesRingRect.set(minutesRectOffset, minutesRectOffset, right - left - minutesRectOffset, bottom - top - minutesRectOffset)
        val innerCircleOffset = minutesRectOffset + ringsWidth * 1.5f
        innnerCircleRect.set(innerCircleOffset, innerCircleOffset, right - left - innerCircleOffset, bottom - top - innerCircleOffset)

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

        // so it sets thumb positions up again correctly
        minutes = minutes
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
                animateValue(preMotionValue)
                Log.v(LOG_TAG, "onTouch: down $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_MOVE -> {
                val newValue = getValueFromAngle(centreRelX, centreRelY)
                if (newValue != minutes) minutes = newValue
                Log.v(LOG_TAG, "onTouch: move $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_UP -> {
                val value = getValueFromAngle(centreRelX, centreRelY)
                animateValue(value)

                Log.v(LOG_TAG, "onTouch: up $centreRelX/$centreRelY")
            }
            MotionEvent.ACTION_CANCEL -> {
                minutes = preMotionValue
                Log.v(LOG_TAG, "onTouch: cancel $centreRelX/$centreRelY")
            }
        }

        invalidate()

        return true
    }

    private fun animateValue(value: Int) {
        if (minutes != value) {
            ObjectAnimator.ofInt(this, "minutes", minutes).apply {
                duration = 300L
                addUpdateListener {
                    Log.v(LOG_TAG, "animateValue: listener $minutes")
                    this@TimePickerCircle.invalidate()
                }
                start()
            }
        }
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

        // arc for minutes
        paint.color = workColour
        // work should fill up the whole circle when 0      //Log.d(LOG_TAG, "onDraw: work=${currentInput == WORK} minutes=$minutes")
        if (currentInput == WORK && minutes == 0) {
            canvas.drawCircle(centreX, centreY, minutesRingOuterRadius, paint)
        }
        else {
            canvas.drawArc(minutesRingRect, 270f, minutesDrawnSweepAngle, true, paint)
        }
        paint.color = divisionsBackgroundColour
        canvas.drawCircle(centreX, centreY, minutesRingInnerRadius, paint)

        // overdraw inner circles
        paint.color = workColour
        canvas.drawArc(innnerCircleRect, 180f, 180f, true, paint)
        paint.color = restColour
        canvas.drawArc(innnerCircleRect, 0f, 180f, true, paint)

        // divisions
        paint.color = divisionsColour
        paint.strokeWidth = divisionsStrokeWidth
        canvas.drawLines(divisionsPoints, paint)

        // thumb affordances
        if (acceptInput) {

            val pointToX = centreY + (minutesRingInnerRadius * cos(toRadians((drawnThumbDegrees - 90f).toDouble())))
            val pointToY = centreX + (minutesRingInnerRadius * sin(toRadians((drawnThumbDegrees - 90f).toDouble())))

            val thumbX = centreY + ((hoursRingInnerRadius - thumbSize / 1.5f) * cos(toRadians((drawnThumbDegrees - 90f).toDouble())))
            val thumbY = centreX + ((hoursRingInnerRadius - thumbSize / 1.5f) * sin(toRadians((drawnThumbDegrees - 90f).toDouble())))

            canvas.drawLine(thumbX.toFloat(), thumbY.toFloat(), pointToX.toFloat(), pointToY.toFloat(), paint)

            paint.color = workColour

            paint.strokeWidth = divisionsStrokeWidth * 3f
            canvas.drawCircle(thumbX.toFloat(), thumbY.toFloat(), thumbSize / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = backgroundColour
            canvas.drawCircle(thumbX.toFloat(), thumbY.toFloat(), thumbSize / 2f, paint)
        }
    }

    //todo
    fun setActiveTimerViewModel(activeTimerViewModel: ActiveTimerViewModel) {
        activeTimerViewModel.timer.value?.run { Log.w(LOG_TAG, "setActiveTimerViewModel: got a timer") }
            ?: Log.w(LOG_TAG, "setActiveTimerViewModel: set but no timer yet")
    }
}


class TimePickerTextView : TimerStatefulTextView, TimerStatefulView {
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

    fun isWorkTextTimer() = this.id == R.id.work_time

    fun setTime(timeInMillis: Long) {

        // work timer should show as 60 in the case of user putting it back to 0, more natural that way
        val newText = if (timeInMillis == 0L && isWorkTextTimer()) {"60:00"} else TIMER_FORMATTER.format(timeInMillis)
//        Log.d(LOG_TAG, "setTime: $newText")

        text = if (boldTextBeginAtChar != -1 && newText.isNotEmpty()) {
            val spannable = SpannableString(newText)

            if (boldTextBeginAtChar < newText.length) {
//                Log.v(LOG_TAG, "setTime: spanning text $newText bold starts $boldTextBeginAtChar end ${if (boldTextEndAtChar == -1) newText.length else min(boldTextEndAtChar, newText.length)} (endchar=$boldTextEndAtChar, len=${newText.length})")
                spannable.setSpan(boldTypefaceSpan, boldTextBeginAtChar, if (boldTextEndAtChar == -1) newText.length else min(boldTextEndAtChar, newText.length), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            spannable
        }
        else {
            newText
        }
    }

    fun setInputMinutesOrHours(divisions: Float) {
        if (divisions == TimePickerCircle.MINUTES) {
             boldTextBeginAtChar = 3
             boldTextEndAtChar = -1
        } else {
             boldTextBeginAtChar = 0
             boldTextEndAtChar = 2
        }
    }

    fun setTick(on: Boolean) {
        if ((on && currentTextColor == defColour) || (!on && currentTextColor == tickColour))
            setTextColor(if (on) tickColour else defColour)
    }
}

interface TimerStatefulView {
    fun testActiveTimerStates(activeTimer: ActiveTimer?)
    fun clearActiveTimerStates()
}

open class TimerStatefulTextView : AppCompatTextView, TimerStatefulView {
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

    override fun testActiveTimerStates(activeTimer: ActiveTimer?) {
        if (timerStateType != activeTimer?.timerState) {
            timerStateType = activeTimer?.timerState
            refreshDrawableState()
        }
    }

    override fun clearActiveTimerStates() {
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