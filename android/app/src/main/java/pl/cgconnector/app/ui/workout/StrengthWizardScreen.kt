package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.BuiltInTemplates
import pl.cgconnector.app.domain.workout.StrengthWorkout
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
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.templateSaved) {
        if (state.templateSaved) {
            viewModel.onTemplateSavedShown()
            snackbarHostState.showSnackbar(resources.getString(R.string.wizard_template_saved, state.name))
        }
    }

    var showTemplates by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    /** Klucz ćwiczenia, które zmieniamy w wyszukiwarce (null = dodawanie nowych). */
    var replacingKey by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_strength_title)) },
                navigationIcon = { CloseIconButton(onClick = onCancel) },
                actions = {
                    TextButton(onClick = { showTemplates = true }) { Text(stringResource(R.string.wizard_templates)) }
                    TextButton(onClick = viewModel::save, enabled = !state.isSaving) {
                        Text(stringResource(R.string.wizard_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            if (state.exercises.isEmpty()) {
                item { Text(stringResource(R.string.wizard_no_exercises), style = MaterialTheme.typography.bodyMedium) }
            }
            itemsIndexed(state.exercises, key = { _, exercise -> exercise.key }) { index, exercise ->
                ExerciseCard(
                    number = index + 1,
                    exercise = exercise,
                    isFirst = index == 0,
                    isLast = index == state.exercises.lastIndex,
                    onChange = viewModel::updateExercise,
                    onChangeExercise = {
                        replacingKey = exercise.key
                        showPicker = true
                    },
                    onMove = { up -> viewModel.moveExercise(exercise.key, up) },
                    onRemove = { viewModel.removeExercise(exercise.key) },
                )
            }
            item {
                Button(
                    onClick = {
                        replacingKey = null
                        showPicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wizard_add_exercises)) }
            }
        }
    }

    if (showPicker) {
        val key = replacingKey
        ExercisePickerSheet(
            multiSelect = key == null,
            onPicked = { names ->
                if (key == null) viewModel.addExercises(names) else names.firstOrNull()?.let { viewModel.replaceExercise(key, it) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showTemplates) {
        TemplateSheet(
            builtIn = BuiltInTemplates.STRENGTH,
            mine = state.userTemplates,
            onPick = { template ->
                (template as? StrengthWorkout)?.let(viewModel::applyTemplate)
                showTemplates = false
            },
            onDelete = viewModel::deleteTemplate,
            onSaveCurrent = {
                viewModel.saveAsTemplate()
                showTemplates = false
            },
            onDismiss = { showTemplates = false },
        )
    }
}

@Composable
private fun ExerciseCard(
    number: Int,
    exercise: ExerciseForm,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (ExerciseForm) -> Unit,
    onChangeExercise: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val displayName = StrengthForm.displayName(exercise.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val changeDescription = stringResource(R.string.wizard_change_exercise, displayName)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onChangeExercise)
                        .semantics { contentDescription = changeDescription },
                ) {
                    Text(
                        "$number. $displayName",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (displayName != exercise.name) {
                        Text(exercise.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                SmallIcon(R.drawable.ic_arrow_up, stringResource(R.string.wizard_move_up, displayName), enabled = !isFirst) {
                    onMove(true)
                }
                SmallIcon(R.drawable.ic_arrow_down, stringResource(R.string.wizard_move_down, displayName), enabled = !isLast) {
                    onMove(false)
                }
                SmallIcon(R.drawable.ic_close, stringResource(R.string.wizard_remove_exercise_description, number), onClick = onRemove)
            }
            ChipRow(listOf(false, true), exercise.byTime, { stringResource(if (it) R.string.wizard_by_time else R.string.wizard_by_reps) }) {
                onChange(exercise.copy(byTime = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(exercise.sets, R.string.wizard_sets, KeyboardType.Number, Modifier.weight(1f)) {
                    onChange(exercise.copy(sets = it))
                }
                if (exercise.byTime) {
                    NumberField(exercise.seconds, R.string.wizard_seconds, KeyboardType.Number, Modifier.weight(1.2f)) {
                        onChange(exercise.copy(seconds = it))
                    }
                } else {
                    NumberField(exercise.reps, R.string.wizard_reps, KeyboardType.Number, Modifier.weight(1f)) {
                        onChange(exercise.copy(reps = it))
                    }
                }
                NumberField(exercise.weightKg, R.string.wizard_weight, KeyboardType.Decimal, Modifier.weight(1f)) {
                    onChange(exercise.copy(weightKg = it))
                }
                NumberField(exercise.restSeconds, R.string.wizard_rest, KeyboardType.Number, Modifier.weight(1.3f)) {
                    onChange(exercise.copy(restSeconds = it))
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(
    icon: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = description)
    }
}
