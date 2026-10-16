package com.compass.vault.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache
import com.compass.vault.security.KeystoreCipher
import com.compass.vault.security.SecureWipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The vault: everything that is stored on disk by this app.
 *
 * On-disk layout (inside the app's private data directory):
 *
 *   filesDir/vault/<id>.bin   - AES-256-GCM encrypted payload
 *   filesDir/vault/<id>.meta  - AES-256-GCM encrypted metadata (JSON)
 *   filesDir/vault/folders.json - AES-256-GCM encrypted folder list
 *   cacheDir/decrypted/        - transient plaintext used during playback only
 *
 * The payload is written by streaming the source `InputStream` straight
 * into the cipher, so a 1 GB video never exists in memory as a whole.
 */
class VaultRepository(
    private val context: Context,
    private val cipher: KeystoreCipher
) {

    companion object {
        private const val VAULT_DIR = "vault"
        private const val TEMP_DIR = "decrypted"
        private const val FOLDERS_FILE = "folders.json"
        private const val META_SUFFIX = ".meta"
        private const val PAYLOAD_SUFFIX = ".bin"

        /** Thumbnails are cached in RAM only - never written to disk. */
        private const val THUMBNAIL_CACHE_KB = 24 * 1024
    }

    private val vaultDir = File(context.filesDir, VAULT_DIR)
    private val tempDir = File(context.cacheDir, TEMP_DIR)

    /** In-memory thumbnail cache. Cleared when the vault locks. */
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        vaultDir.mkdirs()
        tempDir.mkdirs()
    }

    // -----------------------------------------------------------------------
    // Import
    // -----------------------------------------------------------------------

    /**
     * Imports a file that the user picked with the Storage Access Framework.
     *
     * The bytes are streamed from the content provider into the encrypted
     * payload; nothing larger than the 64 KB cipher chunk is ever in memory.
     *
     * @param onProgress called with the number of bytes copied so far.
     */
    suspend fun import(
        source: Uri,
        folder: String?,
        onProgress: (Long) -> Unit
    ): VaultFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, source)
            ?: "import_${System.currentTimeMillis()}"
        val mime = resolver.getType(source) ?: guessMimeType(displayName)
        val id = UUID.randomUUID().toString()
        val payload = File(vaultDir, id + PAYLOAD_SUFFIX)

        var copied = 0L
        // The payload is written to a ".part" file first and published with
        // a rename at the very end. If the import is interrupted (the user
        // leaves the app, the vault locks, the process dies) no half file is
        // left behind - it is wiped instead.
        val partial = File(vaultDir, id + PAYLOAD_SUFFIX + ".part")
        try {
            resolver.openInputStream(source)?.use { raw ->
                val counting = object : FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            copied += n
                            onProgress(copied)
                        }
                        return n
                    }
                }
                FileOutputStream(partial).use { fos ->
                    BufferedOutputStream(fos, 64 * 1024).use { out ->
                        cipher.encryptStream(counting, out)
                    }
                }
            } ?: throw java.io.IOException("Could not open the selected file")

            if (!partial.renameTo(payload)) {
                partial.copyTo(payload, overwrite = true)
                partial.delete()
            }

            val file = VaultFile(
                id = id,
                name = displayName,
                mimeType = mime,
                size = copied,
                createdAt = System.currentTimeMillis(),
                folder = folder,
                kind = kindFor(mime, displayName),
                durationMs = probeDuration(source)
            )
            writeMetadata(file)
            file
        } catch (e: Throwable) {
            // Cancelled or failed: remove the incomplete ciphertext.
            SecureWipe.wipe(partial)
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------------

    /** All files in the vault, newest first. */
    suspend fun list(): List<VaultFile> = withContext(Dispatchers.IO) {
        val files = vaultDir.listFiles { f -> f.name.endsWith(META_SUFFIX) } ?: return@withContext emptyList()
        files.mapNotNull { meta ->
            try {
                val id = meta.name.removeSuffix(META_SUFFIX)
                val json = JSONObject(String(cipher.decryptBytes(meta.readBytes()), Charsets.UTF_8))
                VaultFile.fromJson(id, json)
            } catch (e: Exception) {
                null    // unreadable sidecar -> ignore, never crash the list
            }
        }.sortedByDescending { it.createdAt }
    }

    /** Folders / categories that exist in the vault. */
    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        val file = File(vaultDir, FOLDERS_FILE)
        if (!file.exists()) return@withContext emptyList()
        try {
            val array = JSONArray(String(cipher.decryptBytes(file.readBytes()), Charsets.UTF_8))
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext
        val current = folders().toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            current.sort()
            val array = JSONArray()
            current.forEach { array.put(it) }
            File(vaultDir, FOLDERS_FILE).writeBytes(cipher.encryptBytes(array.toString().toByteArray()))
        }
    }

    // -----------------------------------------------------------------------
    // Deleting / exporting
    // -----------------------------------------------------------------------

    /** Securely removes a file from the vault. */
    suspend fun delete(file: VaultFile) = withContext(Dispatchers.IO) {
        thumbnails.remove(file.id)
        SecureWipe.wipe(File(vaultDir, file.id + PAYLOAD_SUFFIX))
        SecureWipe.wipe(File(vaultDir, file.id + META_SUFFIX))
    }

    /** Moves a file into a folder (or out of every folder when null). */
    suspend fun moveToFolder(file: VaultFile, folder: String?) = withContext(Dispatchers.IO) {
        val moved = file.copy(folder = folder)
        writeMetadata(moved)
    }

    /**
     * Decrypts a file and writes the plaintext to [target].
     * Used by the "export" action - the user has to confirm this first.
     */
    suspend fun export(file: VaultFile, target: Uri, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val payload = File(vaultDir, file.id + PAYLOAD_SUFFIX)
            if (!payload.exists()) throw java.io.IOException("Missing payload")
            resolver.openOutputStream(target, "w")?.use { out ->
                payload.inputStream().use { input ->
                    cipher.decryptStream(input, out) { count -> onProgress(count) }
                }
            } ?: throw java.io.IOException("Could not open the destination")
        }

    /**
     * Decrypts the payload into a temporary file so that MediaPlayer can
     * stream it. The file lives in the cache directory and is wiped as soon
     * as playback stops.
     */
    suspend fun decryptToTemp(file: VaultFile): File = withContext(Dispatchers.IO) {
        val payload = File(vaultDir, file.id + PAYLOAD_SUFFIX)
        if (!payload.exists()) throw java.io.IOException("Missing payload")
        tempDir.mkdirs()
        val target = File(tempDir, "${file.id}.${file.extension.ifEmpty { "bin" }}")
        if (target.exists()) SecureWipe.wipe(target)
        payload.inputStream().use { input ->
            FileOutputStream(target).use { fos ->
                BufferedOutputStream(fos, 64 * 1024).use { out ->
                    cipher.decryptStream(input, out)
                }
            }
        }
        target
    }

    /** Wipes every decrypted temporary file. Called when the vault locks. */
    fun wipeTempFiles() {
        tempDir.listFiles()?.forEach { SecureWipe.wipe(it) }
        thumbnails.evictAll()
    }

    /** Total number of bytes the vault occupies on disk (ciphertext). */
    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) {
        vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Drops the in-memory thumbnail cache. */
    fun clearThumbnails() = thumbnails.evictAll()

    // -----------------------------------------------------------------------
    // Thumbnails
    // -----------------------------------------------------------------------

    /**
     * Builds a thumbnail for [file] **in memory**.
     *
     * Images are decoded straight from the decrypted byte array, videos need
     * a seekable source so the frame is pulled from a temporary file that is
     * wiped again immediately. Neither path ever leaves an unencrypted copy
     * behind on disk.
     */
    suspend fun thumbnail(file: VaultFile, maxPx: Int): Bitmap? {
        thumbnails.get(file.id)?.let { return it }

        return when {
            file.isImage -> imageThumbnail(file, maxPx)
            file.isVideo -> videoThumbnail(file, maxPx)
            else -> null
        }?.also { thumbnails.put(file.id, it) }
    }

    /**
     * Decodes a full image **into memory** for the viewer.
     *
     * The plaintext never touches the disk: the encrypted payload is
     * decrypted into a byte array, decoded and the array is wiped again.
     */
    suspend fun decodeImage(file: VaultFile, maxPx: Int): Bitmap? {
        val payload = File(vaultDir, file.id + PAYLOAD_SUFFIX)
        if (!payload.exists()) return null
        var plain: ByteArray? = null
        return try {
            plain = payload.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                cipher.decryptStream(input, out)
                out.toByteArray()
            }
            val bytes = plain
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        } finally {
            // Wipe the decrypted bytes as soon as the bitmap exists.
            plain?.let { SecureWipe.wipe(it) }
        }
    }

    private suspend fun imageThumbnail(file: VaultFile, maxPx: Int): Bitmap? {
        val payload = File(vaultDir, file.id + PAYLOAD_SUFFIX)
        if (!payload.exists() || payload.length() > 400L * 1024 * 1024) return null

        val plain = payload.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            cipher.decryptStream(input, out)
            out.toByteArray()
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeByteArray(plain, 0, plain.size, options)
        } catch (e: Exception) {
            null
        } finally {
            SecureWipe.wipe(plain)
        }
    }

    private suspend fun videoThumbnail(file: VaultFile, maxPx: Int): Bitmap? {
        val temp = try {
            decryptToTemp(file)
        } catch (e: Exception) {
            return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(temp.absolutePath)
            val frame = retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxPx,
                maxPx
            )
            frame
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
            SecureWipe.wipe(temp)
        }
    }

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxPx && height / (sample * 2) >= maxPx) {
            sample *= 2
        }
        return sample
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun writeMetadata(file: VaultFile) {
        File(vaultDir, file.id + META_SUFFIX)
            .writeBytes(cipher.encryptBytes(file.toJson().toString().toByteArray(Charsets.UTF_8)))
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun kindFor(mime: String, name: String): VaultFile.Kind {
        if (mime.startsWith("image/")) return VaultFile.Kind.IMAGE
        if (mime.startsWith("video/")) return VaultFile.Kind.VIDEO
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic")) return VaultFile.Kind.IMAGE
        if (ext in listOf("mp4", "mkv", "mov", "webm", "3gp", "avi")) return VaultFile.Kind.VIDEO
        return VaultFile.Kind.DOCUMENT
    }

    /**
     * Reads the duration of a video straight from the content provider.
     *
     * The encrypted payload cannot be probed (it is ciphertext), so the
     * metadata is read from the original source instead.
     */
    private fun probeDuration(source: Uri): Long = try {
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(context, source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
        duration
    } catch (e: Exception) {
        0L
    }
}
