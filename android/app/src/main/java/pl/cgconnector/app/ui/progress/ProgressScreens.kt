package pl.cgconnector.app.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.ui.components.ActivityTypeIcon
import pl.cgconnector.app.ui.components.BackIconButton

/** Zakładka Progress: trzy kategorie (Siłownia, Bieganie, Ścianka). */
@Composable
fun ProgressRoute(
    onOpenCategory: (ActivityType) -> Unit,
    viewModel: ProgressTagsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProgressScreen(state = state, onRetry = viewModel::load, onOpenCategory = onOpenCategory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    state: ProgressTagsUiState,
    onRetry: () -> Unit,
    onOpenCategory: (ActivityType) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.progress_title)) }) }) { padding ->
        // Kategorie są stałe, więc pokazujemy je także bez serwera; liczby dochodzą po pobraniu.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ActivityTag.CATEGORIES) { type ->
                val count = if (state.isLoading || state.error != null) null else state.countFor(type)
                NavCard(
                    type = type,
                    title = stringResource(type.categoryRes()),
                    subtitle = count?.let { pluralStringResource(R.plurals.progress_activities_count, it, it) },
                    onClick = { onOpenCategory(type) },
                )
            }
            item {
                val error = state.error
                if (error != null) {
                    LoadState(isLoading = false, error = error, onRetry = onRetry, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(
                        stringResource(R.string.progress_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Tagi jednej kategorii z liczbą aktywności. */
@Composable
fun ProgressCategoryRoute(
    onBack: () -> Unit,
    onOpenTag: (ActivityTag) -> Unit,
    viewModel: ProgressTagsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProgressCategoryScreen(state = state, onRetry = viewModel::load, onBack = onBack, onOpenTag = onOpenTag)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressCategoryScreen(
    state: ProgressTagsUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenTag: (ActivityTag) -> Unit,
) {
    val type = state.category ?: ActivityType.RUNNING
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(type.categoryRes())) },
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { padding ->
        if (!LoadState(state.isLoading, state.error, onRetry, Modifier.padding(padding))) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ActivityTag.forType(type), key = { it.key }) { tag ->
                val count = state.counts[tag]
                NavCard(
                    type = type,
                    title = stringResource(tag.labelRes()),
                    subtitle =
                        count?.let {
                            stringResource(
                                R.string.progress_tag_last,
                                pluralStringResource(R.plurals.progress_activities_count, it.count, it.count),
                                SHORT_DATE.format(it.lastDate),
                            )
                        } ?: stringResource(R.string.progress_tag_none),
                    onClick = { onOpenTag(tag) },
                )
            }
        }
    }
}

@Composable
private fun NavCard(
    type: ActivityType,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActivityTypeIcon(type)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
