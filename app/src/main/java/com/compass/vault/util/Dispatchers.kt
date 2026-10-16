package com.compass.vault.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * A `Dispatchers.Main` implementation built on a plain [Handler].
 *
 * The real `kotlinx-coroutines-android` artifact would provide this, but the
 * app deliberately ships without any third-party dependency, so we implement
 * the ~15 lines ourselves. Behaviour is identical: work is posted to the main
 * looper and runs on the UI thread.
 */
object MainThreadDispatcher : CoroutineDispatcher() {

    private val handler = Handler(Looper.getMainLooper())

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        Looper.myLooper() != Looper.getMainLooper()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

/** Convenience aliases so call sites read nicely. */
object AppDispatchers {
    /** UI thread. */
    val main: CoroutineDispatcher get() = MainThreadDispatcher

    /** Background threads for disk and crypto work. */
    val io: CoroutineDispatcher get() = Dispatchers.IO

    /** CPU bound work (PBKDF2, hashing, thumbnails). */
    val default: CoroutineDispatcher get() = Dispatchers.Default
}
