package pl.cgconnector.app.ui.progress

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.ClimbSession
import pl.cgconnector.app.domain.ExerciseProgress
import pl.cgconnector.app.domain.RunPoint
import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.ui.components.BackIconButton
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.theme.sportColors

/** Wykresy tagu albo (gdy w trasie jest ćwiczenie) wykres serii jednego ćwiczenia. */
@Composable
fun TagProgressRoute(
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: TagProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TagProgressScreen(
        state = state,
        onRetry = viewModel::load,
        onBack = onBack,
        onOpenExercise = onOpenExercise,
        onOpenActivity = onOpenActivity,
        onEditExercises =
            ExerciseEditActions(
                onToggleEditing = viewModel::toggleEditing,
                onMove = viewModel::moveExercise,
                onRemove = viewModel::removeExercise,
                onRestore = viewModel::restoreExercise,
            ),
    )
}

/** Edycja listy ćwiczeń tagu: przesuwanie, usuwanie i przywracanie (zapis tylko na telefonie). */
data class ExerciseEditActions(
    val onToggleEditing: () -> Unit = {},
    val onMove: (name: String, delta: Int) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onRestore: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagProgressScreen(
    state: TagProgressUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenActivity: (Long) -> Unit = {},
    onEditExercises: ExerciseEditActions = ExerciseEditActions(),
) {
    val exercise = state.selectedExercise
    val canEdit =
        exercise == null && state.tag?.type == ActivityType.STRENGTH && !state.progress?.strength.isNullOrEmpty()
    val title =
        when {
            exercise != null -> exerciseName(exercise)
            state.tag != null -> stringResource(state.tag.labelRes())
            else -> ""
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    if (canEdit) {
                        TextButton(onClick = onEditExercises.onToggleEditing) {
                            Text(stringResource(if (state.isEditing) R.string.progress_edit_done else R.string.progress_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!readyOrStatus(state.isLoading, state.error, onRetry, Modifier.padding(padding))) return@Scaffold
        val progress = state.progress
        val tag = state.tag
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                progress == null || tag == null -> item { Text(stringResource(R.string.progress_empty_tag)) }
                exercise != null -> exerciseCharts(exercise, onOpenActivity)
                tag.type == ActivityType.RUNNING ->
                    if (progress.running.isEmpty()) {
                        item { Text(stringResource(R.string.progress_empty_tag)) }
                    } else {
                        runningCharts(progress.running, onOpenActivity)
                    }
                tag.type == ActivityType.STRENGTH ->
                    if (state.isEditing) {
                        exerciseEditor(state.exercises, state.removedExercises, onEditExercises)
                    } else {
                        exerciseList(state.exercises, progress.strength.isNotEmpty(), onOpenExercise)
                    }
                progress.climbing.isEmpty() -> item { Text(stringResource(R.string.progress_climbing_empty)) }
                else -> climbingCharts(progress.climbing, onOpenActivity)
            }
        }
    }
}

private fun LazyListScope.runningCharts(
    runs: List<RunPoint>,
    onOpenActivity: (Long) -> Unit,
) {
    val dates = runs.map { it.date }
    val paceSec = runs.map { run -> run.paceMinPerKm?.let { it * 60 } }
    val paceFormat = { sec: Double -> Formatters.pace(sec / 60) ?: "" }
    val whole = { v: Double -> v.roundToInt().toString() }
    val km = { v: Double -> String.format(Formatters.POLISH, "%.1f", v) }
    // Tempo w sekundach na km, żeby podziałka wypadała na pełnych sekundach; szybciej = wyżej.
    val charts =
        listOf(
            RunChart(R.string.progress_chart_pace, R.string.progress_series_pace, paceSec, paceFormat, invert = true, steps = PACE_STEPS),
            RunChart(R.string.progress_chart_hr, R.string.progress_series_hr, runs.map { it.avgHr?.toDouble() }, whole, minStep = 1.0),
            RunChart(
                R.string.progress_chart_cadence,
                R.string.progress_series_cadence,
                runs.map { it.avgCadenceSpm?.toDouble() },
                whole,
                minStep = 1.0,
            ),
            RunChart(
                R.string.progress_chart_distance,
                R.string.progress_series_distance,
                runs.map { r -> r.distanceM?.let { it / 1000 } },
                km,
            ),
        )
    charts.forEach { chart ->
        val axis = ProgressChartMath.axis(chart.values.filterNotNull(), minStep = chart.minStep, steps = chart.steps) ?: return@forEach
        item(key = "chart-${chart.title}") {
            SessionChartCard(
                title = stringResource(chart.title),
                dates = dates,
                series =
                    listOf(
                        ChartSeries(stringResource(chart.series), chart.values, sportColors(ActivityType.RUNNING).content, chart.format),
                    ),
                axis = axis,
                axisFormat = chart.format,
                invert = chart.invert,
            )
        }
    }
    item(key = "note") { Text(stringResource(R.string.progress_work_laps_note), style = MaterialTheme.typography.bodySmall) }
    item(key = "sessions") { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(runs.reversed(), key = { "run-${it.activityId}" }) { run -> RunRow(run, onClick = { onOpenActivity(run.activityId) }) }
}

private class RunChart(
    @StringRes val title: Int,
    @StringRes val series: Int,
    val values: List<Double?>,
    val format: (Double) -> String,
    val invert: Boolean = false,
    val minStep: Double = 0.0,
    val steps: List<Double>? = null,
)

/** Podziałka tempa w sekundach: pełne 5, 10, 15, 30 s albo minuty. */
private val PACE_STEPS = listOf(5.0, 10.0, 15.0, 30.0, 60.0, 120.0)

private fun LazyListScope.climbingCharts(
    sessions: List<ClimbSession>,
    onOpenActivity: (Long) -> Unit,
) {
    val dates = sessions.map { it.date }
    // Oś „najtrudniejszy top” w kolejności trudności Garmina, podpisy stopniami (V2, V3…).
    val gradeNames = sessions.flatMap { s -> s.grades.map { it.sort to it.grade } }.toMap()
    val gradeFormat = { v: Double -> gradeNames[v.roundToInt()] ?: "" }
    val countFormat = { v: Double -> v.roundToInt().toString() }
    val tops = sessions.map { it.topSort?.toDouble() }
    val attempts = sessions.map { (it.sent + it.failed).toDouble() }
    ProgressChartMath.axis(tops.filterNotNull(), minStep = 1.0)?.let { axis ->
        item(key = "chart-top") {
            SessionChartCard(
                title = stringResource(R.string.progress_chart_top_grade),
                dates = dates,
                series =
                    listOf(
                        ChartSeries(
                            stringResource(R.string.progress_series_top),
                            tops,
                            sportColors(ActivityType.CLIMBING).content,
                            gradeFormat,
                        ),
                    ),
                axis = axis,
                axisFormat = gradeFormat,
            )
        }
    }
    ProgressChartMath.axis(attempts, fromZero = true, minStep = 1.0)?.let { axis ->
        item(key = "chart-attempts") {
            SessionChartCard(
                title = stringResource(R.string.progress_chart_attempts),
                dates = dates,
                series =
                    listOf(
                        ChartSeries(
                            stringResource(R.string.progress_series_attempts),
                            attempts,
                            sportColors(ActivityType.CLIMBING).content,
                            countFormat,
                        ),
                    ),
                axis = axis,
                axisFormat = countFormat,
            )
        }
    }
    item(key = "sessions") { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(sessions.reversed(), key = { "climb-${it.activityId}" }) { session ->
        ClimbRow(session, onClick = { onOpenActivity(session.activityId) })
    }
}

@Composable
private fun ClimbRow(
    session: ClimbSession,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                listOfNotNull(SHORT_DATE.format(session.date), session.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.progress_climb_row, session.topGrade ?: "–", session.sent + session.failed, session.sent),
                style = MaterialTheme.typography.bodyMedium,
            )
            val grades = session.grades.map { stringResource(R.string.progress_climb_grade, it.grade, it.sent, it.sent + it.failed) }
            Text(grades.joinToString("  "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunRow(
    run: RunPoint,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                listOfNotNull(SHORT_DATE.format(run.date), run.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.progress_run_row,
                    run.distanceM?.let { Formatters.km(it) } ?: "–",
                    Formatters.pace(run.paceMinPerKm) ?: "–",
                    run.avgHr?.toString() ?: "–",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (run.workLaps > 0) {
                    stringResource(R.string.progress_work_laps, run.workLaps)
                } else {
                    stringResource(R.string.progress_whole_run)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun LazyListScope.exerciseList(
    exercises: List<ExerciseProgress>,
    anyOnServer: Boolean,
    onOpenExercise: (String) -> Unit,
) {
    if (exercises.isEmpty()) {
        item {
            Text(stringResource(if (anyOnServer) R.string.progress_all_exercises_removed else R.string.progress_no_exercises))
        }
        return
    }
    item { Text(stringResource(R.string.progress_exercises), style = MaterialTheme.typography.titleMedium) }
    items(exercises, key = { it.exercise }) { exercise ->
        Card(Modifier.fillMaxWidth().clickable { onOpenExercise(exercise.exercise) }) {
            Row(Modifier.padding(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(exerciseName(exercise), style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.progress_exercise_sessions, exercise.sessions, exercise.sessions),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.exerciseEditor(
    exercises: List<ExerciseProgress>,
    removed: List<ExerciseProgress>,
    actions: ExerciseEditActions,
) {
    item { Text(stringResource(R.string.progress_edit_hint), style = MaterialTheme.typography.bodySmall) }
    itemsIndexed(exercises, key = { _, e -> "edit-${e.exercise}" }) { index, exercise ->
        val name = exerciseName(exercise)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. $name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                EditIcon(R.drawable.ic_arrow_up, stringResource(R.string.progress_move_up, name), enabled = index > 0) {
                    actions.onMove(exercise.exercise, -1)
                }
                val isLast = index == exercises.lastIndex
                EditIcon(R.drawable.ic_arrow_down, stringResource(R.string.progress_move_down, name), enabled = !isLast) {
                    actions.onMove(exercise.exercise, 1)
                }
                EditIcon(R.drawable.ic_close, stringResource(R.string.progress_remove_exercise, name)) {
                    actions.onRemove(exercise.exercise)
                }
            }
        }
    }
    if (removed.isNotEmpty()) {
        item(key = "removed") { Text(stringResource(R.string.progress_removed_exercises), style = MaterialTheme.typography.titleMedium) }
        items(removed, key = { "removed-${it.exercise}" }) { exercise ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(exerciseName(exercise), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onRestore(exercise.exercise) }) {
                        Text(stringResource(R.string.progress_restore_exercise))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditIcon(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

private fun LazyListScope.exerciseCharts(
    exercise: ExerciseProgress,
    onOpenActivity: (Long) -> Unit,
) {
    // Jeden punkt na trening: najcięższa seria (ciężar i jej powtórzenia), jak w dzienniku na siłowni.
    val tops = ProgressChartMath.topSets(exercise.sets)
    val weights = tops.map { it.weightKg }
    val reps = tops.map { it.reps?.toDouble() }
    ProgressChartMath.axis((weights + reps).filterNotNull(), fromZero = true, minStep = 1.0)?.let { axis ->
        item(key = "top-set") {
            val colors = sportColors(ActivityType.STRENGTH)
            SessionChartCard(
                title = stringResource(R.string.progress_chart_top_set),
                dates = tops.map { it.date },
                series =
                    listOf(
                        ChartSeries(stringResource(R.string.progress_series_kg), weights, colors.content) { Formatters.weight(it) },
                        ChartSeries(stringResource(R.string.progress_series_reps), reps, MaterialTheme.colorScheme.outline) {
                            it.roundToInt().toString()
                        },
                    ),
                axis = axis,
                axisFormat = { Formatters.weight(it) },
            )
        }
    }
    val sessions = exercise.sets.groupBy { it.activityId }.values.toList()
    item { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(sessions.reversed(), key = { "session-${it.first().activityId}" }) { sets ->
        Card(Modifier.fillMaxWidth().clickable { onOpenActivity(sets.first().activityId) }) {
            Column(Modifier.padding(12.dp)) {
                Text(SHORT_DATE.format(sets.first().date), style = MaterialTheme.typography.titleSmall)
                Text(
                    sets.joinToString("  ·  ") { set ->
                        when {
                            set.reps != null && set.weightKg != null -> "${set.reps} × ${Formatters.weight(set.weightKg)} kg"
                            set.reps != null -> "${set.reps}"
                            set.weightKg != null -> "${Formatters.weight(set.weightKg)} kg"
                            else -> "–"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun exerciseName(exercise: ExerciseProgress): String =
    ExerciseCatalog.polishNameForKeys(exercise.category, exercise.exercise) ?: Formatters.exerciseName(exercise.exercise)
