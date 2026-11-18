package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigValidatorTest {
    @Test
    fun `accepts https url and normalizes it`() {
        assertThat(ConfigValidator.validateUrl("  HTTPS://CG-Server.Example.ts.net/  "))
            .isEqualTo(UrlValidation.Valid("https://cg-server.example.ts.net"))
    }

    @Test
    fun `keeps port and path`() {
        assertThat(ConfigValidator.validateUrl("https://example.com:8443/cg/"))
            .isEqualTo(UrlValidation.Valid("https://example.com:8443/cg"))
    }

    @Test
    fun `rejects http`() {
        assertThat(ConfigValidator.validateUrl("http://example.com"))
            .isEqualTo(UrlValidation.Invalid(UrlError.NOT_HTTPS))
    }

    @Test
    fun `rejects url without scheme`() {
        assertThat(ConfigValidator.validateUrl("example.com"))
            .isEqualTo(UrlValidation.Invalid(UrlError.NOT_HTTPS))
    }

    @Test
    fun `rejects other schemes`() {
        listOf("ftp://example.com", "file:///etc/passwd", "javascript:alert(1)").forEach {
            assertThat(ConfigValidator.validateUrl(it)).isEqualTo(UrlValidation.Invalid(UrlError.NOT_HTTPS))
        }
    }

    @Test
    fun `rejects empty url`() {
        assertThat(ConfigValidator.validateUrl("   ")).isEqualTo(UrlValidation.Invalid(UrlError.EMPTY))
    }

    @Test
    fun `rejects https without host`() {
        assertThat(ConfigValidator.validateUrl("https://")).isEqualTo(UrlValidation.Invalid(UrlError.INVALID))
        assertThat(ConfigValidator.validateUrl("https:///path")).isEqualTo(UrlValidation.Invalid(UrlError.INVALID))
    }

    @Test
    fun `rejects malformed url`() {
        assertThat(ConfigValidator.validateUrl("https://exa mple.com"))
            .isEqualTo(UrlValidation.Invalid(UrlError.INVALID))
    }

    @Test
    fun `rejects credentials in url`() {
        assertThat(ConfigValidator.validateUrl("https://user:pass@example.com"))
            .isEqualTo(UrlValidation.Invalid(UrlError.HAS_CREDENTIALS))
    }

    @Test
    fun `rejects query and fragment`() {
        assertThat(ConfigValidator.validateUrl("https://example.com/?token=abc"))
            .isEqualTo(UrlValidation.Invalid(UrlError.HAS_QUERY))
        assertThat(ConfigValidator.validateUrl("https://example.com/#x"))
            .isEqualTo(UrlValidation.Invalid(UrlError.HAS_QUERY))
    }

    @Test
    fun `validates token`() {
        assertThat(ConfigValidator.validateToken("")).isEqualTo(TokenError.EMPTY)
        assertThat(ConfigValidator.validateToken("short")).isEqualTo(TokenError.TOO_SHORT)
        assertThat(ConfigValidator.validateToken("a".repeat(20) + " " + "b".repeat(20))).isEqualTo(TokenError.WHITESPACE)
        assertThat(ConfigValidator.validateToken("  " + "x".repeat(ConfigValidator.MIN_TOKEN_LENGTH) + "  ")).isNull()
    }
}
