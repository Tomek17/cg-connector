package pl.cgconnector.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.domain.ServerConfig
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adres serwera zapisany jawnie, token tylko w postaci zaszyfrowanej kluczem z Keystore.
 * Gdy odszyfrowanie się nie uda (np. klucz unieważniony po zmianie blokady ekranu),
 * konfiguracja jest traktowana jak brak tokenu, a użytkownik wpisuje go ponownie.
 */
@Singleton
class DataStoreServerConfigRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val cipher: TokenCipher,
    ) : ServerConfigRepository {
        override val config: Flow<ServerConfig?> =
            dataStore.data
                .map { prefs ->
                    val url = prefs[KEY_SERVER_URL] ?: return@map null
                    val encrypted = prefs[KEY_TOKEN_ENCRYPTED] ?: return@map null
                    val token =
                        try {
                            cipher.decrypt(encrypted)
                        } catch (_: GeneralSecurityException) {
                            return@map null
                        } catch (_: IllegalArgumentException) {
                            return@map null
                        }
                    ServerConfig(serverUrl = url, token = token)
                }.distinctUntilChanged()

        override suspend fun save(config: ServerConfig) {
            val encrypted = cipher.encrypt(config.token)
            dataStore.edit { prefs ->
                prefs[KEY_SERVER_URL] = config.serverUrl
                prefs[KEY_TOKEN_ENCRYPTED] = encrypted
            }
        }

        override suspend fun clear() {
            dataStore.edit { it.clear() }
        }

        private companion object {
            val KEY_SERVER_URL = stringPreferencesKey("server_url")
            val KEY_TOKEN_ENCRYPTED = stringPreferencesKey("api_token_enc")
        }
    }
