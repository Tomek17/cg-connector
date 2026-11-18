package pl.cgconnector.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import pl.cgconnector.app.data.settings.DataStoreServerConfigRepository.Companion.KEY_SERVER_URL
import pl.cgconnector.app.data.settings.DataStoreServerConfigRepository.Companion.KEY_TOKEN_ENCRYPTED
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DataStoreServerConfigRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Odwracalny „szyfr” testowy; [decryptError] symuluje np. unieważniony klucz Keystore. */
    private class FakeCipher : TokenCipher {
        var decryptError: Exception? = null

        override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String {
            decryptError?.let { throw it }
            return ciphertext.removePrefix("enc:").reversed()
        }
    }

    private val cipher = FakeCipher()
    private val token = "t".repeat(20) + "u".repeat(23)
    private val url = "https://cg.example.ts.net"

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }

    private fun repository(dataStore: DataStore<Preferences>) =
        DataStoreServerConfigRepository(dataStore, cipher, UnconfinedTestDispatcher())

    @Test
    fun emptyStoreIsNotConfigured() =
        runTest {
            assertThat(repository(newDataStore()).state.first()).isEqualTo(ConfigState.NotConfigured)
        }

    @Test
    fun saveStoresOnlyEncryptedTokenAndReadsItBack() =
        runTest {
            val dataStore = newDataStore()
            val repo = repository(dataStore)

            repo.save(ServerConfig(url, token))

            assertThat(repo.state.first()).isEqualTo(ConfigState.Configured(ServerConfig(url, token)))
            assertThat(repo.config.first()).isEqualTo(ServerConfig(url, token))
            val raw = dataStore.data.first()
            assertThat(raw[KEY_TOKEN_ENCRYPTED]).isNotEqualTo(token)
            assertThat(raw[KEY_TOKEN_ENCRYPTED]).doesNotContain(token)
        }

    @Test
    fun decryptFailureKeepsUrlAndRemovesToken() =
        runTest {
            listOf(
                GeneralSecurityException("key invalidated"),
                AEADBadTagException("tag mismatch"),
                ProviderException("keystore failure"),
                IllegalArgumentException("bad base64"),
            ).forEach { error ->
                val dataStore = newDataStore()
                val repo = repository(dataStore)
                repo.save(ServerConfig(url, token))
                cipher.decryptError = error

                assertThat(repo.state.first()).isEqualTo(ConfigState.TokenMissing(url))
                assertThat(repo.config.first()).isNull()
                val raw = dataStore.data.first()
                assertThat(raw[KEY_SERVER_URL]).isEqualTo(url)
                assertThat(raw[KEY_TOKEN_ENCRYPTED]).isNull()

                cipher.decryptError = null
            }
        }

    @Test
    fun savingNewTokenAfterLossRestoresConfiguration() =
        runTest {
            val repo = repository(newDataStore())
            repo.save(ServerConfig(url, token))
            cipher.decryptError = ProviderException("boom")
            assertThat(repo.state.first()).isEqualTo(ConfigState.TokenMissing(url))

            cipher.decryptError = null
            repo.save(ServerConfig(url, "n".repeat(40)))

            assertThat(repo.state.first()).isEqualTo(ConfigState.Configured(ServerConfig(url, "n".repeat(40))))
        }

    @Test
    fun urlWithoutTokenIsTokenMissing() =
        runTest {
            val dataStore = newDataStore()
            dataStore.edit { it[KEY_SERVER_URL] = url }

            assertThat(repository(dataStore).state.first()).isEqualTo(ConfigState.TokenMissing(url))
        }

    @Test
    fun clearRemovesEverything() =
        runTest {
            val repo = repository(newDataStore())
            repo.save(ServerConfig(url, token))

            repo.clear()

            assertThat(repo.state.first()).isEqualTo(ConfigState.NotConfigured)
        }
}
