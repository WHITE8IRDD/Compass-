package com.compass.vault.ui

import android.net.Uri
import android.view.View
import com.compass.vault.data.VaultFile

/**
 * A screen is a plain [View] plus a little lifecycle. Because the app uses a
 * single-activity architecture, the activity swaps the visible child view
 * whenever the route changes.
 */
interface Screen {

    /** The root view of this screen. */
    val view: View

    /** Called right after the screen became visible. */
    fun onShow() {}

    /** Called right before the screen is replaced. */
    fun onHide() {}

    /**
     * @return true when the screen handled the back press itself.
     */
    fun onBack(): Boolean = false
}

/**
 * The navigation contract. Implemented by [com.compass.vault.MainActivity].
 */
interface Navigator {

    /** Replaces the visible screen. */
    fun show(screen: Screen)

    /** Back to the compass (the disguise). */
    fun showCompass()

    /** The vault file list. */
    fun showVault()

    /** The PIN entry screen. */
    fun showPin(reason: PinReason = PinReason.FALLBACK)

    /** The settings screen. */
    fun showSettings()

    /** The image / video viewer. */
    fun showViewer(file: VaultFile)

    /** Opens the system file picker for importing files. */
    fun pickFiles(mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit)

    /** Opens the system "save as" dialog for exporting a file. */
    fun pickExportTarget(suggestedName: String, onTarget: (Uri) -> Unit)

    /**
     * Asks the system to delete the original copies of the imported files.
     * Returns false when the platform cannot do it (Android 9 and older).
     */
    fun requestDeleteOriginals(uris: List<Uri>): Boolean

    /** Shows a short, monochrome toast. */
    fun toast(message: String)

    /** The activity itself (needed for dialogs and the system picker). */
    val activity: android.app.Activity
}

/** Why the PIN screen was opened. */
enum class PinReason {
    /** The owner asked for the PIN (e.g. long press on the dial). */
    FALLBACK,

    /** The secret bearing could not be used (no magnetometer). */
    NO_BEARING,

    /** The owner wants to change the PIN. */
    CHANGE
}
