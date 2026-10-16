package com.compass.vault.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.compass.vault.security.KeystoreCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * File discovery inside the Storage Access Framework folders the owner has
 * explicitly approved.
 *
 * Android does not let an ordinary app crawl arbitrary shared-storage paths
 * (and Compass deliberately does not request `MANAGE_EXTERNAL_STORAGE`), so
 * documents, APKs and other non-media files are only ever listed from a
 * persisted `ACTION_OPEN_DOCUMENT_TREE` grant. Several folders can be granted
 * - on Android 11+ the picker cannot grant the storage root, an SD-card root
 * or the `Download` folder itself, but it can grant their subfolders.
 *
 * Documents found here can be deleted through the provider (`deleteDocument`)
 * with no extra dialog, which is what makes locking a SAF file a single tap.
 */
class DocumentTreeLibraryRepository(
    context: Context,
    private val cipher: KeystoreCipher
) {
    companion object {
        private const val CONFIG_FILE = "library-folders.enc"
        private const val LEGACY_CONFIG_FILE = "library-folder.enc"
        private const val MAX_FILES = 20_000
        private const val MAX_DEPTH = 16
        private const val MAX_FOLDERS = 16
        private const val DIRECTORY_MIME = "vnd.android.document/directory"
    }

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val configFile = File(appContext.filesDir, CONFIG_FILE)
    private val legacyConfigFile = File(appContext.filesDir, LEGACY_CONFIG_FILE)

    // -----------------------------------------------------------------------
    // Persisted folder grants
    // -----------------------------------------------------------------------

    /** Every approved folder, with grants that Android has dropped removed. */
    suspend fun folders(): List<Uri> = withContext(Dispatchers.IO) {
        val stored = readFolders()
        if (stored.isEmpty()) return@withContext emptyList()
        val live = persistedTreeUris()
        val usable = if (live.isEmpty()) stored else stored.filter { it in live }
        if (usable.size != stored.size) runCatching { writeFolders(usable) }
        usable
    }

    /** Adds a folder grant; returns false when it could not be persisted. */
    suspend fun addFolder(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw IOException("The selected location is not an Android folder")
        }
        val current = folders().toMutableList()
        current.remove(treeUri)
        current.add(0, treeUri)
        while (current.size > MAX_FOLDERS) current.removeAt(current.size - 1)
        writeFolders(current)
        true
    }

    suspend fun removeFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val current = folders().toMutableList()
        if (current.remove(treeUri)) writeFolders(current)
    }

    suspend fun folderNames(): List<String> = withContext(Dispatchers.IO) {
        folders().map { displayNameOf(it) }
    }

    private fun displayNameOf(tree: Uri): String {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val name = rootId?.let { queryDisplayName(DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
        if (!name.isNullOrBlank()) return name
        return runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':') }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Folder"
    }

    private fun persistedTreeUris(): Set<Uri> = try {
        resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(HashSet()) { it.uri }
    } catch (_: Exception) {
        emptySet()
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    /**
     * Walks every approved folder. The result is bounded ([MAX_FILES],
     * [MAX_DEPTH]) so a mistakenly granted huge tree cannot freeze the app;
     * [TreeLibraryScan.truncated] tells the UI that the list is incomplete,
     * and unreadable folders are reported instead of being silently skipped.
     */
    suspend fun scan(onProgress: (Int) -> Unit = {}): TreeLibraryScan = withContext(Dispatchers.IO) {
        val trees = folders()
        if (trees.isEmpty()) return@withContext TreeLibraryScan(emptyList(), emptyList())
        val coroutineContext = currentCoroutineContext()
        val files = ArrayList<MediaFile>()
        val names = ArrayList<String>()
        val failed = ArrayList<String>()
        var truncated = false

        for (tree in trees) {
            coroutineContext.ensureActive()
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            val rootName = displayNameOf(tree)
            names += rootName
            if (rootId == null) {
                failed += rootName
                continue
            }
            val visited = HashSet<String>()
            try {
                walk(
                    tree = tree,
                    parentId = rootId,
                    relativePath = "",
                    depth = 0,
                    results = files,
                    visited = visited,
                    onProgress = onProgress,
                    coroutineContext = coroutineContext
                ) { limitReached -> if (limitReached) truncated = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The grant may have been revoked mid-scan; report the folder
                // instead of dropping its files without a word.
                failed += rootName
            }
        }
        TreeLibraryScan(names, files.distinctBy { it.locationKey() }, truncated, failed)
    }

    private suspend fun walk(
        tree: Uri,
        parentId: String,
        relativePath: String,
        depth: Int,
        results: MutableList<MediaFile>,
        visited: MutableSet<String>,
        onProgress: (Int) -> Unit,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        onLimit: (Boolean) -> Unit
    ) {
        coroutineContext.ensureActive()
        if (depth > MAX_DEPTH || results.size >= MAX_FILES || !visited.add(parentId)) {
            if (depth > MAX_DEPTH || results.size >= MAX_FILES) onLimit(true)
            return
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Android returned no listing for the approved folder")
        cursor.use { rows ->
            val idIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (idIndex < 0 || nameIndex < 0) {
                throw IOException("The approved provider does not expose file names")
            }
            while (rows.moveToNext()) {
                coroutineContext.ensureActive()
                val id = rows.getString(idIndex) ?: continue
                val name = rows.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                val mime = if (mimeIndex >= 0 && !rows.isNull(mimeIndex)) rows.getString(mimeIndex) else null
                val childPath = if (relativePath.isBlank()) name else "$relativePath/$name"
                if (mime == DIRECTORY_MIME) {
                    if (depth < MAX_DEPTH && results.size < MAX_FILES) {
                        walk(tree, id, childPath, depth + 1, results, visited, onProgress, coroutineContext, onLimit)
                    } else {
                        onLimit(true)
                    }
                    continue
                }
                if (results.size >= MAX_FILES) {
                    onLimit(true)
                    break
                }
                val mimeType = mime?.takeIf { it.isNotBlank() } ?: LibraryLogic.mimeFromName(name)
                val deletable = flagsIndex < 0 ||
                    (rows.getInt(flagsIndex) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                results += MediaFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    displayName = name,
                    mimeType = mimeType,
                    size = if (sizeIndex >= 0 && !rows.isNull(sizeIndex)) rows.getLong(sizeIndex).coerceAtLeast(0L) else 0L,
                    modifiedAt = if (modifiedIndex >= 0 && !rows.isNull(modifiedIndex)) {
                        rows.getLong(modifiedIndex).coerceAtLeast(0L)
                    } else 0L,
                    dateTakenMs = 0L,
                    kind = LibraryLogic.kindFor(mimeType, name),
                    durationMs = 0L,
                    volumeName = null,
                    relativePath = relativePath,
                    source = MediaFile.Source.SAF_TREE,
                    treeUri = tree.toString(),
                    documentId = id,
                    parentDocumentId = parentId,
                    absolutePath = absolutePathFor(id),
                    deletionSupported = deletable
                )
                onProgress(results.size)
            }
        }
    }

    /**
     * Turns a SAF document id into an absolute path when Android encodes one
     * (`primary:Download/report.pdf`). This is only used for display and for
     * the legacy restore on API 26-28; scoped storage still decides access.
     */
    private fun absolutePathFor(documentId: String): String? {
        if (':' !in documentId) return null
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':').trimStart('/')
        if (path.isBlank()) return null
        val root = when (volume) {
            "primary", "external_primary" -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        return "$root/$path"
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }

    // -----------------------------------------------------------------------
    // Encrypted configuration file
    // -----------------------------------------------------------------------

    private fun writeFolders(folders: List<Uri>) {
        val json = JSONArray().apply { folders.forEach { put(it.toString()) } }
        val encrypted = cipher.encryptBytes(json.toString().toByteArray(Charsets.UTF_8))
        AtomicFileWriter.write(configFile) { it.write(encrypted) }
        // The single-folder file from the previous version is superseded.
        if (legacyConfigFile.isFile) legacyConfigFile.delete()
    }

    private fun readFolders(): List<Uri> {
        if (!configFile.isFile) return readLegacyFolders()
        return try {
            val clear = cipher.decryptBytes(configFile.readBytes())
            val array = JSONArray(String(clear, Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            }
        } catch (error: Exception) {
            throw IOException("Saved folder access could not be verified; no files were changed.", error)
        }
    }

    /** Migration from the 2.3-era single-folder file. */
    private fun readLegacyFolders(): List<Uri> {
        if (!legacyConfigFile.isFile) return emptyList()
        return try {
            val clear = cipher.decryptBytes(legacyConfigFile.readBytes())
            val uri = Uri.parse(String(clear, Charsets.UTF_8))
            if (uri.toString().isBlank()) emptyList() else listOf(uri)
        } catch (error: Exception) {
            throw IOException("Saved folder access could not be verified; no files were changed.", error)
        }
    }
}
