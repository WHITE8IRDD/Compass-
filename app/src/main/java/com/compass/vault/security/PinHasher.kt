package com.compass.vault.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The pure crypto helpers behind the PIN.
 *
 * They are kept in their own file (instead of inside [PinStore]) for two
 * reasons:
 *  * [PinStore] needs an Android `Context` to read and write its state file,
 *    these helpers do not - so they can be unit tested on a plain JVM,
 *  * it makes the security relevant code short enough to read in one go.
 */
object PinHasher {

    /** PBKDF2 rounds. High enough to make offline cracking expensive. */
    const val ITERATIONS = 250_000

    private const val KEY_LENGTH_BITS = 256
    const val SALT_BYTES = 16

    private val random = SecureRandom()

    /** A fresh random salt for a new PIN. */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /**
     * Derives the hash of [pin] with PBKDF2-HMAC-SHA256.
     *
     * 250 000 rounds take roughly half a second on a modern phone, which is
     * slow enough to make a dictionary attack on a stolen hash expensive and
     * fast enough for a person to unlock their own vault.
     */
    fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            // Do not leave the PIN in memory any longer than necessary.
            spec.clearPassword()
        }
    }

    /** True when the PIN is well formed (4-8 digits). */
    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    /**
     * Compares two hashes in constant time, so that the comparison does not
     * leak how many leading bytes were correct through its duration.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
