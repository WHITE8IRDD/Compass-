package com.compass.vault.data

import android.net.Uri

/** A readable file exposed through MediaStore or a user-granted SAF folder. */
data class MediaFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val dateTakenMs: Long,
    val kind: VaultFile.Kind,
    val durationMs: Long,
    val volumeName: String?,
    /** Relative folder path when Android exposes one. */
    val relativePath: String?,
    val source: Source = Source.MEDIA_STORE,
    /** Persisted SAF tree URI for entries found under a user-approved folder. */
    val treeUri: String? = null,
    /** Provider document identifier, retained only in encrypted vault metadata. */
    val documentId: String? = null,
    /** Parent identifier used to recreate the file beside its original location. */
    val parentDocumentId: String? = null,
    /**
     * Absolute path when Android exposes one: the `_data` column on Android 8-9
     * and the path encoded in a SAF document id (`primary:Download/x.pdf`).
     * Only ever used for display and for the legacy (pre-scoped-storage)
     * restore on API 26-28; never treated as proof of access.
     */
    val absolutePath: String? = null,
    /**
     * Whether the provider advertises `FLAG_SUPPORTS_DELETE` for this
     * document. False means locking can still encrypt a copy, but Compass
     * must not claim that the public original was removed.
     */
    val deletionSupported: Boolean = true
) {
    enum class Source { MEDIA_STORE, SAF_TREE }

    /** Stable comparison key for deduplicating a SAF row against a MediaStore row. */
    fun locationKey(): String {
        val safId = documentId
        if (source == Source.SAF_TREE && !safId.isNullOrBlank() && ':' in safId) {
            val volume = safId.substringBefore(':')
            val path = safId.substringAfter(':').trimStart('/')
            return "path:${normalizeVolume(volume)}:$path"
        }
        if (source == Source.MEDIA_STORE && !relativePath.isNullOrBlank()) {
            val path = relativePath!!.trimStart('/') + displayName
            return "path:${normalizeVolume(volumeName.orEmpty())}:$path"
        }
        return uri.toString()
    }

    private fun normalizeVolume(volume: String): String = when (volume) {
        "external", "external_primary", "primary" -> "primary"
        else -> volume
    }
}

enum class MediaSort {
    NEWEST,
    NAME,
    LARGEST
}

data class MediaPage(
    val files: List<MediaFile>,
    val nextOffset: Int?,
    val partialAccess: Boolean = false
)

data class TreeLibraryScan(
    /** Display names of the approved folders that were scanned. */
    val folderNames: List<String> = emptyList(),
    val files: List<MediaFile> = emptyList(),
    /** True when the scan hit the file/depth bound and the list is partial. */
    val truncated: Boolean = false,
    /** Approved folders that could not be read (revoked or restricted grants). */
    val failedFolders: List<String> = emptyList()
)
