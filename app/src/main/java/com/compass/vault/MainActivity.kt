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
import com.compass.vault.ui.screen.LibraryScreen
import com.compass.vault.ui.screen.MediaPreviewScreen
import com.compass.vault.ui.screen.PinScreen
import com.compass.vault.ui.screen.SettingsScreen
import com.compass.vault.ui.screen.SetupScreen
import com.compass.vault.ui.screen.ViewerScreen

/**
 * The only Activity of the app.
 *
 * Responsibilities:
 *  * host the single screen container,
 *  * apply **FLAG_SECURE** to ask Android to block screenshots and recording
 *    and to redact the recent-app preview (platform behavior can vary),
 *  * drive the automatic locking when the app goes to the background,
 *  * talk to the system file pickers (Storage Access Framework) and to the
 *    Android deletion consent that scoped storage requires before an app may
 *    remove another app's media file.
 */
class MainActivity : Activity(), Navigator {

    companion object {
        private const val REQUEST_PICK_FILES = 1001
        private const val REQUEST_RESTORE_TARGET = 1003
        private const val REQUEST_DELETE_ORIGINALS = 1004
        private const val REQUEST_MEDIA_PERMISSION = 1005
        private const val REQUEST_LEGACY_WRITE_PERMISSION = 1006
        private const val REQUEST_PICK_LIBRARY_FOLDER = 1007

        /**
         * Android 10 asks for one consent per media item through
         * [RecoverableSecurityException]; each answer arrives here.
         */
        private const val REQUEST_DELETE_RECOVERABLE = 1008
        private const val STATE_ROUTE = "route"
        private const val ROUTE_SETUP = "setup"
        private const val ROUTE_COMPASS = "compass"
        private const val ROUTE_LIBRARY = "library"
        private const val ROUTE_VAULT = "vault"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_VIEWER = "viewer"
        private const val ROUTE_PIN = "pin"
    }

    private lateinit var graph: AppGraph
    // Created in onCreate(): a field initializer runs before attachBaseContext(),
    // so a View built here would crash with a null base context at launch.
    private lateinit var root: FrameLayout
    private var currentScreen: Screen? = null
    private var route: String = ROUTE_COMPASS
    private var viewerFile: VaultFile? = null

    private var pendingPickCallback: ((List<Uri>) -> Unit)? = null
    private var pendingLibraryFolderCallback: ((Uri) -> Unit)? = null
    private var pendingVaultViewer: VaultFile? = null
    private var pendingRestoreCallback: ((Uri) -> Unit)? = null
    private var pendingMediaPermissionCallback: ((Boolean) -> Unit)? = null

    /** A batch source-removal request while Android's consent dialog is up. */
    private var pendingDeleteCallback: ((Set<String>, String?) -> Unit)? = null
    private var pendingDeleteTargets: List<Pair<Uri, Uri>> = emptyList()
    private var pendingDeleteRemoved: MutableSet<String> = LinkedHashSet()
    private var pendingDeleteMessages: MutableList<String> = mutableListOf()

    /**
     * Android 10 consent queue. Every media item owned by another app needs its
     * own confirmation there, so the senders are answered one after another
     * instead of failing the whole batch when the first one is refused.
     */
    private val recoverableQueue = ArrayDeque<Pair<Uri, IntentSender>>()
    private var recoverableCurrent: Pair<Uri, IntentSender>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * True while a system picker or a system consent dialog is on screen.
     *
     * Those dialogs belong to another process, so our activity is stopped while
     * they are visible. The screen must not be torn down in that case: the
     * lock transaction continues in the background and its progress has to
     * stay visible when the owner comes back.
     */
    private var systemDialogOpen = false

    private val lockListener = object : LockController.Listener {
        override fun onLockChanged(unlocked: Boolean) {
            if (!unlocked) {
                // Remove authenticated routes immediately; the public library
                // contains only files Android still exposes outside the vault.
                viewerFile = null
                pendingVaultViewer = null
                if (route != ROUTE_SETUP && route != ROUTE_LIBRARY) {
                    route = ROUTE_LIBRARY
                    show(LibraryScreen(this@MainActivity, graph, this@MainActivity), activate = false)
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
        graph.lockController.onReturnedToForeground()
        currentScreen?.onShow()
    }

    override fun onStop() {
        super.onStop()
        graph.lockController.onEnteredBackground()
        if (graph.lockController.isUnlocked && !systemDialogOpen) {
            // Replace protected rows/viewers before the task is shown in recents.
            // No sensors or scans are started while the app is in the background.
            route = ROUTE_LIBRARY
            show(LibraryScreen(this, graph, this), activate = false)
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
            ROUTE_VAULT, ROUTE_SETTINGS, ROUTE_VIEWER, ROUTE_PIN -> showLibrary()
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
     *        started. That is used for the "pretend to be a library while we
     *        are in the background" switch, so no scanning keeps running
     *        behind the user's back.
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

    override fun showLibrary() {
        pendingVaultViewer = null
        route = ROUTE_LIBRARY
        show(LibraryScreen(this, graph, this))
    }

    override fun showCompass() {
        route = ROUTE_COMPASS
        show(CompassScreen(this, graph, this))
    }

    override fun showVault() {
        if (!graph.lockController.isUnlocked) {
            showPin(PinReason.VAULT_ACCESS)
            return
        }
        val pending = pendingVaultViewer
        pendingVaultViewer = null
        if (pending != null) {
            route = ROUTE_VIEWER
            viewerFile = pending
            show(ViewerScreen(this, graph, this, pending))
        } else {
            route = ROUTE_VAULT
            show(LibraryScreen(this, graph, this, initialLocked = true))
        }
    }

    override fun showPin(reason: PinReason) {
        route = ROUTE_PIN
        show(PinScreen(this, graph, this, reason))
    }

    override fun showSettings() {
        if (!graph.lockController.isUnlocked) {
            showPin(PinReason.SETTINGS_ACCESS)
            return
        }
        route = ROUTE_SETTINGS
        show(SettingsScreen(this, graph, this))
    }

    override fun showViewer(file: VaultFile) {
        if (!graph.lockController.isUnlocked) {
            pendingVaultViewer = file
            showPin(PinReason.VAULT_ACCESS)
            return
        }
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
        systemDialogOpen = true
        try {
            startActivityForResult(intent, REQUEST_PICK_FILES)
        } catch (_: Exception) {
            systemDialogOpen = false
            pendingPickCallback = null
            toast("The system file picker could not be opened")
        }
    }

    override fun pickLibraryFolder(onPicked: (Uri) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            // Open the picker on a folder that usually holds documents. On
            // Android 11+ the storage root and the Download folder itself
            // cannot be granted any more, their subfolders can.
            runCatching {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Documents"
                    )
                )
            }
        }
        pendingLibraryFolderCallback = onPicked
        systemDialogOpen = true
        try {
            startActivityForResult(intent, REQUEST_PICK_LIBRARY_FOLDER)
        } catch (_: Exception) {
            systemDialogOpen = false
            pendingLibraryFolderCallback = null
            toast("Android could not open the folder picker")
        }
    }

    override fun openPublicFile(uri: Uri, mimeType: String) {
        if (mimeType == "application/vnd.android.package-archive" ||
            uri.lastPathSegment?.endsWith(".apk", ignoreCase = true) == true
        ) {
            toast("APK files can be protected as files; Compass will not install or execute them")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.takeIf { it.isNotBlank() } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("No installed app can open this file type")
        }
    }

    override fun pickRestoreTarget(suggestedName: String, mimeType: String, onTarget: (Uri) -> Unit) {
        pendingRestoreCallback = onTarget
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_TITLE, suggestedName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        systemDialogOpen = true
        try {
            startActivityForResult(intent, REQUEST_RESTORE_TARGET)
        } catch (_: Exception) {
            systemDialogOpen = false
            pendingRestoreCallback = null
            toast("The system save dialog could not be opened")
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
        systemDialogOpen = true
        requestPermissions(missing, REQUEST_MEDIA_PERMISSION)
    }

    /** True when the URI is a SAF document that is not a MediaStore item. */
    private fun isSafDocumentUri(uri: Uri): Boolean =
        uri.authority != "media" && DocumentsContract.isDocumentUri(this, uri)

    /**
     * Remove public originals after the repository has verified the encrypted
     * copy of every one of them.
     *
     * SAF documents are deleted through their provider (no dialog). MediaStore
     * items need Android's own consent on Android 11+ - that consent is asked
     * once for the whole batch - and are deleted directly with the legacy
     * write permission on Android 8-9. Every removal is verified afterwards;
     * the callback reports exactly which sources are provably gone.
     */
    override fun requestDeleteOriginals(
        uris: List<Uri>,
        onResult: (removed: Set<String>, message: String?) -> Unit
    ) {
        if (uris.isEmpty()) {
            onResult(emptySet(), null)
            return
        }
        pendingDeleteCallback = onResult
        pendingDeleteRemoved = LinkedHashSet()
        pendingDeleteMessages = mutableListOf()
        pendingDeleteTargets = emptyList()
        systemDialogOpen = true
        val needsLegacyWritePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            uris.any { !isSafDocumentUri(it) } &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyWritePermission) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_LEGACY_WRITE_PERMISSION)
            pendingDeleteTargets = uris.map { it to (mediaStoreUriFor(it) ?: it) }
            return
        }
        startRemoval(uris)
    }

    private fun startRemoval(uris: List<Uri>) {
        Thread({
            val removed = LinkedHashSet<String>()
            val messages = mutableListOf<String>()
            val safDocuments = uris.filter { isSafDocumentUri(it) }
            val others = uris.filterNot { isSafDocumentUri(it) }

            for (original in safDocuments) {
                val deleted = try {
                    DocumentsContract.deleteDocument(contentResolver, original)
                } catch (_: Exception) {
                    false
                }
                if (deleted && verifyUriAbsent(original)) {
                    removed += original.toString()
                } else {
                    messages += "A provider refused to delete one of the approved documents."
                }
            }

            val mediaTargets = ArrayList<Pair<Uri, Uri>>()
            for (original in others) {
                val target = mediaStoreUriFor(original)
                if (target != null) mediaTargets += original to target
            }
            val direct = others.filter { original -> mediaTargets.none { it.first == original } }
            for (original in direct) {
                val deleted = try {
                    contentResolver.delete(original, null, null) > 0
                } catch (_: Exception) {
                    false
                }
                if (deleted && verifyUriAbsent(original)) removed += original.toString()
                else messages += "One source could not be removed through its provider."
            }

            var consentNeeded = false
            val queued = ArrayList<Pair<Uri, IntentSender>>()
            if (mediaTargets.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    consentNeeded = true
                } else {
                    for ((original, target) in mediaTargets) {
                        val sender = deleteMediaItem(original, target, removed, messages)
                        if (sender != null) queued += original to sender
                    }
                }
            }

            mainHandler.post {
                if (consentNeeded) {
                    pendingDeleteTargets = mediaTargets
                    pendingDeleteRemoved.addAll(removed)
                    pendingDeleteMessages.addAll(messages)
                    launchDeleteConsent(mediaTargets.map { it.second })
                } else if (queued.isNotEmpty()) {
                    pendingDeleteRemoved.addAll(removed)
                    pendingDeleteMessages.addAll(messages)
                    recoverableQueue.clear()
                    recoverableQueue.addAll(queued)
                    askNextRecoverable()
                } else {
                    finishRemoval(removed, messages)
                }
            }
        }, "Compass-source-removal").start()
    }

    /**
     * Removes one media item below Android 11.
     *
     * On Android 10 a file that another app owns cannot be deleted directly:
     * the platform answers with a [RecoverableSecurityException] that carries
     * the consent dialog for exactly this item. The dialog is answered in
     * [askNextRecoverable], one item at a time, and the result is verified
     * like every other removal.
     *
     * On Android 8 and 9 the delete goes through the write permission that is
     * requested before the batch starts.
     */
    private fun deleteMediaItem(
        original: Uri,
        target: Uri,
        removed: MutableSet<String>,
        messages: MutableList<String>
    ): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val deleted = contentResolver.delete(target, null, null) > 0
                if (deleted && verifyUriAbsent(original)) removed += original.toString()
                else messages += "Android did not confirm the deletion of one source."
            } catch (recoverable: RecoverableSecurityException) {
                return recoverable.userAction.actionIntent.intentSender
            } catch (_: Exception) {
                messages += "Android did not confirm the deletion of one source."
            }
            return null
        }
        try {
            val deleted = contentResolver.delete(target, null, null) > 0
            if (deleted && verifyUriAbsent(original)) removed += original.toString()
            else messages += "Android did not confirm the deletion of one source."
        } catch (_: Exception) {
            messages += "Android did not confirm the deletion of one source."
        }
        return null
    }

    private fun launchDeleteConsent(targets: List<Uri>) {
        try {
            val request = MediaStore.createDeleteRequest(contentResolver, targets)
            startIntentSenderForResult(request.intentSender, REQUEST_DELETE_ORIGINALS, null, 0, 0, 0)
        } catch (error: Exception) {
            finishRemoval(
                pendingDeleteRemoved,
                (pendingDeleteMessages + (error.message ?: "Android could not show the deletion dialog.")).toMutableList()
            )
        }
    }

    /**
     * Android 10 only: asks the owner about the next queued media item. Every
     * answer is verified before the next dialog is shown, and a refusal only
     * marks that one item as not removed.
     */
    private fun askNextRecoverable() {
        val next = recoverableQueue.removeFirstOrNull()
        if (next == null) {
            recoverableCurrent = null
            finishRemoval(pendingDeleteRemoved, pendingDeleteMessages.toMutableList())
            return
        }
        recoverableCurrent = next
        try {
            startIntentSenderForResult(next.second, REQUEST_DELETE_RECOVERABLE, null, 0, 0, 0)
        } catch (_: Exception) {
            recoverableCurrent = null
            pendingDeleteMessages += "Android refused to ask about one original; it was left untouched."
            askNextRecoverable()
        }
    }

    /** Verifies one Android 10 consent answer, then continues the queue. */
    private fun confirmRecoverable(original: Uri, approved: Boolean) {
        Thread({
            if (!approved) {
                pendingDeleteMessages += "One public original was kept: the deletion was cancelled."
            } else if (verifyUriAbsent(original)) {
                pendingDeleteRemoved += original.toString()
            } else {
                pendingDeleteMessages += "Android reported success, but one original is still readable."
            }
            mainHandler.post { askNextRecoverable() }
        }, "Compass-source-verification").start()
    }

    /** Publishes the batch result and delivers it to the waiting screen. */
    private fun finishRemoval(removed: Set<String>, messages: List<String>) {
        systemDialogOpen = false
        recoverableQueue.clear()
        recoverableCurrent = null
        val callback = pendingDeleteCallback
        pendingDeleteCallback = null
        pendingDeleteTargets = emptyList()
        pendingDeleteRemoved = LinkedHashSet()
        pendingDeleteMessages = mutableListOf()
        callback?.invoke(removed, messages.firstOrNull())
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

    /** A source counts as gone only when a read also proves it. */
    private fun verifyUriAbsent(uri: Uri): Boolean {
        try {
            val column = if (DocumentsContract.isDocumentUri(this, uri)) "display_name" else "_id"
            val cursor = contentResolver.query(uri, arrayOf(column), null, null, null)
            if (cursor != null) cursor.use { if (it.moveToFirst()) return false }
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            // Some providers do not implement query; fall through to a read check.
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
        if (requestCode != REQUEST_DELETE_ORIGINALS && requestCode != REQUEST_DELETE_RECOVERABLE) {
            systemDialogOpen = false
        }
        when (requestCode) {
            REQUEST_PICK_LIBRARY_FOLDER -> {
                val uri = data?.data
                val returnedFlags = data?.flags ?: 0
                val persistableFlags = returnedFlags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, persistableFlags)
                    } catch (_: Exception) {
                        toast("Android did not allow persistent access to this folder")
                        return
                    }
                    val callback = pendingLibraryFolderCallback
                    pendingLibraryFolderCallback = null
                    callback?.invoke(uri)
                } else {
                    pendingLibraryFolderCallback = null
                }
            }

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

            REQUEST_RESTORE_TARGET -> {
                val callback = pendingRestoreCallback
                pendingRestoreCallback = null
                if (resultCode == Activity.RESULT_OK) data?.data?.let { callback?.invoke(it) }
            }

            REQUEST_DELETE_RECOVERABLE -> {
                val answered = recoverableCurrent
                recoverableCurrent = null
                if (answered == null) {
                    askNextRecoverable()
                } else {
                    confirmRecoverable(answered.first, resultCode == Activity.RESULT_OK)
                }
            }

            REQUEST_DELETE_ORIGINALS -> {
                if (resultCode != Activity.RESULT_OK) {
                    finishRemoval(
                        pendingDeleteRemoved,
                        (pendingDeleteMessages + "Deletion was cancelled; the public originals remain.").toMutableList()
                    )
                    return
                }
                val targets = pendingDeleteTargets
                val alreadyRemoved = LinkedHashSet(pendingDeleteRemoved)
                val messages = ArrayList(pendingDeleteMessages)
                Thread({
                    val removed = LinkedHashSet(alreadyRemoved)
                    for ((original, _) in targets) {
                        if (verifyUriAbsent(original)) removed += original.toString()
                        else messages += "Android reported success, but one original is still readable."
                    }
                    mainHandler.post { finishRemoval(removed, messages) }
                }, "Compass-source-verification").start()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MEDIA_PERMISSION -> {
                systemDialogOpen = false
                val callback = pendingMediaPermissionCallback
                pendingMediaPermissionCallback = null
                callback?.invoke(hasMediaAccess())
            }

            REQUEST_LEGACY_WRITE_PERMISSION -> {
                val targets = pendingDeleteTargets.map { it.first }
                val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    finishRemoval(
                        emptySet(),
                        mutableListOf("Storage permission was denied; no public original was removed.")
                    )
                    return
                }
                if (targets.isEmpty()) {
                    finishRemoval(emptySet(), mutableListOf("Nothing left to remove."))
                    return
                }
                startRemoval(targets)
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
                show(SetupScreen(this, graph, this), activate = false)
            }

            graph.lockController.isUnlocked -> {
                route = ROUTE_VAULT
                show(LibraryScreen(this, graph, this, initialLocked = true), activate = false)
            }

            else -> {
                // The local file browser is the home screen; opening the
                // authenticated vault tab still requires PIN/biometrics.
                route = ROUTE_LIBRARY
                show(LibraryScreen(this, graph, this), activate = false)
            }
        }
    }
}
