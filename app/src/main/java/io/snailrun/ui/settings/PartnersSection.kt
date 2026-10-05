package io.snailrun.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.Partners
import io.snailrun.ui.theme.Spacing

/**
 * People who run some sessions alongside, each one a name and a VMA.
 *
 * Inside the Coach card because that is all a partner is: a second set of paces for the
 * coach to write. They have no runs here and no plan.
 */
@Composable
fun PartnersSection(
    partners: List<Partner>,
    onSave: (Partner) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // The partner being edited; a fresh id for one being added.
    var editing by remember { mutableStateOf<Partner?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Running partners", style = MaterialTheme.typography.titleMedium)
        if (partners.isEmpty()) {
            Text(
                text = "Someone you run some sessions with, without the app. Their paces " +
                    "are written from their VMA and shown beside yours on the days you share.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        partners.forEach { partner ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${partner.name} · VMA ${vmaText(partner.vmaKmh)} km/h",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = partner }) { Text("Edit") }
                TextButton(onClick = { onRemove(partner.id) }) { Text("Remove") }
            }
        }
        TextButton(onClick = {
            editing = Partner(id = (partners.maxOfOrNull { it.id } ?: 0) + 1, name = "", vmaKmh = 0.0)
        }) { Text("Add a partner") }
    }

    editing?.let { partner ->
        PartnerDialog(
            initial = partner,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                onSave(it)
            },
        )
    }
}

@Composable
private fun PartnerDialog(initial: Partner, onDismiss: () -> Unit, onSave: (Partner) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var vma by remember { mutableStateOf(if (initial.vmaKmh > 0.0) vmaText(initial.vmaKmh) else "") }
    val parsed = vma.replace(',', '.').toDoubleOrNull()?.takeIf { it in Partners.VmaRange }
    val valid = name.isNotBlank() && parsed != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "Add a partner" else "Edit ${initial.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(
                    value = vma,
                    onValueChange = { vma = it },
                    label = { Text("VMA, km/h") },
                    singleLine = true,
                    isError = vma.isNotEmpty() && parsed == null,
                    supportingText = {
                        Text(
                            "From a VMA test, such as a half-Cooper or a 6-minute run. " +
                                "Between ${Partners.VmaRange.start.toInt()} and " +
                                "${Partners.VmaRange.endInclusive.toInt()}.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { parsed?.let { onSave(initial.copy(name = name.trim(), vmaKmh = it)) } },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun vmaText(kmh: Double): String =
    if (kmh % 1.0 == 0.0) kmh.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, kmh)
