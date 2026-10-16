package com.compass.vault.data

import org.json.JSONObject

/**
 * One protected vault entry. The payload and this metadata sidecar are both
 * encrypted and live in app-private storage. Public-source and in-progress
 * transaction state are kept so recovery never treats the index alone as
 * proof that a file was moved successfully.
 */
data class VaultFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Size of the verified plaintext file in bytes. */
    val size: Long,
    val createdAt: Long,
    val folder: String?,
    /** Duration in milliseconds for audio/video, 0 when unavailable. */
    val durationMs: Long = 0L,
    val kind: Kind = Kind.DOCUMENT,
    /** Original provider URI, kept only inside the encrypted metadata sidecar. */
    val originUri: String? = null,
    /** MediaStore volume name, when the source came from MediaStore. */
    val originVolume: String? = null,
    /** MediaStore relative directory (for example `Pictures/Camera/`). */
    val originRelativePath: String? = null,
    /** Original capture time, if the provider exposed it. */
    val originDateTakenMs: Long = 0L,
    /**
     * Absolute path of the public source when Android exposed one (the `_data`
     * column on Android 8-9, or the path encoded in a SAF document id). Used
     * for display and for the legacy restore on API 26-28. Access is never
     * inferred from it.
     */
    val originAbsolutePath: String? = null,
    /** SHA-256 of the verified plaintext, used to verify restore/source copies. */
    val contentSha256: String? = null,
    /** Whether an ordinary public source is known to remain accessible. */
    val sourceState: SourceState = SourceState.UNKNOWN,
    /** Original SAF tree URI and sibling directory, both encrypted at rest. */
    val originTreeUri: String? = null,
    val originParentDocumentId: String? = null,
    /** Durable transaction journal state; READY is the only steady state. */
    val operationState: OperationState = OperationState.READY,
    /** Output URI/name recorded before restoring bytes, allowing restart recovery. */
    val pendingDestinationUri: String? = null,
    val pendingDestinationName: String? = null
) {

    enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT, APK, OTHER }

    enum class SourceState {
        /** A readable/public original may still exist; do not claim fully hidden. */
        PUBLIC_COPY_PRESENT,
        /** Source removal was confirmed, or an identical restored copy is present. */
        PRIVATE_ONLY,
        /** Imported before source tracking, or source status could not be established. */
        UNKNOWN
    }

    enum class OperationState {
        READY,
        /** Android deletion consent or SAF deletion is in progress / was interrupted. */
        SOURCE_REMOVAL_PENDING,
        /** A restore transaction has started but no destination URI is journaled yet. */
        RESTORE_PREPARING,
        /** Bytes are being written to the journaled destination. */
        RESTORE_WRITING,
        /** Destination read-back succeeded and publication is complete. */
        RESTORE_PUBLISHED,
        /** Destination status could not be resolved; keep both copies for review. */
        RESTORE_RECOVERY_REQUIRED,
        /** User-confirmed delete or verified restore is removing the encrypted entry. */
        DELETE_PENDING
    }

    /** Lower-case extension without the dot, e.g. `mp4`. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase().take(12)

    val isImage: Boolean get() = kind == Kind.IMAGE
    val isVideo: Boolean get() = kind == Kind.VIDEO
    val isAudio: Boolean get() = kind == Kind.AUDIO
    val isPlayable: Boolean get() = isVideo || isAudio

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mime", mimeType)
        put("size", size)
        put("created", createdAt)
        put("folder", folder ?: JSONObject.NULL)
        put("duration", durationMs)
        put("kind", kind.name)
        put("originUri", originUri ?: JSONObject.NULL)
        put("originVolume", originVolume ?: JSONObject.NULL)
        put("originRelativePath", originRelativePath ?: JSONObject.NULL)
        put("originDateTaken", originDateTakenMs)
        put("originAbsolutePath", originAbsolutePath ?: JSONObject.NULL)
        put("sha256", contentSha256 ?: JSONObject.NULL)
        put("sourceState", sourceState.name)
        put("originTreeUri", originTreeUri ?: JSONObject.NULL)
        put("originParentDocumentId", originParentDocumentId ?: JSONObject.NULL)
        put("operationState", operationState.name)
        put("pendingDestinationUri", pendingDestinationUri ?: JSONObject.NULL)
        put("pendingDestinationName", pendingDestinationName ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): VaultFile = VaultFile(
            id = id,
            name = json.optString("name", "file"),
            mimeType = json.optString("mime", "application/octet-stream"),
            size = json.optLong("size", 0L),
            createdAt = json.optLong("created", 0L),
            folder = json.nullableString("folder"),
            durationMs = json.optLong("duration", 0L),
            kind = runCatching { Kind.valueOf(json.optString("kind", "DOCUMENT")) }
                .getOrDefault(Kind.OTHER),
            originUri = json.nullableString("originUri"),
            originVolume = json.nullableString("originVolume"),
            originRelativePath = json.nullableString("originRelativePath"),
            originDateTakenMs = json.optLong("originDateTaken", 0L),
            originAbsolutePath = json.nullableString("originAbsolutePath"),
            contentSha256 = json.nullableString("sha256"),
            // Files written by the earlier version had no origin/removal
            // record. They stay visibly unverified, never optimistically hidden.
            sourceState = runCatching {
                SourceState.valueOf(json.optString("sourceState", "UNKNOWN"))
            }.getOrDefault(SourceState.UNKNOWN),
            originTreeUri = json.nullableString("originTreeUri"),
            originParentDocumentId = json.nullableString("originParentDocumentId"),
            // Missing state is the legacy steady state. An unknown explicit
            // value is not downgraded to READY: fail the index closed.
            operationState = if (json.has("operationState")) {
                OperationState.valueOf(json.optString("operationState"))
            } else {
                OperationState.READY
            },
            pendingDestinationUri = json.nullableString("pendingDestinationUri"),
            pendingDestinationName = json.nullableString("pendingDestinationName")
        )

        private fun JSONObject.nullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
