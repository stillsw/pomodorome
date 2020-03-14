package com.stillwindsoftware.pomodorome

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
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
        private const val PADDED_WIDTH_PERCENT = .035f
        private const val RINGS_WIDTH_PERCENT = .055f
        private const val DIVISIONS_STROKE_WIDTH_PERCENT = .005f
    }

    private var minutesColour = 0
    private var backgroundColour = 0
    private var divisionsColour = 0
    private var divisionsBackgroundColour = 0
    private var thumbColour = 0
    private var ringsWidth = 1f
    private var divisionsStrokeWidth = 1f
    private var paddedWidth = 0f
    private var thumbLength = 0f
    private var thumbWidth = 0f
    private var textBackroundCornerRadius = 0f
    private var totalRadius = 1f
    private var centreX: Float = 0f
    private var centreY: Float = 0f
    private var minutesRingOuterRadius: Float = 0f
    private var minutesRingInnerRadius = 0f
    private var hoursRingInnerRadius = 0f
    private val divisionsPoints = FloatArray(240) // drawn points
    private val minutesThumbPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val drawnThumbPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    @Suppress("MemberVisibilityCanBePrivate")
    var drawnThumbOffset = 0f // animated when moving between minutes/hours
    @Suppress("MemberVisibilityCanBePrivate")
    var drawnThumbDegrees = 0f // animated when moving between minutes/hours
    var acceptInput = true

    var minutes: Int by Delegates.observable(0) { _, _, new ->
        minutesDrawnSweepAngle = new / MINUTES * 360f
        Log.v(LOG_TAG, "minutes.observed: value=$new drawnSweepAngle=$minutesDrawnSweepAngle")
        setDrawnThumbPosition()
        invalidate()
        timeInMillis = (minutes * 60L) * 60L * 1000L

        if (acceptInput) timePickerTextView?.setTime(timeInMillis)
    }

    var timeInMillis = 0L

    private var minutesDrawnSweepAngle = -1f
    private var hoursDrawnSweepAngle = -1f
    private var motionEventsPointer: Int = -1
    private var preMotionValue = -1

    private lateinit var paint: Paint
    private val minutesRingRect = RectF()
    private val hoursRingRect = RectF()
    private val textBackgroundRect = RectF()
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
        minutesColour = typedArray.getColor(R.styleable.TimePickerCircle_minutesColour, resources.getColor(R.color.colorAccent, null))
        backgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_backgroundColour, resources.getColor(android.R.color.white, null))
        divisionsBackgroundColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsBackgroundColour, backgroundColour)
        divisionsColour = typedArray.getColor(R.styleable.TimePickerCircle_divisionsColour, resources.getColor(android.R.color.black, null))
        thumbColour = typedArray.getColor(R.styleable.TimePickerCircle_thumbColour, minutesColour)
        textBackroundCornerRadius = typedArray.getDimension(R.styleable.TimePickerCircle_textBackgroundCornerRadius, 0f)

        typedArray.recycle()

        paint = Paint()

        setOnTouchListener(this)
        outlineProvider = TimePickerCircleOutlineProvider()
    }

    private fun setDrawnThumbPosition() {
        drawnThumbDegrees = minutes * 6f
    }


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val minSize = min(right - left, bottom - top).toFloat()

        paddedWidth = minSize * PADDED_WIDTH_PERCENT
        ringsWidth = minSize * RINGS_WIDTH_PERCENT
        thumbLength = ringsWidth * 2f
        thumbWidth = thumbLength
        divisionsStrokeWidth = max(minSize * DIVISIONS_STROKE_WIDTH_PERCENT, 1f)

        totalRadius = minSize / 2f
        centreX = (right - left) / 2f
        centreY = (bottom - top) / 2f
//        Log.d(LOG_TAG, "onLayout: left=$left, right=$right, centreX=$centreX, top=$top, bottom=$bottom, centreY=$centreY")
        Log.d(LOG_TAG, "onLayout: w=${right-left}, paddedw=$paddedWidth, ringsw=$ringsWidth")
        minutesRingOuterRadius = if (paddedWidth > 0f) totalRadius - paddedWidth else totalRadius
        minutesRingInnerRadius = minutesRingOuterRadius - ringsWidth
        hoursRingInnerRadius = minutesRingInnerRadius - ringsWidth
        minutesRingRect.set(paddedWidth, paddedWidth, right - left - paddedWidth, bottom - top - paddedWidth)
        val secondRectSize = paddedWidth + ringsWidth
        hoursRingRect.set(secondRectSize, secondRectSize, right - left - secondRectSize, bottom - top - secondRectSize)

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

        // 2 thumbs, drawn with rotation so just need 2 lines each
        minutesThumbPath.reset()
        minutesThumbPath.moveTo(centreX + minutesRingInnerRadius, centreY)
        minutesThumbPath.lineTo(centreX + minutesRingInnerRadius - thumbLength, centreY - thumbWidth / 2f)
        minutesThumbPath.lineTo(centreX + minutesRingInnerRadius - thumbLength, centreY + thumbWidth / 2f)

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
        paint.color = backgroundColour
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        canvas.drawCircle(totalRadius, totalRadius, totalRadius, paint)

        // fill for divisions background
        paint.color = divisionsBackgroundColour
        canvas.drawCircle(totalRadius, totalRadius, minutesRingOuterRadius, paint)

        // arc for minutes
        if (minutes > 0) {
            paint.color = minutesColour
            canvas.drawArc(minutesRingRect, 270f, minutesDrawnSweepAngle, true, paint)
            paint.color = divisionsBackgroundColour
            canvas.drawCircle(totalRadius, totalRadius, minutesRingInnerRadius, paint)
        }

        // overdraw inner circle
        paint.color = backgroundColour
        canvas.drawCircle(totalRadius, totalRadius, hoursRingInnerRadius, paint)

        // divisions
        paint.color = divisionsColour
        paint.strokeWidth = divisionsStrokeWidth
        canvas.drawLines(divisionsPoints, paint)

        // thumb affordance
        if (acceptInput) {
            paint.color = thumbColour

            // draw affordance under minutes or hours, but it may be animating between the 2 as well
            if (timePickerTextView != null) {
//todo                val delta = drawnThumbOffset / thumbHoursOffsetX
                val halfTextWidth = timePickerTextView!!.width / 2f
                val halfTextHeight = timePickerTextView!!.height / 2f
                val textBackgroundX = centreX - halfTextWidth //todo * delta
//                Log.d(LOG_TAG, "onDraw: thumb at is delta=$delta from minutes, so textbackX=$textBackgroundX (centre=$centreX, halfW=$halfTextWidth)")
                textBackgroundRect.set(textBackgroundX, centreY - halfTextHeight, textBackgroundX + halfTextWidth, centreY + halfTextHeight)
                canvas.drawRoundRect(textBackgroundRect, textBackroundCornerRadius, textBackroundCornerRadius, paint)
            }

            drawnThumbPath.reset()
            minutesThumbPath.offset(drawnThumbOffset, 0f, drawnThumbPath)
            canvas.save()
            canvas.rotate(270f + drawnThumbDegrees, centreX, centreY)
            canvas.drawPath(drawnThumbPath, paint)
            canvas.restore()
        }
    }

    //todo
    fun setActiveTimerViewModel(activeTimerViewModel: ActiveTimerViewModel) {
        Log.w(LOG_TAG, "setActiveTimerViewModel: called but needs to be used")
    }

    class TimePickerCircleOutlineProvider : ViewOutlineProvider() {
        override fun getOutline(view: View?, outline: Outline?) {
            view ?: return
            outline ?: return
            val shadow = view.context.resources.getDimension(R.dimen.time_picker_background_shadow).toInt()
            // lit more from top, slight shadow to right as well
            outline.setOval(0, 0, view.width+shadow, view.height+shadow/2)
        }

    }
}


class TimePickerTextView : AppCompatTextView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs)
    }

    companion object {
        const val LOG_TAG = "TimePickerTextView"
        // don't need lint check because this isn't a locale based time, it's just to get the hours and minutes layout
        @SuppressLint("SimpleDateFormat")
        internal val TIMER_FORMATTER = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    }

    private var defColour = -1
    private var tickColour = resources.getColor(android.R.color.transparent, null)
    private var timerStateType: TimerStateType? = null

    private fun init(attrs: AttributeSet) {

        // store the default colour of the text
        val defTypedArray = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textColor))
        defColour = defTypedArray.getColor(0, -1)
        defTypedArray.recycle()
    }

    fun setTime(timeInMillis: Long) {
        val newText = TIMER_FORMATTER.format(timeInMillis)
        text = newText
    }

    fun setTick(on: Boolean) {
        if ((on && currentTextColor == defColour) || (!on && currentTextColor == tickColour))
            setTextColor(if (on) tickColour else defColour)
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

}


