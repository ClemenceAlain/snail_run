package io.snailrun.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.WorkoutType
import io.snailrun.ui.theme.SnailType

/**
 * How loud a badge is, in the one vocabulary the whole app shares.
 *
 * Tones rather than colours, because the point of a badge here is that a week reads at a
 * glance without being read: [Hard] is the same green wherever it appears, so a runner
 * learns "filled green means a day that will hurt" once rather than per screen. Naming
 * them by loudness instead of by hue is also what keeps a new badge from quietly
 * inventing a sixth colour.
 *
 * Every pair below clears 4.5:1 in both schemes, which is the bar that matters: the text
 * is 12sp, so it is small text under WCAG however emphatic it looks.
 */
enum class BadgeTone { Quiet, Easy, Hard, Long, Other }

/**
 * A small pill of uppercase text.
 *
 * Deliberately not Material's `Badge`, which is a notification dot with an optional
 * number in it and carries the error colour by default.
 */
@Composable
fun Badge(
    text: String,
    tone: BadgeTone = BadgeTone.Quiet,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // A full pill rather than the theme's shapes: at this height a 12 dp corner
            // reads as a rounded rectangle, which looks like a very small button.
            .clip(RoundedCornerShape(percent = 50))
            .background(tone.container())
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text.uppercase(),
            // The caption style, which already carries the letter spacing uppercase text
            // needs. Without it "TEMPO" sets tight enough to read as one word.
            style = SnailType.metricCaption,
            color = tone.content(),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BadgeTone.container(): Color = when (this) {
    BadgeTone.Quiet -> MaterialTheme.colorScheme.surfaceVariant
    BadgeTone.Easy -> MaterialTheme.colorScheme.primaryContainer
    BadgeTone.Hard -> MaterialTheme.colorScheme.primary
    BadgeTone.Long -> MaterialTheme.colorScheme.secondaryContainer
    BadgeTone.Other -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun BadgeTone.content(): Color = when (this) {
    BadgeTone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
    BadgeTone.Easy -> MaterialTheme.colorScheme.onPrimaryContainer
    BadgeTone.Hard -> MaterialTheme.colorScheme.onPrimary
    BadgeTone.Long -> MaterialTheme.colorScheme.onSecondaryContainer
    BadgeTone.Other -> MaterialTheme.colorScheme.onTertiary
}

/**
 * What a session's badge looks like.
 *
 * Grouped by what the day costs rather than by what it is called, which is the question
 * somebody scanning a week is actually asking. A progression run and a steady run are
 * different sessions and the same answer to "can I do this after work".
 */
val WorkoutType.badgeTone: BadgeTone
    get() = when (this) {
        WorkoutType.Rest -> BadgeTone.Quiet
        WorkoutType.Recovery, WorkoutType.Easy, WorkoutType.Strides -> BadgeTone.Easy
        WorkoutType.Long, WorkoutType.Progression -> BadgeTone.Long
        WorkoutType.Strength -> BadgeTone.Other
        else -> BadgeTone.Hard
    }

/** Warm-up and cool-down share a tone: both are the parts you are not being tested on. */
val SegmentKind.badgeTone: BadgeTone
    get() = when (this) {
        SegmentKind.Work -> BadgeTone.Hard
        SegmentKind.Recover -> BadgeTone.Easy
        SegmentKind.WarmUp, SegmentKind.CoolDown -> BadgeTone.Quiet
    }

/** "Warm up" rather than "WARMUP": the pill uppercases it, so the source stays readable. */
val SegmentKind.label: String
    get() = when (this) {
        SegmentKind.Work -> "Work"
        SegmentKind.Recover -> "Jog"
        SegmentKind.WarmUp -> "Warm up"
        SegmentKind.CoolDown -> "Cool down"
    }
