package com.stillwindsoftware.pomodorome

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
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
import androidx.lifecycle.Observer
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase.Companion.ONE_MINUTE
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
 * There's no need for things like hiding the keyboard here as the widgit is
 * just for visually editing the timers and for ticking them over
 *
 * Supporting classes which form part of this are underneath
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
        private const val TWELVE_O_CLOCK = 270f
        private const val PADDED_WIDTH_PERCENT = .04f
        private const val RINGS_WIDTH_PERCENT = .055f
        private const val DIVISIONS_STROKE_WIDTH_PERCENT = .005f
        private const val DEGREES_PER_MINUTE = 6f
    }

    private var activity: MainActivity? = null
    private var backgroundColour = 0
    private var bezelColour = 0
    private var thumbShineColour = 0
    private var divisionsColour = 0
    private var divisionsBackgroundColour = 0
    private var ringsWidth = 1f
    private var divisionsStrokeWidth = 1f
    private var shadowWidth = 0f
    private var paddedWidth = 0f
    private var thumbSize = 0f
    private var thumbRadius = 0f
    private var totalRadius = 1f
    private var drawnPickerRadius = 1f
    private var minutesRingOuterRadius = 0f
    private var minutesRingInnerRadius = 0f
    private var hoursRingInnerRadius = 0f
    private var thumbRingRadius = 0f
    private var thumbRingCircumference = 0f
    private var thumbOverlapDegreesShift = 0f     // the amount to shift thumbs on their ring around the circle when overlapping
    private val divisionsPoints = FloatArray(240) // drawn points for lines at the minutes
    private var currentInput = WORK
    private var acceptInput = true
    private var motionEventsPointer = -1
    private var minutesOnTouchDown = -1
    private val minutesRingRect = RectF()
    private val innerCircleRect = RectF()
    private var centrePoint = PointF()
    private val paint: Paint = Paint(ANTI_ALIAS_FLAG)
    private lateinit var clockBackgrd: Drawable
    // accessed from MainActivity
    internal val timerWidgets = arrayOf(TimerWidget(), TimerWidget())

    // Data binding calls this, but it can be before or after onSizeChanged()
    // see comments on beginObservingViewModel()
    var activeTimerViewModel: ActiveTimerViewModel? = null
        set(value) {
            if (centrePoint.x != 0f) {
                trackViewModel()
            }
            field = value
        }

    //private val tempOverlapPointTo = PointF()   // uncomment all references to test overlap mid point

    /**
     * Encapsulates timer values for drawing and text items
     * Minutes is an observable delegate, all the calculations are triggered when
     * that value is set
     */
    inner class TimerWidget { // inner class can access outer class's members
        private var timeInMillis = 0L
        private var thumbDegrees = 0f
        var minutesDrawnSweepAngle = -1f
        var minutesDrawnSweepAngleStart = TWELVE_O_CLOCK // only rest resets this
        var colour = 0
        var timePickerTextView: TimePickerTextView? = null
        val thumbPointTo = PointF()     // where on the clock face the degrees points to
        val thumbPos = PointF()         // where the thumb is calculated to be, free of interference
        val thumbShowPos = PointF()     // where the thumb is displayed, might be shifted by proximity to the other timer

        var minutes: Int by Delegates.observable(0) { _, _, new ->

            val isWorkTime = timerWidgets.indexOf(this) == WORK

            calcSweepAngles(new)
            calcThumbDegrees(new, isWorkTime)

            val prevMillis = timeInMillis
            timeInMillis = (new * 60L) * 1000L

            if (acceptInput) {
                timePickerTextView?.setTime(timeInMillis)
                    ?: Log.w(LOG_TAG, "TimerWidget.minutes.observed: no time picker yet")

                // update view model only if accept input is on AND millis is changed (view model will check that too)
                if (prevMillis != timeInMillis) {
                    activeTimerViewModel?.updateTime(timeInMillis, isWorkTime)
                }
            }

            Log.v(LOG_TAG, "minutes.observed: value=$new drawnSweepAngle=$minutesDrawnSweepAngle for work=$isWorkTime")
            invalidate() // cause a redraw
        }

        /**
         * For rest time have to take into account the work time.
         */
        private fun calcSweepAngles(newMins: Int) {
            minutesDrawnSweepAngle = newMins / MINUTES * FULL_CIRCLE

            // start of rest changes depending on work, for safety always re-calc here
            timerWidgets[REST].minutesDrawnSweepAngleStart = TWELVE_O_CLOCK + timerWidgets[WORK].minutesDrawnSweepAngle
        }

        /**
         * Like sweep angle, for rest time have to take into account the work time
         * but this has extra complication, thumbs may want to overlap, then both
         * adjust position to accommodate the each other
         */
        private fun calcThumbDegrees(newMins: Int, isWorkTime: Boolean, isRecursiveCall: Boolean = false) {

            thumbDegrees = newMins * DEGREES_PER_MINUTE

            if (isWorkTime) {    // calc own degrees, but then also recurse for rest
                thumbDegrees += -QUARTER_CIRCLE
                timerWidgets[REST].calcThumbDegrees(timerWidgets[REST].minutes, isWorkTime = false, isRecursiveCall = true)
            }
            else {
                thumbDegrees += timerWidgets[WORK].thumbDegrees
            }

            with(toRadians(thumbDegrees.toDouble()).toFloat()) {
                thumbPointTo.set(centrePoint.y + minutesRingInnerRadius * cos(this),
                                 centrePoint.x + minutesRingInnerRadius * sin(this))
                thumbPos.set(centrePoint.y + thumbRingRadius * cos(this),
                             centrePoint.x + thumbRingRadius * sin(this))
            }

            // when called for rest time while setting work time, it's redundant to go further
            if (!isRecursiveCall) {
                handleOverlappingThumbs()
            }
        }

        /**
         * back off each thumb by half the distance, work to left (anti-clockwise),
         * rest to right (clockwise) unless rest is nearly up behind work
         */
        private fun handleOverlappingThumbs() {
            // for testing: tempOverlapPointTo.set(0f, 0f)  // test for overlap of thumbs

            val distanceSquared = getDistanceSquared(
                timerWidgets[WORK].thumbPos.x, timerWidgets[WORK].thumbPos.y,
                timerWidgets[REST].thumbPos.x, timerWidgets[REST].thumbPos.y)

            // no interference between thumb positions, reset and done
            // when change orientation both thumbs are at 0,0 so check for that too, it will be
            // corrected when the 2nd thumb's position is calculated
            if (distanceSquared > thumbSize * thumbSize
                || (distanceSquared == 0f && timerWidgets[WORK].thumbShowPos.equals(0f, 0f))) {
                timerWidgets[WORK].thumbShowPos.set(timerWidgets[WORK].thumbPos)
                timerWidgets[REST].thumbShowPos.set(timerWidgets[REST].thumbPos)
                return
            }

            val dist = sqrt(distanceSquared)
            Log.d(LOG_TAG,"handleOverlappingThumbs: thumbs overlap by ${thumbSize - dist} (dist=$dist thumb=$thumbSize)")

            val midPointPointedTo = if (dist == 0f) { thumbPointTo }  // means the 2 thumbs point to the exact same place
            else {
                val vx = timerWidgets[WORK].thumbPointTo.x - timerWidgets[REST].thumbPointTo.x
                val vy = timerWidgets[WORK].thumbPointTo.y - timerWidgets[REST].thumbPointTo.y

                PointF(vx / 2f,vy / 2f)
                    .apply {    // got the vector to midpoint, subtract from 1st point to get the mid
                        Log.d(LOG_TAG, "calcThumbDegrees: Overlap, vector to midpoint $this (work=${timerWidgets[WORK].thumbPointTo} rest=${timerWidgets[REST].thumbPointTo}")
                        x = timerWidgets[WORK].thumbPointTo.x - x
                        y = timerWidgets[WORK].thumbPointTo.y - y
                    }.also {
                        Log.d(LOG_TAG, "calcThumbDegrees: Overlap, point to midpoint $it")
                    }
            }
            // for testing: tempOverlapPointTo.set(midPointPointedTo.x, midPointPointedTo.y)

            val midPointDegrees = toDegrees(abs(atan2(midPointPointedTo.x - centrePoint.x, midPointPointedTo.y - centrePoint.y) - PI))

            val left = with(toRadians(midPointDegrees - thumbOverlapDegreesShift + 270f).toFloat()) {
                PointF(centrePoint.y + thumbRingRadius * cos(this),centrePoint.x + thumbRingRadius * sin(this))
            }

            val right= with(toRadians(midPointDegrees + thumbOverlapDegreesShift + 270f).toFloat()) {
                PointF(centrePoint.y + thumbRingRadius * cos(this),centrePoint.x + thumbRingRadius * sin(this))
            }

            // overlapped with large rest angle must mean rest is on the left
            (timerWidgets[REST].minutesDrawnSweepAngle < QUARTER_CIRCLE).also { isRestOnRight ->
                    timerWidgets[if (isRestOnRight) WORK else REST].thumbShowPos.set(left)
                    timerWidgets[if (isRestOnRight) REST else WORK].thumbShowPos.set(right)
            }
        }

        /**
         * Test for the new value different to old so avoid triggering delegate
         */
        fun updateMinutes(toMinutes: Int) {

            var newMinutes = toMinutes

            // rest is relative to work minutes
            if (timerWidgets.indexOf(this) != WORK) {
                newMinutes = with(toMinutes - timerWidgets[WORK].minutes) {
                    if (this < 0) this + 60 else this  // moved past 12 o'clock
                }
            }

            // only update when values differ
            if (newMinutes != minutes) {
                minutes = newMinutes
            }
        }
    }

    /**
     * Called from each of the constructors
     */
    private fun init(attrs: AttributeSet) {
        getActivity()

        shadowWidth = resources.getDimension(R.dimen.time_picker_background_shadow)

        with(context.obtainStyledAttributes(attrs, R.styleable.TimePickerCircle, 0, 0)) {
            timerWidgets[WORK].colour = getColor(R.styleable.TimePickerCircle_workColour, resources.getColor(R.color.colorAccent, null))
            timerWidgets[REST].colour = getColor(R.styleable.TimePickerCircle_restColour, resources.getColor(R.color.colorAccent, null))
            backgroundColour = getColor(R.styleable.TimePickerCircle_backgroundColour, resources.getColor(android.R.color.white, null))
            divisionsBackgroundColour = getColor(R.styleable.TimePickerCircle_divisionsBackgroundColour, backgroundColour)
            divisionsColour = getColor(R.styleable.TimePickerCircle_divisionsColour, resources.getColor(android.R.color.black, null))
            bezelColour = getColor(R.styleable.TimePickerCircle_bezelColour, resources.getColor(android.R.color.black, null))
            thumbShineColour = resources.getColor(R.color.timePickerThumbShine, null)
            recycle()
        }

        clockBackgrd = resources.getDrawable(R.drawable.ic_timer_background, null)
        setOnTouchListener(this)
    }

    /**
     * Initialize sizes, widths and points to simplify calculations for drawing
     * Note: at the end, if there's already a view model present it starts the
     * observer, this is to handle onSizeChanged being called after the view model
     * is set by the data binding which happens after orientation changes
     */
    override fun onSizeChanged(sizeWidth: Int, sizeHeight: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(sizeWidth, sizeHeight, oldw, oldh)

        Log.d(LOG_TAG, "onSizeChanged: w=$sizeWidth h=$sizeHeight")

        val minSize = min(sizeWidth, sizeHeight).toFloat()

        clockBackgrd.setBounds(0, 0, sizeWidth, sizeHeight)
        centrePoint.set(sizeWidth / 2f, sizeHeight / 2f)
        paddedWidth = minSize * PADDED_WIDTH_PERCENT

        val insetWidth = paddedWidth * 1.53f // very exact to get shadow exactly flush with bezel
        background = InsetDrawable(resources.getDrawable(R.drawable.time_picker_round_background, null), insetWidth.toInt())

        ringsWidth = minSize * RINGS_WIDTH_PERCENT
        thumbRadius = ringsWidth
        thumbSize = thumbRadius * 2f
        divisionsStrokeWidth = max(minSize * DIVISIONS_STROKE_WIDTH_PERCENT, 1f)

        totalRadius = minSize / 2f
        drawnPickerRadius = totalRadius - paddedWidth * 1.6f

        minutesRingOuterRadius = drawnPickerRadius - paddedWidth
        minutesRingInnerRadius = minutesRingOuterRadius - ringsWidth
        hoursRingInnerRadius = minutesRingInnerRadius - ringsWidth / 2f

        thumbRingRadius = hoursRingInnerRadius - thumbSize / 1.5f           // the ring where the thumb is placed around the clock
        thumbRingCircumference = (2f * PI * thumbRingRadius).toFloat()

        // for shifting the thumbs when they overlap will need the degrees difference
        // for size of the thumb (using just more than radius for stroke width, hence 1.8 and not 2)
        // formula for len of an arc = (degrees / 360) * circumference
        thumbOverlapDegreesShift = thumbSize / 1.8f * FULL_CIRCLE / thumbRingCircumference

        // need a rect to be able to draw the arc for minutes
        val minutesRectOffset = totalRadius - minutesRingOuterRadius
        minutesRingRect.set(minutesRectOffset, minutesRectOffset, sizeWidth - minutesRectOffset, sizeHeight - minutesRectOffset)
        val innerCircleOffset = minutesRectOffset + ringsWidth * 1.5f
        innerCircleRect.set(innerCircleOffset, innerCircleOffset, sizeWidth - innerCircleOffset, sizeHeight - innerCircleOffset)

        // setup the points for the minutes/hours drawn lines
        var i = 0
        for (minute in 0 .. 59) {
            val angle = minute / MINUTES * 360f
            val rads = toRadians((angle + 270).toDouble())
            val outerCx = centrePoint.x + (cos(rads) * minutesRingOuterRadius).toFloat()
            val outerCy = centrePoint.y + (sin(rads) * minutesRingOuterRadius).toFloat()
            val innerCx = centrePoint.x + (cos(rads) * if (minute % 5 == 0) hoursRingInnerRadius else minutesRingInnerRadius).toFloat()
            val innerCy = centrePoint.y + (sin(rads) * if (minute % 5 == 0) hoursRingInnerRadius else minutesRingInnerRadius).toFloat()
            divisionsPoints[i++] = outerCx
            divisionsPoints[i++] = outerCy
            divisionsPoints[i++] = innerCx
            divisionsPoints[i++] = innerCy
        }

        // see function comment, only if already have a view model
        activeTimerViewModel?.let {  trackViewModel() }
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {

        if (event == null || !acceptInput) return false

        if (event.action == MotionEvent.ACTION_DOWN) {
            motionEventsPointer = event.getPointerId(0)
        }

        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_UP) {
            val pointerIndex = event.action and MotionEvent.ACTION_POINTER_INDEX_MASK shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
            val pointerId = event.getPointerId(pointerIndex)

            // ignore it as it wasn't the pointer first down that's being tracked
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


        when (event.action and MotionEvent.ACTION_MASK) {   // only need down and move

            MotionEvent.ACTION_DOWN -> {
                minutesOnTouchDown = getMinutesFromAngle(centreRelX, centreRelY)

                // decide which timer is appropriate to activate
                // (use the actual touch point not the relative to centre point)
                allocateTouchToTimer(minutesOnTouchDown, event.x, event.y)
                timerWidgets[currentInput].updateMinutes(minutesOnTouchDown)
            }
            MotionEvent.ACTION_MOVE -> {
                timerWidgets[currentInput].updateMinutes(getMinutesFromAngle(centreRelX, centreRelY))
            }
        }

        invalidate()    // make sure to redraw the screen
        return true     // touch handled
    }

    /**
     * Algorithm to decide from the touch placement which timer is being moved
     */
    private fun allocateTouchToTimer(minutesOnTouchDown: Int, touchX: Float, touchY: Float) {

        // compare distances from touch to thumbs, if within a short distance of one take it
        val distToWork = getDistanceSquared(touchX, touchY, timerWidgets[WORK].thumbPos.x, timerWidgets[WORK].thumbPos.y)
        val distToRest = getDistanceSquared(touchX, touchY, timerWidgets[REST].thumbPos.x, timerWidgets[REST].thumbPos.y)

        if (min(distToWork, distToRest) < (thumbSize * thumbSize * 4)) { // twice the size of a thumb
            currentInput = if (distToWork < distToRest) WORK else REST   // < ensures that if they are same rest is chosen
            Log.d(LOG_TAG, "allocateTouchToTimer: touch within 2 widths of thumb for ${if (currentInput == 0) "work" else "rest"}")
            return
        }

        // as the rest timing can overlap the work timing, check it first
        // note, adding them together could exceed 60 (mins on clock), so calc the ranges first

        val restEndMinutes = timerWidgets[WORK].minutes + timerWidgets[REST].minutes

        if (restEndMinutes > 60 && minutesOnTouchDown <= restEndMinutes % 60
            || (minutesOnTouchDown >= timerWidgets[WORK].minutes
                    && minutesOnTouchDown <= restEndMinutes)) {
            currentInput = REST
            Log.d(LOG_TAG, "allocateTouchToTimer: touch in range of rest")
        }
        else if (minutesOnTouchDown <= timerWidgets[WORK].minutes) {        // not in range of rest, range of work is simple test
            currentInput = WORK
            Log.d(LOG_TAG, "allocateTouchToTimer: touch in range of work")
        }
        else if (timerWidgets[REST].minutes == 0) {                         // over top of each other, choose rest
            currentInput = REST
            Log.d(LOG_TAG, "allocateTouchToTimer: choose rest cos one over the other")
        }
        else {                                                              // neither work nor rest range, so it's somewhere in the white space, just go for nearer
            currentInput = if (distToWork < distToRest) WORK else REST      // < ensures that if they are same rest is chosen, otherwise would always follow work and if rest is 0 it's impossible to set it
            Log.d(LOG_TAG, "allocateTouchToTimer: touch outside of ranges, set to nearest = ${if (currentInput == 0) "work" else "rest"}")
        }
    }

    private fun getDistanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val vx = x1 - x2
        val vy = y1 - y2
        return vx * vx + vy * vy
    }

    private fun getMinutesFromAngle(centreRelX: Float, centreRelY: Float): Int {
        val deg = toDegrees(abs(atan2(centreRelX, centreRelY) - PI)).toFloat()
        val newValue = ((deg / FULL_CIRCLE) * MINUTES).toInt()
        Log.v(LOG_TAG, "getValueFromAngle: degrees=$deg gives value=$newValue")
        return newValue
    }

    override fun onDraw(canvas: Canvas?) {
        canvas ?: return

        clockBackgrd.draw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = backgroundColour
        // a little less than full width so the bezel is visible underneath
        canvas.drawCircle(centrePoint.x, centrePoint.y, drawnPickerRadius, paint)

        // fill for divisions background
        paint.color = divisionsBackgroundColour
        canvas.drawCircle(centrePoint.x, centrePoint.y, minutesRingOuterRadius, paint)

        // arc for minutes for each timer

        for ((index, timeSetting) in timerWidgets.withIndex()) {

            paint.color = timeSetting.colour

            // work should fill up the whole circle when 0      //Log.d(LOG_TAG, "onDraw: work=${currentInput == WORK} minutes=$minutes")
            if (index == WORK && timerWidgets[WORK].minutes == 0) {
                canvas.drawCircle(centrePoint.x, centrePoint.y, minutesRingOuterRadius, paint)
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
            canvas.drawCircle(centrePoint.x, centrePoint.y, minutesRingInnerRadius, paint)
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

                canvas.drawLine(timerWidget.thumbShowPos.x, timerWidget.thumbShowPos.y, timerWidget.thumbPointTo.x, timerWidget.thumbPointTo.y, paint)

                // fill circle

                paint.style = Paint.Style.FILL
                paint.color = timerWidget.colour

                canvas.drawCircle(timerWidget.thumbShowPos.x, timerWidget.thumbShowPos.y, thumbSize / 2f, paint)

                // outline

                paint.strokeWidth = divisionsStrokeWidth * 3f
                paint.style = Paint.Style.STROKE
                paint.color = bezelColour
                canvas.drawCircle(timerWidget.thumbShowPos.x, timerWidget.thumbShowPos.y, thumbSize / 2f, paint)

                // little shine on the thumbs gives a bit of 3D effect

                paint.style = Paint.Style.FILL
                paint.color = thumbShineColour
                canvas.drawArc(
                    timerWidget.thumbShowPos.x - thumbRadius,
                    timerWidget.thumbShowPos.y - thumbRadius,
                    timerWidget.thumbShowPos.x + thumbRadius,
                    timerWidget.thumbShowPos.y + thumbRadius,
                    180f, 180f, true, paint)

                // for testing: if (tempOverlapPointTo.x != 0f) canvas.drawCircle(tempOverlapPointTo.x, tempOverlapPointTo.y, 3f, paint)
            }
        }
    }

    /**
     * Called either from the view model setter or onSizeChanged() depending
     * which is called second. It seems the data binding happens 2nd only after
     * orientation changes
     */
    private fun trackViewModel() {
        activeTimerViewModel!!.timer.observe(activity!!, Observer {
            timer ->
            Log.d(LOG_TAG, "trackViewModel: got a timer $timer")
            timerWidgets[WORK].minutes = (timer.pomodoroDuration / ONE_MINUTE).toInt()
            timerWidgets[REST].minutes = (timer.restDuration / ONE_MINUTE).toInt()

            acceptInput = !timer.isActive()
            activity?.callbackChangeToTimer(acceptInput)
        })
    }

    /**
     * Need the activity to set up the observer, it's a bit messy but works
     */
    private fun getActivity(): MainActivity? {

        if (activity != null) return activity

        var localContext = context
        while (localContext is ContextWrapper) {
            if (localContext is MainActivity) {
                activity = localContext
                break
            }
            localContext = localContext.baseContext
        }

        return activity
    }

    /**
     * Activity calls this when play button is pressed
     */
    fun startTiming() {
        activeTimerViewModel?.start()
    }

    fun editTimers() {
        activeTimerViewModel?.stopIfActive()
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

    /**
     * See comments in layout activity_main.xml... width is not set correctly
     * after orientation change until user interacts with the timers
     * for that reason it's set to match_parent until some other better fix
     */
    fun setTime(timeInMillis: Long) {

        // work timer should show as 60 in the case of user putting it back to 0, more natural that way
        val newText = if (timeInMillis == 0L && isWorkTextTimer()) {"60:00"} else TIMER_FORMATTER.format(timeInMillis)
        //Log.d(LOG_TAG, "setTime: $newText viewW=${this.width}")

        text = if (boldTextBeginAtChar != -1 && newText.isNotEmpty()) {
            SpannableString(newText).apply {

                if (boldTextBeginAtChar < newText.length) {
                    setSpan(boldTypefaceSpan, boldTextBeginAtChar,
                        if (boldTextEndAtChar == -1) newText.length
                        else min(boldTextEndAtChar, newText.length),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
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