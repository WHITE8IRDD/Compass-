package com.compass.vault.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The entire visual language of the app.
 *
 * Rules (kept deliberately boring and serious):
 *  * pure black background, pure white foreground, greys for everything else
 *  * no colours, no gradients, no rounded blobs of accent colour
 *  * one font family (Inter), three weights, generous letter spacing on
 *    small caps labels
 */
object Palette {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Secondary text. */
    const val GRAY = 0xFF9A9A9A.toInt()

    /** Tertiary text, disabled states. */
    const val GRAY_DIM = 0xFF5A5A5A.toInt()

    /** Hairline separators. */
    const val HAIRLINE = 0xFF1E1E1E.toInt()

    /** Slightly raised surfaces (cards, list rows). */
    const val SURFACE = 0xFF0B0B0B.toInt()
    const val SURFACE_RAISED = 0xFF131313.toInt()
}

/** Spacing scale in dp. */
object Spacing {
    const val XS = 4
    const val SM = 8
    const val MD = 16
    const val LG = 24
    const val XL = 32
    const val XXL = 48
}

/** Corner radius in dp. */
object Radii {
    const val SM = 6
    const val MD = 12
    const val LG = 18
}

/**
 * Type scale in sp.
 */
object Type {
    /** Tiny all-caps label. */
    const val LABEL = 11f

    /** List body. */
    const val BODY = 15f

    /** List title. */
    const val TITLE = 17f

    /** Big readout (heading, numbers). */
    const val DISPLAY = 34f

    /** Screen title. */
    const val SCREEN_TITLE = 22f
}

/**
 * The bundled Inter typeface.
 *
 * Inter ships as a *variable* font, so a single file covers every weight.
 * The "wght" axis is set through [Typeface.Builder.setFontVariationSettings]
 * (API 26+), which is exactly our minimum SDK.
 *
 * If the asset is ever missing the app silently falls back to the platform
 * sans-serif - it must never crash because of a font.
 */
object Fonts {

    /** Where the variable font lives inside the APK. */
    private const val FONT_PATH = "fonts/InterVariable.ttf"

    private var base: Typeface? = null
    private var loaded = false

    private fun base(context: Context): Typeface? {
        if (!loaded) {
            loaded = true
            base = try {
                Typeface.createFromAsset(context.applicationContext.assets, "fonts/InterVariable.ttf")
            } catch (e: Exception) {
                null
            }
        }
        return base
    }

    /** Inter Regular (400). */
    fun regular(context: Context): Typeface = weight(context, 400)

    /** Inter Medium (500). */
    fun medium(context: Context): Typeface = weight(context, 500)

    /** Inter SemiBold (600). */
    fun semiBold(context: Context): Typeface = weight(context, 600)

    private fun weight(context: Context, weight: Int): Typeface {
        // Preferred path: build the typeface straight from the asset and set
        // the "wght" axis of the variable font. `setFontVariationSettings`
        // and the AssetManager constructor of `Typeface.Builder` both exist
        // since API 26, which is our minimum.
        try {
            val builder = Typeface.Builder(context.assets, FONT_PATH)
            builder.setFontVariationSettings("wght $weight")
            return builder.build()
        } catch (e: Exception) {
            // Fall through to the plain instance.
        }
        return try {
            Typeface.createFromAsset(context.assets, FONT_PATH)
        } catch (e: Exception) {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers that keep the screen code readable
// ---------------------------------------------------------------------------

fun Context.dp(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
).toInt()

fun Context.dp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
)

fun Context.sp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
)

/** A label in the small all-caps style used throughout the app. */
fun Context.label(
    text: String,
    size: Float = Type.LABEL,
    color: Int = Palette.GRAY,
    letterSpacing: Float = 0.18f
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = Fonts.medium(this@label)
    this.letterSpacing = letterSpacing
    includeFontPadding = false
    setSingleLine(true)
}

/** A body text view. */
fun Context.body(
    text: String,
    size: Float = Type.BODY,
    color: Int = Palette.WHITE,
    weight: Int = 400
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = when (weight) {
        500 -> Fonts.medium(this@body)
        600 -> Fonts.semiBold(this@body)
        else -> Fonts.regular(this@body)
    }
    includeFontPadding = false
    setLineSpacing(0f, 1.35f)
}

/** A 1dp hairline. */
fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(Palette.HAIRLINE)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
}

/** A vertical spacer. */
fun Context.spacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
}

/** A horizontal spacer. */
fun Context.hspace(widthDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT)
}

/** A vertical LinearLayout with the given spacing. */
fun Context.column(spacingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (spacingDp > 0) showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/** A horizontal LinearLayout. */
fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/**
 * Pads a view by the system bar insets, so nothing is hidden behind the
 * status bar or the gesture pill (mandatory on Android 15+ where apps are
 * edge-to-edge by default).
 */
fun View.padForSystemBars(extraTop: Int = 0, extraBottom: Int = 0) {
    val initialTop = paddingTop
    val initialBottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val top = insets.systemWindowInsetTop
        val bottom = insets.systemWindowInsetBottom
        view.setPadding(
            view.paddingLeft,
            initialTop + top + extraTop,
            view.paddingRight,
            initialBottom + bottom + extraBottom
        )
        insets
    }
    requestApplyInsets()
}

/** Formats a byte count for the file list. */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/**
 * A hairline outlined button.
 *
 * Buttons are TextViews with a 1dp border instead of the platform button
 * widget: the platform button brings a light theme, a ripple and elevation
 * with it, none of which fit a pure black & white design.
 */
fun Context.outlineButton(
    text: String,
    onClick: () -> Unit,
    emphasized: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL)
    setTextColor(if (emphasized) Palette.BLACK else Palette.WHITE)
    typeface = Fonts.semiBold(this@outlineButton)
    letterSpacing = 0.18f
    includeFontPadding = false
    gravity = Gravity.CENTER
    val padH = dp(Spacing.LG)
    val padV = dp(Spacing.MD)
    setPadding(padH, padV, padH, padV)
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(Radii.SM).toFloat()
        setColor(if (emphasized) Palette.WHITE else android.graphics.Color.TRANSPARENT)
        setStroke(dp(1), if (emphasized) Palette.WHITE else Palette.GRAY_DIM)
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/** A full width outlined button. */
fun Context.outlineButtonRow(
    text: String,
    onClick: () -> Unit,
    emphasized: Boolean = false
): View = outlineButton(text, onClick, emphasized).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

/** A simple key/value row used by the settings screen. */
fun Context.settingRow(
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(Spacing.MD), 0, dp(Spacing.MD))
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
    val titleView = body(title, Type.BODY, Palette.WHITE)
    row.addView(titleView, LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ))
    if (value != null) {
        val valueView = label(value, Type.LABEL, Palette.GRAY, 0.12f)
        valueView.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        row.addView(valueView)
    }
    return row
}

/** Formats a duration in milliseconds as mm:ss (or h:mm:ss). */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
