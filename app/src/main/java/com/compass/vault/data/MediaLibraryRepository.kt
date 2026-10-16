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
    }

    private val resolver = context.applicationContext.contentResolver
    private val thumbnails = object : LruCache<String, Bitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun page(
        offset: Int,
        query: String,
        sort: MediaSort,
        partialAccess: Boolean,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): MediaPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val selectionParts = mutableListOf("$MEDIA_TYPE_COLUMN IN (?, ?, ?)")
        val selectionArgs = mutableListOf(
            MEDIA_TYPE_IMAGE.toString(),
            MEDIA_TYPE_VIDEO.toString(),
            MEDIA_TYPE_AUDIO.toString()
        )
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
            else -> when {
                mime.startsWith("image/") -> VaultFile.Kind.IMAGE
                mime.startsWith("video/") -> VaultFile.Kind.VIDEO
                mime.startsWith("audio/") -> VaultFile.Kind.AUDIO
                else -> VaultFile.Kind.DOCUMENT
            }
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
            relativePath = stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
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

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
