package com.compass.vault.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Type
import com.compass.vault.ui.dp
import com.compass.vault.ui.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The compass dial.
 *
 * Visual model (a classic magnetic compass):
 *  * the **rose** (degree ticks + N/E/S/W) rotates so that the cardinal
 *    letter of the current heading ends up at the top,
 *  * the **needle** stays fixed and points at the top of the screen, i.e. it
 *    shows the direction the phone is being held in,
 *  * a slim index marker on the outer ring repeats that direction.
 *
 * Everything is drawn by hand so the app has **no image assets and no
 * third-party drawing library** - just a few circles and lines, which stays
 * crisp on every screen density.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Current magnetic heading in degrees (0 = magnetic north). */
    var azimuth: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** When set, a slim arc shows how close the unlock gesture is. */
    var unlockProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** True while the dial is showing the "calibration" overlay. */
    var dimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Palette.WHITE
    }

    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Palette.HAIRLINE
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Palette.GRAY_DIM
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = Palette.WHITE
    }

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val minorLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.GRAY_DIM
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.WHITE
    }

    private val needleTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.GRAY_DIM
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.BLACK
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Palette.WHITE
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.WHITE
    }

    private val needlePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - context.dp(28f)
        if (radius <= 0f) return

        val dim = if (dimmed) 0.35f else 1f

        // --- static frame -----------------------------------------------------
        ringPaint.alpha = (255 * dim).toInt()
        canvas.drawCircle(cx, cy, radius, ringPaint)

        innerRingPaint.alpha = (255 * dim).toInt()
        canvas.drawCircle(cx, cy, radius * 0.66f, innerRingPaint)

        drawIndexMarker(canvas, cx, cy, radius, dim)

        // --- rotating rose ----------------------------------------------------
        canvas.save()
        canvas.rotate(-azimuth, cx, cy)
        drawRose(canvas, cx, cy, radius, dim)
        canvas.restore()

        // --- needle (fixed, points at the top of the screen) -------------------
        drawNeedle(canvas, cx, cy, radius, dim)

        // --- unlock progress --------------------------------------------------
        if (unlockProgress > 0.001f) {
            progressPaint.alpha = 255
            canvas.drawArc(
                cx - radius * 0.93f, cy - radius * 0.93f,
                cx + radius * 0.93f, cy + radius * 0.93f,
                -90f, 360f * unlockProgress, false, progressPaint
            )
        }
    }

    /** The rose: degree ticks and the cardinal letters. */
    private fun drawRose(canvas: Canvas, cx: Float, cy: Float, radius: Float, dim: Float) {
        val tickOuter = radius * 0.955f
        val tickInnerMinor = radius * 0.915f
        val tickInnerMajor = radius * 0.865f

        for (degree in 0 until 360 step 2) {
            val isMajor = degree % 10 == 0
            val isCardinal = degree % 90 == 0
            val paint = when {
                isCardinal -> majorTickPaint
                isMajor -> majorTickPaint
                else -> minorTickPaint
            }
            paint.alpha = if (isMajor) (255 * dim).toInt() else (140 * dim).toInt()
            val inner = when {
                isCardinal -> tickInnerMajor - radius * 0.05f
                isMajor -> tickInnerMajor
                else -> tickInnerMinor
            }
            val angle = Math.toRadians(degree.toDouble() - 90.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + cosA * inner, cy + sinA * inner,
                cx + cosA * tickOuter, cy + sinA * tickOuter, paint
            )
        }

        // Cardinal letters.
        letterPaint.alpha = (255 * dim).toInt()
        letterPaint.textSize = context.sp(Type.TITLE)
        val cardinals = listOf(
            "N" to 0, "E" to 90, "S" to 180, "W" to 270
        )
        cardinals.forEach { (letter, degree) ->
            val angle = Math.toRadians(degree.toDouble() - 90.0)
            val r = radius * 0.755f
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r - (letterPaint.ascent() + letterPaint.descent()) / 2f
            canvas.drawText(letter, x, y, letterPaint)
        }

        // Intercardinal letters, smaller and dimmer.
        minorLetterPaint.alpha = (255 * dim).toInt()
        minorLetterPaint.textSize = context.sp(Type.LABEL)
        listOf("NE" to 45, "SE" to 135, "SW" to 225, "NW" to 315).forEach { (letter, degree) ->
            val angle = Math.toRadians(degree.toDouble() - 90.0)
            val r = radius * 0.775f
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r - (minorLetterPaint.ascent() + minorLetterPaint.descent()) / 2f
            canvas.drawText(letter, x, y, minorLetterPaint)
        }
    }

    /** The classic compass needle, pointing up. */
    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, radius: Float, dim: Float) {
        val tip = cy - radius * 0.60f
        val tail = cy + radius * 0.22f
        val halfWidth = radius * 0.035f
        val gap = radius * 0.045f

        // North half (white).
        needlePaint.alpha = (255 * dim).toInt()
        needlePath.reset()
        needlePath.moveTo(cx, tip)
        needlePath.lineTo(cx + halfWidth, cy - gap)
        needlePath.lineTo(cx - halfWidth, cy - gap)
        needlePath.close()
        canvas.drawPath(needlePath, needlePaint)

        // South half (dim).
        needleTailPaint.alpha = (255 * dim).toInt()
        needlePath.reset()
        needlePath.moveTo(cx, tail)
        needlePath.lineTo(cx + halfWidth, cy + gap)
        needlePath.lineTo(cx - halfWidth, cy + gap)
        needlePath.close()
        canvas.drawPath(needlePath, needleTailPaint)

        // Hub.
        hubPaint.alpha = (255 * dim).toInt()
        canvas.drawCircle(cx, cy, radius * 0.035f, hubPaint)
        ringPaint.alpha = (255 * dim).toInt()
        canvas.drawCircle(cx, cy, radius * 0.035f, ringPaint)
    }

    /** The small marker on the outer ring that shows the direction you face. */
    private fun drawIndexMarker(canvas: Canvas, cx: Float, cy: Float, radius: Float, dim: Float) {
        markerPaint.alpha = (255 * dim).toInt()
        val y = cy - radius - context.dp(6f)
        val w = context.dp(5f)
        val h = context.dp(7f)
        val path = Path().apply {
            moveTo(cx, y + h)
            lineTo(cx - w, y)
            lineTo(cx + w, y)
            close()
        }
        canvas.drawPath(path, markerPaint)
    }
}
