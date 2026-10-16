package com.compass.vault.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
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
        // Plaintext playback files are disposable and must not survive a
        // process restart. Vault transaction artifacts are recovered under
        // mutationMutex when list() runs, rather than racing the first scan.
        cleanupScope.launch {
            tempDir.listFiles()?.forEach { SecureWipe.wipe(it) }
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
                    durationMs = origin?.durationMs ?: if (kindFor(mime, displayName) in setOf(VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO)) probeDuration(source) else 0L,
                    kind = origin?.kind ?: kindFor(mime, displayName),
                    originUri = source.toString(),
                    originVolume = origin?.volumeName,
                    originRelativePath = origin?.relativePath,
                    originDateTakenMs = origin?.dateTakenMs ?: 0L,
                    originAbsolutePath = origin?.absolutePath,
                    contentSha256 = originalDigest.toHex(),
                    sourceState = VaultFile.SourceState.PUBLIC_COPY_PRESENT,
                    originTreeUri = origin?.treeUri,
                    originParentDocumentId = origin?.parentDocumentId
                )
                writeMetadata(file)
                metadataWritten = true
                file
            } catch (error: Throwable) {
                // These are ciphertext-only artifacts; unlinking is safer and
                // substantially faster than overwriting a multi-gigabyte file.
                partial.delete()
                if (published && !metadataWritten) payload.delete()
                throw error
            }
        }
    }

    /** Persist a durable removal intent before showing Android's consent UI. */
    suspend fun markSourceRemovalPending(file: VaultFile): VaultFile = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = readEntry(file.id) ?: throw IOException("Vault entry is missing")
            if (current.sourceState == VaultFile.SourceState.PRIVATE_ONLY) {
                throw IOException("Android already confirmed removal of the public source")
            }
            if (current.operationState !in setOf(
                    VaultFile.OperationState.READY,
                    VaultFile.OperationState.SOURCE_REMOVAL_PENDING
                )
            ) throw IOException("Another vault operation is pending for this item")
            val source = current.originUri?.let(Uri::parse)
                ?: throw IOException("The public source location is unavailable; the source was not removed")
            if (sourceContentMatches(current, source, currentCoroutineContext()) != true) {
                throw IOException("The public source changed or could not be verified; it was not removed")
            }
            val updated = current.copy(operationState = VaultFile.OperationState.SOURCE_REMOVAL_PENDING)
            writeMetadata(updated)
            updated
        }
    }

    /** Persist the platform result only after its delete operation completed. */
    suspend fun markSourceRemoved(file: VaultFile, removed: Boolean): VaultFile = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = readEntry(file.id) ?: throw IOException("Vault entry is missing")
            if (current.operationState != VaultFile.OperationState.SOURCE_REMOVAL_PENDING) {
                throw IOException("The source-removal transaction is not pending")
            }
            val updated = current.copy(
                sourceState = if (removed) VaultFile.SourceState.PRIVATE_ONLY
                else VaultFile.SourceState.PUBLIC_COPY_PRESENT,
                operationState = VaultFile.OperationState.READY,
                pendingDestinationUri = null
            )
            writeMetadata(updated)
            updated
        }
    }

    // -----------------------------------------------------------------------
    // Listing and folders
    // -----------------------------------------------------------------------

    /** All encrypted entries, newest first. Recovery is performed before the index is shown. */
    suspend fun list(): List<VaultFile> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            recoverTransactionsLocked()
            readEntriesLocked(allowDeletePendingWithoutPayload = false).sortedByDescending { it.createdAt }
        }
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
        mutationMutex.withLock {
            val current = readEntry(file.id) ?: throw IOException("Vault entry is missing")
            if (current.operationState != VaultFile.OperationState.READY) {
                throw IOException("A pending file operation must finish before moving this item")
            }
            writeMetadata(current.copy(folder = folder))
        }
    }

    // -----------------------------------------------------------------------
    // Restore / export / delete
    // -----------------------------------------------------------------------

    /** What an automatic unlock achieved. */
    sealed class RestoreOutcome {
        /** The file is back in public storage and the encrypted entry is gone. */
        data class Restored(val uri: Uri?, val name: String, val location: String) : RestoreOutcome()

        /**
         * Compass refuses to guess: the original location is unknown, gone, or
         * a public copy may still exist there. The encrypted entry is kept and
         * the owner picks a destination instead.
         */
        data class NeedsDestination(val reason: String) : RestoreOutcome()
    }

    /**
     * Automatic unlock: put the protected file back where it came from.
     *
     * The order of preference mirrors how the file was locked:
     *  1. an identical public copy already exists at the recorded origin - the
     *     encrypted duplicate is simply dropped (no second copy is created),
     *  2. the recorded SAF folder, restored through the provider,
     *  3. the recorded MediaStore location on Android 10+,
     *  4. the recorded absolute path on Android 8-9 (atomic rename there),
     *  5. otherwise the caller offers the save-picker as an alternative.
     *
     * Every path verifies the written bytes (size + SHA-256) before the
     * encrypted payload is removed. A failure throws and always keeps the
     * encrypted copy.
     */
    suspend fun restoreToOriginal(file: VaultFile, onProgress: (Long) -> Unit = {}): RestoreOutcome =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                recoverTransactionsLocked()
                val stored = readEntry(file.id) ?: throw IOException("This file is no longer in the vault")
                // An interrupted removal (the owner cancelled Android's
                // dialog, or the process died) must not block the unlock: the
                // recovery pass above already confirmed that the source is
                // still there, so the removal intent is taken back first.
                val current = if (stored.operationState == VaultFile.OperationState.SOURCE_REMOVAL_PENDING) {
                    val cleared = stored.copy(
                        operationState = VaultFile.OperationState.READY,
                        sourceState = VaultFile.SourceState.PUBLIC_COPY_PRESENT
                    )
                    writeMetadata(cleared)
                    cleared
                } else {
                    stored
                }
                if (current.operationState != VaultFile.OperationState.READY) {
                    throw IOException("A previous file operation needs attention before this item can be restored")
                }
                val transactionContext = currentCoroutineContext()
                val originUri = current.originUri?.let(Uri::parse)

                // 1. The public original is still there and identical.
                if (originUri != null && sourceContentMatches(current, originUri, transactionContext) == true) {
                    val published = current.copy(
                        operationState = VaultFile.OperationState.RESTORE_PUBLISHED,
                        pendingDestinationUri = originUri.toString()
                    )
                    writeMetadata(published)
                    deleteInternal(published)
                    return@withLock RestoreOutcome.Restored(
                        originUri,
                        current.name,
                        locationLabelFor(current, originUri)
                    )
                }

                // 2. The original may still be public. Writing a second copy
                //    would leave the vault duplicate the brief forbids, so the
                //    owner is asked where the file should go instead.
                if (current.sourceState != VaultFile.SourceState.PRIVATE_ONLY) {
                    return@withLock RestoreOutcome.NeedsDestination(
                        "Compass could not confirm that the public original is gone, so it will not create a second copy."
                    )
                }

                // 3. The SAF folder the file was locked from.
                if (!current.originTreeUri.isNullOrBlank() && !current.originParentDocumentId.isNullOrBlank()) {
                    val restored = restoreToTreeFolder(current, onProgress, transactionContext)
                    if (restored != null) {
                        return@withLock RestoreOutcome.Restored(
                            restored,
                            queryDisplayName(resolver, restored) ?: current.name,
                            current.originTreeUri
                        )
                    }
                }

                // 4. The MediaStore location (Android 10+).
                val mediaRestore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    current.kind in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO) &&
                    !current.originVolume.isNullOrBlank() &&
                    !current.originRelativePath.isNullOrBlank()
                if (mediaRestore) {
                    val restored = restoreToMediaStore(current, onProgress, transactionContext)
                    if (restored != null) {
                        return@withLock RestoreOutcome.Restored(
                            restored,
                            queryDisplayName(resolver, restored) ?: current.name,
                            current.originRelativePath.orEmpty()
                        )
                    }
                }

                // 5. Android 8-9: write straight back to the recorded path and
                //    let the media scanner index it again.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !current.originAbsolutePath.isNullOrBlank()) {
                    val restored = restoreToPath(current, onProgress, transactionContext)
                    if (restored != null) {
                        val target = File(current.originAbsolutePath)
                        return@withLock RestoreOutcome.Restored(
                            Uri.fromFile(target),
                            target.name,
                            target.parent ?: ""
                        )
                    }
                }

                RestoreOutcome.NeedsDestination(
                    "The original folder is no longer available to Compass."
                )
            }
        }

    /**
     * Legacy restore (Android 8-9): decrypt into the recorded directory, verify
     * the bytes, then rename over the final name - a single filesystem
     * operation on the same volume. The encrypted copy is only removed after
     * both verifications succeeded.
     */
    private fun restoreToPath(
        file: VaultFile,
        onProgress: (Long) -> Unit,
        transactionContext: kotlin.coroutines.CoroutineContext
    ): Boolean? {
        val recorded = file.originAbsolutePath ?: return null
        val directory = File(recorded).parentFile?.takeIf { it.isDirectory } ?: return null
        val safeName = LibraryLogic.uniqueName(file.name, directory.list()?.toList().orEmpty())
        val target = File(directory, safeName)
        val partial = File(directory, ".$safeName.compass.part")
        writeMetadata(file.copy(
            operationState = VaultFile.OperationState.RESTORE_PREPARING,
            pendingDestinationUri = target.toURI().toString(),
            pendingDestinationName = safeName
        ))
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val payload = payloadFor(file)
            if (!payload.isFile) throw IOException("Missing encrypted payload")
            FileInputStream(payload).use { encrypted ->
                FileOutputStream(partial).use { fileOut ->
                    BufferedOutputStream(fileOut, 64 * 1024).use { plain ->
                        val sink = DigestOutputStream(plain, digest)
                        cipher.decryptStream(cancellable(encrypted, transactionContext), sink) { count ->
                            written = count
                            onProgress(count)
                        }
                        sink.flush()
                        fileOut.fd.sync()
                    }
                }
            }
            if (written != file.size) throw IOException("The restored byte count did not match the protected file")
            val expected = file.contentSha256?.hexToBytes()
            if (expected != null && !MessageDigest.isEqual(expected, digest.digest())) {
                throw IOException("The restored file failed its integrity check")
            }
            // Read-back verification of the finished artefact before publishing.
            val (readBack, size) = FileInputStream(partial).use { digestStream(it) }
            if (size != file.size || (expected != null && !MessageDigest.isEqual(expected, readBack))) {
                throw IOException("The restored file could not be verified on disk")
            }
            val writing = file.copy(
                operationState = VaultFile.OperationState.RESTORE_WRITING,
                pendingDestinationUri = target.toURI().toString(),
                pendingDestinationName = safeName
            )
            writeMetadata(writing)
            AtomicFileWriter.replace(partial, target)
            val published = writing.copy(operationState = VaultFile.OperationState.RESTORE_PUBLISHED)
            writeMetadata(published)
            committed = true
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(file.mimeType), null)
            deleteInternal(published)
            return true
        } catch (error: Throwable) {
            if (partial.exists() && !partial.delete()) {
                // Objection: an unverified partial file stays behind. Keep the
                // encrypted copy and flag the item for review.
                runCatching {
                    writeMetadata(file.copy(
                        operationState = VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED,
                        pendingDestinationUri = partial.toURI().toString(),
                        pendingDestinationName = safeName
                    ))
                }
                throw IOException("The restore was interrupted and left a partial file. The encrypted copy was kept.", error)
            }
            if (committed) {
                throw IOException("The restore was verified; vault cleanup will resume safely on the next open.", error)
            }
            runCatching {
                writeMetadata(file.copy(
                    operationState = VaultFile.OperationState.READY,
                    pendingDestinationUri = null,
                    pendingDestinationName = null
                ))
            }
            throw error
        }
    }

    private fun locationLabelFor(file: VaultFile, uri: Uri): String = when {
        !file.originRelativePath.isNullOrBlank() -> file.originRelativePath.trimStart('/')
        !file.originAbsolutePath.isNullOrBlank() -> file.originAbsolutePath
        else -> uri.toString()
    }

    /**
     * Restore to a SAF-created document chosen by the user. The destination
     * URI and transaction state are journaled before plaintext bytes are
     * written. A failed or interrupted transfer never removes the vault copy.
     */
    suspend fun restoreToUri(
        file: VaultFile,
        target: Uri,
        onProgress: (Long) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            recoverTransactionsLocked()
            val current = readEntry(file.id) ?: throw IOException("This file is no longer in the vault")
            if (current.operationState != VaultFile.OperationState.READY) {
                throw IOException("A previous file operation needs attention before this item can be restored")
            }
            val transactionContext = currentCoroutineContext()
            var destinationPublished = false
            try {
                writeMetadata(current.copy(
                    operationState = VaultFile.OperationState.RESTORE_WRITING,
                    pendingDestinationUri = target.toString()
                ))
                val digest = writeDecryptedAndVerify(current, target, onProgress, transactionContext)
                if (!verifyUriDigest(target, current.size, digest, transactionContext)) {
                    throw IOException("The destination could not be read back for verification")
                }
                writeMetadata(current.copy(
                    operationState = VaultFile.OperationState.RESTORE_PUBLISHED,
                    pendingDestinationUri = target.toString()
                ))
                destinationPublished = true
                deleteInternal(current.copy(
                    operationState = VaultFile.OperationState.RESTORE_PUBLISHED,
                    pendingDestinationUri = target.toString()
                ))
            } catch (cancelled: CancellationException) {
                if (!destinationPublished) handleFailedRestore(current, target)
                throw cancelled
            } catch (error: Throwable) {
                if (!destinationPublished) handleFailedRestore(current, target)
                else throw IOException("The restore was verified; vault cleanup will resume safely on the next open.", error)
                throw error
            }
        }
    }

    private fun restoreToMediaStore(
        file: VaultFile,
        onProgress: (Long) -> Unit,
        transactionContext: kotlin.coroutines.CoroutineContext
    ): Uri? {
        val volume = file.originVolume?.takeIf { it.isNotBlank() } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = mediaCollection(file.kind, volume) ?: return null
        val path = file.originRelativePath ?: return null
        val name = uniqueDisplayName(volume, path, file.name) ?: return null
        writeMetadata(file.copy(
            operationState = VaultFile.OperationState.RESTORE_PREPARING,
            pendingDestinationUri = null,
            pendingDestinationName = name
        ))
        var target: Uri? = null
        var committed = false
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (file.originDateTakenMs > 0L) {
                    put(MediaStore.MediaColumns.DATE_TAKEN, file.originDateTakenMs)
                }
            }
            target = resolver.insert(collection, values)
                ?: run {
                    writeMetadata(file.copy(operationState = VaultFile.OperationState.READY, pendingDestinationName = null))
                    return null
                }
            val writing = file.copy(
                operationState = VaultFile.OperationState.RESTORE_WRITING,
                pendingDestinationUri = target.toString(),
                pendingDestinationName = name
            )
            writeMetadata(writing)
            val digest = writeDecryptedAndVerify(file, target, onProgress, transactionContext)
            if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                throw IOException("Android could not verify the restored media")
            }
            val publishedValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(target, publishedValues, null, null) != 1) {
                throw IOException("Android could not publish the restored media")
            }
            if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                throw IOException("The restored media failed its final verification")
            }
            val published = writing.copy(operationState = VaultFile.OperationState.RESTORE_PUBLISHED)
            writeMetadata(published)
            committed = true
            deleteInternal(published)
            return target
        } catch (cancelled: CancellationException) {
            if (!committed) handleFailedRestore(file, target)
            throw cancelled
        } catch (error: Throwable) {
            if (committed) {
                throw IOException("The restore was verified; vault cleanup will resume safely on the next open.", error)
            }
            handleFailedRestore(file, target)
            throw error
        }
    }

    private fun restoreToTreeFolder(
        file: VaultFile,
        onProgress: (Long) -> Unit,
        transactionContext: kotlin.coroutines.CoroutineContext
    ): Uri? {
        val tree = runCatching { Uri.parse(file.originTreeUri) }.getOrNull() ?: return null
        val parentId = file.originParentDocumentId ?: return null
        val safeName = uniqueDocumentName(tree, parentId, file.name) ?: return null
        writeMetadata(file.copy(
            operationState = VaultFile.OperationState.RESTORE_PREPARING,
            pendingDestinationUri = null,
            pendingDestinationName = safeName
        ))
        var target: Uri? = null
        var committed = false
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            target = try {
                DocumentsContract.createDocument(resolver, parent, file.mimeType, safeName)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
            if (target == null) {
                writeMetadata(file.copy(operationState = VaultFile.OperationState.READY, pendingDestinationName = null))
                return null
            }
            val writing = file.copy(
                operationState = VaultFile.OperationState.RESTORE_WRITING,
                pendingDestinationUri = target.toString(),
                pendingDestinationName = safeName
            )
            writeMetadata(writing)
            val digest = writeDecryptedAndVerify(file, target, onProgress, transactionContext)
            if (!verifyUriDigest(target, file.size, digest, transactionContext)) {
                throw IOException("The original folder could not read back the restored file")
            }
            val published = writing.copy(operationState = VaultFile.OperationState.RESTORE_PUBLISHED)
            writeMetadata(published)
            committed = true
            deleteInternal(published)
            return target
        } catch (cancelled: CancellationException) {
            if (!committed) handleFailedRestore(file, target)
            throw cancelled
        } catch (error: Throwable) {
            if (committed) {
                throw IOException("The restore was verified; vault cleanup will resume safely on the next open.", error)
            }
            handleFailedRestore(file, target)
            throw error
        }
    }

    /** A conflicting SAF name is never overwritten; failure to inspect is fail-closed. */
    private fun uniqueDocumentName(tree: Uri, parentId: String, requested: String): String? {
        val children = try {
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            ) ?: return null
        } catch (_: Exception) {
            return null
        }
        val names = HashSet<String>()
        children.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0) return null
            while (cursor.moveToNext()) if (!cursor.isNull(index)) names += cursor.getString(index)
        }
        if (requested !in names) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (number in 1..999) {
            val candidate = "$base ($number)$extension"
            if (candidate !in names) return candidate
        }
        return "$base (${System.currentTimeMillis()})$extension"
    }

    /** Preserve the encrypted entry unless a partial destination is confirmed removed. */
    private fun handleFailedRestore(file: VaultFile, target: Uri?) {
        if (target == null) {
            runCatching {
                writeMetadata(file.copy(
                    operationState = VaultFile.OperationState.READY,
                    pendingDestinationUri = null,
                    pendingDestinationName = null
                ))
            }
            return
        }
        val removed = deleteProviderItem(target) && providerConfirmsAbsent(target)
        val updated = file.copy(
            operationState = if (removed) VaultFile.OperationState.READY
            else VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED,
            pendingDestinationUri = if (removed) null else target.toString(),
            pendingDestinationName = file.pendingDestinationName
        )
        runCatching { writeMetadata(updated) }
    }

    /** Deletes an encrypted entry after a user-confirmed, irreversible action. */
    suspend fun delete(file: VaultFile) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = readEntry(file.id) ?: return@withLock
            if (current.operationState != VaultFile.OperationState.READY) {
                throw IOException("A pending file operation must be recovered before deletion")
            }
            deleteInternal(current.copy(pendingDestinationUri = null))
        }
    }

    /**
     * Two-phase deletion: persist the tombstone first, then unlink ciphertext,
     * then remove metadata. Ciphertext does not need a slow random overwrite;
     * deleting the key is not part of this operation.
     */
    private fun deleteInternal(file: VaultFile) {
        val deleting = file.copy(operationState = VaultFile.OperationState.DELETE_PENDING)
        writeMetadata(deleting)
        val payload = File(vaultDir, file.id + PAYLOAD_SUFFIX)
        if (payload.exists() && !payload.delete()) throw IOException("Could not finish removing the encrypted payload")
        val metadata = File(vaultDir, file.id + META_SUFFIX)
        if (metadata.exists() && !metadata.delete()) throw IOException("Could not finish updating the vault index")
        thumbnails.remove(file.id)
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

    /** Recover interrupted moves before exposing the vault list. */
    private suspend fun recoverTransactionsLocked() {
        val context = currentCoroutineContext()
        vaultDir.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        val entries = readEntriesLocked(allowDeletePendingWithoutPayload = true)
        for (entry in entries) {
            context.ensureActive()
            try {
                when (entry.operationState) {
                    VaultFile.OperationState.DELETE_PENDING -> recoverDeletePending(entry)
                    VaultFile.OperationState.RESTORE_PREPARING -> {
                        // The destination URI is not known yet. No bytes were
                        // eligible for writing, so keep the encrypted source
                        // and permit a fresh, conflict-safe attempt.
                        writeMetadata(entry.copy(
                            operationState = VaultFile.OperationState.READY,
                            pendingDestinationUri = null,
                            pendingDestinationName = null
                        ))
                    }
                    VaultFile.OperationState.RESTORE_WRITING,
                    VaultFile.OperationState.RESTORE_PUBLISHED,
                    VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED -> recoverRestore(entry, context)
                    VaultFile.OperationState.SOURCE_REMOVAL_PENDING -> {
                        val source = entry.originUri?.let(Uri::parse)
                        if (source != null && providerConfirmsAbsent(source)) {
                            writeMetadata(entry.copy(
                                sourceState = VaultFile.SourceState.PRIVATE_ONLY,
                                operationState = VaultFile.OperationState.READY
                            ))
                        }
                    }
                    VaultFile.OperationState.READY -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (entry.operationState in setOf(
                        VaultFile.OperationState.RESTORE_WRITING,
                        VaultFile.OperationState.RESTORE_PUBLISHED,
                        VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED
                    )
                ) {
                    runCatching {
                        writeMetadata(entry.copy(operationState = VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED))
                    }
                } else if (entry.operationState == VaultFile.OperationState.DELETE_PENDING) {
                    throw IOException("Vault cleanup is incomplete; protected data was preserved for retry.", error)
                }
            }
        }
        // The source is never deleted before import returns with its metadata
        // committed. Therefore a bare .bin without a sidecar is an unindexed
        // ciphertext temp from an interrupted pre-commit import or a completed
        // tombstone cleanup and is safe to unlink (it is not the only valid copy).
        readEntriesLocked(allowDeletePendingWithoutPayload = true)
    }

    private fun recoverDeletePending(file: VaultFile) {
        val payload = payloadFor(file)
        if (payload.exists() && !payload.delete()) {
            throw IOException("Could not finish removing a previously committed vault entry")
        }
        val metadata = File(vaultDir, file.id + META_SUFFIX)
        if (metadata.exists() && !metadata.delete()) {
            throw IOException("Could not finish updating the vault index")
        }
        thumbnails.remove(file.id)
    }

    private fun recoverRestore(file: VaultFile, coroutineContext: kotlin.coroutines.CoroutineContext) {
        val destination = file.pendingDestinationUri?.let(Uri::parse)
        val expected = file.contentSha256?.hexToBytes()
        if (destination != null && expected != null && payloadFor(file).isFile) {
            val exactCopy = try {
                verifyUriDigest(destination, file.size, expected, coroutineContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (exactCopy && makeMediaStoreVisible(destination)) {
                val published = try {
                    verifyUriDigest(destination, file.size, expected, coroutineContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (published) {
                    try {
                        deleteInternal(file.copy(operationState = VaultFile.OperationState.RESTORE_PUBLISHED))
                    } catch (_: Exception) {
                        // deleteInternal already persisted DELETE_PENDING; the
                        // next listing retries that committed cleanup.
                    }
                    return
                }
            }
        }

        // The output is incomplete, inaccessible, or unverifiable. Attempt to
        // remove only the URI created by this journaled operation. If the
        // provider cannot prove removal, keep both sides and require review.
        val removed = destination != null && deleteProviderItem(destination) && providerConfirmsAbsent(destination)
        writeMetadata(file.copy(
            operationState = if (removed) VaultFile.OperationState.READY
            else VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED,
            pendingDestinationUri = if (removed) null else file.pendingDestinationUri,
            pendingDestinationName = if (removed) null else file.pendingDestinationName
        ))
    }

    private fun makeMediaStoreVisible(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != "media") return true
        return try {
            val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
                ?: return false
            val pending = cursor.use {
                if (!it.moveToFirst()) return false
                val index = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                index >= 0 && it.getInt(index) != 0
            }
            if (!pending) return true
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, values, null, null) == 1
        } catch (_: Exception) {
            false
        }
    }

    private fun readEntry(id: String): VaultFile? {
        val sidecar = File(vaultDir, id + META_SUFFIX)
        if (!sidecar.isFile) return null
        try {
            UUID.fromString(id)
            val json = JSONObject(String(cipher.decryptBytes(sidecar.readBytes()), Charsets.UTF_8))
            val entry = VaultFile.fromJson(id, json)
            if (!payloadFor(entry).isFile && entry.operationState != VaultFile.OperationState.DELETE_PENDING) {
                throw IOException("Encrypted payload is missing")
            }
            return entry
        } catch (error: Exception) {
            throw IOException("Vault entry metadata or payload could not be verified; data was preserved.", error)
        }
    }

    private fun readEntriesLocked(allowDeletePendingWithoutPayload: Boolean): List<VaultFile> {
        val entries = vaultDir.listFiles() ?: throw IOException("Could not scan the private vault directory")
        val sidecars = entries.filter { it.name.endsWith(META_SUFFIX) }
        val files = ArrayList<VaultFile>(sidecars.size)
        for (sidecar in sidecars) {
            val id = sidecar.name.removeSuffix(META_SUFFIX)
            try {
                UUID.fromString(id)
                val json = JSONObject(String(cipher.decryptBytes(sidecar.readBytes()), Charsets.UTF_8))
                val file = VaultFile.fromJson(id, json)
                val payloadExists = payloadFor(file).isFile
                if (!payloadExists && !(allowDeletePendingWithoutPayload &&
                        file.operationState == VaultFile.OperationState.DELETE_PENDING)
                ) throw IOException("Encrypted payload is missing")
                files += file
            } catch (error: Exception) {
                throw IOException("Vault entry metadata could not be verified. Encrypted data was preserved.", error)
            }
        }
        val sidecarIds = sidecars.mapTo(HashSet()) { it.name.removeSuffix(META_SUFFIX) }
        entries.filter { it.name.endsWith(PAYLOAD_SUFFIX) && it.name.removeSuffix(PAYLOAD_SUFFIX) !in sidecarIds }
            .forEach { orphan ->
                if (orphan.exists() && !orphan.delete()) {
                    throw IOException("An interrupted encrypted transaction could not be cleaned up; data was preserved.")
                }
            }
        return files
    }

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

    private fun deleteProviderItem(uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }

    /** A failed read proves absence only when the provider reports a missing document. */
    private fun providerConfirmsAbsent(uri: Uri): Boolean {
        try {
            val column = if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            } else {
                MediaStore.MediaColumns._ID
            }
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return false
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            // Some providers do not support query; verify by opening below.
        }
        return try {
            val input = resolver.openInputStream(uri) ?: return false
            input.close()
            false
        } catch (_: java.io.FileNotFoundException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Null means the provider could not be inspected safely; caller uses the save picker. */
    private fun uniqueDisplayName(volume: String, path: String, requested: String): String? {
        fun exists(name: String): Boolean? = try {
            resolver.query(
                MediaStore.Files.getContentUri(volume),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(path, name),
                null
            )?.use { it.moveToFirst() }
        } catch (_: Exception) {
            null
        }

        val requestedExists = exists(requested) ?: return null
        if (!requestedExists) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (number in 1..999) {
            val candidate = "$base ($number)$extension"
            val candidateExists = exists(candidate) ?: return null
            if (!candidateExists) return candidate
        }
        return null
    }

    private fun mediaCollection(kind: VaultFile.Kind, volume: String): Uri? = when (kind) {
        VaultFile.Kind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        VaultFile.Kind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        VaultFile.Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
        VaultFile.Kind.DOCUMENT, VaultFile.Kind.APK, VaultFile.Kind.OTHER -> null
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
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun kindFor(mime: String, name: String): VaultFile.Kind {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (mime == "application/vnd.android.package-archive" || extension == "apk") return VaultFile.Kind.APK
        if (mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff")) return VaultFile.Kind.IMAGE
        if (mime.startsWith("video/") || extension in setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "mpeg", "mpg")) return VaultFile.Kind.VIDEO
        if (mime.startsWith("audio/") || extension in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr")) return VaultFile.Kind.AUDIO
        if (mime.startsWith("text/") || extension in setOf(
                "pdf", "txt", "md", "csv", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "odt", "ods", "odp", "epub", "html", "htm", "xml", "json"
            )
        ) return VaultFile.Kind.DOCUMENT
        return VaultFile.Kind.OTHER
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
