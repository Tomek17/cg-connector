package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class PasswordEncryptorTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val publicB64 = Base64.getEncoder().encodeToString(pair.public.encoded)

    @Test
    fun `encrypts with OAEP SHA-256 that the server can decrypt`() {
        val encrypted = PasswordEncryptor.encrypt(publicB64, "Tajne-Hasło-123")

        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val spec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        cipher.init(Cipher.DECRYPT_MODE, pair.private, spec)
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)

        assertThat(plain).isEqualTo("Tajne-Hasło-123")
        assertThat(encrypted).doesNotContain("Tajne")
    }

    @Test
    fun `fingerprint is sha256 hex of the key`() {
        val fingerprint = PasswordEncryptor.fingerprint(publicB64)

        assertThat(fingerprint).hasLength(64)
        assertThat(fingerprint).isEqualTo(PasswordEncryptor.fingerprint(publicB64))
    }
}
