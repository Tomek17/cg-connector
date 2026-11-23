package pl.cgconnector.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.data.repository.SyncNowOutcome
import pl.cgconnector.app.data.repository.SyncNowOutcome.Reason
import pl.cgconnector.app.ui.progress.messageRes
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SyncNowButton(viewModel: SyncNowViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SyncNowButton(state, viewModel::syncNow)
}

@Composable
fun SyncNowButton(
    state: SyncNowUiState,
    onSync: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onSync, enabled = state.canSync, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isSyncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(if (state.isSyncing) R.string.sync_now_running else R.string.sync_now))
            }
        }
        state.nextAllowedAt?.takeIf { !state.isSyncing }?.let { next ->
            Text(
                text = stringResource(R.string.sync_now_next_at, TIME.format(next.atZone(ZoneId.systemDefault()))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.outcome?.let { outcome ->
            val (text, isError) = outcome.message()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SyncNowOutcome.message(): Pair<String, Boolean> =
    when (this) {
        is SyncNowOutcome.Finished ->
            stringResource(if (ok) R.string.sync_now_done else R.string.sync_now_partial) to !ok
        SyncNowOutcome.StillRunning -> stringResource(R.string.sync_now_still_running) to false
        is SyncNowOutcome.Failed -> stringResource(error.messageRes()) to true
        is SyncNowOutcome.Refused ->
            when (reason) {
                Reason.TOO_SOON ->
                    retryAt?.let { stringResource(R.string.sync_now_too_soon_at, TIME.format(it)) }
                        ?: stringResource(R.string.sync_now_too_soon)
                Reason.RUNNING -> stringResource(R.string.sync_now_already_running)
                Reason.NEEDS_LOGIN -> stringResource(R.string.sync_now_needs_login)
                Reason.RATE_LIMITED ->
                    retryAt?.let { stringResource(R.string.sync_now_rate_limited_at, DATE_TIME.format(it)) }
                        ?: stringResource(R.string.sync_now_rate_limited)
                Reason.NO_GARMIN -> stringResource(R.string.sync_now_no_garmin)
                Reason.UNKNOWN -> stringResource(R.string.sync_now_refused)
            } to true
    }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d.MM HH:mm")
