package pl.cgconnector.app.ui.status

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.PeriodSummary
import pl.cgconnector.app.domain.RefreshOutcome
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.ui.activities.labelRes
import pl.cgconnector.app.ui.components.LabelValue
import pl.cgconnector.app.ui.format.Formatters
import java.time.Instant

@Composable
fun StatusRoute(viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatusScreen(state = state, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: StatusUiState,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.status_title)) },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Text(stringResource(R.string.status_check_now))
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) return@Scaffold
        val status = state.status
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isStale) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Text(stringResource(R.string.status_stale_warning), modifier = Modifier.padding(16.dp))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelValue(stringResource(R.string.status_server), stringResource(state.serverState.labelRes()))
                    LabelValue(stringResource(R.string.status_last_check), dateOrNever(status.lastRefreshAttemptAt))
                    status.lastRefreshOutcome?.let {
                        LabelValue(stringResource(R.string.status_last_result), stringResource(it.labelRes()))
                    }
                    LabelValue(stringResource(R.string.status_app_refresh), dateOrNever(status.lastRefreshSuccessAt))
                    LabelValue(stringResource(R.string.status_server_sync), dateOrNever(status.serverLastSyncAt))
                    LabelValue(stringResource(R.string.status_garmin), stringResource(garminLabelRes(status.garminStatus)))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.status_this_week), style = MaterialTheme.typography.titleMedium)
                    if (state.currentWeek.isEmpty()) {
                        Text(stringResource(R.string.status_week_empty))
                    } else {
                        state.currentWeek.forEach { WeekRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekRow(summary: PeriodSummary) {
    val amount =
        summary.distanceM?.takeIf { it > 0 }?.let { stringResource(R.string.unit_km, Formatters.km(it)) }
            ?: Formatters.duration(summary.durationSec)
    Text(stringResource(R.string.status_week_row, stringResource(summary.type.labelRes()), summary.count, amount))
}

@Composable
private fun dateOrNever(instant: Instant?): String =
    instant?.let { Formatters.dateTime(it) } ?: stringResource(R.string.status_never)

@StringRes
private fun ServerState.labelRes(): Int =
    when (this) {
        ServerState.ONLINE -> R.string.status_server_online
        ServerState.OFFLINE -> R.string.status_server_offline
        ServerState.UNKNOWN -> R.string.status_server_unknown
    }

@StringRes
private fun RefreshOutcome.labelRes(): Int =
    when (this) {
        RefreshOutcome.SUCCESS -> R.string.status_result_success
        RefreshOutcome.UNAVAILABLE -> R.string.status_result_unavailable
        RefreshOutcome.UNAUTHORIZED -> R.string.status_result_unauthorized
        RefreshOutcome.INVALID_RESPONSE -> R.string.status_result_invalid
        RefreshOutcome.NOT_CONFIGURED -> R.string.status_result_not_configured
    }

@StringRes
private fun garminLabelRes(status: String?): Int =
    when (status) {
        "ok" -> R.string.status_garmin_ok
        "needs_login" -> R.string.status_garmin_needs_login
        "rate_limited" -> R.string.status_garmin_rate_limited
        else -> R.string.status_garmin_unknown
    }
