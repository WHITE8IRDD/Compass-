package com.compass.vault.data

import android.net.Uri

/** A single shared media row discovered through Android MediaStore. */
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
    val relativePath: String?
)

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
