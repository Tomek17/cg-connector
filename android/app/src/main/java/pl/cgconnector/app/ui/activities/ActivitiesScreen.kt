package pl.cgconnector.app.ui.activities

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.WeekGrouping
import pl.cgconnector.app.domain.WeekSummary
import pl.cgconnector.app.ui.components.ActivityTypeIcon
import pl.cgconnector.app.ui.components.RefreshIconButton
import pl.cgconnector.app.ui.format.Formatters
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivitiesRoute(
    onOpenSettings: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: ActivitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ActivitiesEvent.Refreshed ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.activities_refreshed))
                ActivitiesEvent.ServerUnavailable ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.activities_refresh_failed))
                ActivitiesEvent.NotConfigured -> onOpenSettings()
                ActivitiesEvent.Unauthorized -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.activities_unauthorized),
                            actionLabel = resources.getString(R.string.activities_settings),
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) onOpenSettings()
                }
                ActivitiesEvent.InvalidResponse ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.activities_invalid_response))
            }
        }
    }

    ActivitiesScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onTypeSelected = viewModel::onTypeSelected,
        onRefresh = viewModel::refresh,
        onOpenActivity = onOpenActivity,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    state: ActivitiesUiState,
    snackbarHostState: SnackbarHostState,
    onTypeSelected: (ActivityType?) -> Unit,
    onRefresh: () -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activities_title)) },
                actions = { RefreshIconButton(onClick = onRefresh, isRefreshing = state.isRefreshing) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TypeFilter(selected = state.selectedType, onSelected = onTypeSelected)
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!state.isLoading && state.activities.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (state.selectedType == null) {
                                    R.string.activities_empty
                                } else {
                                    R.string.activities_empty_filtered
                                },
                            ),
                        )
                    }
                } else {
                    val weeks = remember(state.activities) { WeekGrouping.group(state.activities) }
                    val currentWeek = remember { WeekGrouping.weekStart(LocalDate.now(AppZone.WARSAW)) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        weeks.forEach { week ->
                            item(key = "week-${week.weekStart}") { WeekHeader(week, currentWeek) }
                            items(week.activities, key = { it.id }) { activity ->
                                ActivityRow(activity, onClick = { onOpenActivity(activity.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeFilter(
    selected: ActivityType?,
    onSelected: (ActivityType?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.filter_all)) },
        )
        ActivityType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelected(type) },
                label = { Text(stringResource(type.labelRes())) },
            )
        }
    }
}

/** Nagłówek tygodnia: „Ten tydzień” albo zakres dat, z liczbą treningów, kilometrami biegu i czasem. */
@Composable
private fun WeekHeader(
    week: WeekSummary,
    currentWeek: LocalDate,
) {
    val title =
        when (week.weekStart) {
            currentWeek -> stringResource(R.string.week_current)
            currentWeek.minusWeeks(1) -> stringResource(R.string.week_previous)
            else ->
                stringResource(
                    R.string.week_range,
                    WEEK_DATE.format(week.weekStart),
                    WEEK_DATE.format(week.weekStart.plusDays(6)),
                )
        }
    val totals =
        buildList {
            add(pluralStringResource(R.plurals.workouts_count, week.count, week.count))
            week.runningDistanceM.takeIf { it > 0 }?.let { add(stringResource(R.string.week_running_km, Formatters.km(it))) }
            add(Formatters.duration(week.durationSec))
        }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(totals.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActivityRow(
    activity: Activity,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActivityTypeIcon(activity.type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = activity.name ?: stringResource(R.string.activity_untitled),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${stringResource(activity.type.labelRes())} · ${DATE_FORMAT.format(activity.startTime)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val details =
                    buildList {
                        add(Formatters.duration(activity.durationSec))
                        activity.distanceM?.let { add(stringResource(R.string.activity_distance_km, it / 1000.0)) }
                        if (activity.type == ActivityType.RUNNING) {
                            Formatters.pace(activity.averagePace())?.let { add(stringResource(R.string.unit_pace, it)) }
                        }
                        activity.avgHr?.let { add(stringResource(R.string.activity_avg_hr, it)) }
                    }
                Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Tempo jak na ekranie szczegółów: z serwera, a gdy go brak, z prędkości albo dystansu i czasu. */
internal fun Activity.averagePace(): Double? =
    paceMinPerKm
        ?: Formatters.paceFromSpeed(avgSpeedMps)
        ?: Formatters.paceFromDistance(distanceM, durationSec.toDouble())

@StringRes
internal fun ActivityType.labelRes(): Int =
    when (this) {
        ActivityType.RUNNING -> R.string.activity_type_running
        ActivityType.STRENGTH -> R.string.activity_type_strength
        ActivityType.CLIMBING -> R.string.activity_type_climbing
        ActivityType.OTHER -> R.string.activity_type_other
    }

private val WEEK_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("pl-PL"))

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("EEE, d MMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
        .withZone(ZoneId.systemDefault())
