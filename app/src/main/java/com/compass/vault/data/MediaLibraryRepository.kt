package com.compass.vault.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only discovery and RAM thumbnail cache for shared media.
 *
 * Queries MediaStore rather than walking external-storage paths. The caller
 * must first obtain the relevant runtime media permission; SAF-picked files
 * remain available even when library permission is denied.
 */
class MediaLibraryRepository(context: Context) {

    companion object {
        const val DEFAULT_PAGE_SIZE = 60
        private const val THUMB_CACHE_KB = 12 * 1024
        private const val MEDIA_TYPE_COLUMN = MediaStore.Files.FileColumns.MEDIA_TYPE
        private const val MEDIA_TYPE_IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        private const val MEDIA_TYPE_VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        private const val MEDIA_TYPE_AUDIO = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO

        /**
         * MediaStore keeps rows for files that are not media at all (documents,
         * APK files, archives, ...) with `media_type = 0`. On Android 10-12 the
         * storage permission also covers those rows, so Compass can list them.
         * On Android 13+ Android only returns media rows to an unprivileged
         * app, and the sections stay empty until a folder is approved. The app
         * says so instead of pretending those files do not exist.
         */
        private const val MEDIA_TYPE_OTHER = 0
    }

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val thumbnails = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun page(
        offset: Int,
        query: String,
        sort: MediaSort,
        partialAccess: Boolean,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        kind: VaultFile.Kind? = null
    ): MediaPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val mediaTypes = when (kind) {
            VaultFile.Kind.IMAGE -> listOf(MEDIA_TYPE_IMAGE)
            VaultFile.Kind.VIDEO -> listOf(MEDIA_TYPE_VIDEO)
            VaultFile.Kind.AUDIO -> listOf(MEDIA_TYPE_AUDIO)
            // The "Other files" section shows audio plus everything that is
            // not media, because both share the same place in the design.
            VaultFile.Kind.OTHER -> listOf(MEDIA_TYPE_AUDIO, MEDIA_TYPE_OTHER)
            VaultFile.Kind.DOCUMENT, VaultFile.Kind.APK -> listOf(MEDIA_TYPE_OTHER)
            else -> listOf(MEDIA_TYPE_IMAGE, MEDIA_TYPE_VIDEO, MEDIA_TYPE_AUDIO)
        }
        val selectionParts = mutableListOf(
            "$MEDIA_TYPE_COLUMN IN (${mediaTypes.joinToString(",") { "?" }})"
        )
        val selectionArgs = mediaTypes.mapTo(mutableListOf()) { it.toString() }
        if (cleanQuery.isNotEmpty()) {
            selectionParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$cleanQuery%"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selectionParts += "${MediaStore.MediaColumns.IS_PENDING}=0"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.IS_TRASHED}=0"
        }
        val selection = selectionParts.joinToString(" AND ")
        val sortOrder = when (sort) {
            MediaSort.NEWEST -> "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            MediaSort.NAME -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC, ${MediaStore.MediaColumns._ID} ASC"
            MediaSort.LARGEST -> "${MediaStore.MediaColumns.SIZE} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        }

        val collection = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MEDIA_TYPE_COLUMN,
            "duration"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
            projection += MediaStore.MediaColumns.VOLUME_NAME
            projection += MediaStore.MediaColumns.DATE_TAKEN
        } else {
            // Android 8-9: the absolute path is the only reliable way to put a
            // restored file back where it came from.
            projection += MediaStore.MediaColumns.DATA
        }

        val rows = mutableListOf<MediaFile>()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset.coerceAtLeast(0))
        }

        resolver.query(collection, projection.toTypedArray(), args, CancellationSignal())?.use { cursor ->
            val hasMore = cursor.count > pageSize
            while (cursor.moveToNext() && rows.size < pageSize) {
                val media = cursor.toMediaFile(collection) ?: continue
                rows += media
            }
            MediaPage(rows, if (hasMore) offset + rows.size else null, partialAccess)
        } ?: MediaPage(emptyList(), null, partialAccess)
    }

    suspend fun thumbnail(file: MediaFile, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = file.uri.toString() + ":" + maxPx
        thumbnails.get(key)?.let { return@withContext it }
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(file.uri, android.util.Size(maxPx, maxPx), CancellationSignal())
            } else {
                decodeSampled(file.uri, maxPx)
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) thumbnails.put(key, bitmap)
        bitmap
    }

    suspend fun preview(file: MediaFile, maxPx: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(file.uri, android.util.Size(maxPx, maxPx), CancellationSignal())
            } else {
                decodeSampled(file.uri, maxPx)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A thumbnail for any listed file: MediaStore rows and SAF documents. */
    suspend fun thumbnailAny(file: MediaFile, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = file.uri.toString() + ":" + maxPx
        thumbnails.get(key)?.let { return@withContext it }
        val bitmap = try {
            when (file.kind) {
                VaultFile.Kind.IMAGE -> imageThumbnail(file.uri, maxPx)
                VaultFile.Kind.VIDEO -> videoFrame(file.uri, maxPx)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) thumbnails.put(key, bitmap)
        bitmap
    }

    /** A bigger preview used by the in-app viewer (never the full resolution). */
    suspend fun previewAny(file: MediaFile, maxPx: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            when (file.kind) {
                VaultFile.Kind.IMAGE -> imageThumbnail(file.uri, maxPx, preferPlatformThumbnail = false)
                VaultFile.Kind.VIDEO -> videoFrame(file.uri, maxPx)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a bounded bitmap. The platform thumbnail service is used when it
     * is available (Android 10+) because it keeps the full image out of the
     * heap; otherwise the stream is decoded with `inSampleSize`.
     */
    private fun imageThumbnail(uri: Uri, maxPx: Int, preferPlatformThumbnail: Boolean = true): Bitmap? {
        if (preferPlatformThumbnail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return resolver.loadThumbnail(uri, android.util.Size(maxPx, maxPx), CancellationSignal())
            } catch (_: Exception) {
                // Fall through to a sampled decode for providers without thumbnails.
            }
        }
        return decodeSampled(uri, maxPx)
    }

    /** One frame of a video, read through the provider, never the whole file. */
    private fun videoFrame(uri: Uri, maxPx: Int): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.getScaledFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    fun clearCache() {
        thumbnails.evictAll()
    }

    private fun Cursor.toMediaFile(collection: Uri): MediaFile? {
        val idIndex = getColumnIndex(MediaStore.MediaColumns._ID)
        if (idIndex < 0) return null
        val id = getLong(idIndex)
        val name = stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: "media_$id"
        val mime = stringOrNull(MediaStore.MediaColumns.MIME_TYPE) ?: mimeFromName(name)
        val type = longOrNull(MEDIA_TYPE_COLUMN)?.toInt()
        val kind = when (type) {
            MEDIA_TYPE_IMAGE -> VaultFile.Kind.IMAGE
            MEDIA_TYPE_VIDEO -> VaultFile.Kind.VIDEO
            MEDIA_TYPE_AUDIO -> VaultFile.Kind.AUDIO
            else -> LibraryLogic.kindFor(mime, name)
        }
        val volume = stringOrNull(MediaStore.MediaColumns.VOLUME_NAME)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external"
        val itemUri = ContentUris.withAppendedId(collection, id)
        val modifiedSeconds = longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L
        return MediaFile(
            uri = itemUri,
            displayName = name,
            mimeType = mime,
            size = longOrNull(MediaStore.MediaColumns.SIZE) ?: 0L,
            modifiedAt = if (modifiedSeconds > 0L) modifiedSeconds * 1000L else 0L,
            dateTakenMs = longOrNull(MediaStore.MediaColumns.DATE_TAKEN) ?: 0L,
            kind = kind,
            durationMs = longOrNull("duration") ?: 0L,
            volumeName = volume,
            relativePath = stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
            absolutePath = stringOrNull(MediaStore.MediaColumns.DATA)
        )
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun mimeFromName(name: String): String = LibraryLogic.mimeFromName(name)
}
