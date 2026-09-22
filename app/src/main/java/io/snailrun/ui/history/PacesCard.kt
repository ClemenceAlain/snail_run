package io.snailrun.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.domain.coach.Confidence
import io.snailrun.domain.coach.FitnessEstimate
import io.snailrun.ui.components.Badge
import io.snailrun.ui.components.BadgeTone
import io.snailrun.ui.components.HelpButton
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.format.NBSP
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun dayformat() = DateTimeFormatter.ofPattern("EEE d MMM", UiLocale)

private val PacesHelp = listOf(
    "Paces come from the fastest stretches inside your own runs, put through Daniels " +
        "and Gilbert's equations. Nothing is guessed and nothing is looked up.",
    "Only efforts of 5 km and longer are trusted. A fast kilometre inside an easy run " +
        "is usually a surge, and read as a time trial it would make every pace too fast.",
    "So until there is a recent long effort, the coach writes easy and threshold work " +
        "and refuses to price an interval session at all.",
)

/**
 * What you can currently run at, folded away until asked for.
 *
 * It used to sit open at the bottom of the Coach tab, under a heading giving a VDOT. Two
 * things were wrong with that. A VDOT is a number with no use at the point of reading it
 * — it does not tell you how to run today, and a low one reads as a verdict. And the
 * paces themselves answer a question about the runner rather than about the week, which
 * is the question this whole tab is for: a personal best over 5 km and the threshold pace
 * that best implies are the same fact said twice.
 *
 * So it lives here, beside the records, and it is closed until tapped. The paces are
 * still *used* on the Coach tab, written into every session; they are just no longer
 * recited there.
 */
@Composable
fun PacesCard(
    fitness: FitnessEstimate?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SnailCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Training paces", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = fitness?.let {
                        "From your ${distanceName(it.fromDistanceM)} on " +
                            dayformat().format(it.fromDate)
                    } ?: "Run a hard five kilometres and they appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = if (expanded) "Hide paces" else "Show paces",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // The back chevron, turned: down when closed, up when open. One drawable
                // rather than a second one that has to be kept in step with it.
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else -90f },
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(Spacing.l))
                if (fitness == null) {
                    Text(
                        text = "Until then the coach writes easy running only. It will " +
                            "not price a session from a pace it cannot stand behind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                val paces = fitness.paces
                PaceLine(
                    "Easy",
                    "${RunFormat.pace(paces.easySecPerKm.start)}–" +
                        RunFormat.pace(paces.easySecPerKm.endInclusive),
                )
                PaceLine("Marathon", RunFormat.pace(paces.marathonSecPerKm))
                PaceLine("Threshold", RunFormat.pace(paces.thresholdSecPerKm))
                if (fitness.confidence == Confidence.Solid) {
                    PaceLine("Interval", RunFormat.pace(paces.intervalSecPerKm))
                    PaceLine("Repetition", RunFormat.pace(paces.repetitionSecPerKm))
                } else {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = "Interval and repetition pace need a hard 5 km to price " +
                            "them from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(Spacing.l))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    // Now a footnote rather than the heading it used to be. It is here
                    // for the one reader who wants to compare it with a table.
                    Badge("${fitness.vdot.roundToInt()} VDOT", BadgeTone.Quiet)
                    Spacer(Modifier.weight(1f))
                    HelpButton(title = "Paces", body = PacesHelp)
                }
            }
        }
    }
}

@Composable
private fun PaceLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$value$NBSP/km",
            style = SnailType.metricSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun distanceName(meters: Int) = when (meters) {
    21_097 -> "half marathon"
    42_195 -> "marathon"
    else -> "${meters / 1000} km"
}
