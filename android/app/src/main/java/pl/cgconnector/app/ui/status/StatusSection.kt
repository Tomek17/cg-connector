package pl.cgconnector.app.ui.status

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import pl.cgconnector.app.domain.RefreshOutcome
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.ui.components.LabelValue
import pl.cgconnector.app.ui.components.StatusBadge
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.theme.StatusTone
import java.time.Instant

/**
 * Sekcja „Połączenie i synchronizacja” w Ustawieniach. Świeżość danych przeliczana co minutę,
 * tylko gdy ekran jest widoczny (co najmniej RESUMED).
 */
@Composable
fun StatusSectionRoute(viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.tick()
                delay(StatusViewModel.TICK_INTERVAL_MS)
            }
        }
    }
    StatusSection(state = state, onRefresh = viewModel::refresh)
}

@Composable
fun StatusSection(
    state: StatusUiState,
    onRefresh: () -> Unit,
) {
    if (state.isLoading) return
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.status_section_title), style = MaterialTheme.typography.titleMedium)
        when (state.freshness) {
            Freshness.FRESH -> Unit
            Freshness.NEVER_FETCHED ->
                Card(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.status_never_fetched), modifier = Modifier.padding(16.dp))
                }
            Freshness.STALE ->
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.status_server), modifier = Modifier.weight(1f))
                    StatusBadge(stringResource(state.serverState.labelRes()), state.serverState.tone())
                }
                LabelValue(stringResource(R.string.status_last_check), dateOrNever(status.lastRefreshAttemptAt))
                status.lastRefreshOutcome?.let {
                    LabelValue(stringResource(R.string.status_last_result), stringResource(it.labelRes()))
                }
                LabelValue(stringResource(R.string.status_app_refresh), dateOrNever(status.lastRefreshSuccessAt))
                LabelValue(stringResource(R.string.status_server_sync), dateOrNever(status.serverLastSyncAt))
                LabelValue(stringResource(R.string.status_garmin), stringResource(garminLabelRes(status.garminStatus)))
                OutlinedButton(onClick = onRefresh, enabled = !state.isRefreshing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.status_check_now))
                }
            }
        }
    }
}

fun ServerState.tone(): StatusTone =
    when (this) {
        ServerState.ONLINE -> StatusTone.SUCCESS
        ServerState.OFFLINE -> StatusTone.ERROR
        ServerState.UNKNOWN -> StatusTone.NEUTRAL
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
