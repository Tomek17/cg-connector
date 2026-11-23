package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.BuiltInTemplates
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.ui.components.CloseIconButton

@Composable
fun RunningWizardRoute(
    onDone: () -> Unit,
    /** Po zapisie; domyślnie jak wyjście bez zapisu. */
    onSaved: () -> Unit = onDone,
    viewModel: RunningWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isSaved) { if (state.isSaved) onSaved() }
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
    RunningWizardScreen(
        state = state,
        viewModel = viewModel,
        onCancel = onCancel,
        snackbarHostState = snackbarHostState,
        onShowTemplates = { showTemplates = true },
    )
    if (showTemplates) {
        TemplateSheet(
            builtIn = BuiltInTemplates.RUNNING,
            mine = state.userTemplates,
            onPick = { template ->
                (template as? RunningWorkout)?.let(viewModel::applyTemplate)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunningWizardScreen(
    state: RunningWizardState,
    viewModel: RunningWizardViewModel,
    onCancel: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onShowTemplates: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isEditing) R.string.wizard_running_edit_title else R.string.wizard_running_title))
                },
                navigationIcon = { CloseIconButton(onClick = onCancel) },
                actions = {
                    TextButton(onClick = onShowTemplates) { Text(stringResource(R.string.wizard_templates)) }
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
            item { ProblemList(state.problems) }
            itemsIndexed(state.blocks, key = { _, block -> block.key }) { index, block ->
                BlockCard(index + 1, block, viewModel)
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RunStepKind.WARMUP, RunStepKind.INTERVAL, RunStepKind.COOLDOWN).forEach { kind ->
                        OutlinedButton(onClick = { viewModel.addStep(kind) }) {
                            Text(stringResource(R.string.wizard_add, stringResource(kind.labelRes())))
                        }
                    }
                    OutlinedButton(onClick = viewModel::addRepeat) { Text(stringResource(R.string.wizard_add_repeat)) }
                }
            }
        }
    }
}

@Composable
private fun BlockCard(
    number: Int,
    block: BlockForm,
    viewModel: RunningWizardViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val times = block.repeatTimes
                if (times != null) {
                    OutlinedTextField(
                        value = times,
                        onValueChange = { viewModel.onRepeatTimesChange(block.key, it) },
                        label = { Text(stringResource(R.string.wizard_repeat_times, number)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        stringResource(R.string.wizard_step_number, number),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                val description = stringResource(R.string.wizard_remove_description, number)
                TextButton(
                    onClick = { viewModel.removeBlock(block.key) },
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(stringResource(R.string.wizard_remove)) }
            }
            block.steps.forEach { step -> StepEditor(step) { viewModel.updateStep(block.key, it) } }
        }
    }
}

@Composable
private fun StepEditor(
    step: StepForm,
    onChange: (StepForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow(RunStepKind.entries, step.kind, { stringResource(it.labelRes()) }) { onChange(step.copy(kind = it)) }
        ChipRow(EndType.entries, step.endType, { stringResource(it.labelRes()) }) { onChange(step.copy(endType = it)) }
        when (step.endType) {
            EndType.TIME -> NumberField(step.duration, R.string.wizard_duration, KeyboardType.Text) { onChange(step.copy(duration = it)) }
            EndType.DISTANCE ->
                NumberField(step.distanceKm, R.string.wizard_distance, KeyboardType.Decimal) { onChange(step.copy(distanceKm = it)) }
            EndType.LAP -> Unit
        }
        ChipRow(TargetType.entries, step.targetType, { stringResource(it.labelRes()) }) { onChange(step.copy(targetType = it)) }
        when (step.targetType) {
            TargetType.NONE -> Unit
            TargetType.PACE ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(step.paceFrom, R.string.wizard_pace_from, KeyboardType.Text, Modifier.weight(1f)) {
                        onChange(step.copy(paceFrom = it))
                    }
                    NumberField(step.paceTo, R.string.wizard_pace_to, KeyboardType.Text, Modifier.weight(1f)) {
                        onChange(step.copy(paceTo = it))
                    }
                }
            TargetType.HR_ZONE -> ChipRow((1..5).toList(), step.hrZone, { it.toString() }) { onChange(step.copy(hrZone = it)) }
            TargetType.HR_RANGE ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(step.hrFrom, R.string.wizard_hr_from, KeyboardType.Number, Modifier.weight(1f)) {
                        onChange(step.copy(hrFrom = it))
                    }
                    NumberField(step.hrTo, R.string.wizard_hr_to, KeyboardType.Number, Modifier.weight(1f)) {
                        onChange(step.copy(hrTo = it))
                    }
                }
        }
    }
}

@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
internal fun NumberField(
    value: String,
    label: Int,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier,
    )
}

internal fun RunStepKind.labelRes(): Int =
    when (this) {
        RunStepKind.WARMUP -> R.string.step_warmup
        RunStepKind.INTERVAL -> R.string.step_interval
        RunStepKind.RECOVERY -> R.string.step_recovery
        RunStepKind.REST -> R.string.step_rest
        RunStepKind.COOLDOWN -> R.string.step_cooldown
    }

private fun EndType.labelRes(): Int =
    when (this) {
        EndType.TIME -> R.string.end_time
        EndType.DISTANCE -> R.string.end_distance
        EndType.LAP -> R.string.end_lap
    }

private fun TargetType.labelRes(): Int =
    when (this) {
        TargetType.NONE -> R.string.target_none
        TargetType.PACE -> R.string.target_pace
        TargetType.HR_ZONE -> R.string.target_hr_zone
        TargetType.HR_RANGE -> R.string.target_hr_range
    }
