package pl.cgconnector.app.domain

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class UrlError { EMPTY, INVALID, NOT_HTTPS, HAS_CREDENTIALS, HAS_QUERY }

enum class TokenError { EMPTY, TOO_SHORT, WHITESPACE }

sealed interface UrlValidation {
    data class Valid(val normalizedUrl: String) : UrlValidation

    data class Invalid(val error: UrlError) : UrlValidation
}

/** Walidacja danych z ekranu konfiguracji. Czysta logika, bez zależności od Androida. */
object ConfigValidator {
    /** Token serwera ma ≥32 bajty losowości, więc w zapisie tekstowym ma co najmniej 32 znaki. */
    const val MIN_TOKEN_LENGTH = 32

    fun validateUrl(raw: String): UrlValidation {
        val input = raw.trim()
        if (input.isEmpty()) return UrlValidation.Invalid(UrlError.EMPTY)

        val uri =
            try {
                URI(input)
            } catch (_: URISyntaxException) {
                return UrlValidation.Invalid(UrlError.INVALID)
            }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") return UrlValidation.Invalid(UrlError.NOT_HTTPS)
        if (uri.rawUserInfo != null) return UrlValidation.Invalid(UrlError.HAS_CREDENTIALS)
        val host = uri.host
        if (host.isNullOrBlank()) return UrlValidation.Invalid(UrlError.INVALID)
        if (uri.rawQuery != null || uri.rawFragment != null) return UrlValidation.Invalid(UrlError.HAS_QUERY)

        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return UrlValidation.Valid("https://${host.lowercase(Locale.ROOT)}$port$path")
    }

    fun validateToken(raw: String): TokenError? {
        val token = raw.trim()
        return when {
            token.isEmpty() -> TokenError.EMPTY
            token.any { it.isWhitespace() } -> TokenError.WHITESPACE
            token.length < MIN_TOKEN_LENGTH -> TokenError.TOO_SHORT
            else -> null
        }
    }
}
