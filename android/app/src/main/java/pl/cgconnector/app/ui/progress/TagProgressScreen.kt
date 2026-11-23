package pl.cgconnector.app.ui.progress

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.ClimbSession
import pl.cgconnector.app.domain.ExerciseProgress
import pl.cgconnector.app.domain.RunPoint
import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.ui.components.BackIconButton
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.theme.sportColors
import kotlin.math.roundToInt

/** Wykresy tagu albo (gdy w trasie jest ćwiczenie) wykres serii jednego ćwiczenia. */
@Composable
fun TagProgressRoute(
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    viewModel: TagProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TagProgressScreen(state = state, onRetry = viewModel::load, onBack = onBack, onOpenExercise = onOpenExercise)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagProgressScreen(
    state: TagProgressUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val exercise = state.selectedExercise
    val title =
        when {
            exercise != null -> exerciseName(exercise)
            state.tag != null -> stringResource(state.tag.labelRes())
            else -> ""
        }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackIconButton(onClick = onBack) }) },
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
                exercise != null -> exerciseCharts(exercise)
                tag.type == ActivityType.RUNNING ->
                    if (progress.running.isEmpty()) {
                        item { Text(stringResource(R.string.progress_empty_tag)) }
                    } else {
                        runningCharts(progress.running)
                    }
                tag.type == ActivityType.STRENGTH -> exerciseList(progress.strength, onOpenExercise)
                progress.climbing.isEmpty() -> item { Text(stringResource(R.string.progress_climbing_empty)) }
                else -> climbingCharts(progress.climbing)
            }
        }
    }
}

private fun LazyListScope.runningCharts(runs: List<RunPoint>) {
    val dates = ProgressChartMath.dateRange(runs.map { it.date }) ?: return
    val charts =
        listOf(
            Triple(R.string.progress_chart_pace, runs.map { it.paceMinPerKm }, { v: Double -> Formatters.pace(v) ?: "" }),
            Triple(R.string.progress_chart_hr, runs.map { it.avgHr?.toDouble() }, { v: Double -> v.roundToInt().toString() }),
            Triple(R.string.progress_chart_cadence, runs.map { it.avgCadenceSpm?.toDouble() }, { v: Double -> v.roundToInt().toString() }),
            Triple(R.string.progress_chart_distance, runs.map { it.distanceM }, { v: Double -> Formatters.km(v) }),
        )
    charts.forEach { (titleRes, values, format) ->
        val pace = titleRes == R.string.progress_chart_pace
        // Tempo: szybciej (mniej min/km) wyżej. Płaska seria tempa dostaje zakres ±10 s.
        val data = ProgressChartMath.sessions(values, invert = pace, flatPadding = if (pace) 1.0 / 6 else 1.0) ?: return@forEach
        item(key = "chart-$titleRes") {
            ChartCard(
                title = stringResource(titleRes),
                data = data,
                sessions = values.count { it != null },
                dates = dates,
                format = format,
                connect = true,
                color = sportColors(ActivityType.RUNNING).content,
                invert = pace,
            )
        }
    }
    item(key = "note") { Text(stringResource(R.string.progress_work_laps_note), style = MaterialTheme.typography.bodySmall) }
    item(key = "sessions") { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(runs.reversed(), key = { "run-${it.activityId}" }) { run -> RunRow(run) }
}

private fun LazyListScope.climbingCharts(sessions: List<ClimbSession>) {
    val dates = ProgressChartMath.dateRange(sessions.map { it.date }) ?: return
    // Oś „najtrudniejszy top” w kolejności trudności Garmina, podpisy stopniami (V2, V3…).
    val gradeNames = sessions.flatMap { s -> s.grades.map { it.sort to it.grade } }.toMap()
    val gradeFormat = { v: Double -> gradeNames[v.roundToInt()] ?: "" }
    val countFormat = { v: Double -> v.roundToInt().toString() }
    val charts =
        listOf(
            Triple(R.string.progress_chart_top_grade, sessions.map { it.topSort?.toDouble() }, gradeFormat),
            Triple(R.string.progress_chart_sent, sessions.map { it.sent.toDouble() }, countFormat),
            Triple(R.string.progress_chart_failed, sessions.map { it.failed.toDouble() }, countFormat),
        )
    charts.forEach { (titleRes, values, format) ->
        val data = ProgressChartMath.sessions(values) ?: return@forEach
        item(key = "chart-$titleRes") {
            ChartCard(
                title = stringResource(titleRes),
                data = data,
                sessions = values.count { it != null },
                dates = dates,
                format = format,
                connect = true,
                color = sportColors(ActivityType.CLIMBING).content,
            )
        }
    }
    item(key = "sessions") { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(sessions.reversed(), key = { "climb-${it.activityId}" }) { session -> ClimbRow(session) }
}

@Composable
private fun ClimbRow(session: ClimbSession) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                listOfNotNull(SHORT_DATE.format(session.date), session.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.progress_climb_row, session.topGrade ?: "–", session.sent, session.failed),
                style = MaterialTheme.typography.bodyMedium,
            )
            val grades = session.grades.map { stringResource(R.string.progress_climb_grade, it.grade, it.sent, it.sent + it.failed) }
            Text(grades.joinToString("  "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunRow(run: RunPoint) {
    Card(Modifier.fillMaxWidth()) {
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
    onOpenExercise: (String) -> Unit,
) {
    if (exercises.isEmpty()) {
        item { Text(stringResource(R.string.progress_no_exercises)) }
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

private fun LazyListScope.exerciseCharts(exercise: ExerciseProgress) {
    val sessions = ProgressChartMath.groupBySession(exercise.sets) { it.activityId }
    val dates = ProgressChartMath.dateRange(exercise.sets.map { it.date }) ?: return
    // Ciężar nad powtórzeniami; każda seria to osobna kropka.
    ProgressChartMath.sets(sessions.map { s -> s.map { it.weightKg } })?.let { data ->
        item(key = "weight") {
            ChartCard(
                title = stringResource(R.string.progress_chart_weight),
                data = data,
                sessions = sessions.size,
                dates = dates,
                format = { Formatters.weight(it) },
                connect = false,
                color = sportColors(ActivityType.STRENGTH).content,
            )
        }
    }
    ProgressChartMath.sets(sessions.map { s -> s.map { it.reps?.toDouble() } })?.let { data ->
        item(key = "reps") {
            ChartCard(
                title = stringResource(R.string.progress_chart_reps),
                data = data,
                sessions = sessions.size,
                dates = dates,
                format = { it.roundToInt().toString() },
                connect = false,
                color = sportColors(ActivityType.STRENGTH).content,
            )
        }
    }
    item { Text(stringResource(R.string.progress_sessions), style = MaterialTheme.typography.titleMedium) }
    items(sessions.reversed(), key = { "session-${it.first().activityId}" }) { sets ->
        Card(Modifier.fillMaxWidth()) {
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
