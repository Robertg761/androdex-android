package io.androdex.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import kotlin.math.min
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    // Keep legacy storage names so the app can reuse existing paired-device state.
    private val preferences = context.getSharedPreferences("androdex.secure", Context.MODE_PRIVATE)
    private val cryptoLock = Any()

    fun readString(key: String): String? {
        return readStringState(key).value
    }

    fun readStringState(key: String): SecureReadState {
        val stored = preferences.getString(key, null) ?: return SecureReadState(
            value = null,
            wasPresent = false,
            isUnreadable = false,
        )
        val decrypted = runCatching {
            synchronized(cryptoLock) {
                runCryptoOperationWithRetry { decrypt(stored) }
            }
        }.getOrNull()
        return SecureReadState(
            value = decrypted,
            wasPresent = true,
            isUnreadable = decrypted == null,
        )
    }

    fun writeString(key: String, value: String, synchronously: Boolean = false) {
        val encrypted = synchronized(cryptoLock) {
            runCryptoOperationWithRetry { encrypt(value) }
        }
        val editor = preferences.edit().putString(key, encrypted)
        if (synchronously) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun remove(key: String, synchronously: Boolean = false) {
        val editor = preferences.edit().remove(key)
        if (synchronously) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv ?: error("Cipher did not generate an IV.")
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val decoded = Base64.decode(payload, Base64.NO_WRAP)
        require(decoded.size > 12) { "Invalid secure payload" }
        val iv = decoded.copyOfRange(0, 12)
        val ciphertext = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun <T> runCryptoOperationWithRetry(block: () -> T): T {
        var lastError: Throwable? = null
        repeat(CRYPTO_OPERATION_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                if (!isRetryableKeystoreFailure(error) || attempt == CRYPTO_OPERATION_ATTEMPTS - 1) {
                    throw error
                }
                Thread.sleep(min(CRYPTO_RETRY_DELAY_MS * (attempt + 1), CRYPTO_RETRY_MAX_DELAY_MS))
            }
        }
        throw lastError ?: IllegalStateException("SecureStore crypto operation failed without an exception.")
    }

    private fun isRetryableKeystoreFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }
            .mapNotNull { throwable -> throwable.message?.lowercase() }
            .any { message ->
                "invalid operation handle" in message
                    || "outcome: pruned" in message
                    || "keystoreoperation::finish" in message
            }
    }

    private companion object {
        const val KEY_ALIAS = "androdex_android_secure_store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CRYPTO_OPERATION_ATTEMPTS = 3
        const val CRYPTO_RETRY_DELAY_MS = 25L
        const val CRYPTO_RETRY_MAX_DELAY_MS = 100L
    }

    data class SecureReadState(
        val value: String?,
        val wasPresent: Boolean,
        val isUnreadable: Boolean,
    )
}
