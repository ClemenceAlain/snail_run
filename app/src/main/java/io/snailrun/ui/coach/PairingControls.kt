package io.snailrun.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.snailrun.domain.coach.PairMode
import io.snailrun.domain.coach.Pairing
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.SharedSession
import io.snailrun.ui.format.PartnerFormat
import io.snailrun.ui.theme.Spacing

/**
 * Who a session is run with, at the top of its sheet.
 *
 * Solo is the first chip and the default, because most sessions are: sharing is a choice
 * made one day at a time, and nothing about an unshared session changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PairingControls(
    partners: List<Partner>,
    pairing: Pairing?,
    shared: SharedSession?,
    canTranslate: Boolean,
    onPair: (Pairing?) -> Unit,
    onShare: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when {
            partners.isEmpty() -> Text(
                text = "Running this with someone? Add them under Settings → Coach.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            !canTranslate -> Text(
                text = "Sharing needs paces, and the coach has no fitness estimate to write them from yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    FilterChip(
                        selected = pairing == null,
                        onClick = { onPair(null) },
                        label = { Text("Solo") },
                    )
                    partners.forEach { partner ->
                        FilterChip(
                            selected = pairing?.partnerId == partner.id,
                            onClick = {
                                onPair(Pairing(partner.id, pairing?.mode ?: PairMode.Mirror))
                            },
                            label = { Text("With ${partner.name}") },
                        )
                    }
                }
                if (pairing != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Time it so we regroup", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = pairing.mode == PairMode.Together,
                            onCheckedChange = { on ->
                                onPair(pairing.copy(mode = if (on) PairMode.Together else PairMode.Mirror))
                            },
                        )
                    }
                    shared?.let { PartnerFormat.summary(it) }?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    if (shared != null) {
                        TextButton(onClick = onShare) { Text("Send ${shared.partner.name} the session") }
                    }
                }
            }
        }
    }
}
