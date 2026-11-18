package pl.cgconnector.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import pl.cgconnector.app.di.IoDispatcher
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adres serwera zapisany jawnie, token tylko w postaci zaszyfrowanej kluczem z Keystore.
 * Odszyfrowanie (operacja Keystore, potencjalnie wolna) działa na [IoDispatcher].
 *
 * Gdy odszyfrowanie się nie uda (np. klucz unieważniony po zmianie blokady ekranu albo uszkodzony zapis),
 * adres zostaje, a token jest usuwany: stan [ConfigState.TokenMissing], użytkownik wkleja token ponownie.
 * Błąd odczytu pliku DataStore (IOException) daje stan jak przy braku konfiguracji, zamiast awarii aplikacji.
 */
@Singleton
class DataStoreServerConfigRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val cipher: TokenCipher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ServerConfigRepository {
        override val state: Flow<ConfigState> =
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map(::toState)
                .distinctUntilChanged()
                .flowOn(ioDispatcher)

        override suspend fun save(config: ServerConfig) {
            withContext(ioDispatcher) {
                val encrypted = cipher.encrypt(config.token)
                dataStore.edit { prefs ->
                    prefs[KEY_SERVER_URL] = config.serverUrl
                    prefs[KEY_TOKEN_ENCRYPTED] = encrypted
                }
            }
        }

        override suspend fun clear() {
            withContext(ioDispatcher) { dataStore.edit { it.clear() } }
        }

        private suspend fun toState(prefs: Preferences): ConfigState {
            val url = prefs[KEY_SERVER_URL] ?: return ConfigState.NotConfigured
            val encrypted = prefs[KEY_TOKEN_ENCRYPTED] ?: return ConfigState.TokenMissing(url)
            return try {
                ConfigState.Configured(ServerConfig(serverUrl = url, token = cipher.decrypt(encrypted)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isUndecryptable()) throw e
                removeUndecryptableToken()
                ConfigState.TokenMissing(url)
            }
        }

        private suspend fun removeUndecryptableToken() {
            try {
                dataStore.edit { it.remove(KEY_TOKEN_ENCRYPTED) }
            } catch (_: IOException) {
                // Nie udało się usunąć: przy następnym odczycie spróbujemy ponownie, stan i tak to TokenMissing.
            }
        }

        private fun Exception.isUndecryptable() =
            this is GeneralSecurityException || this is ProviderException || this is IllegalArgumentException

        internal companion object {
            val KEY_SERVER_URL = stringPreferencesKey("server_url")
            val KEY_TOKEN_ENCRYPTED = stringPreferencesKey("api_token_enc")
        }
    }
