package pl.cgconnector.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM z kluczem w Android Keystore. Klucz nie opuszcza Keystore'a
 * (na S23 Ultra: StrongBox/TEE). Format zapisu: Base64(IV || szyfrogram+tag).
 *
 * Zastępuje przestarzałe EncryptedSharedPreferences (androidx.security:security-crypto
 * jest wycofywane), zgodnie z zaleceniem: DataStore + własne szyfrowanie kluczem z Keystore.
 */
@Singleton
class KeystoreTokenCipher
    @Inject
    constructor() : TokenCipher {
        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        }

        override fun decrypt(ciphertext: String): String {
            val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
            require(bytes.size > IV_LENGTH) { "Ciphertext too short" }
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val payload = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            return String(cipher.doFinal(payload), Charsets.UTF_8)
        }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "cg_api_token_key"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val IV_LENGTH = 12
            const val TAG_LENGTH_BITS = 128
            const val KEY_SIZE_BITS = 256
        }
    }
