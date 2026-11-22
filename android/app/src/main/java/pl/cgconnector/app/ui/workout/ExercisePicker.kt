package pl.cgconnector.app.ui.workout

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.workout.CatalogExercise
import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.domain.workout.ExerciseGroup

/**
 * Wyszukiwarka ćwiczeń po polsku. [multiSelect]: zaznacza się kilka i dodaje przyciskiem „Dodaj (n)”;
 * bez niego (zmiana ćwiczenia w karcie) dotknięcie od razu wybiera. Gdy nic z listy nie pasuje, można
 * użyć dokładnej angielskiej nazwy z pełnego katalogu Garmina (sprawdza ją serwer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    multiSelect: Boolean,
    onPicked: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<ExerciseGroup?>(null) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    val results = remember(query, group) { ExerciseCatalog.search(query, group) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (multiSelect) R.string.picker_title else R.string.picker_title_single),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (multiSelect) {
                    Button(onClick = { onPicked(selected) }, enabled = selected.isNotEmpty()) {
                        Text(stringResource(R.string.picker_add, selected.size))
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.picker_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(listOf<ExerciseGroup?>(null) + ExerciseGroup.entries, group, { groupLabel(it) }) {
                group = if (it == group) null else it
            }
            if (multiSelect && selected.isNotEmpty()) {
                Text(
                    stringResource(R.string.picker_selected) + ": " + selected.joinToString(", ") { StrengthForm.displayName(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(results, key = { it.garminName }) { exercise ->
                    val isSelected = exercise.garminName in selected
                    ExerciseRow(exercise, multiSelect, isSelected) {
                        if (!multiSelect) {
                            onPicked(listOf(exercise.garminName))
                        } else {
                            selected = if (isSelected) selected - exercise.garminName else selected + exercise.garminName
                        }
                    }
                    HorizontalDivider()
                }
                if (results.isEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.picker_no_results), style = MaterialTheme.typography.bodyMedium)
                            val raw = query.trim()
                            if (raw.isNotEmpty()) {
                                TextButton(onClick = { onPicked(selected + raw) }) {
                                    Text(stringResource(R.string.picker_use_garmin_name, raw))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise: CatalogExercise,
    multiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(exercise.polishName, style = MaterialTheme.typography.bodyLarge)
            Text(exercise.garminName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (multiSelect) {
            // Wiersz obsługuje dotknięcie; pole wyboru tylko pokazuje stan.
            Checkbox(checked = isSelected, onCheckedChange = null)
        }
    }
}

@Composable
private fun groupLabel(group: ExerciseGroup?): String = stringResource(group?.labelRes() ?: R.string.filter_all)

@StringRes
private fun ExerciseGroup.labelRes(): Int =
    when (this) {
        ExerciseGroup.LEGS -> R.string.group_legs
        ExerciseGroup.POSTERIOR -> R.string.group_posterior
        ExerciseGroup.CHEST -> R.string.group_chest
        ExerciseGroup.BACK -> R.string.group_back
        ExerciseGroup.SHOULDERS -> R.string.group_shoulders
        ExerciseGroup.ARMS -> R.string.group_arms
        ExerciseGroup.CORE -> R.string.group_core
        ExerciseGroup.FULL -> R.string.group_full
    }
