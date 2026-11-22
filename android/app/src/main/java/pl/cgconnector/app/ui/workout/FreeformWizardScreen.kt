package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.WorkoutSport
import pl.cgconnector.app.domain.workout.WorkoutValidator
import pl.cgconnector.app.ui.components.CloseIconButton
import pl.cgconnector.app.ui.queue.sportLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeformWizardRoute(
    onDone: () -> Unit,
    viewModel: FreeformWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDone() }
    val onCancel = rememberLeaveGuard(hasChanges = viewModel::hasUnsavedChanges, onLeave = onDone)
    val sportName = sportLabel(state.sport).orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_freeform_title, sportName)) },
                navigationIcon = { CloseIconButton(onClick = onCancel) },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.isSaving) {
                        Text(stringResource(R.string.wizard_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChipRow(listOf(WorkoutSport.BOULDERING, WorkoutSport.OTHER), state.sport, { sportLabel(it).orEmpty() }) {
                viewModel.onSportChange(it)
            }
            NameAndDateFields(state.name, viewModel::onNameChange, state.date, state.today, viewModel::onDateChange)
            ProblemList(state.problems)
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.wizard_description)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (state.sport == WorkoutSport.BOULDERING) {
                                R.string.wizard_description_hint_bouldering
                            } else {
                                R.string.wizard_description_hint_other
                            },
                        ),
                    )
                },
                supportingText = {
                    Text(stringResource(R.string.wizard_description_counter, state.description.trim().length, WorkoutValidator.MAX_DESCRIPTION))
                },
                isError = state.description.trim().length > WorkoutValidator.MAX_DESCRIPTION,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(state.minutes, R.string.wizard_minutes, KeyboardType.Number, onValueChange = viewModel::onMinutesChange)
            Text(
                stringResource(R.string.wizard_freeform_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
