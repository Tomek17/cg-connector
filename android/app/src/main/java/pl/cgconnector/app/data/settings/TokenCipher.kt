package pl.cgconnector.app.data.settings

/** Szyfrowanie tokenu przed zapisem na dysk. */
interface TokenCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}
