package io.snailrun.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.snailrun.data.prefs.Settings
import io.snailrun.data.voice.TtsState
import io.snailrun.ui.components.SnailCard
import io.snailrun.ui.theme.Spacing

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
    val onDemoEnabled: (Boolean) -> Unit,
    val onDemoSpeedFactor: (Int) -> Unit,
)

@Composable
fun SettingsScreen(
    settings: Settings,
    ttsState: TtsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        listOf(0.0, 500.0, 1000.0, 2000.0, 5000.0).forEach { meters ->
                            FilterChip(
                                selected = settings.voice.everyMeters == meters,
                                onClick = { actions.onAnnounceEveryMeters(meters) },
                                label = { Text(if (meters == 0.0) "Off" else "${(meters / 1000)} km") },
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
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
        SectionTitle("GPX files")

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
        SectionTitle("While recording")

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Keep the screen on",
                checked = settings.keepScreenOn,
                onCheckedChange = actions.onKeepScreenOn,
            )
        }

        Spacer(Modifier.height(Spacing.section))
        SectionTitle("Demo mode")

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Record a fake run",
                checked = settings.demo.enabled,
                onCheckedChange = actions.onDemoEnabled,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "Start replays a made-up loop instead of reading the GPS chip, so " +
                    "you can see the numbers, the voice and the trace without going " +
                    "outside. The run is saved like any other and labelled DEMO, and it " +
                    "is left out of your records.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = settings.demo.enabled) {
                Column {
                    Spacer(Modifier.height(Spacing.l))
                    Text("Speed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
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
        SectionTitle("Privacy")

        SnailCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "snail run has no internet permission at all. Android will not " +
                    "give it a network connection, so nothing you record can leave this " +
                    "phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.s),
    )
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

