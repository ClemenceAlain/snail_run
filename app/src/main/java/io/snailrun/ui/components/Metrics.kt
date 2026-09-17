package io.snailrun.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import io.snailrun.ui.theme.SnailType
import io.snailrun.ui.theme.Spacing

/**
 * A number with its caption underneath.
 *
 * The value styles all carry tabular figures, so nothing shifts as the digits change.
 * Deliberately not animated: these tick once a second, and a crossfade would read as
 * jitter, undoing the point of the tabular figures.
 */
@Composable
fun MetricReadout(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = SnailType.metricLarge,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = value,
            style = valueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = caption.uppercase(),
            style = SnailType.metricCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The three-up distance / time / pace row used on the detail screen. */
@Composable
fun MetricRow(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = SnailType.metricLarge,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        metrics.forEach { (value, caption) ->
            MetricReadout(value = value, caption = caption, valueStyle = valueStyle)
        }
    }
}
