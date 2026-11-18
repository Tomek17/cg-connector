package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutProblem.Code
import pl.cgconnector.app.domain.workout.WorkoutValidator
import pl.cgconnector.app.ui.format.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Formatters.POLISH)

/** Nazwa treningu i dzień w kalendarzu Garmin (od dziś do +365 dni, jak na serwerze). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameAndDateFields(
    name: String,
    onNameChange: (String) -> Unit,
    date: LocalDate,
    today: LocalDate,
    onDateChange: (LocalDate) -> Unit,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.wizard_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_date, DATE_FORMAT.format(date)))
        }
    }
    if (pickerOpen) {
        val lastDay = today.plusDays(WorkoutValidator.MAX_DAYS_AHEAD)
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = date.toUtcMillis(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis.toLocalDate() in today..lastDay

                        override fun isSelectableYear(year: Int): Boolean = year in today.year..lastDay.year
                    },
            )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(it.toLocalDate()) }
                    pickerOpen = false
                }) { Text(stringResource(R.string.wizard_date_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.queue_delete_cancel)) }
            },
        ) { DatePicker(state = state) }
    }
}

/** Lista problemów po próbie zapisu; pusta = nic nie pokazuje. [positionLabel]: „Krok %d: %s” albo „Ćwiczenie…”. */
@Composable
fun ProblemList(
    problems: List<WorkoutProblem>,
    positionLabel: Int = R.string.wizard_problem_at,
) {
    if (problems.isEmpty()) return
    // Czytnik ekranu ogłasza błędy po „Zapisz”, bez przenoszenia fokusu.
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        problems.forEach { problem ->
            val text = stringResource(problem.code.textRes())
            Text(
                text = problem.position?.let { stringResource(positionLabel, it, text) } ?: text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun Code.textRes(): Int =
    when (this) {
        Code.NAME_EMPTY -> R.string.problem_name_empty
        Code.NAME_TOO_LONG -> R.string.problem_name_too_long
        Code.NO_STEPS -> R.string.problem_no_steps
        Code.TOO_MANY_STEPS -> R.string.problem_too_many_steps
        Code.DURATION_OUT_OF_RANGE -> R.string.problem_duration
        Code.DISTANCE_OUT_OF_RANGE -> R.string.problem_distance
        Code.PACE_OUT_OF_RANGE -> R.string.problem_pace
        Code.HR_ZONE_OUT_OF_RANGE -> R.string.problem_hr_zone
        Code.HR_RANGE_INVALID -> R.string.problem_hr_range
        Code.REPEAT_TIMES_OUT_OF_RANGE -> R.string.problem_repeat_times
        Code.REPEAT_EMPTY -> R.string.problem_repeat_empty
        Code.NO_EXERCISES -> R.string.problem_no_exercises
        Code.TOO_MANY_EXERCISES -> R.string.problem_too_many_exercises
        Code.EXERCISE_NAME_INVALID -> R.string.problem_exercise_name
        Code.SETS_OUT_OF_RANGE -> R.string.problem_sets
        Code.REPS_OR_SECONDS -> R.string.problem_reps_or_seconds
        Code.REPS_OUT_OF_RANGE -> R.string.problem_reps
        Code.SECONDS_OUT_OF_RANGE -> R.string.problem_seconds
        Code.WEIGHT_OUT_OF_RANGE -> R.string.problem_weight
        Code.REST_OUT_OF_RANGE -> R.string.problem_rest
        Code.DATE_IN_PAST -> R.string.problem_date_past
        Code.DATE_TOO_FAR -> R.string.problem_date_far
    }
