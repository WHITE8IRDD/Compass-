package com.compass.vault.security

import android.app.Activity
import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Platform-backed biometric readiness and prompt handling. */
class BiometricGate(private val activity: Activity) {

    enum class Availability {
        READY,
        NO_HARDWARE,
        NOT_ENROLLED,
        TEMPORARILY_UNAVAILABLE,
        UNSUPPORTED
    }

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Error(val message: String) : Result()
    }

    fun interface Handle {
        fun cancel()
    }

    /** Hardware presence alone is not sufficient: enrollment must also exist. */
    @Suppress("DEPRECATION")
    fun availability(): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Availability.UNSUPPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = activity.getSystemService(Context.BIOMETRIC_SERVICE)
                as? android.hardware.biometrics.BiometricManager ?: return Availability.UNSUPPORTED
            return when (manager.canAuthenticate()) {
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.TEMPORARILY_UNAVAILABLE
                else -> Availability.UNSUPPORTED
            }
        }

        // Android 6–8.1 have no BiometricPrompt/BiometricManager. The
        // framework FingerprintManager is the only supported system API; the
        // app PIN remains available as a fallback on all of these devices.
        val manager = activity.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
            ?: return Availability.NO_HARDWARE
        return try {
            when {
                !manager.isHardwareDetected -> Availability.NO_HARDWARE
                !manager.hasEnrolledFingerprints() -> Availability.NOT_ENROLLED
                else -> Availability.READY
            }
        } catch (_: SecurityException) {
            Availability.TEMPORARILY_UNAVAILABLE
        }
    }

    fun availabilityMessage(availability: Availability = availability()): String = when (availability) {
        Availability.READY -> "Biometric unlock is ready."
        Availability.NO_HARDWARE -> "This device has no supported biometric sensor. Use your PIN."
        Availability.NOT_ENROLLED -> "Set up a fingerprint or other device biometric in Android Settings, then try again."
        Availability.TEMPORARILY_UNAVAILABLE -> "The biometric sensor is temporarily unavailable. Use your PIN."
        Availability.UNSUPPORTED -> "Biometric unlock is not supported on this Android version. Use your PIN."
    }

    /**
     * Present a system biometric prompt (API 28+) or the legacy system
     * fingerprint callback with a small app-owned fallback dialog (API 26–27).
     * No biometric data is read, stored, or handled by Compass.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        fallbackLabel: String = "Use PIN",
        onResult: (Result) -> Unit
    ): Handle {
        val ready = availability()
        if (ready != Availability.READY) {
            onResult(Result.Error(availabilityMessage(ready)))
            return Handle { }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PlatformPrompt(activity, title, subtitle, fallbackLabel, onResult).show()
        } else {
            LegacyFingerprintPrompt(activity, title, subtitle, fallbackLabel, onResult).show()
        }
    }
}

/** API 28+ system-owned prompt; prompt appearance is intentionally not customized. */
private class PlatformPrompt(
    private val activity: Activity,
    private val title: String,
    private val subtitle: String,
    private val fallbackLabel: String,
    private val callback: (BiometricGate.Result) -> Unit
) : BiometricGate.Handle {

    private val signal = CancellationSignal()
    private val finished = AtomicBoolean(false)
    private val executor = Executor { task -> Handler(Looper.getMainLooper()).post(task) }

    fun show(): BiometricGate.Handle {
        try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(fallbackLabel, executor) { _, _ -> finish(BiometricGate.Result.Cancelled) }
                .build()
            prompt.authenticate(
                signal,
                executor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                    ) = finish(BiometricGate.Result.Success)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                            errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                        ) {
                            finish(BiometricGate.Result.Cancelled)
                        } else {
                            finish(BiometricGate.Result.Error(errString.toString()))
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // A rejected sample is not terminal. The system prompt
                        // stays visible and can accept another attempt.
                    }
                }
            )
        } catch (error: Exception) {
            finish(BiometricGate.Result.Error(error.message ?: "Could not start biometric authentication"))
        }
        return this
    }

    override fun cancel() {
        try { signal.cancel() } catch (_: Exception) { }
        finish(BiometricGate.Result.Cancelled)
    }

    private fun finish(result: BiometricGate.Result) {
        if (finished.compareAndSet(false, true)) callback(result)
    }
}

/** Framework fingerprint fallback for API 26–27, before BiometricPrompt existed. */
@Suppress("DEPRECATION")
private class LegacyFingerprintPrompt(
    private val activity: Activity,
    private val title: String,
    private val subtitle: String,
    private val fallbackLabel: String,
    private val callback: (BiometricGate.Result) -> Unit
) : BiometricGate.Handle {

    private val signal = CancellationSignal()
    private val finished = AtomicBoolean(false)
    private var dialog: android.app.AlertDialog? = null

    fun show(): BiometricGate.Handle {
        val manager = activity.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
        if (manager == null) {
            finish(BiometricGate.Result.Error("Fingerprint authentication is unavailable"))
            return this
        }
        dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("$subtitle\n\nTouch the device fingerprint sensor, or choose your PIN.")
            .setNegativeButton(fallbackLabel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()
        dialog?.show()
        try {
            manager.authenticate(
                null,
                signal,
                0,
                object : FingerprintManager.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                        finish(BiometricGate.Result.Success)
                    }

                    override fun onAuthenticationFailed() {
                        // A non-matching sample is recoverable; keep listening.
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED ||
                            errorCode == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED
                        ) {
                            finish(BiometricGate.Result.Cancelled)
                        } else {
                            finish(BiometricGate.Result.Error(errString.toString()))
                        }
                    }
                },
                null
            )
        } catch (error: Exception) {
            finish(BiometricGate.Result.Error(error.message ?: "Could not start fingerprint authentication"))
        }
        return this
    }

    override fun cancel() {
        try { signal.cancel() } catch (_: Exception) { }
        finish(BiometricGate.Result.Cancelled)
    }

    private fun finish(result: BiometricGate.Result) {
        if (finished.compareAndSet(false, true)) {
            try { dialog?.dismiss() } catch (_: Exception) { }
            dialog = null
            callback(result)
        }
    }
}
