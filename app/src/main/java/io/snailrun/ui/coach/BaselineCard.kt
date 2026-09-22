package io.snailrun.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.snailrun.domain.coach.CoachBaseline
import io.snailrun.domain.coach.Races
import io.snailrun.ui.components.HelpButton
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.format.RunFormat
import io.snailrun.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val BaselineHelp = listOf(
    "The coach plans from the runs in the app, and a fresh install has none — so the " +
        "first month it would hand a 50 km-a-week runner three easy jogs.",
    "What you answer here stands in for the four weeks before today, on the days you " +
        "have not recorded a run. Every rule then applies to it unchanged: the ramp, " +
        "the long-run cap, the hard-day spacing.",
    "It is a statement about the past, so it ages out by itself. A month from now the " +
        "app knows more about you than this does, and none of it is left.",
)

private fun dayFormat() = DateTimeFormatter.ofPattern("d MMM yyyy", UiLocale)

/**
 * The four questions the coach cannot answer for itself.
 *
 * At the top of the tab while it is unanswered and the app has no history worth the
 * name, and out of the way as a single line afterwards. Asking once and then living in a
 * footnote is deliberate: a card that keeps offering to re-describe your past is a card
 * that invites you to keep flattering it.
 */
@Composable
fun BaselineCard(
    baseline: CoachBaseline?,
    editing: Boolean,
    today: LocalDate,
    onEdit: (Boolean) -> Unit,
    onSave: (CoachBaseline?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editing) {
        BaselineForm(
            baseline = baseline,
            today = today,
            onCancel = { onEdit(false) },
            onSave = onSave,
            modifier = modifier,
        )
        return
    }

    SnailCard(modifier = modifier.fillMaxWidth()) {
        if (baseline == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Where are you starting?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                HelpButton(title = "Starting point", body = BaselineHelp)
            }
            Text(
                text = "The coach can only see the runs in this app. Tell it what the " +
                    "last month looked like and the first week will fit you rather than " +
                    "a beginner.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton(onClick = { onEdit(true) }) { Text("Answer four questions") }
                TextButton(onClick = { onSave(null) }) { Text("Start from nothing") }
            }
            return@SnailCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Starting point",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            HelpButton(title = "Starting point", body = BaselineHelp)
        }
        Text(
            text = summarise(baseline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = fading(baseline, today),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onEdit(true) }) { Text("Change") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaselineForm(
    baseline: CoachBaseline?,
    today: LocalDate,
    onCancel: () -> Unit,
    onSave: (CoachBaseline?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var runsPerWeek by remember { mutableStateOf(baseline?.runsPerWeek ?: 3) }
    var weeklyKm by remember { mutableStateOf(baseline?.weeklyMeters.toKmField()) }
    var longestKm by remember { mutableStateOf(baseline?.longestRunMeters.toKmField()) }
    var raceDistance by remember { mutableStateOf(baseline?.raceDistanceMeters) }
    var raceMinutes by remember {
        mutableStateOf(baseline?.raceDurationMs?.let { (it / 60_000).toString() }.orEmpty())
    }
    var raceSeconds by remember {
        mutableStateOf(baseline?.raceDurationMs?.let { (it / 1000 % 60).toString() }.orEmpty())
    }
    var raceDay by remember {
        mutableStateOf(baseline?.raceDateEpochDay ?: today.minusWeeks(2).toEpochDay())
    }
    var pickingDate by remember { mutableStateOf(false) }

    SnailCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Where are you starting?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            HelpButton(title = "Starting point", body = BaselineHelp)
        }

        Spacer(Modifier.height(Spacing.l))
        Question("How many days a week have you been running?")
        ChipRow {
            (0..7).forEach { days ->
                FilterChip(
                    selected = runsPerWeek == days,
                    onClick = { runsPerWeek = days },
                    label = { Text(days.toString()) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Question("How far in a typical week?")
        KmField(value = weeklyKm, onValueChange = { weeklyKm = it }, label = "km a week")

        Spacer(Modifier.height(Spacing.l))
        Question("And your longest run in the last month?")
        KmField(value = longestKm, onValueChange = { longestKm = it }, label = "km")

        Spacer(Modifier.height(Spacing.l))
        Question("Have you raced or time-trialled recently?")
        Text(
            // Said plainly, because it is the answer that moves every pace on the
            // screen: the app would rather have nothing than have a guess.
            text = "This sets your training paces. Leave it out unless it was a real " +
                "effort you held to the end.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
        ChipRow {
            FilterChip(
                selected = raceDistance == null,
                onClick = { raceDistance = null },
                label = { Text("No") },
            )
            Races.Distances.forEach { meters ->
                FilterChip(
                    selected = raceDistance == meters,
                    onClick = { raceDistance = meters },
                    label = { Text(raceLabel(meters)) },
                )
            }
        }

        if (raceDistance != null) {
            Spacer(Modifier.height(Spacing.m))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = raceMinutes,
                    onValueChange = { raceMinutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
                OutlinedTextField(
                    value = raceSeconds,
                    onValueChange = { raceSeconds = it.filter(Char::isDigit).take(2) },
                    label = { Text("sec") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dayFormat().format(LocalDate.ofEpochDay(raceDay)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { pickingDate = true }) { Text("Change the date") }
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextButton(
                onClick = {
                    val durationMs = durationMs(raceMinutes, raceSeconds)
                    onSave(
                        CoachBaseline(
                            runsPerWeek = runsPerWeek,
                            weeklyMeters = weeklyKm.toKm() * 1000,
                            longestRunMeters = longestKm.toKm() * 1000,
                            raceDistanceMeters = raceDistance.takeIf { durationMs != null },
                            raceDurationMs = durationMs.takeIf { raceDistance != null },
                            raceDateEpochDay = raceDay.takeIf {
                                raceDistance != null && durationMs != null
                            },
                            recordedOnEpochDay = today.toEpochDay(),
                        )
                    )
                },
            ) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = raceDay * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Read back in UTC, like the race date in Settings: the picker
                        // hands out UTC midnight whatever the phone's zone, and reading
                        // it locally moves the day for anyone far enough east or west.
                        state.selectedDateMillis?.let { millis ->
                            raceDay = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        pickingDate = false
                    },
                ) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun KmField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { entry -> onValueChange(entry.filter { it.isDigit() || it == '.' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(160.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
}

// ---- text ----------------------------------------------------------------------------

private fun summarise(baseline: CoachBaseline): String = buildString {
    append(
        when (baseline.runsPerWeek) {
            0 -> "Not running"
            1 -> "One run a week"
            else -> "${baseline.runsPerWeek} runs a week"
        }
    )
    if (baseline.weeklyMeters > 0) {
        append(", ${RunFormat.distanceKm(baseline.weeklyMeters)} km")
        append(", longest ${RunFormat.distanceKm(baseline.longestRunMeters)} km")
    }
    append(".")
    baseline.race?.let { race ->
        append(" ${raceLabel(race.distanceMeters)} in ${RunFormat.duration(race.durationMs)}")
        append(" on ${dayFormat().format(race.date)}.")
    }
}

/** How much of the answer is still doing any work, so the card cannot outstay it. */
private fun fading(baseline: CoachBaseline, today: LocalDate): String {
    val daysLeft = 28 - (today.toEpochDay() - baseline.recordedOnEpochDay)
    return when {
        daysLeft <= 0 -> "Your own runs have taken over from this entirely."
        else -> "Standing in for the four weeks before " +
            "${dayFormat().format(baseline.recordedOn)}, for another $daysLeft days."
    }
}

private fun raceLabel(meters: Int) = when (meters) {
    21_097 -> "Half marathon"
    42_195 -> "Marathon"
    else -> "${meters / 1000} km"
}

private fun durationMs(minutes: String, seconds: String): Long? {
    val m = minutes.toLongOrNull() ?: return null
    val s = seconds.toLongOrNull() ?: 0
    val total = m * 60_000 + s * 1_000
    return total.takeIf { it > 0 }
}

private fun Double?.toKmField(): String =
    if (this == null || this <= 0.0) "" else (this / 1000.0).let {
        if (it >= 10) it.roundToInt().toString() else String.format(Locale.US, "%.1f", it)
    }

private fun String.toKm(): Double = trim().toDoubleOrNull()?.coerceIn(0.0, 400.0) ?: 0.0
