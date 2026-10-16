package com.compass.vault.ui

import android.net.Uri
import android.view.View
import com.compass.vault.data.MediaFile
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

    /** Public local file library (shared storage remains subject to Android grants). */
    fun showLibrary()

    /** Back to the compass screen. */
    fun showCompass()

    /** Opens the authenticated vault list, requesting PIN/biometrics if needed. */
    fun showVault()

    /** The PIN entry screen. */
    fun showPin(reason: PinReason = PinReason.FALLBACK)

    /** The protected security settings screen; requests authentication when locked. */
    fun showSettings()

    /** The image / audio / video viewer for encrypted vault content. */
    fun showViewer(file: VaultFile)

    /** In-app preview for a public MediaStore row (before it is locked). */
    fun showMediaPreview(file: MediaFile)

    /** Opens the system file picker for importing files. */
    fun pickFiles(mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit)

    /** Asks the owner to grant access to a folder for local document/APK browsing. */
    fun pickLibraryFolder(onPicked: (Uri) -> Unit)

    /** Opens a public file with an installed compatible viewer (never used for APK installation). */
    fun openPublicFile(uri: Uri, mimeType: String)

    /** Opens the system "save as" dialog for an explicit plaintext export. */
    fun pickExportTarget(suggestedName: String, onTarget: (Uri) -> Unit)

    /** Opens a SAF destination picker for restoring a protected document. */
    fun pickRestoreTarget(suggestedName: String, mimeType: String, onTarget: (Uri) -> Unit)

    /** Ask Android/provider to remove a public source after the encrypted copy is verified. */
    fun requestDeleteOriginal(uri: Uri, onResult: (removed: Boolean, message: String?) -> Unit)

    /** Whether the user granted read access to any shared-media collection. */
    fun hasMediaAccess(): Boolean

    /** True when Android granted only a subset of shared-media categories/items. */
    fun hasPartialMediaAccess(): Boolean

    /** Request Android media permissions (including partial visual-media access on Android 14+). */
    fun requestMediaAccess(onResult: (Boolean) -> Unit)

    /** Shows a short, monochrome toast. */
    fun toast(message: String)

    /** The activity itself (needed for dialogs and the system picker). */
    val activity: android.app.Activity
}

/** Why the PIN screen was opened. */
enum class PinReason {
    /** The owner asked to enter the authenticated vault. */
    VAULT_ACCESS,

    /** The owner asked to open security settings. */
    SETTINGS_ACCESS,

    /** The owner asked for the PIN (e.g. an optional compass shortcut). */
    FALLBACK,

    /** The secret bearing could not be used (no magnetometer). */
    NO_BEARING,

    /** The owner wants to change the PIN. */
    CHANGE
}
