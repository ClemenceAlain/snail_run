package io.snailrun.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
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
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the form hands back once every field makes sense. */
data class ManualRun(
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val distanceMeters: Double,
)

sealed interface ManualRunEntry {
    data class Valid(val run: ManualRun) : ManualRunEntry
    data class Invalid(val reason: String) : ManualRunEntry
}

/**
 * Reads the form's text fields into a run, or says which one is wrong.
 *
 * Kept apart from the dialog so the rules can be tested without Compose. The distance
 * takes a comma as well as a point: the phone's keyboard offers whichever its region
 * uses, and "5,2" meaning nothing would be a strange thing to refuse.
 */
object ManualRunForm {

    /** Longer than any run, short enough to catch an hour typed into the minutes. */
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    fun parse(
        date: LocalDate,
        time: String,
        hours: String,
        minutes: String,
        seconds: String,
        km: String,
        zone: ZoneId,
        now: Instant,
    ): ManualRunEntry {
        val meters = km.trim().replace(',', '.').toDoubleOrNull()?.times(1000)
        if (meters == null || meters <= 0.0) return ManualRunEntry.Invalid("Enter a distance")
        if (meters > 400_000) return ManualRunEntry.Invalid("That distance is too long")

        val h = hours.trim().ifEmpty { "0" }.toLongOrNull()
        val m = minutes.trim().ifEmpty { "0" }.toLongOrNull()
        val s = seconds.trim().ifEmpty { "0" }.toLongOrNull()
        if (h == null || m == null || s == null || m >= 60 || s >= 60) {
            return ManualRunEntry.Invalid("Enter the time as hours, minutes and seconds")
        }
        val durationMs = (h * 3600 + m * 60 + s) * 1000
        if (durationMs <= 0) return ManualRunEntry.Invalid("Enter how long it took")
        if (durationMs > MAX_DURATION_MS) return ManualRunEntry.Invalid("That time is too long")

        val startTime = parseTime(time) ?: return ManualRunEntry.Invalid("Enter the start time as HH:MM")
        val start = date.atTime(startTime).atZone(zone).toInstant()
        if (start.isAfter(now)) return ManualRunEntry.Invalid("That start is in the future")

        return ManualRunEntry.Valid(
            ManualRun(
                startedAtEpochMs = start.toEpochMilli(),
                durationMs = durationMs,
                distanceMeters = meters,
            )
        )
    }

    /**
     * "18:30", "18h30", "18" or "1830". A number keyboard often has no colon, so four
     * bare digits have to mean a time too.
     */
    private fun parseTime(text: String): LocalTime? {
        val trimmed = text.trim()
        val parts = if (trimmed.length in 3..4 && trimmed.all(Char::isDigit)) {
            listOf(trimmed.dropLast(2), trimmed.takeLast(2))
        } else {
            trimmed.split(':', 'h', 'H', '.')
        }
        if (parts.size !in 1..2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.ifEmpty { "0" }?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}

private fun dayFormat() = DateTimeFormatter.ofPattern("EEE d MMM yyyy", UiLocale)
private fun timeFormat() = DateTimeFormatter.ofPattern("HH:mm", UiLocale)

/**
 * Adds a run the phone did not record.
 *
 * A dialog rather than a screen: three numbers and a date are not a place to navigate
 * to. The start time is filled in with the current one, which is right for the run just
 * finished on a treadmill and harmless for one from last week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRunDialog(
    onDismiss: () -> Unit,
    onSave: (ManualRun) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var time by remember { mutableStateOf(timeFormat().format(LocalTime.now(zone))) }
    var km by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a run") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dayFormat().format(date),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingDate = true }) { Text("Change") }
                }
                Spacer(Modifier.height(Spacing.s))
                NumberField(
                    value = time,
                    onValueChange = { time = it.filter { c -> c.isDigit() || c in ":h." }.take(5) },
                    label = "start",
                    width = 110,
                )
                Spacer(Modifier.height(Spacing.m))
                NumberField(
                    value = km,
                    onValueChange = { km = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    label = "km",
                    width = 140,
                    decimal = true,
                )
                Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    NumberField(hours, { hours = it.filter(Char::isDigit).take(2) }, "h", 72)
                    NumberField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, "min", 72)
                    NumberField(seconds, { seconds = it.filter(Char::isDigit).take(2) }, "sec", 72)
                }
                error?.let {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (
                        val entry = ManualRunForm.parse(
                            date = date,
                            time = time,
                            hours = hours,
                            minutes = minutes,
                            seconds = seconds,
                            km = km,
                            zone = zone,
                            now = Instant.now(),
                        )
                    ) {
                        is ManualRunEntry.Valid -> onSave(entry.run)
                        is ManualRunEntry.Invalid -> error = entry.reason
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        val today = LocalDate.now(zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            // No days after today: a run that has not happened yet is a plan, and plans
            // live on the Coach tab.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Read back in UTC: the picker hands out UTC midnight whatever the
                        // phone's zone, and reading it locally moves the day.
                        state.selectedDateMillis?.let { millis ->
                            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
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
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    width: Int,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.width(width.dp),
    )
}
