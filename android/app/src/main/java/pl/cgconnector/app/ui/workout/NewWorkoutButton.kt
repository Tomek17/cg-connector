package pl.cgconnector.app.ui.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import pl.cgconnector.app.R

/** „Nowy trening” na ekranie kolejki: wybór rodzaju otwiera kreator. */
@Composable
fun NewWorkoutButton(onNewRunning: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(onClick = { expanded = true }) { Text(stringResource(R.string.wizard_new)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wizard_new_running)) },
                onClick = {
                    expanded = false
                    onNewRunning()
                },
            )
        }
    }
}
