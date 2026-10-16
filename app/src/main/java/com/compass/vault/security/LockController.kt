package com.compass.vault.security

import android.content.Context
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the "is the vault open?" state of the app and the automatic locking
 * policy.
 *
 * Locking rules:
 *
 *  * The vault is closed by default. It is opened by the secret bearing
 *    gesture, by the PIN or by a biometric.
 *  * When the app goes to the background the UI immediately switches back to
 *    the compass (so the recent-apps preview shows a compass, not the vault)
 *    and an auto-lock is armed for the grace period (30 s by default).
 *  * If the owner comes back within the grace period the vault stays open -
 *    no annoying re-authentication while you copy a link into another app.
 *  * When the grace period expires, or the process is killed, the vault is
 *    locked, temporary decrypted files are wiped and thumbnails are dropped.
 */
class LockController(
    private val appContext: Context,
    private val pinStore: PinStore,
    /** Wipes decrypted temporary files and in-memory caches. */
    private val onSecureCleanup: () -> Unit
) {

    interface Listener {
        fun onLockChanged(unlocked: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val listeners = mutableListOf<Listener>()
    private var autoLockJob: Job? = null

    @Volatile
    var isUnlocked: Boolean = false
        private set

    /** True while the app is in the background and the grace timer runs. */
    @Volatile
    var isGracePeriod: Boolean = false
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Opens the vault. */
    fun unlock() {
        autoLockJob?.cancel()
        autoLockJob = null
        isGracePeriod = false
        if (!isUnlocked) {
            isUnlocked = true
            notifyListeners(true)
        }
    }

    /** Closes the vault and wipes everything that was decrypted. */
    fun lock() {
        autoLockJob?.cancel()
        autoLockJob = null
        isGracePeriod = false
        onSecureCleanup()
        if (isUnlocked) {
            isUnlocked = false
            notifyListeners(false)
        }
    }

    /**
     * Called when the activity goes to the background.
     * The UI is switched to the compass immediately, the vault is closed
     * after the grace period.
     */
    fun onEnteredBackground() {
        if (!isUnlocked) return
        isGracePeriod = true
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(pinStore.graceSeconds() * 1000L)
            lock()
        }
    }

    /**
     * Called when the activity comes back to the foreground.
     * Returns true when the vault is still open.
     */
    fun onReturnedToForeground(): Boolean {
        if (!isUnlocked) return false
        // Cancelling the job stops the pending auto-lock.
        autoLockJob?.cancel()
        autoLockJob = null
        isGracePeriod = false
        return true
    }

    /** Immediately closes the vault (used by the "Lock now" action). */
    fun lockNow() = lock()

    private fun notifyListeners(unlocked: Boolean) {
        listeners.toList().forEach { it.onLockChanged(unlocked) }
    }
}
