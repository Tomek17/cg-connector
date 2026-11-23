package pl.cgconnector.app.ui.setup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.MainViewModel
import pl.cgconnector.app.StartScreen
import pl.cgconnector.app.data.onboarding.SetupStep
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig
import pl.cgconnector.app.testing.FakeServerConfigRepository
import pl.cgconnector.app.testing.FakeSetupProgressStore
import pl.cgconnector.app.testing.MainDispatcherRule

class SetupViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val config = ServerConfig(serverUrl = "https://cg.example.ngrok-free.dev", token = "cga_" + "x".repeat(40))

    @Test
    fun `new person goes welcome, server, then garmin after the invite is redeemed`() {
        val configRepo = FakeServerConfigRepository()
        val progress = FakeSetupProgressStore()
        val viewModel = SetupViewModel(configRepo, progress)
        assertThat(viewModel.step.value).isEqualTo(SetupStep.WELCOME)

        viewModel.start()
        assertThat(viewModel.step.value).isEqualTo(SetupStep.SERVER)
        assertThat(progress.current.value).isEqualTo(SetupStep.SERVER)

        // InviteViewModel zapisuje konfigurację po wymianie kodu.
        runBlocking { configRepo.save(config) }
        assertThat(viewModel.step.value).isEqualTo(SetupStep.GARMIN)
        assertThat(viewModel.serverUrl.value).isEqualTo(config.serverUrl)
        assertThat(viewModel.back()).isFalse()

        viewModel.next()
        assertThat(viewModel.step.value).isEqualTo(SetupStep.CLAUDE)
        viewModel.next()
        assertThat(viewModel.step.value).isEqualTo(SetupStep.DONE)

        viewModel.finish()
        assertThat(viewModel.exit.value).isEqualTo(SetupExit.HOME)
        assertThat(progress.current.value).isNull()
    }

    @Test
    fun `back from server returns to welcome`() {
        val viewModel = SetupViewModel(FakeServerConfigRepository(), FakeSetupProgressStore())
        viewModel.start()

        assertThat(viewModel.back()).isTrue()
        assertThat(viewModel.step.value).isEqualTo(SetupStep.WELCOME)
    }

    @Test
    fun `interrupted wizard resumes where it stopped`() {
        val viewModel = SetupViewModel(FakeServerConfigRepository(config), FakeSetupProgressStore(SetupStep.CLAUDE))

        assertThat(viewModel.step.value).isEqualTo(SetupStep.CLAUDE)
    }

    @Test
    fun `resume never skips the server or goes back before it`() {
        assertThat(SetupViewModel.resume(SetupStep.CLAUDE, configured = false)).isEqualTo(SetupStep.WELCOME)
        assertThat(SetupViewModel.resume(SetupStep.SERVER, configured = false)).isEqualTo(SetupStep.SERVER)
        assertThat(SetupViewModel.resume(SetupStep.SERVER, configured = true)).isEqualTo(SetupStep.GARMIN)
        assertThat(SetupViewModel.resume(null, configured = true)).isEqualTo(SetupStep.GARMIN)
        assertThat(SetupViewModel.resume(SetupStep.DONE, configured = true)).isEqualTo(SetupStep.DONE)
    }

    @Test
    fun `manual setup ends the wizard`() {
        val progress = FakeSetupProgressStore()
        val viewModel = SetupViewModel(FakeServerConfigRepository(), progress)

        viewModel.manualSetup()

        assertThat(viewModel.exit.value).isEqualTo(SetupExit.MANUAL)
        assertThat(progress.current.value).isNull()
    }

    @Test
    fun `start screen depends on configuration and unfinished wizard`() {
        val configured = ConfigState.Configured(config)
        assertThat(MainViewModel.startScreen(ConfigState.NotConfigured, null)).isEqualTo(StartScreen.SETUP)
        assertThat(MainViewModel.startScreen(ConfigState.TokenMissing(config.serverUrl), null)).isEqualTo(StartScreen.SETTINGS)
        assertThat(MainViewModel.startScreen(configured, SetupStep.GARMIN)).isEqualTo(StartScreen.SETUP)
        // Dotychczasowi użytkownicy (bez zapisanego kroku) nie widzą kreatora.
        assertThat(MainViewModel.startScreen(configured, null)).isEqualTo(StartScreen.HOME)
    }
}
