package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.ui.components.CloseIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthWizardRoute(
    onDone: () -> Unit,
    viewModel: StrengthWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDone() }
    val onCancel = rememberLeaveGuard(hasChanges = viewModel::hasUnsavedChanges, onLeave = onDone)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_strength_title)) },
                navigationIcon = { CloseIconButton(onClick = onCancel) },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.isSaving) {
                        Text(stringResource(R.string.wizard_save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NameAndDateFields(state.name, viewModel::onNameChange, state.date, state.today, viewModel::onDateChange)
            }
            item { ProblemList(state.problems, positionLabel = R.string.wizard_problem_exercise) }
            itemsIndexed(state.exercises, key = { _, exercise -> exercise.key }) { index, exercise ->
                ExerciseCard(index + 1, exercise, viewModel::updateExercise) { viewModel.removeExercise(exercise.key) }
            }
            item {
                OutlinedButton(onClick = viewModel::addExercise) { Text(stringResource(R.string.wizard_add_exercise)) }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    number: Int,
    exercise: ExerciseForm,
    onChange: (ExerciseForm) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.wizard_exercise_number, number),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val description = stringResource(R.string.wizard_remove_exercise_description, number)
                TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = description }) {
                    Text(stringResource(R.string.wizard_remove))
                }
            }
            OutlinedTextField(
                value = exercise.name,
                onValueChange = { onChange(exercise.copy(name = it)) },
                label = { Text(stringResource(R.string.wizard_exercise_name)) },
                supportingText = { Text(stringResource(R.string.wizard_exercise_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = StrengthForm.suggestionsFor(exercise.name)
            if (suggestions.isNotEmpty()) {
                ChipRow(suggestions, selected = null, label = { it }) { onChange(exercise.copy(name = it)) }
            }
            ChipRow(listOf(false, true), exercise.byTime, { stringResource(if (it) R.string.wizard_by_time else R.string.wizard_by_reps) }) {
                onChange(exercise.copy(byTime = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(exercise.sets, R.string.wizard_sets, KeyboardType.Number, Modifier.weight(1f)) {
                    onChange(exercise.copy(sets = it))
                }
                if (exercise.byTime) {
                    NumberField(exercise.seconds, R.string.wizard_seconds, KeyboardType.Number, Modifier.weight(1f)) {
                        onChange(exercise.copy(seconds = it))
                    }
                } else {
                    NumberField(exercise.reps, R.string.wizard_reps, KeyboardType.Number, Modifier.weight(1f)) {
                        onChange(exercise.copy(reps = it))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(exercise.weightKg, R.string.wizard_weight, KeyboardType.Decimal, Modifier.weight(1f)) {
                    onChange(exercise.copy(weightKg = it))
                }
                NumberField(exercise.restSeconds, R.string.wizard_rest, KeyboardType.Number, Modifier.weight(1f)) {
                    onChange(exercise.copy(restSeconds = it))
                }
            }
        }
    }
}
