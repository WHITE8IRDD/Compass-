package com.compass.vault.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import com.compass.vault.security.KeystoreCipher
import com.compass.vault.security.SecureWipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted private vault storage and verified file transactions.
 *
 * Payloads and their metadata live under [Context.getFilesDir], not shared
 * storage. Payloads use streaming AES-256-GCM; metadata (including source URI,
 * source path and content digest) is encrypted as well. No decrypted content
 * is stored in a public directory. A private cache file is used only where a
 * decoder/player needs a seekable file and is wiped on screen close/auto-lock.
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
        private const val THUMBNAIL_CACHE_KB = 24 * 1024
    }

    private val resolver: ContentResolver = context.contentResolver
    private val vaultDir = File(context.filesDir, VAULT_DIR)
    private val tempDir = File(context.cacheDir, TEMP_DIR)
    private val mutationMutex = Mutex()
    private val tempDecryptSemaphore = Semaphore(2)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTempFiles = ConcurrentHashMap.newKeySet<File>()

    /** In-memory only; dropped as soon as the vault locks. */
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        if (!vaultDir.exists() && !vaultDir.mkdirs()) throw IOException("Could not create private vault storage")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IOException("Could not create private playback storage")
        // Clear stale plaintext and interrupted transaction artifacts from a
        // prior process run. A .part is never eligible for source removal; the
        // original remains until import has fully verified and published.
        cleanupScope.launch {
            tempDir.listFiles()?.forEach { SecureWipe.wipe(it) }
            vaultDir.listFiles()
                ?.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }
                ?.forEach { SecureWipe.wipe(it) }
        }
    }

    // -----------------------------------------------------------------------
    // Import / lock
    // -----------------------------------------------------------------------

    /**
     * Encrypts a selected source into app-private storage and verifies the
     * completed ciphertext by decrypting it to a streaming digest sink. This
     * method never deletes the source. Callers may request source deletion
     * only after this method returns successfully.
     */
    suspend fun import(
        source: Uri,
        folder: String?,
        origin: MediaFile? = null,
        onProgress: (Long) -> Unit = {}
    ): VaultFile = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val coroutineContext = currentCoroutineContext()
            val displayName = origin?.displayName ?: queryDisplayName(resolver, source)
                ?: "import_${System.currentTimeMillis()}"
            val mime = origin?.mimeType ?: resolver.getType(source) ?: guessMimeType(displayName)
            val id = UUID.randomUUID().toString()
            val payload = File(vaultDir, id + PAYLOAD_SUFFIX)
            val partial = File(vaultDir, id + PAYLOAD_SUFFIX + ".part")
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var published = false
            var metadataWritten = false

            try {
                val raw = resolver.openInputStream(source)
                    ?: throw IOException("Android could not open the selected file")
                raw.use { input ->
                    val counted = object : FilterInputStream(input) {
                        override fun read(): Int {
                            coroutineContext.ensureActive()
                            val value = super.read()
                            if (value >= 0) {
                                sourceDigest.update(value.toByte())
                                copied++
                                onProgress(copied)
                            }
                            return value
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            coroutineContext.ensureActive()
                            val count = super.read(buffer, offset, length)
                            if (count > 0) {
                                sourceDigest.update(buffer, offset, count)
                                copied += count
                                onProgress(copied)
                            }
                            return count
                        }
                    }
                    FileOutputStream(partial).use { fileOut ->
                        BufferedOutputStream(fileOut, 64 * 1024).use { encryptedOut ->
                            cipher.encryptStream(counted, encryptedOut)
                            encryptedOut.flush()
                            fileOut.fd.sync()
                        }
                    }
                }

                val originalDigest = sourceDigest.digest()
                val verifiedDigest = MessageDigest.getInstance("SHA-256")
                var verifiedBytes = 0L
                val digestSink = object : OutputStream() {
                    override fun write(value: Int) {
                        verifiedDigest.update(value.toByte())
                        verifiedBytes++
                    }

                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        verifiedDigest.update(buffer, offset, length)
                        verifiedBytes += length
                    }
                }
                FileInputStream(partial).use { encrypted ->
                    cipher.decryptStream(cancellable(encrypted, coroutineContext), digestSink)
                }
                if (verifiedBytes != copied || !MessageDigest.isEqual(originalDigest, verifiedDigest.digest())) {
                    throw IOException("The encrypted copy did not match the selected source")
                }

                AtomicFileWriter.replace(partial, payload)
                published = true
                val file = VaultFile(
                    id = id,
                    name = displayName,
                    mimeType = mime,
                    size = copied,
                    createdAt = System.currentTimeMillis(),
                    folder = folder,
                    durationMs = origin?.durationMs ?: probeDuration(source),
                    kind = origin?.kind ?: kindFor(mime, displayName),
                    originUri = source.toString(),
                    originVolume = origin?.volumeName,
                    originRelativePath = origin?.relativePath,
                    originDateTakenMs = origin?.dateTakenMs ?: 0L,
                    contentSha256 = originalDigest.toHex(),
                    sourceState = VaultFile.SourceState.PUBLIC_COPY_PRESENT
                )
                writeMetadata(file)
                metadataWritten = true
                file
            } catch (error: Throwable) {
                SecureWipe.wipe(partial)
                if (published && !metadataWritten) SecureWipe.wipe(payload)
                throw error
            }
        }
    }

    /** Persist the platform result only after its delete operation completed. */
    suspend fun markSourceRemoved(file: VaultFile, removed: Boolean): VaultFile = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val updated = file.copy(
                sourceState = if (removed) VaultFile.SourceState.PRIVATE_ONLY
                else VaultFile.SourceState.PUBLIC_COPY_PRESENT
            )
            writeMetadata(updated)
            updated
        }
    }

    // -----------------------------------------------------------------------
    // Listing and folders
    // -----------------------------------------------------------------------

    /** All encrypted entries, newest first. Corruption is surfaced, never shown as an empty vault. */
    suspend fun list(): List<VaultFile> = withContext(Dispatchers.IO) {
        val entries = vaultDir.listFiles()
            ?: throw IOException("Could not scan the private vault directory")
        val sidecars = entries.filter { it.name.endsWith(META_SUFFIX) }
        val sidecarIds = sidecars.mapTo(HashSet()) { it.name.removeSuffix(META_SUFFIX) }
        val orphanedPayload = entries.firstOrNull { it.name.endsWith(PAYLOAD_SUFFIX) && it.name.removeSuffix(PAYLOAD_SUFFIX) !in sidecarIds }
        if (orphanedPayload != null) {
            throw IOException("An encrypted payload has no readable metadata. No vault data was changed.")
        }

        val files = ArrayList<VaultFile>(sidecars.size)
        for (sidecar in sidecars) {
            val id = sidecar.name.removeSuffix(META_SUFFIX)
            try {
                UUID.fromString(id)
                val payload = File(vaultDir, id + PAYLOAD_SUFFIX)
                if (!payload.isFile) throw IOException("Encrypted payload is missing")
                val json = JSONObject(String(cipher.decryptBytes(sidecar.readBytes()), Charsets.UTF_8))
                files += VaultFile.fromJson(id, json)
            } catch (error: Exception) {
                throw IOException("Vault entry metadata could not be verified. Encrypted data was preserved.", error)
            }
        }
        files.sortedByDescending { it.createdAt }
    }

    suspend fun folders(): List<String> = withContext(Dispatchers.IO) { readFolders() }

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val clean = name.trim()
            if (clean.isEmpty()) return@withLock
            val existing = readFolders().toMutableList()
            if (!existing.contains(clean)) {
                existing += clean
                existing.sort()
                val json = JSONArray().apply { existing.forEach(::put) }
                AtomicFileWriter.write(File(vaultDir, FOLDERS_FILE)) { out ->
                    out.write(cipher.encryptBytes(json.toString().toByteArray(Charsets.UTF_8)))
                }
            }
        }
    }

    suspend fun moveToFolder(file: VaultFile, folder: String?) = withContext(Dispatchers.IO) {
        mutationMutex.withLock { writeMetadata(file.copy(folder = folder)) }
    }

    // -----------------------------------------------------------------------
    // Restore / export / delete
    // -----------------------------------------------------------------------

    /**
     * Restore a media entry to its recorded MediaStore volume and relative
     * directory. Returns null when the original location is not reconstructible
     * (for example a SAF document, old entry, or pre-Q device); the caller
     * should then ask the user for a destination.
     *
     * The destination stays `IS_PENDING` until decryption, GCM verification,
     * byte count, digest, and a read-back verification all succeed. The
     * encrypted source is removed only after the public item is published.
     */
    suspend fun restoreToOriginalMedia(file: VaultFile, onProgress: (Long) -> Unit = {}): Uri? =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val transactionContext = currentCoroutineContext()
                val originUri = file.originUri?.let(Uri::parse)
                val originalMatches = originUri?.let { sourceContentMatches(file, it, transactionContext) }
                if (originUri != null && originalMatches == true) {
                    deleteInternal(file)
                    return@withLock originUri
                }
                // If the source was never confirmed removed, its absence or
                // changed contents cannot be inferred safely from a scoped
                // MediaStore query. Ask the user for a restore destination.
                if (file.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT) {
                    return@withLock null
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    file.kind !in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO) ||
                    file.originRelativePath.isNullOrBlank()
                ) return@withLock null

                val volume = file.originVolume?.takeIf { it.isNotBlank() }
                    ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val collection = mediaCollection(file.kind, volume) ?: return@withLock null
                val path = file.originRelativePath
                val name = uniqueDisplayName(volume, path, file.name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (file.originDateTakenMs > 0L) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, file.originDateTakenMs)
                    }
                }
                val target = try {
                    resolver.insert(collection, values)
                } catch (_: Exception) {
                    null
                } ?: return@withLock null

                try {
                    val digest = writeDecryptedAndVerify(file, target, onProgress, transactionContext)
                    if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                        throw IOException("Android could not verify the restored media")
                    }
                    val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (resolver.update(target, published, null, null) != 1) {
                        throw IOException("Android could not publish the restored media")
                    }
                    if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                        throw IOException("The restored media failed its final verification")
                    }
                    deleteInternal(file)
                    target
                } catch (error: Throwable) {
                    deleteProviderItem(target)
                    throw error
                }
            }
        }

    /**
     * Restore to a SAF-selected destination. A provider that cannot read back
     * its output is treated as unverified and the encrypted original remains.
     */
    suspend fun restoreToUri(
        file: VaultFile,
        target: Uri,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val transactionContext = currentCoroutineContext()
            try {
                val digest = writeDecryptedAndVerify(file, target, onProgress, transactionContext)
                if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                    throw IOException("The destination could not be read back for verification")
                }
                deleteInternal(file)
            } catch (error: Throwable) {
                deleteProviderItem(target)
                throw error
            }
        }
    }

    /** Decrypts an explicit user-requested export; the protected original remains. */
    suspend fun export(file: VaultFile, target: Uri, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val transactionContext = currentCoroutineContext()
            try {
                val digest = writeDecryptedAndVerify(file, target, onProgress, transactionContext)
                if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                    throw IOException("The exported copy could not be verified")
                }
            } catch (error: Throwable) {
                deleteProviderItem(target)
                throw error
            }
        }

    /** Deletes an encrypted entry after a user-confirmed, irreversible action. */
    suspend fun delete(file: VaultFile) = withContext(Dispatchers.IO) {
        mutationMutex.withLock { deleteInternal(file) }
    }

    private fun deleteInternal(file: VaultFile) {
        thumbnails.remove(file.id)
        SecureWipe.wipe(File(vaultDir, file.id + PAYLOAD_SUFFIX))
        SecureWipe.wipe(File(vaultDir, file.id + META_SUFFIX))
    }

    /** Decrypt to a unique app-private seekable file for video playback/thumbnailing. */
    suspend fun decryptToTemp(file: VaultFile): File = withContext(Dispatchers.IO) {
        tempDecryptSemaphore.withPermit {
            decryptToTempInternal(file, currentCoroutineContext())
        }
    }

    /** Called on auto-lock; temp files are wiped off the UI thread. */
    fun wipeTempFiles() {
        thumbnails.evictAll()
        cleanupScope.launch {
            val files = (activeTempFiles.toList() + tempDir.listFiles().orEmpty()).distinct()
            files.forEach {
                SecureWipe.wipe(it)
                activeTempFiles.remove(it)
            }
        }
    }

    /** Wipes a viewer's playback file without blocking the UI thread. */
    fun wipeTempFile(file: File) {
        val valid = try {
            file.canonicalFile.parentFile == tempDir.canonicalFile
        } catch (_: Exception) {
            false
        }
        if (valid) cleanupScope.launch {
            SecureWipe.wipe(file)
            activeTempFiles.remove(file)
        }
    }

    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) {
        vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearThumbnails() = thumbnails.evictAll()

    // -----------------------------------------------------------------------
    // Private thumbnails and image decode
    // -----------------------------------------------------------------------

    suspend fun thumbnail(file: VaultFile, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        tempDecryptSemaphore.withPermit {
            val key = "${file.id}:$maxPx"
            thumbnails.get(key)?.let { return@withPermit it }
            val coroutineContext = currentCoroutineContext()
            val temp = try {
                decryptToTempInternal(file, coroutineContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withPermit null
            }
            try {
                val bitmap = when {
                    file.isImage -> decodeSampledFile(temp, maxPx)
                    file.isVideo -> videoThumbnail(temp, maxPx)
                    else -> null
                }
                if (bitmap != null) thumbnails.put(key, bitmap)
                bitmap
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                SecureWipe.wipe(temp)
                activeTempFiles.remove(temp)
            }
        }
    }

    /** Images are decoded to a bounded bitmap from a short-lived private temp file. */
    suspend fun decodeImage(file: VaultFile, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        tempDecryptSemaphore.withPermit {
            val coroutineContext = currentCoroutineContext()
            val temp = try {
                decryptToTempInternal(file, coroutineContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withPermit null
            }
            try {
                decodeSampledFile(temp, maxPx)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                SecureWipe.wipe(temp)
                activeTempFiles.remove(temp)
            }
        }
    }

    private fun decryptToTempInternal(file: VaultFile, coroutineContext: kotlin.coroutines.CoroutineContext): File {
        val payload = payloadFor(file)
        if (!payload.isFile) throw IOException("Missing encrypted payload")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IOException("Could not create playback cache")
        val extension = file.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bin"
        val target = File.createTempFile("${file.id.take(8)}-", ".$extension", tempDir)
        activeTempFiles.add(target)
        return try {
            FileInputStream(payload).use { encrypted ->
                FileOutputStream(target).use { fileOut ->
                    BufferedOutputStream(fileOut, 64 * 1024).use { plain ->
                        cipher.decryptStream(cancellable(encrypted, coroutineContext), plain)
                        plain.flush()
                        fileOut.fd.sync()
                    }
                }
            }
            coroutineContext.ensureActive()
            target
        } catch (error: Throwable) {
            SecureWipe.wipe(target)
            activeTempFiles.remove(target)
            throw error
        }
    }

    private fun decodeSampledFile(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        )
    }

    private fun videoThumbnail(file: File, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxPx,
                maxPx
            )
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        if (width <= 0 || height <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxPx && height / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    // -----------------------------------------------------------------------
    // Internal transaction helpers
    // -----------------------------------------------------------------------

    private fun writeMetadata(file: VaultFile) {
        val encrypted = cipher.encryptBytes(file.toJson().toString().toByteArray(Charsets.UTF_8))
        AtomicFileWriter.write(File(vaultDir, file.id + META_SUFFIX)) { out -> out.write(encrypted) }
    }

    private fun readFolders(): List<String> {
        val file = File(vaultDir, FOLDERS_FILE)
        if (!file.isFile) return emptyList()
        try {
            val array = JSONArray(String(cipher.decryptBytes(file.readBytes()), Charsets.UTF_8))
            return (0 until array.length()).map { array.getString(it) }
        } catch (error: Exception) {
            throw IOException("Encrypted folder metadata could not be read; no data was changed.", error)
        }
    }

    private fun payloadFor(file: VaultFile) = File(vaultDir, file.id + PAYLOAD_SUFFIX)

    private fun cancellable(input: InputStream, coroutineContext: kotlin.coroutines.CoroutineContext): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                coroutineContext.ensureActive()
                return super.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                coroutineContext.ensureActive()
                return super.read(buffer, offset, length)
            }
        }

    private fun writeDecryptedAndVerify(
        file: VaultFile,
        target: Uri,
        onProgress: (Long) -> Unit,
        coroutineContext: kotlin.coroutines.CoroutineContext
    ): ByteArray {
        val payload = payloadFor(file)
        if (!payload.isFile) throw IOException("Missing encrypted payload")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val output = resolver.openOutputStream(target, "w") ?: throw IOException("Could not open the destination")
        output.use { rawOut ->
            val digestOut = DigestOutputStream(rawOut, digest)
            FileInputStream(payload).use { encrypted ->
                cipher.decryptStream(cancellable(encrypted, coroutineContext), digestOut) { count ->
                    written = count
                    onProgress(count)
                }
            }
            digestOut.flush()
        }
        if (written != file.size) throw IOException("Restored byte count did not match the protected file")
        val actualDigest = digest.digest()
        val expectedDigest = file.contentSha256?.hexToBytes()
        if (expectedDigest != null && !MessageDigest.isEqual(expectedDigest, actualDigest)) {
            throw IOException("Protected file digest verification failed")
        }
        return actualDigest
    }

    private fun verifyUriDigest(
        uri: Uri,
        expectedSize: Long,
        expectedDigest: ByteArray,
        coroutineContext: kotlin.coroutines.CoroutineContext
    ): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        val (actualDigest, size) = input.use { digestStream(cancellable(it, coroutineContext)) }
        return size == expectedSize && MessageDigest.isEqual(expectedDigest, actualDigest)
    }

    private fun sourceContentMatches(
        file: VaultFile,
        uri: Uri,
        coroutineContext: kotlin.coroutines.CoroutineContext
    ): Boolean? {
        val expected = file.contentSha256?.hexToBytes() ?: return null
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            val (digest, size) = input.use { digestStream(cancellable(it, coroutineContext)) }
            size == file.size && MessageDigest.isEqual(expected, digest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun digestStream(input: InputStream): Pair<ByteArray, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return digest.digest() to size
    }

    private fun deleteProviderItem(uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
            // The encrypted original remains authoritative; caller surfaces the failure.
        }
    }

    private fun uniqueDisplayName(volume: String, path: String, requested: String): String {
        fun exists(name: String): Boolean = try {
            resolver.query(
                MediaStore.Files.getContentUri(volume),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(path, name),
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            true // if we cannot inspect safely, do not risk overwriting a name
        }

        if (!exists(requested)) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (number in 1..999) {
            val candidate = "$base ($number)$extension"
            if (!exists(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()})$extension"
    }

    private fun mediaCollection(kind: VaultFile.Kind, volume: String): Uri? = when (kind) {
        VaultFile.Kind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        VaultFile.Kind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        VaultFile.Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
        VaultFile.Kind.DOCUMENT -> null
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "doc", "docx" -> "application/msword"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun kindFor(mime: String, name: String): VaultFile.Kind {
        if (mime.startsWith("image/")) return VaultFile.Kind.IMAGE
        if (mime.startsWith("video/")) return VaultFile.Kind.VIDEO
        if (mime.startsWith("audio/")) return VaultFile.Kind.AUDIO
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")) return VaultFile.Kind.IMAGE
        if (extension in setOf("mp4", "mkv", "mov", "webm", "3gp", "avi")) return VaultFile.Kind.VIDEO
        if (extension in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")) return VaultFile.Kind.AUDIO
        return VaultFile.Kind.DOCUMENT
    }

    private fun probeDuration(source: Uri): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    } catch (_: Exception) {
        0L
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return try {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }
}
