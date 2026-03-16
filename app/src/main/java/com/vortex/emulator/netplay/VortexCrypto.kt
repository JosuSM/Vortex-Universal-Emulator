package com.vortex.emulator.netplay

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for all netplay traffic.
 *
 * Each lobby room has a shared session key derived from the room secret.
 * All game data packets, input states, and signaling messages are
 * encrypted end-to-end — the relay server never sees plaintext.
 */
class VortexCrypto(private val sessionKey: SecretKey) {

    companion object {
        private const val TAG = "VortexCrypto"
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128

        /** Generate a fresh random AES-256 session key. */
        fun generateSessionKey(): SecretKey {
            val keygen = KeyGenerator.getInstance("AES")
            keygen.init(AES_KEY_SIZE, SecureRandom())
            return keygen.generateKey()
        }

        /** Derive a session key from a room secret (lobby password / room code). */
        fun deriveKeyFromSecret(secret: String): SecretKey {
            // PBKDF2-like: SHA-256 the secret padded to 32 bytes
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(secret.toByteArray(Charsets.UTF_8))
            return SecretKeySpec(keyBytes, "AES")
        }

        /** Encode a session key to a shareable Base64 string. */
        fun keyToBase64(key: SecretKey): String {
            return Base64.encodeToString(key.encoded, Base64.NO_WRAP or Base64.URL_SAFE)
        }

        /** Decode a Base64 string back to a session key. */
        fun keyFromBase64(encoded: String): SecretKey {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE)
            return SecretKeySpec(decoded, "AES")
        }
    }

    private val random = SecureRandom()

    /**
     * Encrypt plaintext bytes with AES-256-GCM.
     * Returns: IV (12 bytes) || ciphertext+tag
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypt a message produced by [encrypt].
     * Input: IV (12 bytes) || ciphertext+tag
     */
    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < GCM_IV_SIZE + 1) return null
        return try {
            val iv = data.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = data.copyOfRange(GCM_IV_SIZE, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed (bad key or tampered data)")
            null
        }
    }

    /** Convenience: encrypt a UTF-8 string. */
    fun encryptString(text: String): ByteArray = encrypt(text.toByteArray(Charsets.UTF_8))

    /** Convenience: decrypt to a UTF-8 string. */
    fun decryptString(data: ByteArray): String? = decrypt(data)?.toString(Charsets.UTF_8)
}
