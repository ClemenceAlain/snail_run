package io.snailrun.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.snailrun.domain.coach.CoachText
import io.snailrun.domain.coach.PaceBasis
import io.snailrun.domain.coach.PlanSchedule
import io.snailrun.domain.coach.PlanTemplates
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.Races
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vdot
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaOrigin
import io.snailrun.domain.coach.VmaSource
import io.snailrun.domain.coach.Vmas
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.format.UiLocale
import io.snailrun.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How the runner answers the first question. */
enum class VmaAnswer { Known, Measured, Unknown }

/**
 * The typed answers, read. Pure, so the parsing is tested without a screen.
 */
object PlanInputs {

    const val MIN_VMA = 6.0
    const val MAX_VMA = 30.0

    /** "14", "14.5" or "14,5" km/h, inside a range a human can run. */
    fun vma(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in MIN_VMA..MAX_VMA }

    /** Metres covered in the six minutes. 600 m to 3 000 m is 6 to 30 km/h. */
    fun testMeters(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it in MIN_VMA * 100..MAX_VMA * 100 }

    /** "45:00", "1:45:00", or a bare number of minutes. */
    fun time(text: String): Long? {
        val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        // Everything after the first part is minutes or seconds, and stops at 59.
        if (parts.drop(1).any { it >= 60 }) return null
        val seconds = when (parts.size) {
            1 -> parts[0] * 60
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3_600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return (seconds * 1000).takeIf { it > 0 }
    }

    /** The VMA the answers give, or null for "I don't know" and for an unreadable one. */
    fun vmaFrom(answer: VmaAnswer, text: String, today: LocalDate): Vma? = when (answer) {
        VmaAnswer.Known -> vma(text)?.let { Vma(Vmas.round1(it), today, VmaSource.Typed) }
        VmaAnswer.Measured -> testMeters(text)?.let { Vma(Vmas.round1(Vmas.fromTestDistance(it)), today, VmaSource.Test) }
        VmaAnswer.Unknown -> null
    }
}

private fun dayFormat() = DateTimeFormatter.ofPattern("EEE d MMM yyyy", UiLocale)

/**
 * Starting a plan: four questions, one at a time.
 *
 * VMA first, because every fast pace in the plan is a percentage of it. A runner who
 * does not know theirs is not asked to guess: the plan opens with the six-minute test
 * instead, and prices itself from the result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanWizard(
    basis: PaceBasis?,
    savedVma: Vma?,
    prefill: RaceGoal?,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onStart: (race: RaceGoal?, targetMs: Long?, sessions: Int, vma: Vma?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf(if (savedVma != null) VmaAnswer.Known else VmaAnswer.Unknown) }
    var vmaText by rememberSaveable { mutableStateOf(savedVma?.kmh?.toString().orEmpty()) }
    var raceMeters by rememberSaveable { mutableStateOf(prefill?.distanceMeters) }
    var raceDay by rememberSaveable {
        mutableStateOf((prefill?.date?.takeIf { it.isAfter(today) } ?: today.plusWeeks(10)).toEpochDay())
    }
    var targetText by rememberSaveable { mutableStateOf("") }
    var sessions by rememberSaveable { mutableStateOf(3) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    val vma = PlanInputs.vmaFrom(answer, vmaText, today)
    val vdot = vma?.let { Vmas.vdotOf(it.kmh) }
        ?: basis?.takeIf { it.origin != VmaOrigin.None }?.fitness?.vdot
    val race = raceMeters?.let { RaceGoal(it, LocalDate.ofEpochDay(raceDay)) }
    val predicted = race?.let { r -> vdot?.let { Vdot.timeMsFor(it, r.distanceMeters.toDouble()) } }

    SnailCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (canCancel) "New plan" else "Start a plan",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Step ${step + 1} of 4",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.l))

        when (step) {
            0 -> {
                Question("Do you know your VMA?")
                Explain(
                    "VMA — maximal aerobic speed — is the speed at which you reach your " +
                        "maximum oxygen uptake. Every fast session in the plan is a " +
                        "percentage of it.",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    // The field means km/h for one answer and metres for the other, so it
                    // is emptied when the answer changes rather than reread in new units.
                    VmaAnswer.entries.forEach { option ->
                        FilterChip(
                            selected = answer == option,
                            onClick = {
                                if (answer != option) vmaText = ""
                                answer = option
                            },
                            label = {
                                Text(
                                    when (option) {
                                        VmaAnswer.Known -> "I know it"
                                        VmaAnswer.Measured -> "I ran the 6-min test"
                                        VmaAnswer.Unknown -> "I don't know"
                                    },
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.m))
                when (answer) {
                    VmaAnswer.Known -> NumberField(vmaText, { vmaText = it }, "VMA, km/h", decimal = true)
                    VmaAnswer.Measured -> {
                        NumberField(vmaText, { vmaText = it }, "Metres in six minutes", decimal = false)
                        vma?.let { Explain("That is a VMA of ${CoachText.kmh(it.kmh)}.") }
                    }
                    VmaAnswer.Unknown -> {
                        Explain(
                            "Then your first session is the test: 15 minutes easy and three " +
                                "strides, then six minutes as far as you can. The distance in " +
                                "metres ÷ 100 is your VMA in km/h — 1 500 m is 15 km/h. The app " +
                                "reads it off the run, and the plan prices itself from it.",
                        )
                        basis?.takeIf { it.origin == VmaOrigin.Estimated }?.vmaKmh?.let {
                            Explain("Until then, your recent running puts it near ${CoachText.kmh(it)}.")
                        }
                    }
                }
            }

            1 -> {
                Question("Are you training for a race?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    FilterChip(raceMeters == null, { raceMeters = null }, { Text("No race") })
                    Races.Distances.forEach { meters ->
                        FilterChip(raceMeters == meters, { raceMeters = meters }, { Text(raceLabel(meters)) })
                    }
                }
                Spacer(Modifier.height(Spacing.m))
                if (race == null) {
                    Explain(
                        "Then the plan is a four-week cycle that repeats: three weeks that " +
                            "build and a lighter fourth, with your 10 km pace as race pace.",
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dayFormat().format(race.date), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { pickingDate = true }) { Text("Change") }
                    }
                    val draft = TrainingPlan(TrainingPlan.startWeekFor(today, firstDayOfWeek), race, null, sessions, today)
                    val weeks = PlanSchedule.weeksIn(draft)
                    val length = PlanTemplates.forDistance(race.distanceMeters).weeks.size
                    Explain(
                        when {
                            weeks == null || weeks < 1 -> "That date is before the plan could start."
                            weeks < 2 -> "Less than two weeks: the plan is only the race week."
                            weeks < length -> "$weeks weeks. The ${length}-week plan is joined part way, and keeps its taper."
                            weeks > length -> "$weeks weeks: ${weeks - length} base weeks, then the $length-week plan."
                            else -> "$weeks weeks: the plan exactly."
                        },
                    )
                }
            }

            2 -> {
                if (race == null) {
                    Question("No race, so no target time.")
                    Explain("Race-pace sessions use your predicted 10 km pace, and follow your VMA.")
                } else {
                    Question("Your target time for the ${CoachText.raceName(race.distanceMeters)}")
                    Explain(
                        predicted?.let { "On today's fitness: ${CoachText.clock(it)}. Leave it empty to train at that." }
                            ?: "Optional. Without one, race pace follows your VMA once it is known.",
                    )
                    NumberField(targetText, { targetText = it }, "h:mm:ss or mm:ss", decimal = false, time = true)
                    val target = PlanInputs.time(targetText)
                    if (target != null && predicted != null && vdot != null) {
                        val asked = Vdot.fromEffort(race.distanceMeters.toDouble(), target)
                        if (asked != null && asked > vdot * 1.05) {
                            Text(
                                text = "That is well beyond today's fitness. Race-pace sessions " +
                                    "at it will be very hard to hold.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            else -> {
                Question("How many sessions a week?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    (TrainingPlan.MIN_SESSIONS..TrainingPlan.MAX_SESSIONS).forEach { n ->
                        FilterChip(sessions == n, { sessions = n }, { Text("$n") })
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Explain(
                    when (sessions) {
                        2 -> "Tuesday and Sunday: a long run, and a VMA or race-pace session in turn."
                        4 -> "Tuesday, Thursday, Friday and Sunday: VMA, race pace, an easy run and a long run."
                        else -> "Tuesday, Thursday and Sunday: VMA, race pace and a long run. The plan as written."
                    } + " Drag a session to another day if these do not suit you.",
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            val ready = when (step) {
                0 -> answer == VmaAnswer.Unknown || vma != null
                1 -> race == null || (PlanSchedule.weeksIn(
                    TrainingPlan(TrainingPlan.startWeekFor(today, firstDayOfWeek), race, null, sessions, today),
                ) ?: 0) >= 1
                2 -> targetText.isBlank() || PlanInputs.time(targetText) != null
                else -> true
            }
            if (step < 3) {
                Button(onClick = { step++ }, enabled = ready) { Text("Next") }
            } else {
                Button(
                    onClick = {
                        onStart(race, race?.let { PlanInputs.time(targetText) }, sessions, vma)
                        step = 0
                    },
                ) { Text("Start the plan") }
            }
        }
    }

    if (pickingDate) {
        RaceDatePicker(
            initial = raceDay,
            onDismiss = { pickingDate = false },
            onPick = {
                raceDay = it
                pickingDate = false
            },
        )
    }
}

@Composable
private fun Question(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun Explain(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.s))
}

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    decimal: Boolean,
    time: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            onValueChange(
                typed.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) || (time && it == ':') }.take(8),
            )
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else if (time) KeyboardType.Text else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The platform date picker.
 *
 * Dates arrive from it as UTC milliseconds whatever the phone's zone, so they are read
 * back in UTC. Reading them in the local zone shifts the race a day either way for
 * anyone far enough east or west, which is exactly the sort of bug nobody notices until
 * the taper starts a week late.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDatePicker(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
                    }
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

fun raceLabel(meters: Int) = when (meters) {
    21_097 -> "Half"
    42_195 -> "Marathon"
    else -> "${meters / 1000} km"
}
