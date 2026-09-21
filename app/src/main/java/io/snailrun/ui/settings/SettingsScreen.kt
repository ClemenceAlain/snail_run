package io.snailrun.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.snailrun.data.prefs.Settings
import io.snailrun.data.voice.TtsState
import io.snailrun.domain.coach.Races
import io.snailrun.ui.components.HelpButton
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SettingsActions(
    val onVoiceEnabled: (Boolean) -> Unit,
    val onAnnounceEveryMeters: (Double) -> Unit,
    val onAnnounceEveryMinutes: (Int) -> Unit,
    val onSpeakElapsed: (Boolean) -> Unit,
    val onSpeakAveragePace: (Boolean) -> Unit,
    val onSpeakLastSplit: (Boolean) -> Unit,
    val onSpeechRate: (Float) -> Unit,
    val onTestVoice: () -> Unit,
    val onOpenTtsSettings: () -> Unit,
    val onChooseExportFolder: () -> Unit,
    val onAutoExport: (Boolean) -> Unit,
    val onKeepScreenOn: (Boolean) -> Unit,
    val onAutoPause: (Boolean) -> Unit,
    val onChooseBasemap: () -> Unit,
    val onRemoveBasemap: () -> Unit,
    /** Distance and date together, or both null to clear the target. */
    val onCoachTarget: (Int?, Long?) -> Unit,
    val onCoachNudge: (Boolean) -> Unit,
    val onDemoEnabled: (Boolean) -> Unit,
    val onDemoSpeedFactor: (Int) -> Unit,
    val onBackup: () -> Unit,
    val onRestore: () -> Unit,
)

@Composable
fun SettingsScreen(
    settings: Settings,
    ttsState: TtsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    basemapStatus: String = "No map file yet.",
    backupStatus: String? = null,
) {
    var showRaceDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Spacing.huge),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = Spacing.l),
        )

        SectionTitle("Voice announcements")

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            val available = ttsState is TtsState.Ready
            SwitchRow(
                label = "Speak my stats",
                checked = settings.voice.enabled && available,
                enabled = available,
                onCheckedChange = actions.onVoiceEnabled,
            )

            VoiceStatus(ttsState, actions.onOpenTtsSettings)

            AnimatedVisibility(visible = settings.voice.enabled && available) {
                Column {
                    Spacer(Modifier.height(Spacing.l))

                    Text("Announce every", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.s))
                    ChipRow {
                        listOf(0.0, 500.0, 1000.0, 2000.0, 5000.0).forEach { meters ->
                            FilterChip(
                                selected = settings.voice.everyMeters == meters,
                                onClick = { actions.onAnnounceEveryMeters(meters) },
                                label = { Text(if (meters == 0.0) "Off" else "${(meters / 1000)} km") },
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.m))
                    ChipRow {
                        listOf(0, 5, 10, 15).forEach { minutes ->
                            FilterChip(
                                selected = settings.voice.everyMillis == minutes * 60_000L,
                                onClick = { actions.onAnnounceEveryMinutes(minutes) },
                                label = { Text(if (minutes == 0) "No timer" else "$minutes min") },
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.l))
                    Text("Say", style = MaterialTheme.typography.titleMedium)
                    CheckRow("Total time", settings.voice.speakElapsed, actions.onSpeakElapsed)
                    CheckRow("Average pace", settings.voice.speakAveragePace, actions.onSpeakAveragePace)
                    CheckRow("Last kilometre pace", settings.voice.speakLastSplitPace, actions.onSpeakLastSplit)

                    Spacer(Modifier.height(Spacing.l))
                    Text("Speech rate", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = settings.speechRate,
                        onValueChange = actions.onSpeechRate,
                        valueRange = 0.5f..2.0f,
                    )
                    TextButton(onClick = actions.onTestVoice) { Text("Test voice") }
                }
            }
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "GPX files",
            help = listOf(
                "A copy for other programs, written to a folder you choose once. Nothing " +
                    "reads it back — a GPX export is not a backup.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Save every run as GPX",
                checked = settings.autoExportEnabled,
                onCheckedChange = actions.onAutoExport,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = settings.exportFolderUri?.let { "Saving to the folder you chose." }
                    ?: "Choose a folder and every finished run is written there automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = actions.onChooseExportFolder) {
                Text(if (settings.exportFolderUri == null) "Choose folder" else "Change folder")
            }
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "While recording",
            help = listOf(
                "Pause when I stop: the clock stops a few seconds after you do and starts " +
                    "again when you move off.",
                "A walking break still counts as running — only standing still pauses. " +
                    "Pausing by hand is never undone for you.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Keep the screen on",
                checked = settings.keepScreenOn,
                onCheckedChange = actions.onKeepScreenOn,
            )

            Spacer(Modifier.height(Spacing.l))
            SwitchRow(
                label = "Pause when I stop",
                checked = settings.autoPauseEnabled,
                onCheckedChange = actions.onAutoPause,
            )
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "Map",
            help = listOf(
                "A map file lives on the phone, like everything else here: put an MBTiles " +
                    "extract of where you run on the device and pick it once.",
                "Runs outside what it covers still draw, as a plain trace. Until you pick " +
                    "one, demo runs are drawn on the small invented map the app ships with.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = basemapStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow {
                TextButton(onClick = actions.onChooseBasemap) {
                    Text(if (settings.basemapUri == null) "Choose map file" else "Replace")
                }
                if (settings.basemapUri != null) {
                    TextButton(onClick = actions.onRemoveBasemap) { Text("Remove") }
                }
            }
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "Coach",
            help = listOf(
                "The Coach tab plans four weeks from the runs you have already done. It " +
                    "needs nothing set here — without a race it builds steadily, which is " +
                    "what most of a year looks like.",
                "On a fresh install it has no runs to read, so the tab asks four questions " +
                    "about the month before it. The answers stand in for that month and " +
                    "age out of it day by day.",
                "Load a session on the record screen and the app counts you through it, " +
                    "out loud and with a buzz at every change of step.",
                "The off-pace warning reads smoothed GPS pace, which wanders under trees " +
                    "and round corners. It waits twenty seconds and speaks at most once a " +
                    "minute, and it is off until you ask for it.",
                "With a race set, the plan counts back from the date: it builds until four " +
                    "weeks out, sharpens inside that, and tapers the last fortnight.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Tell me when I drift off pace",
                checked = settings.coach.nudgeOffPace,
                onCheckedChange = actions.onCoachNudge,
            )

            Spacer(Modifier.height(Spacing.l))
            Text("Training for", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.s))
            ChipRow {
                FilterChip(
                    selected = settings.coach.targetDistanceMeters == null,
                    onClick = { actions.onCoachTarget(null, null) },
                    label = { Text("Nothing") },
                )
                Races.Distances.forEach { meters ->
                    FilterChip(
                        selected = settings.coach.targetDistanceMeters == meters,
                        onClick = {
                            // A distance with no date cannot be periodised, so picking one
                            // seeds a date twelve weeks out — the length of a build — which
                            // the runner then corrects to their actual race.
                            actions.onCoachTarget(
                                meters,
                                settings.coach.targetDateEpochDay
                                    ?: LocalDate.now().plusWeeks(12).toEpochDay(),
                            )
                        },
                        label = { Text(raceLabel(meters)) },
                    )
                }
            }

            AnimatedVisibility(visible = settings.coach.targetDistanceMeters != null) {
                Column {
                    Spacer(Modifier.height(Spacing.m))
                    val date = settings.coach.targetDateEpochDay?.let(LocalDate::ofEpochDay)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = date?.let { dateFormat().format(it) } ?: "No date",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = { showRaceDatePicker = true }) { Text("Change") }
                    }
                }
            }
        }

        if (showRaceDatePicker) {
            RaceDatePicker(
                initial = settings.coach.targetDateEpochDay,
                onDismiss = { showRaceDatePicker = false },
                onPick = { epochDay ->
                    showRaceDatePicker = false
                    actions.onCoachTarget(settings.coach.targetDistanceMeters, epochDay)
                },
            )
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "Demo mode",
            help = listOf(
                "Start replays a made-up loop instead of reading the GPS chip, so you can " +
                    "see the numbers, the voice and the trace without going outside.",
                "The run is saved like any other and labelled DEMO. It counts: it can hold " +
                    "a record, and the coach reads it. Delete it from its own screen.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Record a fake run",
                checked = settings.demo.enabled,
                onCheckedChange = actions.onDemoEnabled,
            )

            AnimatedVisibility(visible = settings.demo.enabled) {
                Column {
                    Spacer(Modifier.height(Spacing.l))
                    Text("Speed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.s))
                    ChipRow {
                        listOf(1, 10, 30, 60).forEach { factor ->
                            FilterChip(
                                selected = settings.demo.speedFactor == factor,
                                onClick = { actions.onDemoSpeedFactor(factor) },
                                label = { Text("${factor}x") },
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = "One hour of running arrives in " +
                            demoDurationLabel(settings.demo.speedFactor) + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle(
            "Your runs",
            help = listOf(
                "Your runs live on this phone and nowhere else. An uninstall, a factory " +
                    "reset or a lost phone takes them with it.",
                "A backup is one file holding every run, every track and every record. Put " +
                    "it somewhere off the phone and it is the only copy that survives.",
                "Your race, your rearranged weeks and what you told the coach about the " +
                    "runs it never saw are in it too. They are about you, not about this " +
                    "phone.",
                "Settings are not, and neither is the map file: Android ties the GPX " +
                    "folder and the map file to this installation, so neither could be " +
                    "restored anyway.",
            ),
        )

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            if (backupStatus != null) {
                Text(
                    text = backupStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton(onClick = actions.onBackup) { Text("Back up") }
                TextButton(onClick = actions.onRestore) { Text("Restore") }
            }
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle("Privacy")

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "No internet permission. Nothing you record can leave this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row of chips that wraps.
 *
 * Five of them — nothing, 5 km, 10 km, half, marathon — do not fit across a phone, and a
 * plain `Row` answers that by squeezing the last one until "Marathon" breaks across two
 * lines inside its own chip. Wrapping to a second line is what the reader expected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
}

/**
 * The platform date picker, opened only when a target exists to attach a date to.
 *
 * Dates arrive from it as UTC milliseconds whatever the phone's zone, so they are read
 * back in UTC. Reading them in the local zone shifts the race a day either way for
 * anyone far enough east or west, which is exactly the sort of bug nobody notices until
 * the taper starts a week late.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceDatePicker(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now().plusWeeks(12).toEpochDay()) *
            86_400_000L,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                .toEpochDay()
                        )
                    }
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

// Built per call: a formatter cached at class-init keeps the locale the app
// started with, which is wrong after the user changes the system language.
private fun dateFormat() = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())

private fun raceLabel(meters: Int) = when (meters) {
    21_097 -> "Half"
    42_195 -> "Marathon"
    else -> "${meters / 1000} km"
}

private fun demoDurationLabel(speedFactor: Int): String {
    val seconds = 3_600 / speedFactor.coerceAtLeast(1)
    return when {
        seconds >= 3_600 -> "an hour"
        seconds >= 120 -> "about ${seconds / 60} minutes"
        else -> "about $seconds seconds"
    }
}

@Composable
private fun VoiceStatus(state: TtsState, onOpenTtsSettings: () -> Unit) {
    when (state) {
        is TtsState.Ready -> {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = if (state.engineHasNoNetworkAccess) {
                    "Using ${state.engineLabel}. This engine has no network access."
                } else {
                    "Using ${state.engineLabel} with an on-device voice."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TtsState.Initialising -> Unit

        // A LineageOS build without Google apps may ship no speech engine at all, so
        // this is the expected case on the target phone, not an edge case.
        TtsState.Unavailable.NoEngineInstalled -> Advice(
            "No speech engine installed. Your runs still record normally. Install an " +
                "offline engine such as eSpeak NG or RHVoice from F-Droid, then set it " +
                "as the default.",
            onOpenTtsSettings,
        )

        TtsState.Unavailable.InitFailed -> Advice(
            "The speech engine would not start. Voice is off; recording is unaffected.",
            onOpenTtsSettings,
        )

        is TtsState.Unavailable.LanguageMissing -> Advice(
            "Voice data for your language is not installed.",
            onOpenTtsSettings,
        )

        is TtsState.Unavailable.LanguageUnsupported -> Advice(
            "The installed engine does not speak your language.",
            onOpenTtsSettings,
        )

        is TtsState.Unavailable.OnlyNetworkVoices -> Advice(
            "The only voices for your language need an internet connection. snail run " +
                "never goes online, so voice stays off. Install eSpeak NG or RHVoice " +
                "for offline speech.",
            onOpenTtsSettings,
        )
    }
}

@Composable
private fun Advice(text: String, onOpenTtsSettings: () -> Unit) {
    Spacer(Modifier.height(Spacing.s))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onOpenTtsSettings) { Text("Open speech settings") }
}

@Composable
private fun SectionTitle(text: String, help: List<String>? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = Spacing.s),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (help != null) {
            HelpButton(title = text, body = help, modifier = Modifier.padding(start = Spacing.xs))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

