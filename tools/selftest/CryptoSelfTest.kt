package com.compass.vault.selftest

import com.compass.vault.security.KeystoreCipher
import com.compass.vault.security.PinHasher
import com.compass.vault.security.SecureWipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A plain JVM test of the security critical code paths.
 *
 * The Android Keystore is not available on a desktop JVM, so the test injects
 * an ordinary JCE key into [KeystoreCipher]. Everything else - the GCM
 * framing, the IV handling, the tag verification, the PIN hashing - is the
 * *same code* the app runs on the phone.
 *
 * Run it with `tools/run-selftest.sh`.
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

private fun <T> expectThrows(name: String, block: () -> T): Boolean {
    return try {
        block()
        println("  FAIL  $name (no exception was thrown)")
        failures++
        false
    } catch (e: Exception) {
        println("  PASS  $name (${e.javaClass.simpleName})")
        true
    }
}

fun main() {
    println("Compass - crypto self test")
    println("==========================")

    // A stand-in for the Android Keystore key.
    val keyStore = KeyStore.getInstance("JCEKS").apply { load(null) }
    val generator = KeyGenerator.getInstance("AES").apply { init(256) }
    val key: SecretKey = generator.generateKey()
    val cipher = KeystoreCipher { key }

    // --- 1. round trip -----------------------------------------------------
    val plain = ByteArray(5 * 1024 * 1024)          // 5 MB
    val random = java.util.Random(42)
    random.nextBytes(plain)

    val encrypted = ByteArrayOutputStream()
    cipher.encryptStream(ByteArrayInputStream(plain), encrypted)
    val sealed = encrypted.toByteArray()
    check("ciphertext is larger than the plaintext (IV + tag)", sealed.size > plain.size)

    val decrypted = ByteArrayOutputStream()
    cipher.decryptStream(ByteArrayInputStream(sealed), decrypted)
    check("5 MB stream round trips byte for byte", decrypted.toByteArray().contentEquals(plain))

    // --- 2. unique IV ------------------------------------------------------
    val second = ByteArrayOutputStream()
    cipher.encryptStream(ByteArrayInputStream(plain), second)
    check("two encryptions of the same data differ (fresh IV)", !second.toByteArray().contentEquals(sealed))

    // --- 3. tamper detection ----------------------------------------------
    val tampered = sealed.copyOf()
    tampered[tampered.size - 20] = (tampered[tampered.size - 20].toInt() xor 0x40).toByte()
    expectThrows("a modified ciphertext is rejected (GCM tag)") {
        cipher.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream())
    }

    val truncated = sealed.copyOf(sealed.size - 5)
    expectThrows("a truncated ciphertext is rejected") {
        cipher.decryptStream(ByteArrayInputStream(truncated), ByteArrayOutputStream())
    }

    // --- 4. a different key cannot read the data ---------------------------
    val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val otherCipher = KeystoreCipher { otherKey }
    expectThrows("a different key cannot decrypt the data") {
        otherCipher.decryptStream(ByteArrayInputStream(sealed), ByteArrayOutputStream())
    }

    // --- 5. small payload API ----------------------------------------------
    val payload = "vault metadata".toByteArray()
    val sealedBytes = cipher.encryptBytes(payload)
    check("byte payload round trips", cipher.decryptBytes(sealedBytes).contentEquals(payload))
    check("byte payload is not stored in the clear", !sealedBytes.toString(Charsets.ISO_8859_1).contains("vault metadata"))

    // --- 5b. no plaintext on disk ------------------------------------------
    val marker = "TOP-SECRET-MARKER-9f3a2b".toByteArray()
    val onDisk = File.createTempFile("compass-selftest", ".bin")
    onDisk.outputStream().use { out ->
        cipher.encryptStream(ByteArrayInputStream(marker), out)
    }
    val rawBytes = onDisk.readBytes()
    check("the ciphertext on disk does not contain the plaintext",
        !rawBytes.toString(Charsets.ISO_8859_1).contains("TOP-SECRET-MARKER"))
    // 1 byte IV length + 12 byte IV + ciphertext + 16 byte GCM tag
    check("the payload grew by the IV and the GCM tag", rawBytes.size == marker.size + 1 + 12 + 16)
    onDisk.delete()

    // --- 6. PIN hashing -----------------------------------------------------
    val salt = PinHasher.newSalt()
    check("salt is 16 bytes", salt.size == 16)
    check("two salts differ", !PinHasher.newSalt().contentEquals(salt))

    val hash = PinHasher.derive("1379", salt, 20_000)
    val hashAgain = PinHasher.derive("1379", salt, 20_000)
    val hashOther = PinHasher.derive("1378", salt, 20_000)
    check("the same PIN and salt give the same hash", hash.contentEquals(hashAgain))
    check("a different PIN gives a different hash", !hash.contentEquals(hashOther))
    check("the hash is not the PIN", !hash.toString(Charsets.ISO_8859_1).contains("1379"))
    check("constant time comparison accepts the right hash", PinHasher.constantTimeEquals(hash, hashAgain))
    check("constant time comparison rejects a wrong hash", !PinHasher.constantTimeEquals(hash, hashOther))
    check("a 4 digit PIN is valid", PinHasher.isValidPin("1379"))
    check("a 3 digit PIN is rejected", !PinHasher.isValidPin("137"))
    check("a 9 digit PIN is rejected", !PinHasher.isValidPin("137912345"))
    check("a non numeric PIN is rejected", !PinHasher.isValidPin("13a9"))

    // --- 7. secure wipe -----------------------------------------------------
    val temp = File.createTempFile("compass-selftest", ".bin")
    temp.writeBytes(ByteArray(1024 * 64) { 0x41 })
    SecureWipe.wipe(temp)
    check("the wiped file is gone", !temp.exists())

    val tempDir = File(System.getProperty("java.io.tmpdir"), "compass-selftest-dir")
    tempDir.mkdirs()
    File(tempDir, "a.bin").writeBytes(ByteArray(4096) { 0x42 })
    SecureWipe.wipeDirectory(tempDir)
    check("the wiped directory is gone", !tempDir.exists())

    println()
    if (failures == 0) {
        println("ALL CHECKS PASSED")
    } else {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
