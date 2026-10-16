package com.compass.vault.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.compass.vault.security.KeystoreCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * File discovery inside a user-approved Storage Access Framework folder.
 * Android does not permit a normal app to crawl arbitrary shared-storage
 * paths, so this repository only enumerates a persisted ACTION_OPEN_DOCUMENT_TREE
 * grant. It does not request MANAGE_EXTERNAL_STORAGE.
 */
class DocumentTreeLibraryRepository(
    context: Context,
    private val cipher: KeystoreCipher
) {
    companion object {
        private const val CONFIG_FILE = "library-folder.enc"
        private const val MAX_FILES = 10_000
        private const val MAX_DEPTH = 16
        private const val DIRECTORY_MIME = "vnd.android.document/directory"
    }

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val configFile = File(appContext.filesDir, CONFIG_FILE)

    suspend fun saveSelectedFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw IOException("The selected location is not a SAF folder")
        }
        val encrypted = cipher.encryptBytes(treeUri.toString().toByteArray(Charsets.UTF_8))
        AtomicFileWriter.write(configFile) { it.write(encrypted) }
    }

    suspend fun selectedFolder(): Uri? = withContext(Dispatchers.IO) { readSelectedFolder() }

    suspend fun scanSelectedFolder(): TreeLibraryScan = withContext(Dispatchers.IO) {
        val tree = readSelectedFolder() ?: return@withContext TreeLibraryScan(null, null, emptyList())
        val coroutineContext = currentCoroutineContext()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val rootName = queryDisplayName(rootUri) ?: "Selected folder"
            val results = ArrayList<MediaFile>()
            val visited = HashSet<String>()
            var truncated = false

            suspend fun walk(parentId: String, path: String, depth: Int) {
                coroutineContext.ensureActive()
                if (depth > MAX_DEPTH || results.size >= MAX_FILES || !visited.add(parentId)) {
                    if (depth > MAX_DEPTH || results.size >= MAX_FILES) truncated = true
                    return
                }
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                val cursor = resolver.query(childrenUri, projection, null, null, null)
                    ?: throw IOException("Android returned no listing for the selected folder")
                cursor.use { rows ->
                    val idIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idIndex < 0 || nameIndex < 0) {
                        throw IOException("The selected provider does not expose file names")
                    }
                    while (rows.moveToNext()) {
                        coroutineContext.ensureActive()
                        val id = rows.getString(idIndex) ?: continue
                        val name = rows.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                        val mime = if (mimeIndex >= 0 && !rows.isNull(mimeIndex)) {
                            rows.getString(mimeIndex)
                        } else null
                        val childPath = if (path.isBlank()) name else "$path/$name"
                        if (mime == DIRECTORY_MIME) {
                            if (depth < MAX_DEPTH && results.size < MAX_FILES) {
                                walk(id, childPath, depth + 1)
                            } else {
                                truncated = true
                            }
                            continue
                        }
                        if (results.size >= MAX_FILES) {
                            truncated = true
                            break
                        }
                        val itemUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val mimeType = mime?.takeIf { it.isNotBlank() } ?: mimeFromName(name)
                        val kind = kindFor(mimeType, name)
                        val size = if (sizeIndex >= 0 && !rows.isNull(sizeIndex)) rows.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                        val modified = if (modifiedIndex >= 0 && !rows.isNull(modifiedIndex)) {
                            rows.getLong(modifiedIndex).coerceAtLeast(0L)
                        } else 0L
                        results += MediaFile(
                            uri = itemUri,
                            displayName = name,
                            mimeType = mimeType,
                            size = size,
                            modifiedAt = modified,
                            dateTakenMs = 0L,
                            kind = kind,
                            durationMs = 0L,
                            volumeName = null,
                            relativePath = path,
                            source = MediaFile.Source.SAF_TREE,
                            treeUri = tree.toString(),
                            documentId = id,
                            parentDocumentId = parentId
                        )
                    }
                }
            }

            walk(rootId, "", 0)
            TreeLibraryScan(tree, rootName, results, truncated)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException(
                "Could not read the selected folder. The Android folder grant may have been revoked or restricted.",
                error
            )
        }
    }

    private fun readSelectedFolder(): Uri? {
        if (!configFile.isFile) return null
        try {
            val clear = cipher.decryptBytes(configFile.readBytes())
            return Uri.parse(String(clear, Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("Saved folder access could not be verified; no files were changed.", error)
        }
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

    private fun kindFor(mime: String, name: String): VaultFile.Kind {
        if (mime == "application/vnd.android.package-archive" || name.endsWith(".apk", ignoreCase = true)) {
            return VaultFile.Kind.APK
        }
        if (mime.startsWith("image/")) return VaultFile.Kind.IMAGE
        if (mime.startsWith("video/")) return VaultFile.Kind.VIDEO
        if (mime.startsWith("audio/")) return VaultFile.Kind.AUDIO
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf", "txt", "md", "csv", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "epub", "html", "htm", "xml", "json" -> VaultFile.Kind.DOCUMENT
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff" -> VaultFile.Kind.IMAGE
            "mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "mpeg", "mpg" -> VaultFile.Kind.VIDEO
            "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> VaultFile.Kind.AUDIO
            else -> VaultFile.Kind.OTHER
        }
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "rtf" -> "application/rtf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
