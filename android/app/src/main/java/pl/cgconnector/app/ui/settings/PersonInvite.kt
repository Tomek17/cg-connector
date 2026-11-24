package pl.cgconnector.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.R
import pl.cgconnector.app.data.onboarding.InviteRefusal
import pl.cgconnector.app.data.onboarding.InviteRefusedException
import pl.cgconnector.app.data.onboarding.OnboardingRepository
import pl.cgconnector.app.data.onboarding.PersonInvite
import javax.inject.Inject

enum class PersonInviteError { NOT_ALLOWED, SELF, BAD_NAME, NETWORK }

data class PersonInviteUiState(
    /** Sekcja widoczna tylko u administratora (Tomka). */
    val isAdmin: Boolean = false,
    val name: String = "",
    val isWorking: Boolean = false,
    val invite: PersonInvite? = null,
    val error: PersonInviteError? = null,
)

/** „Zaproś osobę”: link zaproszenia prosto z aplikacji, bez terminala na laptopie. */
@HiltViewModel
class PersonInviteViewModel
    @Inject
    constructor(
        private val repository: OnboardingRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PersonInviteUiState())
        val uiState: StateFlow<PersonInviteUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val admin =
                    try {
                        repository.isAdmin()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                _uiState.update { it.copy(isAdmin = admin) }
            }
        }

        fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }

        fun create() {
            val name = _uiState.value.name.trim()
            if (name.isEmpty() || _uiState.value.isWorking) return
            _uiState.update { it.copy(isWorking = true, error = null, invite = null) }
            viewModelScope.launch {
                val (invite, error) =
                    try {
                        repository.createInvite(name) to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InviteRefusedException) {
                        null to
                            when (e.reason) {
                                InviteRefusal.NOT_ALLOWED -> PersonInviteError.NOT_ALLOWED
                                InviteRefusal.SELF -> PersonInviteError.SELF
                                InviteRefusal.BAD_NAME -> PersonInviteError.BAD_NAME
                            }
                    } catch (_: Exception) {
                        null to PersonInviteError.NETWORK
                    }
                _uiState.update { it.copy(isWorking = false, invite = invite, error = error) }
            }
        }

        fun done() = _uiState.update { it.copy(invite = null, name = "") }
    }

@Composable
fun PersonInviteSectionRoute(viewModel: PersonInviteViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.isAdmin) return
    PersonInviteSection(state, viewModel::onNameChange, viewModel::create, viewModel::done)
}

@Composable
fun PersonInviteSection(
    state: PersonInviteUiState,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.person_invite_title), style = MaterialTheme.typography.titleMedium)
        val invite = state.invite
        if (invite == null) {
            Text(stringResource(R.string.person_invite_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.person_invite_name)) },
                supportingText = { Text(stringResource(R.string.person_invite_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onCreate,
                enabled = state.name.isNotBlank() && !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (state.isWorking) R.string.account_working else R.string.person_invite_create)) }
            state.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            PersonInviteError.NOT_ALLOWED -> R.string.person_invite_not_allowed
                            PersonInviteError.SELF -> R.string.person_invite_self
                            PersonInviteError.BAD_NAME -> R.string.person_invite_bad_name
                            PersonInviteError.NETWORK -> R.string.account_network
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                stringResource(
                    if (invite.newUser) R.string.person_invite_ready_new else R.string.person_invite_ready_again,
                    invite.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val message = stringResource(R.string.person_invite_message, invite.link)
            val chooserTitle = stringResource(R.string.person_invite_share)
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                    context.startActivity(Intent.createChooser(send, chooserTitle))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(chooserTitle) }
            CopyField(
                label = stringResource(R.string.person_invite_link),
                value = invite.link,
                copyLabel = stringResource(R.string.person_invite_copy),
                sensitive = true,
            )
            OutlinedButton(onClick = onDone) { Text(stringResource(R.string.person_invite_done)) }
        }
    }
}
