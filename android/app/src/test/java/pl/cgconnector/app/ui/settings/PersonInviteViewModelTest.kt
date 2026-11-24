package pl.cgconnector.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.data.onboarding.GarminStep
import pl.cgconnector.app.data.onboarding.InviteRefusal
import pl.cgconnector.app.data.onboarding.InviteRefusedException
import pl.cgconnector.app.data.onboarding.OnboardingRepository
import pl.cgconnector.app.data.onboarding.PersonInvite
import pl.cgconnector.app.testing.MainDispatcherRule

private class InviteOnboarding(
    private val admin: Boolean,
    private val refusal: InviteRefusal? = null,
) : OnboardingRepository {
    val names = mutableListOf<String>()

    override suspend fun redeemInvite(
        serverUrl: String,
        code: String,
    ): String = error("unused")

    override suspend fun garminStatus(): String? = null

    override suspend fun garminLogin(
        email: String,
        password: String,
    ): GarminStep = error("unused")

    override suspend fun garminMfa(code: String): GarminStep = error("unused")

    override suspend fun newClaudeCode(): String? = null

    override suspend fun isAdmin(): Boolean = admin

    override suspend fun createInvite(name: String): PersonInvite {
        names += name
        refusal?.let { throw InviteRefusedException(it) }
        return PersonInvite(name, "https://cg.example/join?code=cgi_x", newUser = true)
    }
}

class PersonInviteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `admin creates a link`() {
        val repository = InviteOnboarding(admin = true)
        val viewModel = PersonInviteViewModel(repository)
        assertThat(viewModel.uiState.value.isAdmin).isTrue()

        viewModel.onNameChange(" wojtek ")
        viewModel.create()

        assertThat(repository.names).containsExactly("wojtek")
        assertThat(viewModel.uiState.value.invite?.link).contains("cgi_")
        viewModel.done()
        assertThat(viewModel.uiState.value.invite).isNull()
        assertThat(viewModel.uiState.value.name).isEmpty()
    }

    @Test
    fun `section hidden for others and refusals shown`() {
        assertThat(PersonInviteViewModel(InviteOnboarding(admin = false)).uiState.value.isAdmin).isFalse()
        val viewModel = PersonInviteViewModel(InviteOnboarding(admin = true, refusal = InviteRefusal.BAD_NAME))

        viewModel.onNameChange("Ża!")
        viewModel.create()

        assertThat(viewModel.uiState.value.error).isEqualTo(PersonInviteError.BAD_NAME)
        assertThat(viewModel.uiState.value.isWorking).isFalse()
    }
}
