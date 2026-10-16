package com.compass.vault.ui.screen

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.column
import com.compass.vault.ui.dp
import com.compass.vault.ui.label
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.sp
import com.compass.vault.ui.spacer
import com.compass.vault.ui.widget.CompassView
import com.compass.vault.util.CompassSensor
import kotlin.math.abs
import com.compass.vault.ui.hspace

/**
 * The compass - the app's public face.
 *
 * To everybody else this is a plain, working compass app.
 *
 * To the owner it is the lock:
 *  * **The secret bearing.** During setup the owner picks a bearing (for
 *    example 137 degrees). When the needle is held on that bearing the app
 *    starts to fill a slim arc; after 1.5 seconds the vault opens. It looks
 *    exactly like somebody taking their bearings.
 *  * **The PIN.** A long press on the dial opens the numeric keypad. The
 *    long press is the "owner only" gesture, and the keypad is additionally
 *    protected by the escalating brute-force lock-out.
 *  * **Biometrics.** If the owner enabled it, the fingerprint key on the
 *    keypad opens the vault without typing anything.
 */
class CompassScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator
) : Screen {

    companion object {
        /** How close (in degrees) the needle has to be to the secret bearing. */
        private const val TOLERANCE_DEGREES = 5f

        /** How long the needle has to rest on the bearing. */
        private const val HOLD_MILLIS = 1500L
    }

    private val compass = CompassView(activity)
    private val sensor = CompassSensor(activity)

    private val headingText = activity.body("", Type.DISPLAY, Palette.WHITE, 500)
    private val cardinalText = activity.label("", Type.LABEL, Palette.GRAY)
    private val hintText = activity.label("", Type.LABEL, Palette.GRAY_DIM, 0.12f)
    private val statusText = activity.label("", Type.LABEL, Palette.GRAY, 0.12f)

    private var heading = 0f
    private var holdStartedAt = 0L
    private var unlocked = false

    override val view: View = buildLayout()

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Palette.BLACK)
            setPadding(0, activity.dp(Spacing.MD), 0, activity.dp(Spacing.MD))
            padForSystemBars()
        }

        // ---- top: title ---------------------------------------------------
        val title = activity.label("COMPASS", Type.LABEL, Palette.GRAY, 0.32f)
        title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(activity.spacer(Spacing.MD))

        // ---- middle: the dial ---------------------------------------------
        compass.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            navigator.showPin()
            true
        }
        root.addView(compass, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- below: the heading readout -----------------------------------
        val readout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        headingText.gravity = Gravity.CENTER
        headingText.letterSpacing = 0.02f
        readout.addView(headingText)
        readout.addView(activity.spacer(Spacing.XS))
        cardinalText.gravity = Gravity.CENTER
        readout.addView(cardinalText)
        root.addView(readout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(activity.spacer(Spacing.MD))

        // ---- bottom: hint / status ----------------------------------------
        statusText.gravity = Gravity.CENTER
        statusText.visibility = View.GONE
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        hintText.gravity = Gravity.CENTER
        root.addView(hintText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        updateReadout()
        return root
    }

    private var firstShow = true

    override fun onShow() {
        // The hint is only useful for the first launches after the setup.
        if (firstShow) {
            firstShow = false
            val showHint = graph.pinStore.consumeUnlockHint()
            hintText.text = if (showHint) "HOLD THE NEEDLE ON YOUR MARK TO OPEN" else ""
            hintText.visibility = if (showHint) View.VISIBLE else View.INVISIBLE
        }

        if (!sensor.isAvailable) {
            // No magnetometer: the bearing unlock cannot work on this device.
            statusText.text = "NO MAGNETOMETER - USE THE KEYPAD"
            statusText.visibility = View.VISIBLE
            hintText.visibility = View.GONE
        }

        sensor.onHeading = { value ->
            heading = value
            compass.azimuth = value
            updateReadout()
            updateUnlockProgress()
        }
        sensor.start()
    }

    override fun onHide() {
        sensor.onHeading = null
        sensor.stop()
    }

    override fun onBack(): Boolean {
        navigator.showLibrary()
        return true
    }

    // -----------------------------------------------------------------------
    // The unlock gesture
    // -----------------------------------------------------------------------

    private fun updateUnlockProgress() {
        if (unlocked) return
        if (!graph.pinStore.isBearingEnabled() || !sensor.isAvailable) {
            compass.unlockProgress = 0f
            return
        }

        val target = graph.pinStore.secretBearing()
        val difference = angleDifference(heading, target)

        if (abs(difference) <= TOLERANCE_DEGREES) {
            if (holdStartedAt == 0L) holdStartedAt = SystemClock.uptimeMillis()
            val elapsed = SystemClock.uptimeMillis() - holdStartedAt
            val progress = (elapsed.toFloat() / HOLD_MILLIS).coerceIn(0f, 1f)
            compass.unlockProgress = progress
            if (progress >= 1f) {
                unlock()
            }
        } else {
            holdStartedAt = 0L
            compass.unlockProgress = 0f
        }
    }

    private fun unlock() {
        unlocked = true
        compass.unlockProgress = 0f
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        graph.lockController.unlock()
        navigator.showVault()
    }

    // -----------------------------------------------------------------------
    // Readout
    // -----------------------------------------------------------------------

    private fun updateReadout() {
        val degrees = ((heading % 360f) + 360f) % 360f
        headingText.text = String.format("%03d°", degrees.toInt())
        cardinalText.text = cardinalName(degrees)
    }

    private fun cardinalName(degrees: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = Math.round(degrees / 45f) % 8
        return names[index]
    }

    private fun angleDifference(a: Float, b: Float): Float {
        var diff = (a - b) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }
}
