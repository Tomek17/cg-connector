package pl.cgconnector.app.data.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import pl.cgconnector.app.data.remote.CgApi
import pl.cgconnector.app.data.remote.CgApiProvider
import pl.cgconnector.app.data.remote.CgPublicApi
import pl.cgconnector.app.data.remote.GarminLoginRequestDto
import pl.cgconnector.app.data.remote.InviteRequestDto
import pl.cgconnector.app.data.remote.MfaRequestDto
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.di.IoDispatcher
import pl.cgconnector.app.domain.PasswordEncryptor
import pl.cgconnector.app.domain.ServerConfig
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Wynik kroku logowania do Garmina (z `status` serwera). */
enum class GarminLoginResult {
    OK,
    NEEDS_MFA,
    BAD_CREDENTIALS,
    MFA_EXPIRED,
    RATE_LIMITED,
    TOO_MANY_ATTEMPTS,
    KEY_CHANGED,
    NOT_CONFIGURED,
    ;

    companion object {
        fun fromStatus(status: String): GarminLoginResult =
            when (status) {
                "ok" -> OK
                "needs_mfa" -> NEEDS_MFA
                "bad_credentials" -> BAD_CREDENTIALS
                "mfa_expired" -> MFA_EXPIRED
                "rate_limited" -> RATE_LIMITED
                "too_many_attempts" -> TOO_MANY_ATTEMPTS
                else -> KEY_CHANGED
            }
    }
}

data class GarminStep(
    val result: GarminLoginResult,
    /** ISO-8601, gdy serwer podał, od kiedy można spróbować ponownie. */
    val retryAt: String? = null,
)

/** Kod zaproszenia był zły, zużyty albo wygasł (HTTP 404). */
class InvalidInviteException : Exception("Nieprawidłowy kod zaproszenia")

/** Klucz serwera różni się od zapamiętanego: hasło nie zostało wysłane. */
class LoginKeyMismatchException : Exception("Klucz serwera się zmienił")

/** Konfiguracja osoby z samej aplikacji: kod zaproszenia → token, logowanie do Garmina, kod do Claude. */
interface OnboardingRepository {
    /** Wymienia kod zaproszenia na token i zapisuje konfigurację. Zwraca nazwę użytkownika. */
    suspend fun redeemInvite(
        serverUrl: String,
        code: String,
    ): String

    /** `ok` | `needs_login` | `rate_limited` | null (brak logowania). */
    suspend fun garminStatus(): String?

    suspend fun garminLogin(
        email: String,
        password: String,
    ): GarminStep

    suspend fun garminMfa(code: String): GarminStep

    /** Nowy kod CG do claude.ai albo `null`, gdy aplikacja nie jest skonfigurowana. */
    suspend fun newClaudeCode(): String?
}

/**
 * Odcisk klucza serwera jest przypinany przy wymianie zaproszenia (albo przy pierwszym pobraniu)
 * i sprawdzany przed każdym wysłaniem hasła.
 */
@Singleton
class ServerOnboardingRepository
    @Inject
    constructor(
        private val configRepository: ServerConfigRepository,
        private val apiProvider: CgApiProvider,
        private val dataStore: DataStore<Preferences>,
        private val baseClient: OkHttpClient,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : OnboardingRepository {
        override suspend fun redeemInvite(
            serverUrl: String,
            code: String,
        ): String =
            withContext(ioDispatcher) {
                val api =
                    Retrofit
                        .Builder()
                        .baseUrl("$serverUrl/")
                        .client(baseClient)
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(CgPublicApi::class.java)
                val response =
                    try {
                        api.redeemInvite(InviteRequestDto(code.trim()))
                    } catch (e: HttpException) {
                        if (e.code() == 404) throw InvalidInviteException()
                        throw e
                    }
                configRepository.save(ServerConfig(serverUrl = serverUrl, token = response.appToken))
                pin(PasswordEncryptor.fingerprint(response.loginKey.publicKey))
                response.user
            }

        override suspend fun garminStatus(): String? = api()?.me()?.garminStatus

        override suspend fun garminLogin(
            email: String,
            password: String,
        ): GarminStep {
            val api = api() ?: return GarminStep(GarminLoginResult.NOT_CONFIGURED)
            val key = api.garminLoginKey().publicKey
            val fingerprint = PasswordEncryptor.fingerprint(key)
            val pinned = dataStore.data.first()[KEY_LOGIN_KEY_FP]
            if (pinned == null) pin(fingerprint) else if (pinned != fingerprint) throw LoginKeyMismatchException()
            val encrypted = withContext(ioDispatcher) { PasswordEncryptor.encrypt(key, password) }
            val response = api.garminLogin(GarminLoginRequestDto(email.trim(), encrypted))
            return GarminStep(GarminLoginResult.fromStatus(response.status), response.retryAt)
        }

        override suspend fun garminMfa(code: String): GarminStep {
            val api = api() ?: return GarminStep(GarminLoginResult.NOT_CONFIGURED)
            val response = api.garminMfa(MfaRequestDto(code.trim()))
            return GarminStep(GarminLoginResult.fromStatus(response.status), response.retryAt)
        }

        override suspend fun newClaudeCode(): String? = api()?.claudeCode()?.code

        private suspend fun api(): CgApi? = apiProvider.get()

        private suspend fun pin(fingerprint: String) {
            dataStore.edit { it[KEY_LOGIN_KEY_FP] = fingerprint }
        }

        internal companion object {
            val KEY_LOGIN_KEY_FP = stringPreferencesKey("login_key_fp")
        }
    }
