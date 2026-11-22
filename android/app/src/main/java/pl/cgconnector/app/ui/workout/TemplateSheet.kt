package pl.cgconnector.app.ui.workout

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.cgconnector.app.R
import pl.cgconnector.app.data.repository.UserTemplate
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.StepEnd
import pl.cgconnector.app.domain.workout.StrengthWorkout
import pl.cgconnector.app.domain.workout.WorkoutDefinition

/** Wybór szablonu: gotowe, własne (z usuwaniem) i zapis bieżącego treningu jako szablonu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(
    builtIn: List<WorkoutDefinition>,
    mine: List<UserTemplate>,
    onPick: (WorkoutDefinition) -> Unit,
    onDelete: (String) -> Unit,
    onSaveCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.wizard_templates_title), style = MaterialTheme.typography.titleLarge) }
            item { SectionTitle(R.string.wizard_templates_mine) }
            if (mine.isEmpty()) {
                item {
                    Text(stringResource(R.string.wizard_templates_mine_empty), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(mine, key = { it.id }) { template ->
                TemplateRow(template.definition, onClick = { onPick(template.definition) }) {
                    val description = stringResource(R.string.wizard_template_delete, template.definition.name)
                    IconButton(onClick = { onDelete(template.id) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = description)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.wizard_template_save_current))
                }
            }
            item { SectionTitle(R.string.wizard_templates_builtin) }
            items(builtIn, key = { "builtin-${it.name}" }) { template ->
                TemplateRow(template, onClick = { onPick(template) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(
    @StringRes text: Int,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TemplateRow(
    template: WorkoutDefinition,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(template.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                TemplateSummary.describe(template),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
    HorizontalDivider()
}

/** Krótki opis szablonu bez słów do tłumaczenia: kroki biegu albo polskie nazwy ćwiczeń. */
object TemplateSummary {
    fun describe(definition: WorkoutDefinition): String =
        when (definition) {
            is RunningWorkout ->
                definition.items.joinToString(" → ") { item ->
                    when (item) {
                        is RunStep -> end(item)
                        is RepeatBlock -> "${item.times}× (${item.steps.joinToString(" + ") { end(it) }})"
                    }
                }
            is StrengthWorkout -> definition.exercises.joinToString(", ") { StrengthForm.displayName(it.name) }
        }

    private fun end(step: RunStep): String =
        when (val end = step.end) {
            is StepEnd.Time -> RunningForm.formatDuration(end.seconds)
            is StepEnd.Distance -> RunningForm.formatKm(end.km) + " km"
            StepEnd.LapButton -> "⏱"
        }
}
