package io.snailrun.ui.detail

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
                    onClick = { menuOpen = false; actions.onDelete() },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
