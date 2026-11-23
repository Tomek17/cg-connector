package pl.cgconnector.app.ui.goal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.RaceGoal
import pl.cgconnector.app.ui.components.BackIconButton
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.progress.messageRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun GoalEditorRoute(
    onDone: () -> Unit,
    viewModel: GoalEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    GoalEditorScreen(
        state = state,
        onBack = onDone,
        onName = viewModel::setName,
        onPreset = viewModel::choosePreset,
        onCustomKm = viewModel::setCustomKm,
        onDate = viewModel::setDate,
        onTime = viewModel::setTime,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(
    state: GoalEditorUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onPreset: (Double?) -> Unit,
    onCustomKm: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
    onTime: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.hasGoal) R.string.goal_edit_title else R.string.goal_new_title)) },
                navigationIcon = { BackIconButton(onBack) },
            )
        },
    ) { padding ->
        if (state.isLoading) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.goal_name)) },
                placeholder = { Text(stringResource(R.string.goal_name_hint)) },
                isError = GoalProblem.NAME in state.problems,
                supportingText = { if (GoalProblem.NAME in state.problems) Text(stringResource(R.string.goal_problem_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.goal_distance), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaceGoal.PRESETS.forEach { km ->
                    FilterChip(
                        selected = state.presetKm == km,
                        onClick = { onPreset(km) },
                        label = { Text(stringResource(presetLabel(km))) },
                    )
                }
                FilterChip(
                    selected = state.presetKm == null,
                    onClick = { onPreset(null) },
                    label = { Text(stringResource(R.string.goal_distance_other)) },
                )
            }
            if (state.presetKm == null) {
                OutlinedTextField(
                    value = state.customKm,
                    onValueChange = onCustomKm,
                    label = { Text(stringResource(R.string.goal_distance_km)) },
                    isError = GoalProblem.DISTANCE in state.problems,
                    supportingText = {
                        if (GoalProblem.DISTANCE in state.problems) Text(stringResource(R.string.goal_problem_distance))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            GoalDateButton(state.date, state.today, onDate)
            OutlinedTextField(
                value = state.time,
                onValueChange = onTime,
                label = { Text(stringResource(R.string.goal_time)) },
                placeholder = { Text("1:45:00") },
                isError = GoalProblem.TIME in state.problems,
                supportingText = {
                    val pace = state.paceSecPerKm?.let { Formatters.pace(it / 60.0) }
                    when {
                        GoalProblem.TIME in state.problems -> Text(stringResource(R.string.goal_problem_time))
                        pace != null -> Text(stringResource(R.string.goal_pace, pace))
                        else -> Text(stringResource(R.string.goal_time_hint))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onSave, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wizard_save))
            }
            if (state.hasGoal) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !state.isSaving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.goal_delete)) }
            }
            Text(stringResource(R.string.goal_claude_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.goal_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.goal_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Data startu: od dziś do [MAX_YEARS_AHEAD] lat naprzód (cel może być odległy, inaczej niż treningi). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDateButton(
    date: LocalDate,
    today: LocalDate,
    onDate: (LocalDate) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.goal_date, DATE_FORMAT.format(date)))
    }
    if (!open) return
    val last = today.plusYears(MAX_YEARS_AHEAD)
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.toLocalDate() in today..last

                    override fun isSelectableYear(year: Int): Boolean = year in today.year..last.year
                },
        )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onDate(it.toLocalDate()) }
                open = false
            }) { Text(stringResource(R.string.wizard_date_ok)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = picker) }
}

fun presetLabel(km: Double): Int =
    when (km) {
        RaceGoal.HALF_MARATHON_KM -> R.string.goal_distance_half
        RaceGoal.MARATHON_KM -> R.string.goal_distance_marathon
        10.0 -> R.string.goal_distance_10k
        else -> R.string.goal_distance_5k
    }

private const val MAX_YEARS_AHEAD = 5L
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Formatters.POLISH)

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
