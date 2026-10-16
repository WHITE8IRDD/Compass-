package com.compass.vault.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Local-filesystem atomic write helpers. All paths must be app-private files. */
object AtomicFileWriter {

    /** Write, sync, then atomically replace [target]; failures preserve its old contents. */
    fun write(target: File, writer: (OutputStream) -> Unit) {
        val parent = target.parentFile ?: throw IOException("Target has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create storage directory")
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                writer(stream)
                stream.flush()
                stream.fd.sync()
            }
            replace(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** Publish a completed sibling file. Never falls back to a byte-by-byte copy. */
    fun replace(source: File, target: File) {
        val sourceParent = source.parentFile?.canonicalFile
        val targetParent = target.parentFile?.canonicalFile
        if (sourceParent == null || sourceParent != targetParent) {
            throw IOException("Atomic publish requires files in the same directory")
        }
        if (!source.isFile) throw IOException("Temporary file is missing")
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-directory rename is still a single filesystem operation on
            // Android's app-private filesystems. If it cannot be done, fail
            // closed rather than leave an unverified copy.
            if (!source.renameTo(target)) throw IOException("Could not atomically publish file")
        }
    }
}
