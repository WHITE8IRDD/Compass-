package com.compass.vault.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Biometric unlock built on the *platform* `BiometricPrompt`
 * (`android.hardware.biometrics.BiometricPrompt`).
 *
 * Why the platform API and not `androidx.biometric`?
 *  * The app ships with **zero third-party dependencies**, so the attack
 *    surface of the unlock path is exactly what AOSP ships.
 *  * The platform prompt is a system dialog: it is rendered by the system
 *    UI process, not by this app, and it cannot be spoofed by a malicious
 *    overlay drawn inside our own window.
 *
 * `androidx.biometric` is only a compatibility wrapper around exactly this
 * class, with extra support for device-credential fallback on API 28/29.
 *
 * The platform prompt only exists from API 28 on, so the code that touches
 * it lives in its own class ([PlatformPrompt]) that is never loaded on older
 * devices.
 */
class BiometricGate(private val activity: Activity) {

    /** True when the device reports a biometric sensor we can use. */
    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm: PackageManager = activity.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            pm.hasSystemFeature(PackageManager.FEATURE_IRIS) ||
            pm.hasSystemFeature(PackageManager.FEATURE_FACE)
    }

    /**
     * Shows the system biometric prompt.
     *
     * @param onResult true when the user was authenticated, false when the
     *        prompt was cancelled, failed or the user chose the fallback.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        fallbackLabel: String = "Use PIN",
        onResult: (Boolean) -> Unit
    ) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        PlatformPrompt(activity, title, subtitle, fallbackLabel, onResult).show()
    }
}

/**
 * The API 28+ implementation of the system prompt.
 *
 * It is a separate class on purpose: on API 26/27 the class
 * `android.hardware.biometrics.BiometricPrompt` does not exist, and the
 * verifier would complain about it if it were referenced from a class that
 * is always loaded.
 */
private class PlatformPrompt(
    private val activity: Activity,
    private val title: String,
    private val subtitle: String,
    private val fallbackLabel: String,
    private val onResult: (Boolean) -> Unit
) {

    /**
     * `Context.getMainExecutor()` only exists from API 28, so the executor is
     * built by hand - it simply posts to the main thread.
     */
    private val executor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }

    fun show() {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(fallbackLabel, executor) { _, _ -> onResult(false) }
            .build()

        val signal = CancellationSignal()
        prompt.authenticate(
            signal,
            executor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                ) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    // A single rejected finger - keep the prompt open.
                }
            }
        )
    }
}
