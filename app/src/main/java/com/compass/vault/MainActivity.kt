package com.compass.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.compass.vault.data.VaultFile
import com.compass.vault.security.LockController
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.PinReason
import com.compass.vault.ui.Screen
import com.compass.vault.ui.screen.CompassScreen
import com.compass.vault.ui.screen.PinScreen
import com.compass.vault.ui.screen.SettingsScreen
import com.compass.vault.ui.screen.SetupScreen
import com.compass.vault.ui.screen.VaultScreen
import com.compass.vault.ui.screen.ViewerScreen

/**
 * The only Activity of the app.
 *
 * Responsibilities:
 *  * host the single screen container,
 *  * apply **FLAG_SECURE** to the window (no screenshots, no screen
 *    recording, no thumbnail in the recent-apps list),
 *  * drive the automatic locking when the app goes to the background,
 *  * talk to the system file picker (Storage Access Framework) and to the
 *    MediaStore delete request.
 */
class MainActivity : Activity(), Navigator {

    companion object {
        private const val REQUEST_PICK_FILES = 1001
        private const val REQUEST_EXPORT_TARGET = 1002
        private const val STATE_ROUTE = "route"
        private const val ROUTE_SETUP = "setup"
        private const val ROUTE_COMPASS = "compass"
        private const val ROUTE_VAULT = "vault"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_VIEWER = "viewer"
    }

    private lateinit var graph: AppGraph
    private val root = FrameLayout(this)
    private var currentScreen: Screen? = null
    private var route: String = ROUTE_COMPASS
    private var viewerFile: VaultFile? = null

    private var pendingPickCallback: ((List<Uri>) -> Unit)? = null
    private var pendingExportCallback: ((Uri) -> Unit)? = null

    /**
     * True while a system picker (import / export) is on screen.
     *
     * The system picker is a different app, so our activity is stopped while
     * it is open. We must not tear the vault screen down in that case: the
     * import continues in the background and its progress has to stay
     * visible when the user comes back.
     */
    private var systemPickerOpen = false

    private val lockListener = object : LockController.Listener {
        override fun onLockChanged(unlocked: Boolean) {
            if (!unlocked) {
                // The vault closed: go back to the compass disguise and drop
                // any decrypted state that is still on screen.
                viewerFile = null
                if (route != ROUTE_SETUP) {
                    route = ROUTE_COMPASS
                    // The lock fires while the app is in the background, so
                    // the screen is only rendered - the magnetometer is
                    // started again in onStart.
                    show(CompassScreen(this@MainActivity, graph, this@MainActivity), activate = false)
                }
            }
        }
    }

    override val activity: Activity get() = this

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ------------------------------------------------------------------
        // SECURITY: FLAG_SECURE.
        // Blocks screenshots, blocks screen recording / screen sharing and
        // replaces the recent-apps thumbnail with a blank rectangle.
        // It is applied to the single window, so it covers every screen.
        // ------------------------------------------------------------------
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        graph = (application as CompassApp).graph
        graph.lockController.addListener(lockListener)

        root.setBackgroundColor(Palette.BLACK)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(root)

        route = savedInstanceState?.getString(STATE_ROUTE) ?: ROUTE_COMPASS
        renderRoute()
    }

    override fun onStart() {
        super.onStart()
        // If the vault is still inside its grace period we bring the owner
        // straight back to the vault instead of asking for the gesture again.
        if (graph.lockController.onReturnedToForeground()) {
            // Only replace the screen when it is not the vault already -
            // returning from the system picker must not restart the screen,
            // because that would cancel a running import.
            if (route != ROUTE_VAULT || currentScreen == null) {
                route = ROUTE_VAULT
                show(VaultScreen(this, graph, this))
            }
        } else {
            // Re-activate whatever is on screen (the compass sensor, ...).
            currentScreen?.onShow()
        }
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded: arm the auto-lock and immediately switch the visible
        // screen back to the compass, so nothing but a compass is on screen
        // when the app is looked at from the launcher / recents.
        graph.lockController.onEnteredBackground()
        if (graph.lockController.isUnlocked && !systemPickerOpen) {
            // Switch back to the compass disguise, but do not start the
            // magnetometer while we are in the background.
            route = ROUTE_COMPASS
            show(CompassScreen(this, graph, this), activate = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ROUTE, route)
    }

    override fun onDestroy() {
        graph.lockController.removeListener(lockListener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentScreen?.onBack() == true) return
        when (route) {
            ROUTE_VAULT, ROUTE_SETTINGS, ROUTE_VIEWER -> showCompass()
            else -> super.onBackPressed()
        }
    }

    // -----------------------------------------------------------------------
    // Navigator
    // -----------------------------------------------------------------------

    override fun show(screen: Screen) {
        show(screen, activate = true)
    }

    /**
     * @param activate when false the screen is only rendered - it is not
     *        started. That is used for the "pretend to be a compass while we
     *        are in the background" switch, so the magnetometer does not
     *        keep running behind the user's back.
     */
    private fun show(screen: Screen, activate: Boolean) {
        currentScreen?.onHide()
        currentScreen = screen
        root.removeAllViews()
        root.addView(screen.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (activate) screen.onShow()
    }

    override fun showCompass() {
        route = ROUTE_COMPASS
        show(CompassScreen(this, graph, this))
    }

    override fun showVault() {
        route = ROUTE_VAULT
        show(VaultScreen(this, graph, this))
    }

    override fun showPin(reason: PinReason) {
        route = ROUTE_COMPASS
        show(PinScreen(this, graph, this, reason))
    }

    override fun showSettings() {
        route = ROUTE_SETTINGS
        show(SettingsScreen(this, graph, this))
    }

    override fun showViewer(file: VaultFile) {
        route = ROUTE_VIEWER
        viewerFile = file
        show(ViewerScreen(this, graph, this, file))
    }

    override fun pickFiles(mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (mimeTypes.size == 1) {
                type = mimeTypes[0]
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingPickCallback = onPicked
        systemPickerOpen = true
        startActivityForResult(intent, REQUEST_PICK_FILES)
    }

    override fun pickExportTarget(suggestedName: String, onTarget: (Uri) -> Unit) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        pendingExportCallback = onTarget
        systemPickerOpen = true
        startActivityForResult(intent, REQUEST_EXPORT_TARGET)
    }

    /**
     * Asks the system to delete the original copies of the imported files.
     *
     * The Storage Access Framework hands out *document* URIs
     * (`content://com.android.providers.media.documents/document/image%2F42`).
     * The delete request of the platform only understands *MediaStore* URIs
     * (`content://media/external/images/media/42`), so the document id is
     * translated back into a MediaStore id first.
     *
     * @return true when the system dialog was shown, false when the device
     *         or the file type does not support it.
     */
    override fun requestDeleteOriginals(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val mediaUris = uris.mapNotNull { mediaStoreUriFor(it) }
        if (mediaUris.isEmpty()) return false

        return try {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, mediaUris)
            startIntentSender(pendingIntent.intentSender, null, 0, 0, 0, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts a media document URI into the matching MediaStore URI.
     * Returns null for files that do not live in the media library
     * (downloads, PDFs, ...) - those cannot be deleted that way.
     */
    private fun mediaStoreUriFor(uri: Uri): Uri? {
        if (uri.authority != "com.android.providers.media.documents") return null
        val documentId = uri.lastPathSegment ?: return null
        val type = documentId.substringBefore('/')
        val id = documentId.substringAfterLast('/')
        if (id.toLongOrNull() == null) return null
        val collection = when (type) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        return collection.buildUpon().appendPath(id).build()
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -----------------------------------------------------------------------
    // Results
    // -----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        systemPickerOpen = false
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_FILES -> {
                val uris = mutableListOf<Uri>()
                data?.data?.let { uris.add(it) }
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }
                if (uris.isNotEmpty()) pendingPickCallback?.invoke(uris)
            }

            REQUEST_EXPORT_TARGET -> {
                data?.data?.let { pendingExportCallback?.invoke(it) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------------

    private fun renderRoute() {
        when {
            !graph.pinStore.isSetupDone() -> {
                route = ROUTE_SETUP
                show(SetupScreen(this, graph, this))
            }

            graph.lockController.isUnlocked -> {
                route = ROUTE_VAULT
                show(VaultScreen(this, graph, this))
            }

            else -> {
                route = ROUTE_COMPASS
                show(CompassScreen(this, graph, this))
            }
        }
    }
}
