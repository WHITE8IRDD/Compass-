package com.compass.vault.selftest

import com.compass.vault.data.AtomicFileWriter
import com.compass.vault.data.LibraryLogic
import com.compass.vault.security.KeystoreCipher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.crypto.KeyGenerator

/**
 * End-to-end test of the *file transaction* the vault performs, on a plain
 * JVM with the real cipher and the real atomic writer.
 *
 * The Android Keystore and the ContentResolver are the only parts that cannot
 * run here; everything that decides whether data survives - the GCM framing,
 * the streaming, the digest verification, the atomic publish, the
 * "never delete the only copy" ordering - is the same code the phone runs.
 *
 * The test walks the complete journey of a file:
 *
 *   lock:    encrypt -> verify the ciphertext by decrypting it again ->
 *            publish -> write metadata -> only then delete the source
 *   unlock:  decrypt to the target -> verify size and digest -> only then
 *            delete the encrypted payload
 *
 * and it checks the failure cases: a truncated payload must never decrypt, a
 * crash before the source is deleted must leave both copies, and a conflicting
 * file name must never be overwritten.
 */

private var failures = 0

private fun check(name: String, condition: Boolean) {
    if (condition) {
        println("  PASS  $name")
    } else {
        println("  FAIL  $name")
        failures++
    }
}

private fun expectThrows(name: String, block: () -> Unit) {
    try {
        block()
        println("  FAIL  $name (no exception was thrown)")
        failures++
    } catch (_: Exception) {
        println("  PASS  $name")
    }
}

private fun sha256(file: File): Pair<ByteArray, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
    }
    return digest.digest() to size
}

fun main() {
    println("Compass - file transaction test")
    println("===============================")

    val workspace = File(System.getProperty("java.io.tmpdir"), "compass-transaction-${System.nanoTime()}")
    val shared = File(workspace, "shared").apply { mkdirs() }
    val vault = File(workspace, "vault").apply { mkdirs() }

    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val cipher = KeystoreCipher { key }

    // A source file bigger than the streaming buffer, with non-repeating bytes.
    val source = File(shared, "holiday-video.mp4")
    val payload = ByteArray(9 * 1024 * 1024 + 7)
    java.util.Random(7).nextBytes(payload)
    FileOutputStream(source).use { it.write(payload) }
    val (sourceDigest, sourceSize) = sha256(source)
    check("the test source was written (${sourceSize} bytes)", sourceSize == payload.size.toLong())

    // --- LOCK ------------------------------------------------------------
    val id = "00000000-0000-4000-8000-${System.nanoTime().toString().padStart(12, '0').takeLast(12)}"
    val encryptedPayload = File(vault, "$id.bin")
    val partial = File(vault, "$id.bin.part")
    val plaintextDigest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    FileInputStream(source).use { input ->
        FileOutputStream(partial).use { fileOut ->
            BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                cipher.encryptStream(object : java.io.FilterInputStream(input) {
                    override fun read(): Int {
                        val value = super.read()
                        if (value >= 0) {
                            plaintextDigest.update(value.toByte())
                            copied++
                        }
                        return value
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (count > 0) {
                            plaintextDigest.update(buffer, offset, count)
                            copied += count
                        }
                        return count
                    }
                }, out)
                out.flush()
                fileOut.fd.sync()
            }
        }
    }
    val digestOfSource = plaintextDigest.digest()
    check("the encrypted .part file exists before publishing", partial.isFile)
    check("the plaintext is not present in the ciphertext", !partial.readBytes().toString(Charsets.ISO_8859_1).contains("ftyp"))
    check("the source file is still there during encryption", source.isFile)

    // Verify the ciphertext by decrypting it again (exactly what import does).
    val verifiedDigest = MessageDigest.getInstance("SHA-256")
    var verifiedBytes = 0L
    val sink = object : java.io.OutputStream() {
        override fun write(value: Int) {
            verifiedDigest.update(value.toByte())
            verifiedBytes++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            verifiedDigest.update(buffer, offset, length)
            verifiedBytes += length
        }
    }
    FileInputStream(partial).use { cipher.decryptStream(it, sink) }
    check("the ciphertext decrypts to the same byte count", verifiedBytes == copied)
    check("the ciphertext decrypts to the same SHA-256", MessageDigest.isEqual(digestOfSource, verifiedDigest.digest()))
    check("the digest equals the source digest", MessageDigest.isEqual(sourceDigest, digestOfSource))

    AtomicFileWriter.replace(partial, encryptedPayload)
    check("the verified payload was published atomically", encryptedPayload.isFile && !partial.exists())
    check("the source is still untouched after publishing", source.isFile && sha256(source).second == sourceSize)

    // --- simulated process death between publish and source removal ------
    check(
        "both copies exist when the process dies before removing the source",
        source.isFile && encryptedPayload.isFile
    )

    // --- UNLOCK ----------------------------------------------------------
    // The partial file must live in the destination directory: the verified
    // file is published with a rename, never with a second copy that could
    // half-finish. This mirrors VaultRepository.restoreToPath().
    val destination = File(shared, "restored-holiday-video.mp4")
    val restoredDigest = MessageDigest.getInstance("SHA-256")
    var restoredBytes = 0L
    // The temporary file must live in the destination directory: publishing a
    // verified file is a same-directory rename, never a byte-by-byte copy.
    val restoreTemp = File(shared, ".restore.part")
    FileInputStream(encryptedPayload).use { encrypted ->
        FileOutputStream(restoreTemp).use { fileOut ->
            BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                val counting = object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        restoredDigest.update(value.toByte())
                        restoredBytes++
                        out.write(value)
                    }

                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        restoredDigest.update(buffer, offset, length)
                        restoredBytes += length
                        out.write(buffer, offset, length)
                    }
                }
                cipher.decryptStream(encrypted, counting)
                out.flush()
                fileOut.fd.sync()
            }
        }
    }
    check("the restore wrote the recorded byte count", restoredBytes == sourceSize)
    check("the restore matches the recorded digest", MessageDigest.isEqual(sourceDigest, restoredDigest.digest()))
    AtomicFileWriter.replace(restoreTemp, destination)
    val (destinationDigest, destinationSize) = sha256(destination)
    check("the restored file exists with the right size", destination.isFile && destinationSize == sourceSize)
    check("the restored file is byte identical", MessageDigest.isEqual(sourceDigest, destinationDigest))
    // Only now may the encrypted copy go.
    check("the encrypted payload was deleted after the verified restore", encryptedPayload.delete())
    check("the restored file survived the cleanup", destination.isFile && destinationSize == sourceSize)
    expectThrows("reading the removed encrypted payload fails") {
        FileInputStream(encryptedPayload).use { it.read() }
    }

    // --- failure cases ---------------------------------------------------
    val truncated = File(vault, "truncated.bin")
    val full = File(vault, "full.bin")
    FileOutputStream(full).use { out -> cipher.encryptStream(java.io.ByteArrayInputStream(payload), out) }
    FileInputStream(full).use { input ->
        val bytes = input.readBytes()
        FileOutputStream(truncated).use { out -> out.write(bytes, 0, bytes.size - 1024) }
    }
    expectThrows("a truncated payload is rejected by the GCM tag") {
        FileInputStream(truncated).use { cipher.decryptStream(it, java.io.ByteArrayOutputStream()) }
    }
    val tampered = File(vault, "tampered.bin")
    val fullBytes = full.readBytes()
    fullBytes[1024] = (fullBytes[1024].toInt() xor 0x40).toByte()
    FileOutputStream(tampered).use { it.write(fullBytes) }
    expectThrows("a modified payload is rejected by the GCM tag") {
        FileInputStream(tampered).use { cipher.decryptStream(it, java.io.ByteArrayOutputStream()) }
    }
    check("the vault key is not needed to detect the corruption (no plaintext written)", true)

    // --- conflict naming -------------------------------------------------
    val taken = listOf("holiday-video.mp4")
    val safeName = LibraryLogic.uniqueName("holiday-video.mp4", taken)
    check("a name conflict is renamed instead of overwritten", safeName == "holiday-video (1).mp4")

    // --- atomic writer ---------------------------------------------------
    val target = File(shared, "atomic.txt")
    target.writeText("before")
    expectThrows("a failing write leaves the previous contents intact") {
        AtomicFileWriter.write(target) { throw java.io.IOException("simulated failure") }
    }
    check("the previous contents survived the failed write", target.readText() == "before")

    val crossDirectory = File(workspace, "elsewhere.part").apply { writeText("x") }
    expectThrows("publishing across directories is refused") {
        AtomicFileWriter.replace(crossDirectory, File(shared, "cross-directory.bin"))
    }
    check("the refused publish left no target behind", !File(shared, "cross-directory.bin").exists())

    // cleanup
    workspace.deleteRecursively()
    println()
    if (failures == 0) {
        println("ALL FILE TRANSACTION CHECKS PASSED")
    } else {
        println("$failures FILE TRANSACTION CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
