package io.snailrun

import android.content.Context
import io.snailrun.data.backup.DatabaseBackup
import io.snailrun.data.basemap.BasemapStore
import io.snailrun.data.haptics.AndroidHaptics
import io.snailrun.data.haptics.Haptics
import io.snailrun.data.db.SnailDatabase
import io.snailrun.data.location.DemoLocationSource
import io.snailrun.data.location.LocationSource
import io.snailrun.data.location.PlatformLocationSource
import io.snailrun.data.prefs.DemoSettings
import io.snailrun.data.prefs.SettingsRepository
import io.snailrun.data.repo.RunRepository
import io.snailrun.data.voice.AndroidVoiceAnnouncer
import io.snailrun.data.voice.ResourceSpeechVocabulary
import io.snailrun.data.voice.VoiceAnnouncer
import io.snailrun.domain.coach.Workout
import io.snailrun.domain.voice.PaceSpeechFormatter
import io.snailrun.tracking.RecordingState
import io.snailrun.tracking.RunRecorder
import java.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Hand-rolled dependency graph. One developer and one graph, so Hilt's annotation
 * processing would cost more build time than the wiring it saves.
 *
 * Everything here is lazy: the database and the speech engine are not touched until
 * something asks for them.
 */
class AppContainer(private val context: Context) {

    val clock: Clock = Clock.systemDefaultZone()

    private val database: SnailDatabase by lazy { SnailDatabase.build(context) }

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val runRepository: RunRepository by lazy { RunRepository(database.runDao(), clock) }

    val locationSource: LocationSource by lazy { PlatformLocationSource(context) }

    /**
     * The demo source reads its speed on every subscription rather than holding a copy,
     * so changing the setting between runs takes effect without rebuilding the graph.
     */
    private var demoSpeedFactor: Int = DemoSettings().speedFactor

    private val demoLocationSource: LocationSource by lazy {
        DemoLocationSource(speedFactor = { demoSpeedFactor })
    }

    /** Which source a run should use. Decided once, at start, from the saved settings. */
    fun locationSourceFor(demo: DemoSettings): LocationSource =
        if (demo.enabled) {
            demoSpeedFactor = demo.speedFactor
            demoLocationSource
        } else {
            locationSource
        }

    val basemapStore: BasemapStore by lazy { BasemapStore(context, settings) }

    val haptics: Haptics by lazy { AndroidHaptics(context) }

    /**
     * A session chosen but not yet started.
     *
     * In memory on purpose. The recorder and the screen that arms this share one process,
     * the gap between choosing a session and pressing Start is seconds, and if the process
     * dies inside it the runner gets an ordinary run rather than a wrong one — which is
     * cheaper than a serialisation format nothing else needs.
     */
    var armedWorkout: Workout? = null

    /**
     * The database is handed over as a lambda rather than as a value: a restore closes it
     * and replaces the file underneath, so nothing may hold the instance across that.
     */
    val databaseBackup: DatabaseBackup by lazy {
        DatabaseBackup(
            context = context,
            database = { database },
            isRecording = { runRecorder.state.value is RecordingState.Active },
            readCoach = { settings.settings.first().coach },
            writeCoach = { settings.restoreCoach(it) },
        )
    }

    val voiceAnnouncer: VoiceAnnouncer by lazy {
        AndroidVoiceAnnouncer(
            context = context,
            formatter = PaceSpeechFormatter(ResourceSpeechVocabulary(context)),
        )
    }

    val gpxExporter: io.snailrun.data.export.GpxExporter by lazy {
        io.snailrun.data.export.GpxExporter(context, runRepository, settings)
    }

    val runRecorder: RunRecorder by lazy {
        RunRecorder(
            repository = runRepository,
            settings = settings,
            clock = clock,
        )
    }
}
