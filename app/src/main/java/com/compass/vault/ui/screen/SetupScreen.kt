package com.compass.vault.ui.screen

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.security.BiometricGate
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
import com.compass.vault.ui.hairline
import com.compass.vault.ui.hspace
import com.compass.vault.ui.label
import com.compass.vault.ui.outlineButton
import com.compass.vault.ui.outlineButtonRow
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.spacer
import com.compass.vault.ui.widget.CompassView
import com.compass.vault.ui.widget.PinDotsView
import com.compass.vault.ui.widget.PinPadView
import com.compass.vault.util.AppDispatchers
import com.compass.vault.util.CompassSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The first-run wizard.
 *
 * Four steps:
 *  1. the warning that the files live inside the app,
 *  2. create a PIN (4-8 digits),
 *  3. pick the secret bearing - the owner's own "mark" on the compass,
 *  4. optionally enable biometric unlock.
 */
class SetupScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator
) : Screen {

    private enum class Step { WARNING, CREATE_PIN, CONFIRM_PIN, BEARING, BIOMETRIC }

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val content = LinearLayout(activity)
    private val sensor = CompassSensor(activity)
    private val biometricGate = BiometricGate(activity)

    private var step = Step.WARNING
    private var entered = StringBuilder()
    private var firstPin: String? = null
    private var bearing = 90f
    private var heading = 0f

    override val view: View = buildRoot()

    private fun buildRoot(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            setPadding(
                activity.dp(Spacing.LG), activity.dp(Spacing.LG),
                activity.dp(Spacing.LG), activity.dp(Spacing.LG)
            )
            padForSystemBars()
        }
        content.orientation = LinearLayout.VERTICAL
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        render()
        return root
    }

    override fun onShow() {
        // The scope is cancelled in onHide(); a fresh one is needed when the
        // very same screen instance is shown again.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        sensor.onHeading = { value ->
            heading = value
            if (step == Step.BEARING) updateBearingReadout()
        }
        if (sensor.isAvailable) sensor.start()
    }

    override fun onHide() {
        sensor.onHeading = null
        sensor.stop()
        scope.cancel()
    }

    override fun onBack(): Boolean = true

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        when (step) {
            Step.WARNING -> renderWarning()
            Step.CREATE_PIN -> renderPin("CREATE A PIN", "4 to 8 digits. This is your fallback unlock.")
            Step.CONFIRM_PIN -> renderPin("CONFIRM THE PIN", "Type the same digits again.")
            Step.BEARING -> renderBearing()
            Step.BIOMETRIC -> renderBiometric()
        }
    }

    private fun renderWarning() {
        val title = activity.body("COMPASS", Type.SCREEN_TITLE, Palette.WHITE, 600)
        content.addView(title)
        content.addView(activity.spacer(Spacing.SM))
        content.addView(activity.body(
            "A vault that looks like a compass.",
            Type.BODY, Palette.GRAY
        ))
        content.addView(activity.spacer(Spacing.XL))

        // The one-time warning the owner must read.
        val warning = activity.body(
            "FILES LIVE INSIDE THE APP.\nUNINSTALLING THE APP DELETES THEM FOREVER.",
            Type.BODY, Palette.WHITE, 600
        )
        warning.setLineSpacing(0f, 1.4f)
        content.addView(warning)
        content.addView(activity.spacer(Spacing.SM))
        content.addView(activity.body(
            "There is no cloud copy and no recovery. Export anything you cannot lose.",
            Type.BODY, Palette.GRAY
        ))
        content.addView(activity.spacer(Spacing.XL))
        content.addView(activity.hairline())
        content.addView(activity.spacer(Spacing.XL))

        content.addView(activity.outlineButtonRow("SET UP MY VAULT", {
            step = Step.CREATE_PIN
            render()
        }, emphasized = true))
        content.addView(activity.spacer(Spacing.MD))
    }

    private fun renderPin(title: String, subtitle: String) {
        val titleView = activity.label(title, Type.LABEL, Palette.GRAY, 0.28f)
        titleView.gravity = Gravity.CENTER
        content.addView(titleView)
        content.addView(activity.spacer(Spacing.SM))

        val sub = activity.body(subtitle, Type.BODY, Palette.GRAY)
        sub.gravity = Gravity.CENTER
        content.addView(sub)
        content.addView(activity.spacer(Spacing.LG))

        val dots = PinDotsView(activity)
        dots.total = 8
        content.addView(dots, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(activity.spacer(Spacing.LG))

        val pad = PinPadView(activity)
        pad.onDigit = { digit ->
            if (entered.length < 8) {
                entered.append(digit)
                dots.filled = entered.length
            }
        }
        pad.onBackspace = {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                dots.filled = entered.length
            }
        }
        pad.onConfirm = { onPinConfirmed() }
        content.addView(pad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun renderBearing() {
        val titleView = activity.label("PICK YOUR MARK", Type.LABEL, Palette.GRAY, 0.28f)
        titleView.gravity = Gravity.CENTER
        content.addView(titleView)
        content.addView(activity.spacer(Spacing.SM))

        val sub = activity.body(
            "This bearing opens your vault. Point the needle at it and hold it still.",
            Type.BODY, Palette.GRAY
        )
        sub.gravity = Gravity.CENTER
        content.addView(sub)
        content.addView(activity.spacer(Spacing.MD))

        val compass = CompassView(activity)
        compass.azimuth = heading
        content.addView(compass, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val readout = activity.label("", Type.TITLE, Palette.WHITE, 0.1f)
        readout.gravity = Gravity.CENTER
        readout.tag = "bearing_readout"
        content.addView(readout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(activity.spacer(Spacing.SM))

        // Fine adjustment: -5 / +5 degrees.
        val adjust = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        adjust.addView(activity.outlineButton("-5", { adjustBearing(-5f) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        adjust.addView(activity.hspace(Spacing.MD))
        adjust.addView(activity.outlineButton("+5", { adjustBearing(5f) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(adjust, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(activity.spacer(Spacing.MD))

        content.addView(activity.outlineButtonRow("USE THIS BEARING", {
            graph.pinStore.setSecretBearing(bearing)
            graph.pinStore.setBearingEnabled(true)
            step = if (biometricGate.isAvailable()) Step.BIOMETRIC else Step.WARNING
            if (step == Step.WARNING) finishSetup()
            render()
        }, emphasized = true))
        content.addView(activity.spacer(Spacing.SM))
        content.addView(activity.outlineButtonRow("USE THE PIN ONLY", {
            graph.pinStore.setBearingEnabled(false)
            step = if (biometricGate.isAvailable()) Step.BIOMETRIC else Step.WARNING
            if (step == Step.WARNING) finishSetup()
            render()
        }))
        content.addView(activity.spacer(Spacing.MD))

        updateBearingReadout()
    }

    private fun renderBiometric() {
        val titleView = activity.label("BIOMETRIC UNLOCK", Type.LABEL, Palette.GRAY, 0.28f)
        titleView.gravity = Gravity.CENTER
        content.addView(titleView)
        content.addView(activity.spacer(Spacing.SM))

        val sub = activity.body(
            "Use your fingerprint instead of the PIN. The PIN still works as a fallback.",
            Type.BODY, Palette.GRAY
        )
        sub.gravity = Gravity.CENTER
        content.addView(sub)
        content.addView(activity.spacer(Spacing.XL))

        content.addView(activity.outlineButtonRow("ENABLE FINGERPRINT", {
            graph.pinStore.setBiometricEnabled(true)
            finishSetup()
        }, emphasized = true))
        content.addView(activity.spacer(Spacing.MD))
        content.addView(activity.outlineButtonRow("NOT NOW", {
            graph.pinStore.setBiometricEnabled(false)
            finishSetup()
        }))
        content.addView(activity.spacer(Spacing.MD))
    }

    private fun updateBearingReadout() {
        val readout = content.findViewWithTag<TextView>("bearing_readout") ?: return
        readout.text = String.format("%03d°  %s", bearing.toInt(), cardinalName(bearing))
    }

    private fun adjustBearing(delta: Float) {
        bearing = ((bearing + delta) % 360f + 360f) % 360f
        updateBearingReadout()
    }

    // -----------------------------------------------------------------------
    // Logic
    // -----------------------------------------------------------------------

    private fun onPinConfirmed() {
        val pin = entered.toString()
        entered = StringBuilder()
        if (pin.length < 4) {
            navigator.toast("USE AT LEAST 4 DIGITS")
            return
        }
        when (step) {
            Step.CREATE_PIN -> {
                firstPin = pin
                step = Step.CONFIRM_PIN
                render()
            }

            Step.CONFIRM_PIN -> {
                if (pin != firstPin) {
                    navigator.toast("PINS DO NOT MATCH")
                    step = Step.CREATE_PIN
                    render()
                    return
                }
                scope.launch {
                    graph.pinStore.setPin(pin)
                    step = Step.BEARING
                    render()
                }
            }

            else -> Unit
        }
    }

    private fun finishSetup() {
        scope.launch {
            graph.pinStore.setPin(firstPin ?: return@launch)
            graph.pinStore.setSecretBearing(bearing)
            graph.pinStore.setBearingEnabled(true)
            navigator.showCompass()
        }
    }

    private fun cardinalName(degrees: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[Math.round(degrees / 45f) % 8]
    }
}
