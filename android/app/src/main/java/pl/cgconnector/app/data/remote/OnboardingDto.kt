package pl.cgconnector.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/v1/invite/redeem`. */
@Serializable
data class InviteRequestDto(
    val code: String,
)

/** Klucz publiczny do szyfrowania hasła Garmina (`RSA-OAEP-256`, Base64 DER SPKI). */
@Serializable
data class LoginKeyDto(
    val algorithm: String = "RSA-OAEP-256",
    @SerialName("public_key") val publicKey: String,
    val fingerprint: String = "",
)

@Serializable
data class InviteResponseDto(
    val user: String,
    @SerialName("app_token") val appToken: String,
    @SerialName("login_key") val loginKey: LoginKeyDto,
)

/** `POST /api/v1/garmin/login`: hasło tylko zaszyfrowane kluczem serwera. */
@Serializable
data class GarminLoginRequestDto(
    val email: String,
    @SerialName("password_encrypted") val passwordEncrypted: String,
)

/** `POST /api/v1/garmin/mfa`. */
@Serializable
data class MfaRequestDto(
    val code: String,
)

/**
 * `status`: `ok` | `needs_mfa` | `bad_credentials` | `mfa_expired` | `rate_limited` |
 * `too_many_attempts` | `key_changed`.
 */
@Serializable
data class GarminLoginResponseDto(
    val status: String,
    @SerialName("retry_at") val retryAt: String? = null,
    @SerialName("sync_started") val syncStarted: Boolean = false,
)

/** `POST /api/v1/claude-code`. */
@Serializable
data class ClaudeCodeDto(
    val code: String,
)

/** `POST /api/v1/invites` (tylko administrator). */
@Serializable
data class NewInviteRequestDto(
    val name: String,
)

@Serializable
data class NewInviteDto(
    val name: String,
    val link: String,
    @SerialName("new_user") val newUser: Boolean,
    @SerialName("expires_at") val expiresAt: String,
)
