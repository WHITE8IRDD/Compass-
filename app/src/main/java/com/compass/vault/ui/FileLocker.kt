package com.compass.vault.ui

import android.net.Uri
import com.compass.vault.AppGraph
import com.compass.vault.data.MediaFile
import com.compass.vault.data.VaultFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

/**
 * The lock transaction, in one place so that every screen (library rows, bulk
 * selection, the media preview) performs exactly the same steps:
 *
 *  1. **encrypt and verify** - the source is streamed into the private vault
 *     as AES-256-GCM ciphertext and the completed ciphertext is decrypted
 *     again to compare byte count and SHA-256 with the source. The source is
 *     not touched in this phase; a failure here leaves everything as it was.
 *  2. **journal the removal intent** - the vault entry is durably marked
 *     `SOURCE_REMOVAL_PENDING` before any Android UI can interrupt us, so a
 *     crash in the middle can be resolved on the next launch.
 *  3. **remove the public original** - SAF documents are deleted through
 *     their provider, MediaStore media through Android's own consent (one
 *     dialog for the whole batch on Android 11+), legacy volumes through the
 *     write permission.
 *  4. **verify and record** - each URI is read back; only a source that is
 *     provably gone is recorded as removed. Everything else stays visibly
 *     labelled "public original may remain".
 *
 * The class never deletes a file whose encrypted copy has not been verified,
 * and never removes the encrypted copy on a failed removal.
 */
class FileLocker(
    private val graph: AppGraph,
    private val navigator: Navigator
) {

    enum class Phase { ENCRYPTING, REMOVING, DONE }

    data class Summary(
        /** Files that are now encrypted in the vault. */
        val protected: Int,
        /** Sources Android confirmed as removed. */
        val removed: Int,
        /** Sources that may still be publicly accessible. */
        val retained: Int,
        /** Files that could not be protected at all (nothing was deleted). */
        val failed: Int,
        /** Human readable per-file problems, shown once at the end. */
        val messages: List<String> = emptyList()
    ) {
        val fullyHidden: Boolean get() = protected > 0 && retained == 0 && removed >= protected
    }

    /** Locks every listed file, including the removal of its public original. */
    suspend fun lock(
        files: List<MediaFile>,
        folder: String?,
        onProgress: (phase: Phase, text: String) -> Unit
    ): Summary {
        val messages = ArrayList<String>()
        val imported = ArrayList<Pair<VaultFile, Uri>>()
        var failed = 0
        val alreadyIndexed = try {
            graph.repository.list().associateBy { it.originUri }
        } catch (error: Exception) {
            throw IOException("The vault index could not be verified; no source file was changed.", error)
        }
        val needsRemoval = ArrayList<VaultFile>()
        for ((index, media) in files.withIndex()) {
            try {
                val existing = alreadyIndexed[media.uri.toString()]
                if (existing != null) {
                    if (existing.sourceState != VaultFile.SourceState.PRIVATE_ONLY) needsRemoval += existing
                    continue
                }
                onProgress(Phase.ENCRYPTING, "ENCRYPTING ${index + 1} OF ${files.size} · ${media.displayName}")
                val file = graph.repository.import(media.uri, folder, origin = media) { bytes ->
                    onProgress(Phase.ENCRYPTING, "ENCRYPTING ${formatSize(bytes)} · ${media.displayName}")
                }
                imported += file to media.uri
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                messages += "${media.displayName}: ${error.message ?: "could not be protected"}"
            }
        }
        val removal = removeSources(imported.map { it.first to it.second } + needsRemoval.map { it to null }, onProgress)
        val attempted = imported.size + needsRemoval.size
        return Summary(
            protected = imported.size,
            removed = removal.first,
            retained = attempted - removal.first,
            failed = failed,
            messages = messages
        )
    }

    /** Locks files that were chosen through the system file picker. */
    suspend fun lockPicked(
        uris: List<Uri>,
        folder: String?,
        onProgress: (phase: Phase, text: String) -> Unit
    ): Summary {
        val messages = ArrayList<String>()
        val imported = ArrayList<Pair<VaultFile, Uri>>()
        var failed = 0
        val alreadyIndexed = try {
            graph.repository.list().associateBy { it.originUri }
        } catch (error: Exception) {
            throw IOException("The vault index could not be verified; no source file was changed.", error)
        }
        val needsRemoval = ArrayList<VaultFile>()
        for ((index, uri) in uris.distinct().withIndex()) {
            try {
                val existing = alreadyIndexed[uri.toString()]
                if (existing != null) {
                    if (existing.sourceState != VaultFile.SourceState.PRIVATE_ONLY) needsRemoval += existing
                    continue
                }
                onProgress(Phase.ENCRYPTING, "ENCRYPTING FILE ${index + 1} OF ${uris.size}")
                val file = graph.repository.import(uri, folder) { bytes ->
                    onProgress(Phase.ENCRYPTING, "ENCRYPTING ${formatSize(bytes)}")
                }
                imported += file to uri
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                messages += "${uri.lastPathSegment ?: "file"}: ${error.message ?: "could not be protected"}"
            }
        }
        val removal = removeSources(imported.map { it.first to it.second } + needsRemoval.map { it to null }, onProgress)
        val attempted = imported.size + needsRemoval.size
        return Summary(
            protected = imported.size,
            removed = removal.first,
            retained = attempted - removal.first,
            failed = failed,
            messages = messages
        )
    }

    /** Retries only the removal step for entries that are already encrypted. */
    suspend fun retryRemoval(
        files: List<VaultFile>,
        onProgress: (phase: Phase, text: String) -> Unit
    ): Summary {
        val removal = removeSources(files.map { it to null }, onProgress)
        return Summary(protected = 0, removed = removal.first, retained = files.size - removal.first, failed = 0)
    }

    private data class RemovalResult(val first: Int, val removedUris: Set<String> = emptySet())

    private suspend fun removeSources(
        targets: List<Pair<VaultFile, Uri?>>,
        onProgress: (phase: Phase, text: String) -> Unit
    ): RemovalResult {
        if (targets.isEmpty()) return RemovalResult(0, emptySet())
        onProgress(Phase.REMOVING, "VERIFYING PUBLIC SOURCES")
        val journaled = ArrayList<Pair<VaultFile, Uri>>()
        for ((file, explicit) in targets) {
            try {
                val pending = if (file.operationState == VaultFile.OperationState.SOURCE_REMOVAL_PENDING) {
                    // Written before an earlier interruption; the journal is
                    // already durable.
                    file
                } else {
                    graph.repository.markSourceRemovalPending(file)
                }
                val uri = explicit ?: pending.originUri?.let(Uri::parse)
                if (uri == null) throw IOException("The recorded source location is missing")
                journaled += pending to uri
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Leave the entry as it is: the encrypted copy is safe and the
                // row keeps saying that the public original may remain.
            }
        }
        if (journaled.isEmpty()) return RemovalResult(0, emptySet())
        onProgress(
            Phase.REMOVING,
            if (journaled.size == 1) "REMOVING THE PUBLIC ORIGINAL" else "REMOVING ${journaled.size} PUBLIC ORIGINALS"
        )
        val removed = try {
            navigator.requestDeleteOriginalsAwait(journaled.map { it.second })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        var count = 0
        for ((pending, uri) in journaled) {
            val wasRemoved = uri.toString() in removed
            try {
                graph.repository.markSourceRemoved(pending, wasRemoved)
            } catch (_: Exception) {
                // The journal stays SOURCE_REMOVAL_PENDING; the next list()
                // re-derives the real state from the provider.
            }
            if (wasRemoved) count++
        }
        return RemovalResult(count, removed)
    }

    private fun formatSize(bytes: Long): String = com.compass.vault.data.LibraryLogic.formatSize(bytes)
}

/**
 * Suspends until Android (or the provider) has answered the removal request.
 * The implementation lives in `MainActivity`; every screen shares this bridge.
 */
suspend fun Navigator.requestDeleteOriginalsAwait(uris: List<Uri>): Set<String> =
    suspendCancellableCoroutine { continuation ->
        requestDeleteOriginals(uris) { removed, _ ->
            if (continuation.isActive) continuation.resume(removed)
        }
    }
