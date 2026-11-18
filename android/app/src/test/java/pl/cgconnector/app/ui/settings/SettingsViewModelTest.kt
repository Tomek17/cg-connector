package pl.cgconnector.app.ui.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig
import pl.cgconnector.app.domain.TokenError
import pl.cgconnector.app.domain.UrlError
import pl.cgconnector.app.testing.FakeServerConfigRepository
import pl.cgconnector.app.testing.MainDispatcherRule

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val validToken = "t".repeat(43)

    @Test
    fun `starts empty when not configured`() {
        val viewModel = SettingsViewModel(FakeServerConfigRepository())

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.serverUrl).isEmpty()
        assertThat(state.hasSavedToken).isFalse()
    }

    @Test
    fun `loads saved url but never exposes saved token`() {
        val repo = FakeServerConfigRepository(ServerConfig("https://cg.example.ts.net", validToken))
        val viewModel = SettingsViewModel(repo)

        val state = viewModel.uiState.value
        assertThat(state.serverUrl).isEqualTo("https://cg.example.ts.net")
        assertThat(state.token).isEmpty()
        assertThat(state.hasSavedToken).isTrue()
    }

    @Test
    fun `lost token keeps url and asks for token again`() {
        val repo = FakeServerConfigRepository(ConfigState.TokenMissing("https://cg.example.ts.net"))
        val viewModel = SettingsViewModel(repo)

        assertThat(viewModel.uiState.value.serverUrl).isEqualTo("https://cg.example.ts.net")
        assertThat(viewModel.uiState.value.tokenLost).isTrue()
        assertThat(viewModel.uiState.value.hasSavedToken).isFalse()

        viewModel.save()
        assertThat(viewModel.uiState.value.tokenError).isEqualTo(TokenError.EMPTY)

        viewModel.onTokenChange(validToken)
        viewModel.save()
        assertThat(repo.saved).containsExactly(ServerConfig("https://cg.example.ts.net", validToken))
        assertThat(viewModel.uiState.value.tokenLost).isFalse()
    }

    @Test
    fun `rejects http url and does not save`() {
        val repo = FakeServerConfigRepository()
        val viewModel = SettingsViewModel(repo)

        viewModel.onServerUrlChange("http://cg.example.ts.net")
        viewModel.onTokenChange(validToken)
        viewModel.save()

        assertThat(viewModel.uiState.value.urlError).isEqualTo(UrlError.NOT_HTTPS)
        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `reports url and token errors together`() {
        val repo = FakeServerConfigRepository()
        val viewModel = SettingsViewModel(repo)

        viewModel.onServerUrlChange("")
        viewModel.onTokenChange("abc")
        viewModel.save()

        val state = viewModel.uiState.value
        assertThat(state.urlError).isEqualTo(UrlError.EMPTY)
        assertThat(state.tokenError).isEqualTo(TokenError.TOO_SHORT)
        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `editing a field clears its error`() {
        val viewModel = SettingsViewModel(FakeServerConfigRepository())
        viewModel.onServerUrlChange("http://x")
        viewModel.save()
        assertThat(viewModel.uiState.value.urlError).isNotNull()

        viewModel.onServerUrlChange("https://x")

        assertThat(viewModel.uiState.value.urlError).isNull()
    }

    @Test
    fun `saves normalized https url and token`() =
        runTest {
            val repo = FakeServerConfigRepository()
            val viewModel = SettingsViewModel(repo)

            viewModel.events.test {
                viewModel.onServerUrlChange(" https://CG.example.ts.net/ ")
                viewModel.onTokenChange(" $validToken ")
                viewModel.save()

                assertThat(awaitItem()).isEqualTo(SettingsEvent.Saved)
            }
            assertThat(repo.saved).containsExactly(ServerConfig("https://cg.example.ts.net", validToken))
            assertThat(viewModel.uiState.value.token).isEmpty()
            assertThat(viewModel.uiState.value.hasSavedToken).isTrue()
        }

    @Test
    fun `empty token keeps previously saved token`() =
        runTest {
            val repo = FakeServerConfigRepository(ServerConfig("https://old.example.ts.net", validToken))
            val viewModel = SettingsViewModel(repo)

            viewModel.onServerUrlChange("https://new.example.ts.net")
            viewModel.save()

            assertThat(repo.saved).containsExactly(ServerConfig("https://new.example.ts.net", validToken))
        }

    @Test
    fun `empty token without saved token is an error`() {
        val repo = FakeServerConfigRepository()
        val viewModel = SettingsViewModel(repo)

        viewModel.onServerUrlChange("https://cg.example.ts.net")
        viewModel.save()

        assertThat(viewModel.uiState.value.tokenError).isEqualTo(TokenError.EMPTY)
        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `save failure emits event and re-enables saving`() =
        runTest {
            val repo = FakeServerConfigRepository().apply { failOnSave = true }
            val viewModel = SettingsViewModel(repo)

            viewModel.events.test {
                viewModel.onServerUrlChange("https://cg.example.ts.net")
                viewModel.onTokenChange(validToken)
                viewModel.save()

                assertThat(awaitItem()).isEqualTo(SettingsEvent.SaveFailed)
            }
            assertThat(viewModel.uiState.value.isSaving).isFalse()
        }
}
