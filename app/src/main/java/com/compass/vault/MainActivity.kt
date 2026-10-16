package com.compass.vault

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.compass.vault.data.MediaFile
import com.compass.vault.data.VaultFile
import com.compass.vault.security.LockController
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.PinReason
import com.compass.vault.ui.Screen
import com.compass.vault.ui.screen.CompassScreen
import com.compass.vault.ui.screen.MediaPreviewScreen
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
 *  * apply **FLAG_SECURE** to ask Android to block screenshots and recording
 *    and redact the recent-app preview (platform behavior can vary),
 *  * drive the automatic locking when the app goes to the background,
 *  * talk to the system file picker (Storage Access Framework) and to the
 *    MediaStore delete request.
 */
class MainActivity : Activity(), Navigator {

    companion object {
        private const val REQUEST_PICK_FILES = 1001
        private const val REQUEST_EXPORT_TARGET = 1002
        private const val REQUEST_RESTORE_TARGET = 1003
        private const val REQUEST_DELETE_ORIGINAL = 1004
        private const val REQUEST_MEDIA_PERMISSION = 1005
        private const val REQUEST_LEGACY_WRITE_PERMISSION = 1006
        private const val STATE_ROUTE = "route"
        private const val ROUTE_SETUP = "setup"
        private const val ROUTE_COMPASS = "compass"
        private const val ROUTE_VAULT = "vault"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_VIEWER = "viewer"
    }

    private lateinit var graph: AppGraph
    // Created in onCreate(): a field initializer runs before attachBaseContext(),
    // so a View built here would crash with a null base context at launch.
    private lateinit var root: FrameLayout
    private var currentScreen: Screen? = null
    private var route: String = ROUTE_COMPASS
    private var viewerFile: VaultFile? = null

    private var pendingPickCallback: ((List<Uri>) -> Unit)? = null
    private var pendingExportCallback: ((Uri) -> Unit)? = null
    private var pendingRestoreCallback: ((Uri) -> Unit)? = null
    private var pendingDeleteCallback: ((Boolean, String?) -> Unit)? = null
    private var pendingDeleteUri: Uri? = null
    private var pendingMediaPermissionCallback: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        root = FrameLayout(this)
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

    override fun showMediaPreview(file: MediaFile) {
        route = ROUTE_VIEWER
        show(MediaPreviewScreen(this, graph, this, file))
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
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        pendingPickCallback = onPicked
        systemPickerOpen = true
        try {
            startActivityForResult(intent, REQUEST_PICK_FILES)
        } catch (_: Exception) {
            systemPickerOpen = false
            pendingPickCallback = null
            toast("The system file picker could not be opened")
        }
    }

    override fun pickExportTarget(suggestedName: String, onTarget: (Uri) -> Unit) {
        pendingExportCallback = onTarget
        launchCreateDocument(suggestedName, "*/*", REQUEST_EXPORT_TARGET)
    }

    override fun pickRestoreTarget(suggestedName: String, mimeType: String, onTarget: (Uri) -> Unit) {
        pendingRestoreCallback = onTarget
        launchCreateDocument(suggestedName, mimeType, REQUEST_RESTORE_TARGET)
    }

    private fun launchCreateDocument(suggestedName: String, mimeType: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_TITLE, suggestedName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        systemPickerOpen = true
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            systemPickerOpen = false
            pendingExportCallback = null
            pendingRestoreCallback = null
            toast("The system save picker could not be opened")
        }
    }

    override fun hasMediaAccess(): Boolean {
        fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_AUDIO) ||
                (Build.VERSION.SDK_INT >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun hasPartialMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasMediaAccess()) return false
        val allCategories = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        return !allCategories
    }

    override fun requestMediaAccess(onResult: (Boolean) -> Unit) {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isEmpty()) {
            onResult(hasMediaAccess())
            return
        }
        pendingMediaPermissionCallback = onResult
        systemPickerOpen = true
        requestPermissions(missing, REQUEST_MEDIA_PERMISSION)
    }

    /**
     * Remove a public source only after the repository has verified its
     * encrypted copy. Android-owned media deletion is a system-consent flow.
     */
    override fun requestDeleteOriginal(uri: Uri, onResult: (Boolean, String?) -> Unit) {
        val mediaUri = mediaStoreUriFor(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaUri != null) {
            launchDeleteRequest(mediaUri, onResult)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mediaUri != null &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDeleteUri = mediaUri
            pendingDeleteCallback = onResult
            systemPickerOpen = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_LEGACY_WRITE_PERMISSION)
            return
        }
        performDirectDelete(uri, mediaUri, onResult)
    }

    private fun launchDeleteRequest(uri: Uri, callback: (Boolean, String?) -> Unit, sender: IntentSender? = null) {
        pendingDeleteUri = uri
        pendingDeleteCallback = callback
        systemPickerOpen = true
        try {
            val intentSender = sender ?: MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
            startIntentSenderForResult(intentSender, REQUEST_DELETE_ORIGINAL, null, 0, 0, 0)
        } catch (error: Exception) {
            systemPickerOpen = false
            pendingDeleteUri = null
            pendingDeleteCallback = null
            callback(false, error.message ?: "Android could not start the deletion request")
        }
    }

    private fun performDirectDelete(originalUri: Uri, mediaUri: Uri?, callback: (Boolean, String?) -> Unit) {
        val target = mediaUri ?: originalUri
        Thread({
            var removed = false
            var message: String? = null
            var confirmation: IntentSender? = null
            try {
                removed = if (mediaUri == null && DocumentsContract.isDocumentUri(this, originalUri)) {
                    DocumentsContract.deleteDocument(contentResolver, originalUri)
                } else {
                    contentResolver.delete(target, null, null) > 0
                }
                if (removed && !verifyUriAbsent(target)) {
                    removed = false
                    message = "Android reported deletion, but the original is still discoverable"
                }
                if (!removed && message == null) message = "The provider did not confirm deletion"
            } catch (error: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    confirmation = error.userAction.actionIntent.intentSender
                } else {
                    message = "Android denied permission to remove the original"
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not remove the original"
            }
            val finalRemoved = removed
            val finalMessage = message
            val sender = confirmation
            mainHandler.post {
                if (sender != null) launchDeleteRequest(target, callback, sender)
                else callback(finalRemoved, finalMessage)
            }
        }, "Compass-source-delete").start()
    }

    /** Convert a media document URI to a specific MediaStore item URI. */
    private fun mediaStoreUriFor(uri: Uri): Uri? {
        if (uri.authority == "media") return uri
        if (uri.authority != "com.android.providers.media.documents") return null
        return try {
            val parts = DocumentsContract.getDocumentId(uri).split(':')
            if (parts.size < 2) return null
            val id = parts.last().toLongOrNull() ?: return null
            val volume = if (parts.size >= 3) parts[1] else MediaStore.VOLUME_EXTERNAL_PRIMARY
            val collection = when (parts.first()) {
                "image" -> MediaStore.Images.Media.getContentUri(volume)
                "video" -> MediaStore.Video.Media.getContentUri(volume)
                "audio" -> MediaStore.Audio.Media.getContentUri(volume)
                "file" -> MediaStore.Files.getContentUri(volume)
                else -> return null
            }
            android.content.ContentUris.withAppendedId(collection, id)
        } catch (_: Exception) {
            null
        }
    }

    /** A source is considered absent only when a read also proves it is gone. */
    private fun verifyUriAbsent(uri: Uri): Boolean {
        try {
            val column = if (DocumentsContract.isDocumentUri(this, uri)) "display_name" else "_id"
            val cursor = contentResolver.query(uri, arrayOf(column), null, null, null)
            if (cursor != null) cursor.use { if (it.moveToFirst()) return false }
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            // Some providers do not implement query; fall through to the read check.
        }
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            input.close()
            false
        } catch (_: java.io.FileNotFoundException) {
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
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
        when (requestCode) {
            REQUEST_PICK_FILES -> {
                val uris = mutableListOf<Uri>()
                data?.data?.let(uris::add)
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
                }
                val unique = uris.distinct()
                val returnedFlags = data?.flags ?: 0
                val persistableFlags = returnedFlags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                unique.forEach { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, persistableFlags) }
                    catch (_: Exception) { /* provider may grant only a temporary URI */ }
                }
                val callback = pendingPickCallback
                pendingPickCallback = null
                if (resultCode == Activity.RESULT_OK && unique.isNotEmpty()) callback?.invoke(unique)
            }

            REQUEST_EXPORT_TARGET -> {
                val callback = pendingExportCallback
                pendingExportCallback = null
                if (resultCode == Activity.RESULT_OK) data?.data?.let { callback?.invoke(it) }
            }

            REQUEST_RESTORE_TARGET -> {
                val callback = pendingRestoreCallback
                pendingRestoreCallback = null
                if (resultCode == Activity.RESULT_OK) data?.data?.let { callback?.invoke(it) }
            }

            REQUEST_DELETE_ORIGINAL -> {
                val uri = pendingDeleteUri
                val callback = pendingDeleteCallback
                pendingDeleteUri = null
                pendingDeleteCallback = null
                if (callback != null && uri != null) {
                    if (resultCode != Activity.RESULT_OK) {
                        callback(false, "Deletion was cancelled; the original remains accessible")
                    } else {
                        Thread({
                            val absent = verifyUriAbsent(uri)
                            mainHandler.post {
                                callback(
                                    absent,
                                    if (absent) null else "Android did not verify that the original was removed"
                                )
                            }
                        }, "Compass-source-verification").start()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        systemPickerOpen = false
        when (requestCode) {
            REQUEST_MEDIA_PERMISSION -> {
                val callback = pendingMediaPermissionCallback
                pendingMediaPermissionCallback = null
                callback?.invoke(hasMediaAccess())
            }

            REQUEST_LEGACY_WRITE_PERMISSION -> {
                val uri = pendingDeleteUri
                val callback = pendingDeleteCallback
                pendingDeleteUri = null
                pendingDeleteCallback = null
                val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (granted && uri != null && callback != null) {
                    performDirectDelete(uri, uri) { removed, message -> callback(removed, message) }
                } else {
                    callback?.invoke(false, "Storage permission was denied; the original remains accessible")
                }
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
