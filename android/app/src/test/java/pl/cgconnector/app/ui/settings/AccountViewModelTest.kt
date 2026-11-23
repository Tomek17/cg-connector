package pl.cgconnector.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.data.onboarding.GarminLoginResult
import pl.cgconnector.app.data.onboarding.GarminStep
import pl.cgconnector.app.data.onboarding.InvalidInviteException
import pl.cgconnector.app.data.onboarding.OnboardingRepository
import pl.cgconnector.app.data.onboarding.PendingInviteStore
import pl.cgconnector.app.domain.InviteLink
import pl.cgconnector.app.testing.MainDispatcherRule

private class FakeOnboarding(
    var status: String? = null,
    val loginResults: ArrayDeque<GarminStep> = ArrayDeque(),
    var claudeCode: String? = "cgc_test",
    var inviteValid: Boolean = true,
) : OnboardingRepository {
    val logins = mutableListOf<Pair<String, String>>()
    val mfaCodes = mutableListOf<String>()
    val invites = mutableListOf<Pair<String, String>>()

    override suspend fun redeemInvite(
        serverUrl: String,
        code: String,
    ): String {
        invites += serverUrl to code
        if (!inviteValid) throw InvalidInviteException()
        return "ola"
    }

    override suspend fun garminStatus(): String? = status

    override suspend fun garminLogin(
        email: String,
        password: String,
    ): GarminStep {
        logins += email to password
        return loginResults.removeFirst()
    }

    override suspend fun garminMfa(code: String): GarminStep {
        mfaCodes += code
        return loginResults.removeFirst()
    }

    override suspend fun newClaudeCode(): String? = claudeCode
}

class AccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `connected account shows connected stage`() {
        val viewModel = AccountViewModel(FakeOnboarding(status = "ok"))

        assertThat(viewModel.uiState.value.garminStage).isEqualTo(GarminStage.CONNECTED)
    }

    @Test
    fun `login with mfa ends connected and clears secrets`() {
        val repo =
            FakeOnboarding(
                loginResults = ArrayDeque(listOf(GarminStep(GarminLoginResult.NEEDS_MFA), GarminStep(GarminLoginResult.OK))),
            )
        val viewModel = AccountViewModel(repo)
        assertThat(viewModel.uiState.value.garminStage).isEqualTo(GarminStage.LOGIN)

        viewModel.onEmailChange("ola@example.com")
        viewModel.onPasswordChange("haslo")
        viewModel.login()
        assertThat(viewModel.uiState.value.garminStage).isEqualTo(GarminStage.MFA)
        assertThat(viewModel.uiState.value.password).isEmpty()

        viewModel.onMfaChange("123456")
        viewModel.submitMfa()

        val state = viewModel.uiState.value
        assertThat(state.garminStage).isEqualTo(GarminStage.CONNECTED)
        assertThat(state.mfaCode).isEmpty()
        assertThat(state.message).isEqualTo(AccountMessage.GARMIN_CONNECTED_SYNC)
        assertThat(repo.logins).containsExactly("ola@example.com" to "haslo")
        assertThat(repo.mfaCodes).containsExactly("123456")
    }

    @Test
    fun `bad password clears password and shows message`() {
        val repo = FakeOnboarding(loginResults = ArrayDeque(listOf(GarminStep(GarminLoginResult.BAD_CREDENTIALS))))
        val viewModel = AccountViewModel(repo)
        viewModel.onEmailChange("ola@example.com")
        viewModel.onPasswordChange("zle")
        viewModel.login()

        assertThat(viewModel.uiState.value.message).isEqualTo(AccountMessage.BAD_CREDENTIALS)
        assertThat(viewModel.uiState.value.password).isEmpty()
    }

    @Test
    fun `rate limit keeps retry time`() {
        val repo =
            FakeOnboarding(
                loginResults = ArrayDeque(listOf(GarminStep(GarminLoginResult.RATE_LIMITED, "2026-11-24T13:05:00+01:00"))),
            )
        val viewModel = AccountViewModel(repo)
        viewModel.onEmailChange("ola@example.com")
        viewModel.onPasswordChange("x")
        viewModel.login()

        assertThat(viewModel.uiState.value.message).isEqualTo(AccountMessage.RATE_LIMITED)
        assertThat(formatRetry(viewModel.uiState.value.retryAt!!)).isEqualTo("24.11 13:05")
    }

    @Test
    fun `empty form does not call server`() {
        val repo = FakeOnboarding()
        AccountViewModel(repo).login()

        assertThat(repo.logins).isEmpty()
    }

    @Test
    fun `claude code is shown once generated`() {
        val viewModel = AccountViewModel(FakeOnboarding(status = "ok"))
        viewModel.newClaudeCode()
        assertThat(viewModel.uiState.value.claudeCode).isEqualTo("cgc_test")

        viewModel.hideClaudeCode()
        assertThat(viewModel.uiState.value.claudeCode).isNull()
    }

    @Test
    fun `invite redeems with normalized url`() {
        val repo = FakeOnboarding()
        val viewModel = InviteViewModel(repo, PendingInviteStore())
        viewModel.onServerUrlChange("https://cg.example.ngrok-free.dev/")
        viewModel.onCodeChange("cgi_abc")
        viewModel.redeem()

        assertThat(repo.invites).hasSize(1)
        assertThat(repo.invites[0].second).isEqualTo("cgi_abc")
        assertThat(viewModel.uiState.value.message).isNull()
        assertThat(viewModel.uiState.value.code).isEmpty()
    }

    @Test
    fun `invalid invite shows message and keeps code`() {
        val repo = FakeOnboarding(inviteValid = false)
        val viewModel = InviteViewModel(repo, PendingInviteStore())
        viewModel.onServerUrlChange("https://cg.example.ngrok-free.dev")
        viewModel.onCodeChange("cgi_zly")
        viewModel.redeem()

        assertThat(viewModel.uiState.value.message).isEqualTo(AccountMessage.INVITE_INVALID)
        assertThat(viewModel.uiState.value.code).isEqualTo("cgi_zly")
    }

    @Test
    fun `invite link prefills the form once`() {
        val store = PendingInviteStore()
        val viewModel = InviteViewModel(FakeOnboarding(), store)
        store.offer(InviteLink("https://cg.example.ngrok-free.dev", "cgi_" + "a".repeat(43)))

        val state = viewModel.uiState.value
        assertThat(state.serverUrl).isEqualTo("https://cg.example.ngrok-free.dev")
        assertThat(state.code).isEqualTo("cgi_" + "a".repeat(43))
        assertThat(state.fromLink).isTrue()
        assertThat(store.invite.value).isNull()
    }
}
