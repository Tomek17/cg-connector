package pl.cgconnector.app.data.remote

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ServerConfig
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buduje [CgApi] dla bieżącej konfiguracji. Adres serwera ustawia użytkownik w aplikacji,
 * więc Retrofit nie może być singletonem z adresem znanym w czasie kompilacji.
 */
@Singleton
class CgApiProvider
    @Inject
    constructor(
        private val configRepository: ServerConfigRepository,
        private val baseClient: OkHttpClient,
        private val json: Json,
    ) {
        private var cached: Pair<ServerConfig, CgApi>? = null

        /** `null`, gdy aplikacja nie jest skonfigurowana. */
        suspend fun get(): CgApi? {
            val config = configRepository.config.first() ?: return null
            synchronized(this) {
                cached?.let { (cachedConfig, api) -> if (cachedConfig == config) return api }
                val client =
                    baseClient
                        .newBuilder()
                        .addInterceptor(AuthInterceptor(config.token))
                        .build()
                val api =
                    Retrofit
                        .Builder()
                        .baseUrl(config.serverUrl + "/")
                        .client(client)
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(CgApi::class.java)
                cached = config to api
                return api
            }
        }
    }
