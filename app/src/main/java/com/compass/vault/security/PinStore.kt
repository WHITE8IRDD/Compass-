package com.compass.vault.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Stores the owner's PIN **only as a salted hash**, plus the brute-force
 * bookkeeping.
 *
 * The state file itself is encrypted with the Keystore key, so even a
 * forensic dump of the app's data directory yields nothing usable: the salt
 * and the hash live inside a GCM-authenticated blob that only this install
 * (and this device) can open.
 *
 * The PIN is never written to disk, never logged and never stored in
 * SharedPreferences in plain form.
 */
class PinStore(context: Context, private val cipher: KeystoreCipher) {

    companion object {
        private const val ITERATIONS = PinHasher.ITERATIONS

        /** Wrong attempts allowed before the escalating lock-out kicks in. */
        private const val FREE_ATTEMPTS = 5

        /** First lock-out delay in milliseconds. */
        private const val BASE_LOCKOUT_MS = 5_000L

        /** Each additional failure multiplies the delay by this factor. */
        private const val LOCKOUT_GROWTH = 3L

        /** Upper bound for a single lock-out. */
        private const val MAX_LOCKOUT_MS = 15 * 60 * 1000L

        /** After this many failures the app starts showing a decoy vault. */
        private const val DECOY_THRESHOLD = 10

        private const val FILE_NAME = ".authstate"
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private data class State(
        var salt: ByteArray = ByteArray(0),
        var hash: ByteArray = ByteArray(0),
        var iterations: Int = ITERATIONS,
        var failures: Int = 0,
        var lockedUntil: Long = 0L,
        var setupDone: Boolean = false,
        var secretBearing: Float = 90f,
        var bearingEnabled: Boolean = true,
        var biometricEnabled: Boolean = false,
        var decoyEnabled: Boolean = true,
        var graceSeconds: Int = 30,
        var hintUses: Int = 0
    )

    private var state: State = State()

    init {
        synchronized(this) { state = read() }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun isSetupDone(): Boolean = state.setupDone

    fun secretBearing(): Float = state.secretBearing

    fun setSecretBearing(bearing: Float) {
        state.secretBearing = ((bearing % 360f) + 360f) % 360f
        persist()
    }

    fun isBearingEnabled(): Boolean = state.bearingEnabled

    fun setBearingEnabled(enabled: Boolean) {
        state.bearingEnabled = enabled
        persist()
    }

    fun isBiometricEnabled(): Boolean = state.biometricEnabled

    fun setBiometricEnabled(enabled: Boolean) {
        state.biometricEnabled = enabled
        persist()
    }

    fun isDecoyEnabled(): Boolean = state.decoyEnabled

    fun setDecoyEnabled(enabled: Boolean) {
        state.decoyEnabled = enabled
        persist()
    }

    fun graceSeconds(): Int = state.graceSeconds

    fun setGraceSeconds(seconds: Int) {
        state.graceSeconds = seconds.coerceIn(0, 600)
        persist()
    }

    /** Milliseconds left of the current lock-out, 0 when the app is usable. */
    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (state.lockedUntil - now).coerceAtLeast(0L)

    /** True when the decoy vault should be shown instead of the real one. */
    fun isDecoyActive(): Boolean =
        state.decoyEnabled && state.failures >= DECOY_THRESHOLD

    /**
     * The little hint on the compass screen ("hold the needle on your mark")
     * is only shown for the first few launches after the setup, so that the
     * disguise gets better every day.
     *
     * @return true when the hint should be visible this time.
     */
    fun consumeUnlockHint(): Boolean {
        val show = state.hintUses < 3
        if (show) {
            state.hintUses += 1
            persist()
        }
        return show
    }

    /**
     * Creates (or replaces) the PIN.
     * @return true when the new PIN was stored.
     */
    suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (!isValidPin(pin)) return@withContext false
        mutex.withLock {
            val salt = PinHasher.newSalt()
            val hash = PinHasher.derive(pin, salt, ITERATIONS)
            state.salt = salt
            state.hash = hash
            state.iterations = ITERATIONS
            state.failures = 0
            state.lockedUntil = 0L
            state.setupDone = true
            persist()
        }
        true
    }

    /**
     * Verifies a PIN attempt.
     *
     * @return [VerifyResult]
     */
    suspend fun verify(pin: String): VerifyResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (state.hash.isEmpty()) return@withLock VerifyResult.NotConfigured
            if (lockoutRemainingMs() > 0L) {
                return@withLock VerifyResult.LockedOut(lockoutRemainingMs())
            }

            val candidate = PinHasher.derive(pin, state.salt, state.iterations)
            // Constant time comparison - avoids leaking how many leading
            // bytes matched through timing.
            val ok = PinHasher.constantTimeEquals(candidate, state.hash)
            SecureWipe.wipe(candidate)

            if (ok) {
                state.failures = 0
                state.lockedUntil = 0L
                persist()
                VerifyResult.Ok
            } else {
                state.failures += 1
                val delay = lockoutDelayFor(state.failures)
                if (state.failures > FREE_ATTEMPTS) {
                    state.lockedUntil = System.currentTimeMillis() + delay
                }
                persist()
                VerifyResult.Wrong(state.failures, delay.takeIf { state.failures > FREE_ATTEMPTS } ?: 0L)
            }
        }
    }

    /** True when the PIN is well formed (4-8 digits). */
    fun isValidPin(pin: String): Boolean = PinHasher.isValidPin(pin)

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun lockoutDelayFor(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0L
        var delay = BASE_LOCKOUT_MS
        repeat(minOf(failures - FREE_ATTEMPTS, 6)) { delay *= LOCKOUT_GROWTH }
        return delay.coerceAtMost(MAX_LOCKOUT_MS)
    }

    private fun read(): State {
        if (!file.exists()) return State()
        return try {
            val json = JSONObject(String(cipher.decryptBytes(file.readBytes()), Charsets.UTF_8))
            State(
                salt = Base64.decode(json.optString("salt"), Base64.NO_WRAP),
                hash = Base64.decode(json.optString("hash"), Base64.NO_WRAP),
                iterations = json.optInt("iterations", ITERATIONS),
                failures = json.optInt("failures", 0),
                lockedUntil = json.optLong("lockedUntil", 0L),
                setupDone = json.optBoolean("setupDone", false),
                secretBearing = json.optDouble("secretBearing", 90.0).toFloat(),
                bearingEnabled = json.optBoolean("bearingEnabled", true),
                biometricEnabled = json.optBoolean("biometricEnabled", false),
                decoyEnabled = json.optBoolean("decoyEnabled", true),
                graceSeconds = json.optInt("graceSeconds", 30),
                hintUses = json.optInt("hintUses", 0)
            )
        } catch (e: Exception) {
            // Corrupt or unreadable state (e.g. the Keystore key was lost).
            // Start over rather than crash: the owner will have to re-create
            // the PIN, and the old encrypted files become unreadable anyway.
            State()
        }
    }

    private fun persist() {
        synchronized(this) {
            val json = JSONObject().apply {
                put("salt", Base64.encodeToString(state.salt, Base64.NO_WRAP))
                put("hash", Base64.encodeToString(state.hash, Base64.NO_WRAP))
                put("iterations", state.iterations)
                put("failures", state.failures)
                put("lockedUntil", state.lockedUntil)
                put("setupDone", state.setupDone)
                put("secretBearing", state.secretBearing.toDouble())
                put("bearingEnabled", state.bearingEnabled)
                put("biometricEnabled", state.biometricEnabled)
                put("decoyEnabled", state.decoyEnabled)
                put("graceSeconds", state.graceSeconds)
                put("hintUses", state.hintUses)
            }
            try {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeBytes(cipher.encryptBytes(json.toString().toByteArray(Charsets.UTF_8)))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (e: Exception) {
                // Nothing sensible to do here; the state simply stays as it was.
            }
        }
    }

    /** Outcome of a PIN attempt. */
    sealed class VerifyResult {
        /** PIN correct. */
        object Ok : VerifyResult()

        /** No PIN has been configured yet. */
        object NotConfigured : VerifyResult()

        /**
         * PIN wrong.
         * @param failures total number of consecutive failures
         * @param lockoutMs how long the app is now blocked (0 = not blocked)
         */
        data class Wrong(val failures: Int, val lockoutMs: Long) : VerifyResult()

        /** The app is currently blocked because of too many failures. */
        data class LockedOut(val remainingMs: Long) : VerifyResult()
    }
}
