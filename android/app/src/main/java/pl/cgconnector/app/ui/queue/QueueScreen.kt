package pl.cgconnector.app.ui.queue

import androidx.annotation.StringRes
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
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutSport
import pl.cgconnector.app.ui.components.ActivityTypeIcon
import pl.cgconnector.app.ui.components.RefreshIconButton
import pl.cgconnector.app.ui.components.activityType
import pl.cgconnector.app.ui.components.StatusBadge
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.theme.StatusTone
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Formatters.POLISH)

@Composable
fun QueueRoute(
    onCopy: (QueuedWorkout) -> Unit = {},
    onOpen: (QueuedWorkout) -> Unit = {},
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
        onRetry = viewModel::retry,
        onCopy = onCopy,
        onOpen = onOpen,
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
    onRetry: (QueuedWorkout) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    onCopy: (QueuedWorkout) -> Unit = {},
    onOpen: (QueuedWorkout) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                actions = { RefreshIconButton(onClick = onRefresh) },
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
                items(state.items, key = { it.id }) { item ->
                    QueueRow(
                        item,
                        onDelete = { onDelete(item) },
                        onRetry = { onRetry(item) },
                        onCopy = { onCopy(item) },
                        onOpen = { onOpen(item) },
                    )
                }
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
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActivityTypeIcon(item.sport?.activityType() ?: ActivityType.OTHER)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOfNotNull(sportLabel(item.sport), DATE_FORMAT.format(item.date)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusBadge(stringResource(item.statusLabel()), item.statusTone()) }
                if (item.canRetry) {
                    val description = stringResource(R.string.queue_retry_description, item.name)
                    TextButton(onClick = onRetry, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.queue_retry))
                    }
                }
                if (item.canCopy && item.sport != null) {
                    val description = stringResource(R.string.queue_copy_description, item.name)
                    TextButton(onClick = onCopy, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.queue_copy))
                    }
                }
                if (item.canDelete) {
                    val description = stringResource(R.string.queue_delete_description, item.name)
                    TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.queue_delete))
                    }
                }
            }
            if (item.status == QueueStatus.FAILED && item.lastError != null) {
                Text(stringResource(R.string.queue_error_intro), style = MaterialTheme.typography.bodySmall)
                Text(
                    item.lastError,
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

@Composable
internal fun sportLabel(sport: WorkoutSport?): String? =
    when (sport) {
        WorkoutSport.RUNNING -> stringResource(R.string.workout_sport_running)
        WorkoutSport.STRENGTH -> stringResource(R.string.workout_sport_strength)
        WorkoutSport.OTHER -> stringResource(R.string.workout_sport_other)
        WorkoutSport.BOULDERING -> stringResource(R.string.workout_sport_bouldering)
        null -> null
    }

internal fun QueuedWorkout.statusTone(): StatusTone =
    when {
        deleteRequested -> StatusTone.NEUTRAL
        status == QueueStatus.SENT -> StatusTone.SUCCESS
        status == QueueStatus.FAILED -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }

@StringRes
internal fun QueuedWorkout.statusLabel(): Int =
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
