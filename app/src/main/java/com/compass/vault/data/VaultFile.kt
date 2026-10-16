package com.compass.vault.data

import org.json.JSONObject

/**
 * One protected vault entry. Metadata is encrypted in the vault sidecar;
 * payload bytes are stored separately as AES-GCM ciphertext.
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
    /** SHA-256 of the verified plaintext, used to verify restore/source copies. */
    val contentSha256: String? = null,
    /** Whether an ordinary public source is known to remain accessible. */
    val sourceState: SourceState = SourceState.UNKNOWN
) {

    enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT }

    enum class SourceState {
        /** A readable/public original may still exist; do not claim fully hidden. */
        PUBLIC_COPY_PRESENT,
        /** Source removal was confirmed, or an identical restored copy is present. */
        PRIVATE_ONLY,
        /** Imported before source tracking, or source status could not be established. */
        UNKNOWN
    }

    /** Lower-case extension without the dot, e.g. `mp4`. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase().take(8)

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
        put("sha256", contentSha256 ?: JSONObject.NULL)
        put("sourceState", sourceState.name)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): VaultFile = VaultFile(
            id = id,
            name = json.optString("name", "file"),
            mimeType = json.optString("mime", "application/octet-stream"),
            size = json.optLong("size", 0L),
            createdAt = json.optLong("created", 0L),
            folder = if (json.isNull("folder")) null else json.optString("folder"),
            durationMs = json.optLong("duration", 0L),
            kind = runCatching { Kind.valueOf(json.optString("kind", "DOCUMENT")) }
                .getOrDefault(Kind.DOCUMENT),
            originUri = json.nullableString("originUri"),
            originVolume = json.nullableString("originVolume"),
            originRelativePath = json.nullableString("originRelativePath"),
            originDateTakenMs = json.optLong("originDateTaken", 0L),
            contentSha256 = json.nullableString("sha256"),
            // Files written by the previous version had no origin/removal
            // record. They must remain visibly unverified, never be reported
            // as fully hidden by an optimistic migration default.
            sourceState = runCatching {
                SourceState.valueOf(json.optString("sourceState", "UNKNOWN"))
            }.getOrDefault(SourceState.UNKNOWN)
        )

        private fun JSONObject.nullableString(key: String): String? =
            if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
