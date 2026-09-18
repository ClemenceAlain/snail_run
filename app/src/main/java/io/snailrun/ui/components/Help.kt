package io.snailrun.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.ui.theme.Spacing

/**
 * A "?" that opens what a screen would otherwise have said out loud.
 *
 * The app has opinions and reasons for them, and they are worth reading once. They are
 * not worth reading every time somebody opens Settings to change the speech rate — a
 * paragraph the reader has already understood is noise the second time, and four of them
 * push the control they came for below the fold.
 *
 * A dialog rather than a tooltip: these run to several sentences, and a touch tooltip is
 * a long-press away and truncates.
 */
@Composable
fun HelpButton(title: String, body: List<String>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }, modifier = modifier.size(32.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_help),
            contentDescription = "About $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    body.forEach { paragraph ->
                        Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
