package com.compass.vault.data

import java.util.Locale

/**
 * Pure, side-effect free helpers of the file library.
 *
 * Everything in here is deliberately free of Android types so that the exact
 * same code can be exercised by the plain-JVM self test in `tools/selftest`
 * (see `LibraryLogicTest.kt`). The UI and the repositories both call these
 * functions, so a rule such as "never overwrite a conflicting file name" is
 * implemented and tested exactly once.
 */
object LibraryLogic {

    /** Image extensions Compass can classify (and, for most, decode). */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff")

    /** Video extensions that Android's MediaPlayer/MediaMetadataRetriever usually handle. */
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "webm", "3gp", "avi", "mpeg", "mpg", "ts")

    /** Audio extensions. */
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "mid", "midi")

    /** Document extensions offered in the Documents section. */
    val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "txt", "md", "csv", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods",
        "ppt", "pptx", "odp", "epub", "html", "htm", "xml", "json", "log", "ics", "vcf"
    )

    /** Archive and other extensions that belong to the "Other files" section. */
    val OTHER_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "apks", "xapk", "iso", "ttf", "otf")

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Lower-case extension without the dot; empty when the name has none. */
    fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase(Locale.ROOT).take(12)

    /**
     * Classifies a file from its MIME type and, as a fallback, its extension.
     * The MIME type wins when it is specific; `application/octet-stream` and
     * empty values are treated as "unknown".
     */
    fun kindFor(mimeType: String, displayName: String): VaultFile.Kind {
        val mime = mimeType.lowercase(Locale.ROOT)
        val extension = extensionOf(displayName)
        if (mime == APK_MIME || extension == "apk") return VaultFile.Kind.APK
        when {
            mime.startsWith("image/") -> return VaultFile.Kind.IMAGE
            mime.startsWith("video/") -> return VaultFile.Kind.VIDEO
            mime.startsWith("audio/") -> return VaultFile.Kind.AUDIO
            mime.startsWith("text/") -> return VaultFile.Kind.DOCUMENT
            mime == "application/pdf" -> return VaultFile.Kind.DOCUMENT
        }
        return when {
            extension in IMAGE_EXTENSIONS -> VaultFile.Kind.IMAGE
            extension in VIDEO_EXTENSIONS -> VaultFile.Kind.VIDEO
            extension in AUDIO_EXTENSIONS -> VaultFile.Kind.AUDIO
            extension in DOCUMENT_EXTENSIONS -> VaultFile.Kind.DOCUMENT
            extension in OTHER_EXTENSIONS -> VaultFile.Kind.OTHER
            else -> VaultFile.Kind.OTHER
        }
    }

    /** Best-effort MIME type for a name (used when a provider reports none). */
    fun mimeFromName(displayName: String): String = when (extensionOf(displayName)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "rtf" -> "application/rtf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "epub" -> "application/epub+zip"
        "ics" -> "text/calendar"
        "vcf" -> "text/x-vcard"
        "apk" -> APK_MIME
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "xapk" -> "application/x-apk-bundle"
        else -> "application/octet-stream"
    }

    /**
     * A name that collides with [existing] is never reused: the candidate gets
     * the first free `name (n).ext` suffix. This is the single rule that keeps
     * a restore from overwriting a file that appeared at the destination
     * while the item was locked.
     */
    fun uniqueName(requested: String, existing: Collection<String>): String {
        val taken = existing.toHashSet()
        if (requested !in taken) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (number in 1..999) {
            val candidate = "$base ($number)$extension"
            if (candidate !in taken) return candidate
        }
        return "$base (${System.currentTimeMillis()})$extension"
    }

    /** Case-insensitive substring match used by every search box. */
    fun matchesQuery(name: String, query: String): Boolean =
        query.isBlank() || name.contains(query.trim(), ignoreCase = true)

    /** True when a media item belongs to the section the owner is looking at. */
    fun matchesSection(kind: VaultFile.Kind, section: VaultFile.Kind?): Boolean =
        section == null || kind == section

    /** The location shown in the file details dialog. */
    fun locationLabel(relativePath: String?, absolutePath: String?, fallback: String): String = when {
        !relativePath.isNullOrBlank() -> relativePath.trimStart('/')
        !absolutePath.isNullOrBlank() -> absolutePath
        else -> fallback
    }

    /**
     * A short human readable size for the list rows: `2.4 MB`.
     * Kept here (and not in the UI) so the tests cover the edge cases.
     */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** `mm:ss` (or `h:mm:ss`) for media durations. */
    fun formatDuration(millis: Long): String {
        if (millis <= 0) return ""
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** The three-letter badge shown on a row without a thumbnail. */
    fun badge(kind: VaultFile.Kind): String = when (kind) {
        VaultFile.Kind.IMAGE -> "IMG"
        VaultFile.Kind.VIDEO -> "VID"
        VaultFile.Kind.AUDIO -> "AUD"
        VaultFile.Kind.DOCUMENT -> "DOC"
        VaultFile.Kind.APK -> "APK"
        VaultFile.Kind.OTHER -> "FILE"
    }
}
