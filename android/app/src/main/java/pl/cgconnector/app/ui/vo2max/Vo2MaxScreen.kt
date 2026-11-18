package pl.cgconnector.app.ui.vo2max

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.ui.format.Formatters
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Vo2MaxRoute(viewModel: Vo2MaxViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Vo2MaxScreen(state = state, onSportSelected = viewModel::onSportSelected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Vo2MaxScreen(
    state: Vo2MaxUiState,
    onSportSelected: (String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.vo2_title)) }) }) { padding ->
        val latest = state.latest
        if (state.isLoading) return@Scaffold
        if (latest == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vo2_empty))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.sports.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.sports.forEach { sport ->
                        FilterChip(
                            selected = sport == state.selectedSport,
                            onClick = { onSportSelected(sport) },
                            label = { Text(sportLabel(sport)) },
                        )
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.vo2_current), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.vo2_value, latest.value), style = MaterialTheme.typography.displaySmall)
                    Text(
                        stringResource(R.string.vo2_measured_at, DATE.format(latest.date)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.change?.let { change ->
                        val signed = String.format(Formatters.POLISH, "%+.1f", change)
                        Text(
                            stringResource(R.string.vo2_change, Vo2MaxViewModel.CHANGE_WINDOW_DAYS, signed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            LineChartMath.normalize(state.points)?.let { data ->
                val first = state.points.first().date
                val description =
                    stringResource(
                        R.string.vo2_chart_description,
                        DATE.format(first),
                        DATE.format(latest.date),
                        data.minValue,
                        data.maxValue,
                    )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.vo2_value, data.maxValue), style = MaterialTheme.typography.labelSmall)
                        LineChart(
                            data = data,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .semantics { contentDescription = description },
                        )
                        Text(stringResource(R.string.vo2_value, data.minValue), style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(DATE.format(first), style = MaterialTheme.typography.labelSmall)
                            Text(DATE.format(latest.date), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** Wykres liniowy rysowany w Canvas (bez zewnętrznej biblioteki). */
@Composable
private fun LineChart(
    data: ChartData,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = size.height * (1 - fraction)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val offsets = data.points.map { Offset(it.x * size.width, (1 - it.y) * size.height) }
        if (offsets.size > 1) {
            val path =
                Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    offsets.drop(1).forEach { lineTo(it.x, it.y) }
                }
            drawPath(path, lineColor, style = Stroke(width = stroke))
        }
        drawCircle(lineColor, radius = stroke * 2, center = offsets.last())
    }
}

@Composable
private fun sportLabel(sport: String): String =
    when (sport) {
        "running" -> stringResource(R.string.vo2_sport_running)
        "cycling" -> stringResource(R.string.vo2_sport_cycling)
        else -> sport
    }

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("pl-PL"))
