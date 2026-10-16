package com.compass.vault.security

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Best-effort secure deletion.
 *
 * On modern flash storage a plain `delete()` only un-links the directory
 * entry; the blocks stay around until the wear leveller overwrites them.
 * To make recovery as hard as possible we overwrite the file contents with
 * random bytes *before* unlinking it.
 *
 * Honest caveat: this is defence in depth, not a guarantee. On wear-levelled
 * flash, the controller may remap the physical block, so a sufficiently
 * determined attacker with physical access to the raw flash could still
 * recover fragments. The real protection is that the data was **encrypted**
 * in the first place - the ciphertext is worthless without the Keystore key.
 */
object SecureWipe {

    private val random = SecureRandom()

    /** Overwrites [file] with random bytes and then deletes it. */
    fun wipe(file: File) {
        if (!file.exists()) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                var remaining = raf.length()
                val buffer = ByteArray(64 * 1024)
                // Write from the end backwards so a crash half way through
                // still destroys the tail of the file.
                while (remaining > 0) {
                    random.nextBytes(buffer)
                    val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.seek(remaining - chunk)
                    raf.write(buffer, 0, chunk)
                    remaining -= chunk
                }
                raf.fd.sync()
            }
        } catch (_: Exception) {
            // Best effort - we still try to delete below.
        } finally {
            file.delete()
        }
    }

    /** Wipes every file inside [dir] and then removes the directory. */
    fun wipeDirectory(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { wipe(it) }
        dir.delete()
    }

    /** Overwrites a byte array in memory (used for decrypted buffers). */
    fun wipe(buffer: ByteArray?) {
        if (buffer == null) return
        buffer.fill(0)
    }
}
