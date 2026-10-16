package com.compass.vault.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 *
 * Design rules (these are the "non-negotiable" security requirements):
 *
 *  1. The master key is *generated inside* the Android Keystore and is
 *     non-exportable: there is no `getEncoded()` that ever returns key bytes,
 *     so the key cannot leak into a file, a log, a backup or a heap dump.
 *     On devices with a TEE / StrongBox the key is additionally protected by
 *     the secure hardware.
 *
 *  2. Every single file gets a brand new random IV (96 bit, from
 *     `SecureRandom`). Re-using an IV with the same key would be
 *     catastrophic for GCM, so the IV is generated per-encryption and stored
 *     in front of the ciphertext.
 *
 *  3. GCM is an AEAD: the 128 bit authentication tag is verified on
 *     decryption. Tampered ciphertext fails loudly instead of silently
 *     decrypting to garbage.
 *
 *  4. Nothing is ever read fully into memory. [encryptStream] and
 *     [decryptStream] work on byte ranges, so a 1 GB video costs 64 KB of
 *     RAM.
 *
 * The key source is injected ([KeyProvider]) so that the exact same code can
 * be exercised by the JVM self test in `tools/selftest`.
 */
class KeystoreCipher(private val keyProvider: KeyProvider = AndroidKeystoreKeyProvider()) {

    /** Where the master key comes from. */
    fun interface KeyProvider {
        /** Returns the master key, creating it on first use. */
        fun getOrCreateKey(): SecretKey

        /** True when the key is protected by secure hardware. */
        fun isHardwareBacked(): Boolean = false
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Alias of the single master key inside the Keystore. */
        private const val KEY_ALIAS = "compass.vault.master.v1"

        /** AES-256 in GCM mode: the industry default for authenticated encryption. */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 96 bit IV - the value recommended by NIST for GCM. */
        private const val IV_BYTES = 12

        /** 128 bit authentication tag. */
        private const val TAG_BITS = 128

        /** Chunk size used by the streaming helpers. 64 KB. */
        private const val CHUNK = 64 * 1024
    }

    /**
     * The master key. It is created on first use and then lives inside the
     * Keystore for the lifetime of the installation.
     */
    private val secretKey: SecretKey by lazy { keyProvider.getOrCreateKey() }

    /** True when the master key is backed by secure hardware (TEE / StrongBox). */
    fun isHardwareBacked(): Boolean = keyProvider.isHardwareBacked()

    /**
     * The default key source: the Android Keystore.
     */
    class AndroidKeystoreKeyProvider : KeyProvider {

        private val keyStore: KeyStore by lazy {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }

        override fun getOrCreateKey(): SecretKey {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)                       // <- AES-256
                .setRandomizedEncryptionRequired(true) // the platform must use a fresh IV
                .build()

            generator.init(spec)
            return generator.generateKey()
        }

        override fun isHardwareBacked(): Boolean = try {
            val factory = javax.crypto.SecretKeyFactory.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            val info = factory.getKeySpec(
                secretKey(), android.security.keystore.KeyInfo::class.java
            ) as? android.security.keystore.KeyInfo ?: return false
            // `isInsideSecureHardware` is the API 23+ way to ask the Keystore
            // whether the key lives in a TEE / StrongBox. It was replaced by
            // `getSecurityLevel()` in API 31, which is not needed here.
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        } catch (e: Exception) {
            false
        }

        private fun secretKey(): SecretKey = getOrCreateKey()
    }

    // -----------------------------------------------------------------------
    // Streaming API - used for every file that can be bigger than RAM.
    // -----------------------------------------------------------------------

    /**
     * Encrypts [input] into [output].
     *
     * The result is laid out as:
     *   [1 byte IV length][12 bytes IV][ciphertext + 16 byte GCM tag]
     *
     * The IV is stored in the clear on purpose - it is not secret, it only
     * has to be unique per key, and it has to be readable at decryption time.
     */
    fun encryptStream(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV length: ${iv.size}" }

        output.write(iv.size)
        output.write(iv)

        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            var out: ByteArray? = cipher.update(buffer, 0, read)
            if (out != null) output.write(out)
        }
        // doFinal() appends the GCM authentication tag and finalises the stream.
        val tail = cipher.doFinal()
        if (tail != null) output.write(tail)
        output.flush()
    }

    /**
     * Decrypts [input] into [output] and **verifies the authentication tag**.
     *
     * @param onBytes called with the number of *plaintext* bytes written so far.
     * @throws javax.crypto.AEADBadTagException (wrapped) when the data was
     *         tampered with or the key does not match.
     */
    fun decryptStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}) {
        val ivLength = input.read()
        if (ivLength !in 1..32) throw java.io.IOException("Corrupt vault file (bad IV header)")
        val iv = ByteArray(ivLength)
        readFully(input, iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))

        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            var out: ByteArray? = cipher.update(buffer, 0, read)
            if (out != null) {
                output.write(out)
                total += out.size
                onBytes(total)
            }
        }
        // Throws AEADBadTagException if the tag does not verify.
        val tail = cipher.doFinal()
        if (tail != null) {
            output.write(tail)
            total += tail.size
            onBytes(total)
        }
        output.flush()
    }

    // -----------------------------------------------------------------------
    // Small-payload API - used for the (tiny) metadata sidecar files.
    // -----------------------------------------------------------------------

    fun encryptBytes(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val out = java.io.ByteArrayOutputStream(iv.size + data.size + TAG_BITS / 8)
        out.write(iv.size)
        out.write(iv)
        out.write(cipher.doFinal(data))
        return out.toByteArray()
    }

    fun decryptBytes(data: ByteArray): ByteArray {
        require(data.size > 2) { "Corrupt payload" }
        val ivLength = data[0].toInt()
        require(ivLength in 1..32 && data.size > ivLength + 1) { "Corrupt payload" }
        val iv = data.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw java.io.IOException("Unexpected end of stream")
            offset += read
        }
    }
}
