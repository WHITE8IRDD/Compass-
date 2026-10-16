package com.compass.vault.security

import android.content.Context
import android.util.Base64
import com.compass.vault.data.AtomicFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Stores a salted PIN hash, never the PIN itself, plus local lockout/settings
 * state. The entire state is encrypted with the Android Keystore key and
 * atomically replaced so a crash cannot turn a configured vault into a fresh
 * setup screen.
 */
class PinStore(context: Context, private val cipher: KeystoreCipher) {

    companion object {
        private const val ITERATIONS = PinHasher.ITERATIONS
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 5_000L
        private const val LOCKOUT_GROWTH = 3L
        private const val MAX_LOCKOUT_MS = 15 * 60 * 1000L
        private const val DECOY_THRESHOLD = 10
        private const val FILE_NAME = ".authstate"
        private const val STATE_ERROR = "PIN security state is unreadable or could not be saved. Vault data was preserved; do not reinstall or clear app data."
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private data class State(
        val salt: ByteArray = ByteArray(0),
        val hash: ByteArray = ByteArray(0),
        val iterations: Int = ITERATIONS,
        val failures: Int = 0,
        val lockedUntil: Long = 0L,
        val setupDone: Boolean = false,
        val secretBearing: Float = 90f,
        val bearingEnabled: Boolean = true,
        val biometricEnabled: Boolean = false,
        val decoyEnabled: Boolean = true,
        val graceSeconds: Int = 30,
        val hintUses: Int = 0
    )

    @Volatile private var state: State = State()
    @Volatile private var loadError: Exception? = null

    init {
        state = read()
    }

    fun isSetupDone(): Boolean = loadError != null || state.setupDone
    fun hasSecurityStateError(): Boolean = loadError != null
    fun secretBearing(): Float = state.secretBearing
    fun isBearingEnabled(): Boolean = state.bearingEnabled
    fun isBiometricEnabled(): Boolean = state.biometricEnabled
    fun isDecoyEnabled(): Boolean = state.decoyEnabled
    fun graceSeconds(): Int = state.graceSeconds

    fun setSecretBearing(bearing: Float): Boolean = updateState {
        it.copy(secretBearing = ((bearing % 360f) + 360f) % 360f)
    }

    fun setCompassUnlock(bearing: Float, enabled: Boolean): Boolean = updateState {
        it.copy(
            secretBearing = ((bearing % 360f) + 360f) % 360f,
            bearingEnabled = enabled
        )
    }

    fun setBearingEnabled(enabled: Boolean): Boolean = updateState { it.copy(bearingEnabled = enabled) }
    fun setBiometricEnabled(enabled: Boolean): Boolean = updateState { it.copy(biometricEnabled = enabled) }
    fun setDecoyEnabled(enabled: Boolean): Boolean = updateState { it.copy(decoyEnabled = enabled) }
    fun setGraceSeconds(seconds: Int): Boolean = updateState { it.copy(graceSeconds = seconds.coerceIn(0, 600)) }

    /** Milliseconds left of the current lock-out, 0 when the app is usable. */
    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (state.lockedUntil - now).coerceAtLeast(0L)

    /** True when the decoy route should be shown instead of the keypad. */
    fun isDecoyActive(): Boolean = state.decoyEnabled && state.failures >= DECOY_THRESHOLD

    /** The optional compass hint is consumed only if the update is persisted. */
    fun consumeUnlockHint(): Boolean {
        val current = state
        if (current.hintUses >= 3 || loadError != null) return false
        return persist(current.copy(hintUses = current.hintUses + 1))
    }

    /** Creates or replaces the PIN. Returns false if validation or durable save fails. */
    suspend fun setPin(
        pin: String,
        initialBearing: Float? = null,
        bearingEnabled: Boolean? = null,
        biometricEnabled: Boolean? = null
    ): Boolean = withContext(Dispatchers.Default) {
        if (loadError != null || !isValidPin(pin)) return@withContext false
        mutex.withLock {
            if (loadError != null) return@withLock false
            val salt = PinHasher.newSalt()
            val hash = PinHasher.derive(pin, salt, ITERATIONS)
            val normalizedBearing = initialBearing?.let { ((it % 360f) + 360f) % 360f }
            val next = state.copy(
                salt = salt,
                hash = hash,
                iterations = ITERATIONS,
                failures = 0,
                lockedUntil = 0L,
                setupDone = true,
                secretBearing = normalizedBearing ?: state.secretBearing,
                bearingEnabled = bearingEnabled ?: state.bearingEnabled,
                biometricEnabled = biometricEnabled ?: state.biometricEnabled
            )
            persist(next).also { saved -> if (!saved) SecureWipe.wipe(hash) }
        }
    }

    /** Verify a PIN; failures are only reported after lockout state is durably saved. */
    suspend fun verify(pin: String): VerifyResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (loadError != null) return@withLock VerifyResult.StorageError(STATE_ERROR)
            val current = state
            if (current.hash.isEmpty() || !current.setupDone) return@withLock VerifyResult.NotConfigured
            val remaining = (current.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > 0L) return@withLock VerifyResult.LockedOut(remaining)

            val candidate = try {
                PinHasher.derive(pin, current.salt, current.iterations)
            } catch (_: Exception) {
                return@withLock VerifyResult.StorageError(STATE_ERROR)
            }
            val matches = try {
                PinHasher.constantTimeEquals(candidate, current.hash)
            } finally {
                SecureWipe.wipe(candidate)
            }

            if (matches) {
                if (!persist(current.copy(failures = 0, lockedUntil = 0L))) {
                    VerifyResult.StorageError(STATE_ERROR)
                } else {
                    VerifyResult.Ok
                }
            } else {
                val failures = current.failures + 1
                val delay = lockoutDelayFor(failures)
                val lockedUntil = if (failures > FREE_ATTEMPTS) System.currentTimeMillis() + delay else 0L
                if (!persist(current.copy(failures = failures, lockedUntil = lockedUntil))) {
                    VerifyResult.StorageError(STATE_ERROR)
                } else {
                    VerifyResult.Wrong(failures, delay.takeIf { failures > FREE_ATTEMPTS } ?: 0L)
                }
            }
        }
    }

    fun isValidPin(pin: String): Boolean = PinHasher.isValidPin(pin)

    private fun updateState(transform: (State) -> State): Boolean = synchronized(this) {
        if (loadError != null) return@synchronized false
        persist(transform(state))
    }

    private fun lockoutDelayFor(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0L
        var delay = BASE_LOCKOUT_MS
        repeat(minOf(failures - FREE_ATTEMPTS, 6)) { delay *= LOCKOUT_GROWTH }
        return delay.coerceAtMost(MAX_LOCKOUT_MS)
    }

    private fun read(): State {
        if (!file.exists()) return State()
        val clear = try {
            cipher.decryptBytes(file.readBytes())
        } catch (error: Exception) {
            loadError = error
            return State(setupDone = true)
        }
        return try {
            val json = JSONObject(String(clear, Charsets.UTF_8))
            val loaded = State(
                salt = Base64.decode(json.optString("salt"), Base64.NO_WRAP),
                hash = Base64.decode(json.optString("hash"), Base64.NO_WRAP),
                iterations = json.optInt("iterations", ITERATIONS),
                failures = json.optInt("failures", 0).coerceAtLeast(0),
                lockedUntil = json.optLong("lockedUntil", 0L).coerceAtLeast(0L),
                setupDone = json.optBoolean("setupDone", false),
                secretBearing = json.optDouble("secretBearing", 90.0).toFloat().let { ((it % 360f) + 360f) % 360f },
                bearingEnabled = json.optBoolean("bearingEnabled", true),
                biometricEnabled = json.optBoolean("biometricEnabled", false),
                decoyEnabled = json.optBoolean("decoyEnabled", true),
                graceSeconds = json.optInt("graceSeconds", 30).coerceIn(0, 600),
                hintUses = json.optInt("hintUses", 0).coerceAtLeast(0)
            )
            if (loaded.setupDone && (loaded.salt.size != 16 || loaded.hash.size != 32 || loaded.iterations !in 10_000..1_000_000)) {
                throw IOException("Invalid authenticated PIN state")
            }
            loaded
        } catch (error: Exception) {
            // Do not reset to setup: that would let someone overwrite the PIN
            // state while leaving an existing encrypted vault behind.
            loadError = error
            State(setupDone = true)
        } finally {
            SecureWipe.wipe(clear)
        }
    }

    /** AtomicFileWriter preserves the last valid state if an update fails. */
    private fun persist(next: State): Boolean = synchronized(this) {
        if (loadError != null) return@synchronized false
        val json = JSONObject().apply {
            put("salt", Base64.encodeToString(next.salt, Base64.NO_WRAP))
            put("hash", Base64.encodeToString(next.hash, Base64.NO_WRAP))
            put("iterations", next.iterations)
            put("failures", next.failures)
            put("lockedUntil", next.lockedUntil)
            put("setupDone", next.setupDone)
            put("secretBearing", next.secretBearing.toDouble())
            put("bearingEnabled", next.bearingEnabled)
            put("biometricEnabled", next.biometricEnabled)
            put("decoyEnabled", next.decoyEnabled)
            put("graceSeconds", next.graceSeconds)
            put("hintUses", next.hintUses)
        }
        val clear = json.toString().toByteArray(Charsets.UTF_8)
        try {
            AtomicFileWriter.write(file) { output -> output.write(cipher.encryptBytes(clear)) }
            state = next
            true
        } catch (_: Exception) {
            false
        } finally {
            SecureWipe.wipe(clear)
        }
    }

    sealed class VerifyResult {
        object Ok : VerifyResult()
        object NotConfigured : VerifyResult()
        data class Wrong(val failures: Int, val lockoutMs: Long) : VerifyResult()
        data class LockedOut(val remainingMs: Long) : VerifyResult()
        data class StorageError(val message: String) : VerifyResult()
    }
}
