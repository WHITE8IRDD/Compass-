package com.compass.vault.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Type
import com.compass.vault.ui.dp
import com.compass.vault.ui.sp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A monochrome numeric keypad, drawn by hand.
 *
 * Why draw it instead of using a GridLayout of Buttons? Because the app has
 * no AndroidX dependency, and because a hand-drawn keypad lets us control
 * the exact geometry: 72dp circles, 1dp hairline strokes, 26sp digits and
 * generous hit areas - the "large touch targets" requirement.
 */
class PinPadView(context: Context) : View(context) {

    /** Called with the digit that was pressed. */
    var onDigit: ((Char) -> Unit)? = null

    /** Called when the backspace key is pressed. */
    var onBackspace: (() -> Unit)? = null

    /** Called when the confirm (tick) key is pressed. */
    var onConfirm: (() -> Unit)? = null

    private data class Key(val label: String, val kind: Kind, val digit: Char = ' ') {
        enum class Kind { DIGIT, BACKSPACE, CONFIRM }
    }

    private val keys: List<Key> = listOf(
        Key("1", Key.Kind.DIGIT, '1'),
        Key("2", Key.Kind.DIGIT, '2'),
        Key("3", Key.Kind.DIGIT, '3'),
        Key("4", Key.Kind.DIGIT, '4'),
        Key("5", Key.Kind.DIGIT, '5'),
        Key("6", Key.Kind.DIGIT, '6'),
        Key("7", Key.Kind.DIGIT, '7'),
        Key("8", Key.Kind.DIGIT, '8'),
        Key("9", Key.Kind.DIGIT, '9'),
        Key("", Key.Kind.CONFIRM),
        Key("0", Key.Kind.DIGIT, '0'),
        Key("", Key.Kind.BACKSPACE)
    )

    private val density = context.resources.displayMetrics.density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Palette.WHITE
    }

    private val specialCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Palette.GRAY_DIM
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        color = Palette.GRAY
    }

    /** Geometry of the last layout pass, used for hit testing. */
    private val keyBounds = HashMap<Key, FloatArray>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Square grid: 3 columns wide, 4 rows high; respect the parent's limit.
        val desired = (width * 4f / 3f).roundToInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(desired, MeasureSpec.getSize(heightMeasureSpec))
            else -> desired
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        keyBounds.clear()

        val columns = 3
        val rows = 4
        val cellWidth = width / columns.toFloat()
        val cellHeight = height / rows.toFloat()
        val radius = min(cellWidth, cellHeight) * 0.36f

        textPaint.textSize = context.sp(26f)

        keys.forEachIndexed { index, key ->
            val column = index % columns
            val row = index / columns
            val cx = cellWidth * (column + 0.5f)
            val cy = cellHeight * (row + 0.5f)
            keyBounds[key] = floatArrayOf(cx, cy, radius)

            when (key.kind) {
                Key.Kind.DIGIT -> {
                    circlePaint.color = Palette.WHITE
                    canvas.drawCircle(cx, cy, radius, circlePaint)
                    val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(key.label, cx, baseline, textPaint)
                }

                Key.Kind.BACKSPACE -> {
                    specialCirclePaint.color = Palette.GRAY_DIM
                    canvas.drawCircle(cx, cy, radius, specialCirclePaint)
                    drawBackspace(canvas, cx, cy, radius * 0.42f)
                }

                Key.Kind.CONFIRM -> {
                    specialCirclePaint.color = Palette.GRAY
                    canvas.drawCircle(cx, cy, radius, specialCirclePaint)
                    drawTick(canvas, cx, cy, radius * 0.42f)
                }
            }
        }
    }

    private fun drawTick(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        iconPaint.color = Palette.GRAY
        val path = Path().apply {
            moveTo(cx - size * 0.75f, cy)
            lineTo(cx - size * 0.15f, cy + size * 0.6f)
            lineTo(cx + size * 0.8f, cy - size * 0.65f)
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        iconPaint.color = Palette.GRAY
        val path = Path().apply {
            moveTo(cx - size, cy)
            lineTo(cx - size * 0.45f, cy - size * 0.72f)
            lineTo(cx + size, cy - size * 0.72f)
            lineTo(cx + size, cy + size * 0.72f)
            lineTo(cx - size * 0.45f, cy + size * 0.72f)
            close()
        }
        canvas.drawPath(path, iconPaint)
        // The "x" inside the pentagon.
        canvas.drawLine(cx - size * 0.12f, cy - size * 0.3f, cx + size * 0.45f, cy + size * 0.3f, iconPaint)
        canvas.drawLine(cx + size * 0.45f, cy - size * 0.3f, cx - size * 0.12f, cy + size * 0.3f, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                keyBounds.forEach { (key, bounds) ->
                    val (cx, cy, radius) = bounds
                    if (hypot(x - cx, y - cy) <= radius * 1.35f) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            when (key.kind) {
                                Key.Kind.DIGIT -> onDigit?.invoke(key.digit)
                                Key.Kind.BACKSPACE -> onBackspace?.invoke()
                                Key.Kind.CONFIRM -> onConfirm?.invoke()
                            }
                        }
                        return true
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The row of dots that shows how many digits have been entered.
 */
class PinDotsView(context: Context) : View(context) {

    /** Number of digits that are currently filled. */
    var filled: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            invalidate()
        }

    /** Total number of dots to draw. */
    var total: Int = 6
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    /** When true the dots are drawn as empty rings (error state). */
    var error: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * context.resources.displayMetrics.density
        color = Palette.WHITE
    }

    private val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.WHITE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, context.dp(18))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = context.dp(4f)
        val spacing = context.dp(22f)
        val totalWidth = spacing * (total - 1)
        var x = width / 2f - totalWidth / 2f
        val cy = height / 2f
        for (i in 0 until total) {
            if (i < filled) {
                filledPaint.color = if (error) Palette.GRAY else Palette.WHITE
                canvas.drawCircle(x, cy, radius, filledPaint)
            } else {
                paint.color = if (error) Palette.GRAY else Palette.GRAY_DIM
                canvas.drawCircle(x, cy, radius, paint)
            }
            x += spacing
        }
    }
}
