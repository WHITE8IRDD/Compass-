package com.compass.vault.data

import org.json.JSONObject

/**
 * One entry of the vault.
 *
 * The *content* of the file never lives in this object - only its metadata.
 * The bytes are stored encrypted in `filesDir/vault/<id>.bin`.
 */
data class VaultFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Size of the *plaintext* file in bytes. */
    val size: Long,
    val createdAt: Long,
    /** Optional folder / category name. */
    val folder: String?,
    /** Duration in milliseconds for videos, 0 otherwise. */
    val durationMs: Long = 0L,
    val kind: Kind = Kind.DOCUMENT
) {

    enum class Kind { IMAGE, VIDEO, DOCUMENT }

    /** Lower-case file extension without the dot, e.g. "mp4". */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase().take(8)

    val isImage: Boolean get() = kind == Kind.IMAGE
    val isVideo: Boolean get() = kind == Kind.VIDEO

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mime", mimeType)
        put("size", size)
        put("created", createdAt)
        put("folder", folder ?: JSONObject.NULL)
        put("duration", durationMs)
        put("kind", kind.name)
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
                .getOrDefault(Kind.DOCUMENT)
        )
    }
}
