package io.snailrun.ui.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import io.snailrun.R

data class RunDetailActions(
    val onBack: () -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailTopBar(actions: RunDetailActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Save GPX as…") },
                    onClick = { menuOpen = false; actions.onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Share GPX") },
                    onClick = { menuOpen = false; actions.onShare() },
                )
                DropdownMenuItem(
                    text = { Text("Delete run") },
                    onClick = { menuOpen = false; confirmingDelete = true },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )

    // Deleting a run deletes its track, and nothing reconstructs a track. The menu item
    // sits one tap from "Share", so the dialog is what stands between a mis-tap and a run
    // that no longer exists anywhere.
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this run?") },
            text = {
                Text(
                    "The track, the splits and the records from this run are removed " +
                        "from the phone. This cannot be undone — only a backup taken " +
                        "beforehand brings it back.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingDelete = false; actions.onDelete() },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
    }
}
