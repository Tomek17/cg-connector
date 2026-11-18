package pl.cgconnector.app.ui.queue

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutSport
import pl.cgconnector.app.ui.format.Formatters
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Formatters.POLISH)

@Composable
fun QueueRoute(
    floatingActionButton: @Composable () -> Unit = {},
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Statusy zmieniają się po stronie serwera (wysyłka do Garmina), więc pobieramy je przy wejściu.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    QueueScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onDelete = viewModel::askDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::dismissDelete,
        floatingActionButton = floatingActionButton,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: QueueUiState,
    onRefresh: () -> Unit,
    onDelete: (QueuedWorkout) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                actions = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.queue_refresh)) } },
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        if (!state.isLoading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.queue_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Text(stringResource(R.string.queue_watch_hint), style = MaterialTheme.typography.bodySmall)
                }
                items(state.items, key = { it.id }) { item -> QueueRow(item, onDelete = { onDelete(item) }) }
            }
        }
    }

    state.confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.queue_delete_title)) },
            text = { Text(stringResource(R.string.queue_delete_text, item.name)) },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.queue_delete_confirm)) } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.queue_delete_cancel)) } },
        )
    }
}

@Composable
private fun QueueRow(
    item: QueuedWorkout,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            val sport =
                when (item.sport) {
                    WorkoutSport.RUNNING -> stringResource(R.string.workout_sport_running)
                    WorkoutSport.STRENGTH -> stringResource(R.string.workout_sport_strength)
                    null -> null
                }
            Text(
                text = listOfNotNull(sport, DATE_FORMAT.format(item.date)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(item.statusLabel()),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (item.status == QueueStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.weight(1f),
                )
                if (item.canDelete) {
                    val description = stringResource(R.string.queue_delete_description, item.name)
                    TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.queue_delete))
                    }
                }
            }
            if (item.status == QueueStatus.FAILED && item.lastError != null) {
                Text(
                    stringResource(R.string.queue_error, item.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!item.createdInApp) {
                Text(stringResource(R.string.queue_created_elsewhere), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun QueuedWorkout.statusLabel(): Int =
    if (deleteRequested) {
        R.string.queue_status_deleting
    } else {
        when (status) {
            QueueStatus.PENDING -> R.string.queue_status_pending
            QueueStatus.SENT -> R.string.queue_status_sent
            QueueStatus.FAILED -> R.string.queue_status_failed
            QueueStatus.DELETING -> R.string.queue_status_deleting
            QueueStatus.DELETED -> R.string.queue_status_deleted
        }
    }
