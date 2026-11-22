package pl.cgconnector.app.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ExerciseSet
import pl.cgconnector.app.domain.HrZone
import pl.cgconnector.app.domain.Lap
import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.ui.activities.labelRes
import pl.cgconnector.app.ui.components.BackIconButton
import pl.cgconnector.app.ui.components.LabelValue
import pl.cgconnector.app.ui.components.RefreshIconButton
import pl.cgconnector.app.ui.format.Formatters
import kotlin.math.roundToInt

@Composable
fun ActivityDetailRoute(
    onBack: () -> Unit,
    viewModel: ActivityDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ActivityDetailScreen(state = state, onBack = onBack, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    state: ActivityDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.activity?.name ?: stringResource(R.string.detail_title)) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    if (state.detail != null) RefreshIconButton(onClick = onRefresh, isRefreshing = state.isFetching)
                },
            )
        },
    ) { padding ->
        val detail = state.detail
        when {
            state.isLoading -> Unit
            detail == null ->
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.detail_not_found))
                }
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Header(detail.activity) }
                    item { SummaryCard(detail.activity) }
                    item { FetchStatus(state, onRefresh) }
                    if (detail.laps.isNotEmpty()) laps(detail.laps)
                    if (detail.hrZones.isNotEmpty()) item { HrZonesCard(detail.hrZones) }
                    if (detail.exerciseSets.isNotEmpty()) sets(detail.exerciseSets)
                }
        }
    }
}

@Composable
private fun Header(activity: Activity) {
    Text(
        text = "${stringResource(activity.type.labelRes())} · ${Formatters.dateTime(activity.startTime)}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SummaryCard(activity: Activity) {
    val rows =
        buildList {
            add(R.string.detail_duration to Formatters.duration(activity.durationSec))
            activity.movingDurationSec?.let { add(R.string.detail_moving_duration to Formatters.duration(it)) }
            activity.distanceM?.let { add(R.string.detail_distance to stringResource(R.string.unit_km, Formatters.km(it))) }
            val pace =
                activity.paceMinPerKm
                    ?: Formatters.paceFromSpeed(activity.avgSpeedMps)
                    ?: Formatters.paceFromDistance(activity.distanceM, activity.durationSec.toDouble())
            Formatters.pace(pace)?.let { add(R.string.detail_avg_pace to stringResource(R.string.unit_pace, it)) }
            activity.avgHr?.let { add(R.string.detail_avg_hr to stringResource(R.string.unit_bpm, it)) }
            activity.maxHr?.let { add(R.string.detail_max_hr to stringResource(R.string.unit_bpm, it)) }
            activity.elevationGainM?.let { add(R.string.detail_elevation to stringResource(R.string.unit_m, it)) }
            activity.avgPowerW?.let { add(R.string.detail_power to stringResource(R.string.unit_w, it)) }
            activity.avgCadenceSpm?.let { add(R.string.detail_cadence to stringResource(R.string.unit_spm, it)) }
            activity.aerobicTrainingEffect?.let { add(R.string.detail_aerobic_te to stringResource(R.string.unit_decimal, it)) }
            activity.anaerobicTrainingEffect?.let {
                add(R.string.detail_anaerobic_te to stringResource(R.string.unit_decimal, it))
            }
            activity.trainingLoad?.let { add(R.string.detail_training_load to stringResource(R.string.unit_integer, it)) }
        }
    SectionCard(R.string.detail_summary) {
        rows.forEach { (label, value) -> LabelValue(stringResource(label), value) }
    }
}

@Composable
private fun FetchStatus(
    state: ActivityDetailUiState,
    onRetry: () -> Unit,
) {
    val detail = state.detail ?: return
    when {
        state.isFetching && !detail.hasDetails ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text(stringResource(R.string.detail_loading))
            }
        state.error != null ->
            Column {
                Text(stringResource(state.error.messageRes()), color = MaterialTheme.colorScheme.error)
                if (!state.isFetching) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
                }
            }
        detail.hasDetails && !detail.detailsAvailable ->
            Text(stringResource(R.string.detail_unavailable_on_server))
    }
    detail.detailsFetchedAt?.let {
        Text(
            text = stringResource(R.string.detail_fetched_at, Formatters.dateTime(it)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun LazyListScope.laps(laps: List<Lap>) {
    item {
        Text(stringResource(R.string.detail_laps), style = MaterialTheme.typography.titleMedium)
    }
    item {
        LapRow(
            no = stringResource(R.string.detail_lap_header_no),
            distance = stringResource(R.string.detail_lap_header_distance),
            time = stringResource(R.string.detail_lap_header_time),
            pace = stringResource(R.string.detail_lap_header_pace),
            hr = stringResource(R.string.detail_lap_header_hr),
            isHeader = true,
        )
        HorizontalDivider()
    }
    items(laps, key = { "lap-${it.index}" }) { lap ->
        LapRow(
            no = lap.index.toString(),
            distance = lap.distanceM?.let { Formatters.km(it) } ?: "–",
            time = lap.durationSec?.let { Formatters.duration(it) } ?: "–",
            pace = Formatters.pace(Formatters.lapPace(lap)) ?: "–",
            hr = lap.avgHr?.toString() ?: "–",
            isHeader = false,
        )
    }
}

@Composable
private fun LapRow(
    no: String,
    distance: String,
    time: String,
    pace: String,
    hr: String,
    isHeader: Boolean,
) {
    val weight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Cell(no, 0.6f, weight)
        Cell(distance, 1f, weight)
        Cell(time, 1f, weight)
        Cell(pace, 1f, weight)
        Cell(hr, 0.8f, weight)
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    fontWeight: FontWeight,
) {
    Text(text = text, modifier = Modifier.weight(weight), fontWeight = fontWeight, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HrZonesCard(zones: List<HrZone>) {
    val total = zones.sumOf { it.seconds }.takeIf { it > 0 } ?: 1.0
    SectionCard(R.string.detail_hr_zones) {
        zones.forEach { zone ->
            val share = (zone.seconds / total).toFloat()
            val label =
                zone.lowBpm?.let { stringResource(R.string.detail_zone_label_from, zone.zone, it) }
                    ?: stringResource(R.string.detail_zone_label, zone.zone)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabelValue(
                    label,
                    stringResource(R.string.detail_zone_value, Formatters.duration(zone.seconds), (share * 100).roundToInt()),
                )
                LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun LazyListScope.sets(sets: List<ExerciseSet>) {
    item {
        Text(stringResource(R.string.detail_sets), style = MaterialTheme.typography.titleMedium)
    }
    items(sets, key = { "set-${it.index}" }) { set -> SetRow(set) }
}

@Composable
private fun SetRow(set: ExerciseSet) {
    if (set.isRest) {
        val duration = set.durationSec?.let { Formatters.duration(it) } ?: "–"
        Text(
            text = stringResource(R.string.detail_rest, duration),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp),
        )
        return
    }
    val name =
        ExerciseCatalog.polishNameForKeys(set.category, set.exercise)
            ?: (set.exercise ?: set.category)?.let { Formatters.exerciseName(it) }
    val details =
        buildList {
            val reps = set.reps
            val weight = set.weightKg
            when {
                reps != null && weight != null -> add(stringResource(R.string.detail_set_reps_weight, reps, Formatters.weight(weight)))
                reps != null -> add(stringResource(R.string.detail_set_reps, reps))
            }
            set.durationSec?.let { add(stringResource(R.string.detail_set_duration, Formatters.duration(it))) }
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name ?: stringResource(R.string.detail_set_unknown_exercise), style = MaterialTheme.typography.titleSmall)
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionCard(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@StringRes
private fun DetailError.messageRes(): Int =
    when (this) {
        DetailError.OFFLINE -> R.string.detail_offline
        DetailError.UNAUTHORIZED -> R.string.detail_unauthorized
        DetailError.NOT_CONFIGURED -> R.string.detail_not_configured
        DetailError.INVALID_RESPONSE -> R.string.detail_invalid_response
    }
