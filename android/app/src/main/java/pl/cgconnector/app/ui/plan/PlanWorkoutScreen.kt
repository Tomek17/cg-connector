package pl.cgconnector.app.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.domain.workout.FreeformWorkout
import pl.cgconnector.app.domain.workout.Pace
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunItem
import pl.cgconnector.app.domain.workout.RunProfile
import pl.cgconnector.app.domain.workout.RunSegment
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.StepEnd
import pl.cgconnector.app.domain.workout.StepTarget
import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout
import pl.cgconnector.app.ui.components.BackIconButton
import pl.cgconnector.app.ui.components.StatusBadge
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.queue.sportLabel
import pl.cgconnector.app.ui.queue.statusLabel
import pl.cgconnector.app.ui.queue.statusTone
import pl.cgconnector.app.ui.workout.RunningForm
import pl.cgconnector.app.ui.workout.labelRes
import java.time.format.DateTimeFormatter

@Composable
fun PlanWorkoutRoute(
    onBack: () -> Unit,
    onEdit: (QueuedWorkout) -> Unit,
    viewModel: PlanWorkoutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlanWorkoutScreen(state = state, onBack = onBack, onEdit = onEdit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanWorkoutScreen(
    state: PlanWorkoutUiState,
    onBack: () -> Unit,
    onEdit: (QueuedWorkout) -> Unit,
) {
    val item = state.item
    val definition = state.definition
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: stringResource(R.string.plan_detail_title)) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    if (item != null && (definition is StrengthWorkout || definition is RunningWorkout) && state.canEdit) {
                        TextButton(onClick = { onEdit(item) }) { Text(stringResource(R.string.plan_detail_edit)) }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) return@Scaffold
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.plan_detail_missing))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(item) }
            when (definition) {
                is RunningWorkout -> running(definition)
                is StrengthWorkout -> strength(definition)
                is FreeformWorkout -> item { FreeformCard(definition) }
                null -> item { Text(stringResource(R.string.plan_detail_no_definition)) }
            }
        }
    }
}

@Composable
private fun Header(item: QueuedWorkout) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            listOfNotNull(sportLabel(item.sport), DATE.format(item.date)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusBadge(stringResource(item.statusLabel()), item.statusTone())
    }
}

private fun LazyListScope.running(workout: RunningWorkout) {
    val segments = RunProfile.segments(workout)
    item(key = "chart") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val total = RunProfile.totalSeconds(segments)
                val km = RunProfile.totalKm(segments)
                val estimated = segments.any { it.estimated }
                Text(
                    listOfNotNull(
                        stringResource(
                            if (estimated) R.string.plan_detail_total_time_about else R.string.plan_detail_total_time,
                            Formatters.duration(total.toLong()),
                        ),
                        km?.let { stringResource(R.string.plan_detail_total_km, Formatters.km(it * 1000)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                val paced = segments.filter { it.fastSecPerKm != null }
                val fastest = paced.minOfOrNull { it.fastSecPerKm!! }
                val slowest = paced.maxOfOrNull { it.slowSecPerKm!! }
                if (fastest != null) {
                    Text(stringResource(R.string.plan_detail_pace_axis, Pace.format(fastest)), style = MaterialTheme.typography.labelSmall)
                }
                val description = stringResource(R.string.plan_detail_chart_description, segments.size)
                RunBlocksChart(
                    segments = segments,
                    modifier = Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description },
                )
                if (slowest != null && slowest != fastest) {
                    Text(stringResource(R.string.plan_detail_pace_axis, Pace.format(slowest)), style = MaterialTheme.typography.labelSmall)
                }
                Legend()
            }
        }
    }
    itemsIndexed(workout.items, key = { index, _ -> "run-$index" }) { _, runItem -> RunItemCard(runItem) }
}

@Composable
private fun RunBlocksChart(
    segments: List<RunSegment>,
    modifier: Modifier = Modifier,
) {
    val bars = RunChartMath.bars(segments)
    val colors = segments.map { kindColor(it.kind) }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val gap = 1.dp.toPx()
        bars.forEachIndexed { index, bar ->
            val left = bar.x0 * size.width
            val width = (bar.x1 - bar.x0) * size.width - gap
            val top = (1 - bar.height) * size.height
            drawRoundRect(
                color = if (bar.hasPace) colors[index] else colors[index].copy(alpha = 0.45f),
                topLeft = Offset(left, top),
                size = Size(width.coerceAtLeast(1f), size.height - top),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun kindColor(kind: RunStepKind): Color =
    when (kind) {
        RunStepKind.WARMUP, RunStepKind.COOLDOWN -> MaterialTheme.colorScheme.tertiary
        RunStepKind.INTERVAL -> MaterialTheme.colorScheme.primary
        RunStepKind.RECOVERY, RunStepKind.REST -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(RunStepKind.WARMUP, RunStepKind.INTERVAL, RunStepKind.RECOVERY).forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = kindColor(kind), shape = RoundedCornerShape(2.dp), modifier = Modifier.size(10.dp)) {}
                Text(stringResource(kind.legendRes()), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun RunStepKind.legendRes(): Int =
    when (this) {
        RunStepKind.WARMUP, RunStepKind.COOLDOWN -> R.string.plan_detail_legend_easy
        RunStepKind.INTERVAL -> R.string.step_interval
        RunStepKind.RECOVERY, RunStepKind.REST -> R.string.plan_detail_legend_recovery
    }

@Composable
private fun RunItemCard(item: RunItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (item) {
                is RunStep -> StepLine(item)
                is RepeatBlock -> {
                    Text(stringResource(R.string.plan_detail_repeat, item.times), style = MaterialTheme.typography.titleSmall)
                    item.steps.forEach { StepLine(it, Modifier.padding(start = 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StepLine(
    step: RunStep,
    modifier: Modifier = Modifier,
) {
    val end =
        when (val e = step.end) {
            is StepEnd.Time -> RunningForm.formatDuration(e.seconds)
            is StepEnd.Distance -> stringResource(R.string.unit_km, RunningForm.formatKm(e.km))
            StepEnd.LapButton -> stringResource(R.string.end_lap)
        }
    val target =
        when (val t = step.target) {
            is StepTarget.Pace -> {
                val fast = minOf(t.fromSecPerKm, t.toSecPerKm)
                val slow = maxOf(t.fromSecPerKm, t.toSecPerKm)
                stringResource(R.string.plan_detail_target_pace, Pace.format(fast), Pace.format(slow))
            }
            is StepTarget.HrZone -> stringResource(R.string.plan_detail_target_zone, t.zone)
            is StepTarget.HrRange -> stringResource(R.string.plan_detail_target_hr, t.fromBpm, t.toBpm)
            null -> null
        }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = kindColor(step.kind), shape = RoundedCornerShape(2.dp), modifier = Modifier.size(10.dp)) {}
        Text(stringResource(step.kind.labelRes()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(listOfNotNull(end, target).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LazyListScope.strength(workout: StrengthWorkout) {
    itemsIndexed(workout.exercises, key = { index, _ -> "ex-$index" }) { index, exercise -> ExerciseCard(index + 1, exercise) }
}

@Composable
private fun ExerciseCard(
    number: Int,
    exercise: StrengthExercise,
) {
    val volume =
        when {
            exercise.reps != null -> pluralStringResource(R.plurals.plan_detail_sets_reps, exercise.sets, exercise.sets, exercise.reps)
            else -> pluralStringResource(R.plurals.plan_detail_sets_seconds, exercise.sets, exercise.sets, exercise.seconds ?: 0)
        }
    val details =
        listOfNotNull(
            volume,
            exercise.weightKg?.let { stringResource(R.string.plan_detail_weight, Formatters.weight(it)) },
            stringResource(R.string.plan_detail_rest, exercise.restSeconds),
        ).joinToString(" · ")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$number. ${ExerciseCatalog.polishName(exercise.name) ?: Formatters.exerciseName(exercise.name)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(details, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FreeformCard(workout: FreeformWorkout) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (workout.description.isNotBlank()) Text(workout.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                workout.minutes?.let { stringResource(R.string.plan_detail_minutes, it) } ?: stringResource(R.string.end_lap),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Formatters.POLISH)
