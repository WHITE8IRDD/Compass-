package com.compass.vault.ui.screen

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.security.BiometricGate
import com.compass.vault.security.PinStore
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.PinReason
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
import com.compass.vault.ui.label
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.spacer
import com.compass.vault.ui.widget.PinDotsView
import com.compass.vault.ui.widget.PinPadView
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * The numeric keypad.
 *
 * It is reached by long-pressing the compass dial, and it is the fallback
 * when the device has no magnetometer or the owner does not want to use the
 * secret bearing.
 *
 * Brute-force protection lives in [PinStore]: after five wrong attempts the
 * app blocks for 5 seconds, then 15, 45, 135 ... up to 15 minutes. After ten
 * failures the app shows a **decoy vault** instead of the keypad.
 */
class PinScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator,
    private val reason: PinReason
) : Screen {

    private enum class Mode { VERIFY, CREATE, CONFIRM }

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val dots = PinDotsView(activity)
    private val pad = PinPadView(activity)
    private val titleView = activity.label("", Type.LABEL, Palette.GRAY, 0.28f)
    private val errorView = activity.body("", Type.BODY, Palette.GRAY)
    private val progressView = activity.label("", Type.LABEL, Palette.GRAY, 0.12f)

    private val biometricGate = BiometricGate(activity)

    private var mode = Mode.VERIFY
    private var entered = StringBuilder()
    private var pendingNewPin: String? = null
    private var busy = false
    private var lockoutUntil = 0L

    private lateinit var keypadGroup: LinearLayout
    private lateinit var decoyGroup: LinearLayout
    private lateinit var fingerprintLink: android.widget.TextView

    private val ticker = object : Runnable {
        override fun run() {
            val remaining = lockoutUntil - SystemClock.uptimeMillis()
            if (remaining > 0) {
                errorView.text = "TOO MANY ATTEMPTS - WAIT ${(remaining / 1000) + 1}S"
                progressView.postDelayed(this, 250)
            } else {
                lockoutUntil = 0L
                errorView.text = ""
                progressView.text = ""
            }
        }
    }

    override val view: View = buildLayout()

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Palette.BLACK)
            setPadding(
                activity.dp(Spacing.LG), activity.dp(Spacing.LG),
                activity.dp(Spacing.LG), activity.dp(Spacing.LG)
            )
            padForSystemBars()
        }

        titleView.gravity = Gravity.CENTER
        root.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(activity.spacer(Spacing.LG))

        // --- the keypad ----------------------------------------------------
        keypadGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        dots.total = 8
        keypadGroup.addView(dots, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keypadGroup.addView(activity.spacer(Spacing.LG))
        errorView.gravity = Gravity.CENTER
        keypadGroup.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keypadGroup.addView(activity.spacer(Spacing.XS))
        progressView.gravity = Gravity.CENTER
        keypadGroup.addView(progressView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keypadGroup.addView(activity.spacer(Spacing.MD))

        pad.onDigit = { digit -> onDigit(digit) }
        pad.onBackspace = { onBackspace() }
        pad.onConfirm = { submit() }
        keypadGroup.addView(pad, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Biometric shortcut (only when the owner enabled it).
        fingerprintLink = activity.label("USE FINGERPRINT", Type.LABEL, Palette.GRAY_DIM, 0.2f)
        fingerprintLink.gravity = Gravity.CENTER
        fingerprintLink.setPadding(0, activity.dp(Spacing.SM), 0, 0)
        fingerprintLink.setOnClickListener { startBiometric() }
        keypadGroup.addView(fingerprintLink, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(keypadGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // --- the decoy -----------------------------------------------------
        decoyGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val empty = activity.body("Nothing here.", Type.TITLE, Palette.GRAY)
        empty.gravity = Gravity.CENTER
        decoyGroup.addView(empty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        decoyGroup.addView(activity.spacer(Spacing.XL))
        val forgot = activity.label("FORGOT PIN?", Type.LABEL, Palette.GRAY_DIM, 0.2f)
        forgot.gravity = Gravity.CENTER
        forgot.setOnClickListener {
            decoyGroup.visibility = View.GONE
            keypadGroup.visibility = View.VISIBLE
        }
        decoyGroup.addView(forgot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(decoyGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        updateTitle()
        return root
    }

    override fun onShow() {
        // The scope is cancelled in onHide(); a fresh one is needed when the
        // very same screen instance is shown again.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        val remaining = graph.pinStore.lockoutRemainingMs()
        if (remaining > 0) {
            lockoutUntil = SystemClock.uptimeMillis() + remaining
            progressView.post(ticker)
        }
        fingerprintLink.visibility =
            if (graph.pinStore.isBiometricEnabled() && biometricGate.isAvailable()) View.VISIBLE else View.GONE
        if (graph.pinStore.isDecoyActive()) showDecoy()
    }

    override fun onHide() {
        progressView.removeCallbacks(ticker)
        scope.cancel()
    }

    override fun onBack(): Boolean {
        navigator.showCompass()
        return true
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private fun onDigit(digit: Char) {
        if (busy || lockoutUntil > SystemClock.uptimeMillis()) return
        if (entered.length >= 8) return
        entered.append(digit)
        dots.filled = entered.length
    }

    private fun onBackspace() {
        if (busy) return
        if (entered.isNotEmpty()) {
            entered.deleteCharAt(entered.length - 1)
            dots.filled = entered.length
        }
    }

    private fun submit() {
        val pin = entered.toString()
        entered = StringBuilder()
        dots.filled = 0
        busy = true

        scope.launch {
            when (mode) {
                Mode.VERIFY -> verify(pin)
                Mode.CREATE -> create(pin)
                Mode.CONFIRM -> confirm(pin)
            }
        }
    }

    private suspend fun verify(pin: String) {
        when (val result = graph.pinStore.verify(pin)) {
            is PinStore.VerifyResult.Ok -> {
                busy = false
                if (reason == PinReason.CHANGE) {
                    pendingNewPin = null
                    mode = Mode.CREATE
                    updateTitle()
                } else {
                    graph.lockController.unlock()
                    navigator.showVault()
                }
            }

            is PinStore.VerifyResult.LockedOut -> {
                busy = false
                lockoutUntil = SystemClock.uptimeMillis() + result.remainingMs
                progressView.post(ticker)
            }

            is PinStore.VerifyResult.Wrong -> {
                busy = false
                errorView.text = "INCORRECT PIN"
                shake()
                if (result.lockoutMs > 0) {
                    lockoutUntil = SystemClock.uptimeMillis() + result.lockoutMs
                    progressView.post(ticker)
                }
                if (graph.pinStore.isDecoyActive()) showDecoy()
            }

            else -> {
                busy = false
                errorView.text = "NO PIN CONFIGURED"
            }
        }
    }

    private suspend fun create(pin: String) {
        if (!graph.pinStore.isValidPin(pin)) {
            busy = false
            errorView.text = "USE 4 TO 8 DIGITS"
            shake()
            return
        }
        pendingNewPin = pin
        mode = Mode.CONFIRM
        busy = false
        errorView.text = ""
        updateTitle()
    }

    private suspend fun confirm(pin: String) {
        if (pin != pendingNewPin) {
            busy = false
            errorView.text = "PINS DO NOT MATCH"
            shake()
            mode = Mode.CREATE
            updateTitle()
            return
        }
        val ok = graph.pinStore.setPin(pin)
        busy = false
        if (ok) {
            navigator.toast("PIN UPDATED")
            navigator.showSettings()
        } else {
            errorView.text = "COULD NOT SAVE THE PIN"
        }
    }

    private fun startBiometric() {
        if (!graph.pinStore.isBiometricEnabled() || !biometricGate.isAvailable()) return
        biometricGate.authenticate(
            title = "Unlock Compass",
            subtitle = "Confirm your identity to open the vault"
        ) { success ->
            if (success) {
                graph.lockController.unlock()
                navigator.showVault()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Decoy vault
    // -----------------------------------------------------------------------

    /**
     * After a lot of failed attempts the app pretends to be an empty vault.
     * A stranger tapping around sees nothing interesting; the owner can
     * still reach the keypad through the "FORGOT PIN?" link.
     */
    private fun showDecoy() {
        keypadGroup.visibility = View.GONE
        decoyGroup.visibility = View.VISIBLE
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun updateTitle() {
        titleView.text = when (mode) {
            Mode.VERIFY -> when (reason) {
                PinReason.CHANGE -> "CURRENT PIN"
                else -> "ENTER PIN"
            }
            Mode.CREATE -> "NEW PIN"
            Mode.CONFIRM -> "CONFIRM PIN"
        }
    }

    private fun shake() {
        val amplitude = activity.dp(10f).toFloat()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                view.translationX = sin(t * 8f * Math.PI.toFloat()) * amplitude * (1f - t)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.translationX = 0f
                }
            })
            start()
        }
    }
}
