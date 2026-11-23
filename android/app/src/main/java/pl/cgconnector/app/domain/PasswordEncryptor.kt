package pl.cgconnector.app.domain

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Szyfruje hasło Garmina kluczem publicznym serwera (RSA-OAEP, SHA-256 także w MGF1), zanim
 * wyjdzie z telefonu. Tunel (ngrok) widzi ruch w jawnej postaci, więc bez tego widziałby hasło.
 * MGF1 trzeba podać jawnie: domyślnie Android używa w nim SHA-1, a serwer oczekuje SHA-256.
 */
object PasswordEncryptor {
    private val OAEP_SHA256 =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    /** Odcisk klucza: SHA-256 z DER (hex), liczony lokalnie, nie ufamy polu z serwera. */
    fun fingerprint(publicKeyBase64: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(publicKeyBase64))
            .joinToString("") { "%02x".format(it) }

    fun encrypt(
        publicKeyBase64: String,
        password: String,
    ): String {
        val key =
            KeyFactory
                .getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_SHA256)
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }
}
