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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivitiesRoute(
    onOpenSettings: () -> Unit,
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
            }
        }
    }

    ActivitiesScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onTypeSelected = viewModel::onTypeSelected,
        onRefresh = viewModel::refresh,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    state: ActivitiesUiState,
    snackbarHostState: SnackbarHostState,
    onTypeSelected: (ActivityType?) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activities_title)) },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Text(stringResource(R.string.activities_refresh))
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.activities_settings))
                    }
                },
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(state.activities, key = { it.id }) { activity -> ActivityRow(activity) }
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

@Composable
private fun ActivityRow(activity: Activity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    add(formatDuration(activity.durationSec))
                    activity.distanceM?.let { add(stringResource(R.string.activity_distance_km, it / 1000.0)) }
                    activity.avgHr?.let { add(stringResource(R.string.activity_avg_hr, it)) }
                }
            Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun formatDuration(totalSec: Long): String {
    val hours = (totalSec / 3600).toInt()
    val minutes = ((totalSec % 3600) / 60).toInt()
    val seconds = (totalSec % 60).toInt()
    return if (hours > 0) {
        stringResource(R.string.activity_duration_hms, hours, minutes, seconds)
    } else {
        stringResource(R.string.activity_duration_ms, minutes, seconds)
    }
}

@StringRes
private fun ActivityType.labelRes(): Int =
    when (this) {
        ActivityType.RUNNING -> R.string.activity_type_running
        ActivityType.STRENGTH -> R.string.activity_type_strength
        ActivityType.CLIMBING -> R.string.activity_type_climbing
        ActivityType.OTHER -> R.string.activity_type_other
    }

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("EEE, d MMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
        .withZone(ZoneId.systemDefault())
