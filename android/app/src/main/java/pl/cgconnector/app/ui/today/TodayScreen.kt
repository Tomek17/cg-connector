package pl.cgconnector.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.Freshness
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.domain.TypeTotals
import pl.cgconnector.app.domain.WeekSummary
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.ui.activities.labelRes
import pl.cgconnector.app.ui.components.ActivityTypeIcon
import pl.cgconnector.app.ui.components.RefreshIconButton
import pl.cgconnector.app.ui.components.StatusBadge
import pl.cgconnector.app.ui.components.StatusDot
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.queue.sportLabel
import pl.cgconnector.app.ui.queue.statusLabel
import pl.cgconnector.app.ui.queue.statusTone
import pl.cgconnector.app.ui.status.tone
import pl.cgconnector.app.ui.theme.StatusTone
import pl.cgconnector.app.ui.vo2max.Vo2MaxViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TodayRoute(
    onOpenPlan: () -> Unit,
    onOpenVo2Max: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.tick()
                delay(TodayViewModel.TICK_INTERVAL_MS)
            }
        }
    }
    TodayScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenPlan = onOpenPlan,
        onOpenVo2Max = onOpenVo2Max,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenVo2Max: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.today_title)) },
                actions = { RefreshIconButton(onClick = onRefresh, isRefreshing = state.isRefreshing) },
            )
        },
    ) { padding ->
        if (state.isLoading) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NextWorkoutCard(state.nextWorkout, state.today, onOpenPlan)
            WeekCard(state.week)
            Vo2Card(state, onOpenVo2Max)
            ServerLine(state, onOpenSettings)
        }
    }
}

@Composable
private fun NextWorkoutCard(
    workout: QueuedWorkout?,
    today: LocalDate,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.today_next_workout), style = MaterialTheme.typography.labelLarge)
            if (workout == null) {
                Text(stringResource(R.string.today_no_workout), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(workout.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = listOfNotNull(dayLabel(workout.date, today), sportLabel(workout.sport)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                StatusBadge(stringResource(workout.statusLabel()), workout.statusTone())
            }
        }
    }
}

@Composable
private fun WeekCard(week: WeekSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.today_this_week), style = MaterialTheme.typography.labelLarge)
            if (week.count == 0) {
                Text(stringResource(R.string.today_week_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                week.byType.forEach { TypeRow(it) }
                Text(
                    stringResource(
                        R.string.today_week_total,
                        pluralStringResource(R.plurals.workouts_count, week.count, week.count),
                        Formatters.duration(week.durationSec),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TypeRow(totals: TypeTotals) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActivityTypeIcon(totals.type)
        Column(Modifier.weight(1f)) {
            Text(stringResource(totals.type.labelRes()), style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${totals.count}× · ${Formatters.duration(totals.durationSec)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        totals.distanceM?.takeIf { it > 0 }?.let {
            Text(stringResource(R.string.unit_km, Formatters.km(it)), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Vo2Card(
    state: TodayUiState,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.today_vo2_title), style = MaterialTheme.typography.labelLarge)
            val latest = state.vo2Latest
            if (latest == null) {
                Text(stringResource(R.string.today_vo2_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.vo2_value, latest.value), style = MaterialTheme.typography.displaySmall)
                    state.vo2Change?.let { change ->
                        val signed = String.format(Formatters.POLISH, "%+.1f", change)
                        StatusBadge(
                            text = stringResource(R.string.today_vo2_change, signed, Vo2MaxViewModel.CHANGE_WINDOW_DAYS),
                            tone =
                                when {
                                    change > 0 -> StatusTone.SUCCESS
                                    change < 0 -> StatusTone.ERROR
                                    else -> StatusTone.NEUTRAL
                                },
                        )
                    }
                }
                Text(
                    stringResource(R.string.vo2_measured_at, SHORT_DATE.format(latest.date)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Jedna linijka o serwerze; dotknięcie otwiera Ustawienia ze szczegółami. */
@Composable
private fun ServerLine(
    state: TodayUiState,
    onClick: () -> Unit,
) {
    val stale = state.freshness == Freshness.STALE
    val asOf = state.dataAsOf?.let { Formatters.dateTime(it) }
    val text =
        when {
            state.freshness == Freshness.NEVER_FETCHED -> stringResource(R.string.today_never_fetched)
            stale -> stringResource(R.string.today_stale)
            asOf == null || state.serverState == ServerState.UNKNOWN -> stringResource(R.string.today_server_unknown)
            state.serverState == ServerState.ONLINE -> stringResource(R.string.today_server_online, asOf)
            else -> stringResource(R.string.today_server_offline, asOf)
        }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (stale) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(if (stale) StatusTone.ERROR else state.serverState.tone())
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun dayLabel(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> stringResource(R.string.today_day_today)
        today.plusDays(1) -> stringResource(R.string.today_day_tomorrow)
        else -> DAY_DATE.format(date)
    }

private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Formatters.POLISH)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Formatters.POLISH)
