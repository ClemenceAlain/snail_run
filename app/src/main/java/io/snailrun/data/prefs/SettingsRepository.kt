package io.snailrun.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.snailrun.domain.coach.CoachBaseline
import io.snailrun.domain.voice.VoiceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val voice: VoiceConfig = VoiceConfig(enabled = false),
    /** Tree URI of the folder every finished run is written to, if one was chosen. */
    val exportFolderUri: String? = null,
    val autoExportEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Stops the clock when the runner stops, and starts it again when they move off. */
    val autoPauseEnabled: Boolean = false,
    val speechRate: Float = 1.0f,
    /** Local basemap file. Absent means runs are drawn as a plain trace. */
    val basemapUri: String? = null,
    val demo: DemoSettings = DemoSettings(),
    val coach: CoachSettings = CoachSettings(),
)

/**
 * A race to train towards, if there is one.
 *
 * Both fields null is the normal state and a supported one: without a target the coach
 * plans a balanced week off the runner's own history, which is what most people want
 * most of the year. The date is stored as an epoch day rather than a string because the
 * only thing ever done with it is arithmetic against another date.
 */
data class CoachSettings(
    val targetDistanceMeters: Int? = null,
    val targetDateEpochDay: Long? = null,
    /**
     * Weeks the runner has rearranged by hand, keyed on the week's first day.
     *
     * The plan itself is never stored — it is recomputed from the history every time it
     * is shown. What is stored is the one thing that cannot be recomputed: the fact that
     * somebody dragged Tuesday's tempo to Thursday because they are at work on Tuesday.
     * A permutation survives a replan, where a stored plan would have to be reconciled
     * with one.
     */
    val dayOrders: Map<Long, List<Int>> = emptyMap(),
    /**
     * Whether the app comments on your pace inside a rep.
     *
     * Off by default, and the only cue that is. The pace it reads is smoothed GPS, which
     * wanders ten seconds a kilometre under trees and round corners, so this is the one
     * that can start nagging — and a runner who turns off the nagging usually turns off
     * the whole voice with it.
     */
    val nudgeOffPace: Boolean = false,
    /**
     * What the runner said they had been doing before the app was watching, if they
     * answered. See [CoachBaseline] — it is history, not a setting, which is why it is
     * in the backup along with the runs.
     */
    val baseline: CoachBaseline? = null,
    /**
     * Whether the questions have been put. Separate from [baseline] because declining
     * them is an answer: without this the card would ask again on every visit.
     */
    val baselineAsked: Boolean = false,
)

/**
 * `20353:3,0,1,2,4,5,6|20360:0,1,2,3,4,5,6`
 *
 * One preference holding every rearranged week rather than a key each. DataStore takes
 * dynamic keys happily enough, but they are never enumerable, so old weeks could only
 * accumulate — and a runner who moves a session most weeks would leave a key behind for
 * every week they ever ran.
 */
internal object DayOrders {

    fun encode(orders: Map<Long, List<Int>>): String =
        orders.entries.sortedBy { it.key }
            .joinToString("|") { (week, order) -> "$week:${order.joinToString(",")}" }

    fun decode(raw: String?): Map<Long, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("|").mapNotNull { entry ->
            val week = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val order = entry.substringAfter(':', "").split(",").mapNotNull(String::toIntOrNull)
            // A permutation of exactly seven days or nothing. Anything else is a file
            // written by a version that meant something different by it.
            if (order.sorted() != (0..6).toList()) null else week to order
        }.toMap()
    }
}

/**
 * The coach's state as flat strings, which is what a backup can carry.
 *
 * Here rather than in the backup class because the encoding of a day order and of a
 * baseline already lives here, and two codecs for the same string is how a restore ends
 * up reading a week it cannot reproduce. A key that is absent means the value was not
 * set, which is the same thing the preference store means by it.
 */
fun CoachSettings.toBackupRows(): Map<String, String> = buildMap {
    targetDistanceMeters?.let { put("target_distance", it.toString()) }
    targetDateEpochDay?.let { put("target_date", it.toString()) }
    DayOrders.encode(dayOrders).takeIf { it.isNotEmpty() }?.let { put("day_orders", it) }
    put("nudge_off_pace", nudgeOffPace.toString())
    baseline?.let { put("baseline", CoachBaselines.encode(it)) }
    put("baseline_asked", baselineAsked.toString())
}

fun coachSettingsFrom(rows: Map<String, String>): CoachSettings = CoachSettings(
    targetDistanceMeters = rows["target_distance"]?.toIntOrNull(),
    targetDateEpochDay = rows["target_date"]?.toLongOrNull(),
    dayOrders = DayOrders.decode(rows["day_orders"]),
    nudgeOffPace = rows["nudge_off_pace"].toBoolean(),
    baseline = CoachBaselines.decode(rows["baseline"]),
    baselineAsked = rows["baseline_asked"].toBoolean(),
)

/**
 * `runsPerWeek,weeklyM,longestM,raceM,raceMs,raceDay,recordedDay`
 *
 * One string rather than seven preferences, for the same reason [DayOrders] is one: the
 * seven are written together and mean nothing apart, and a half-written set is a
 * description of four weeks that never happened. An unparseable string reads as no
 * baseline at all, which is the state the coach was designed around anyway.
 */
internal object CoachBaselines {

    fun encode(baseline: CoachBaseline): String = listOf(
        baseline.runsPerWeek,
        baseline.weeklyMeters.toLong(),
        baseline.longestRunMeters.toLong(),
        baseline.raceDistanceMeters ?: 0,
        baseline.raceDurationMs ?: 0L,
        baseline.raceDateEpochDay ?: 0L,
        baseline.recordedOnEpochDay,
    ).joinToString(",")

    fun decode(raw: String?): CoachBaseline? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",")
        if (parts.size != 7) return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        return CoachBaseline(
            runsPerWeek = numbers[0].toInt(),
            weeklyMeters = numbers[1].toDouble(),
            longestRunMeters = numbers[2].toDouble(),
            raceDistanceMeters = numbers[3].toInt().takeIf { it > 0 },
            raceDurationMs = numbers[4].takeIf { it > 0 },
            raceDateEpochDay = numbers[5].takeIf { it > 0 },
            recordedOnEpochDay = numbers[6],
        )
    }
}

/**
 * Replays a synthetic run instead of reading the GPS chip, so the app can be tried
 * indoors. Off by default and never implied by anything else: a run recorded this way
 * is real data in the database, and only the tag on it says otherwise.
 */
data class DemoSettings(
    val enabled: Boolean = false,
    /** Wall-clock compression. 30 means an hour of running arrives in two minutes. */
    val speedFactor: Int = 30,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun setVoiceEnabled(enabled: Boolean) = edit { it[VOICE_ENABLED] = enabled }

    suspend fun setAnnounceEveryMeters(meters: Double) =
        edit { it[ANNOUNCE_EVERY_METERS] = meters.toFloat() }

    suspend fun setAnnounceEveryMinutes(minutes: Int) =
        edit { it[ANNOUNCE_EVERY_MINUTES] = minutes }

    suspend fun setSpeakElapsed(value: Boolean) = edit { it[SPEAK_ELAPSED] = value }

    suspend fun setSpeakAveragePace(value: Boolean) = edit { it[SPEAK_AVERAGE_PACE] = value }

    suspend fun setSpeakLastSplit(value: Boolean) = edit { it[SPEAK_LAST_SPLIT] = value }

    suspend fun setSpeechRate(rate: Float) = edit { it[SPEECH_RATE] = rate }

    suspend fun setExportFolderUri(uri: String?) = edit {
        if (uri == null) it.remove(EXPORT_FOLDER_URI) else it[EXPORT_FOLDER_URI] = uri
    }

    suspend fun setAutoExportEnabled(value: Boolean) = edit { it[AUTO_EXPORT] = value }

    suspend fun setKeepScreenOn(value: Boolean) = edit { it[KEEP_SCREEN_ON] = value }

    suspend fun setAutoPauseEnabled(value: Boolean) = edit { it[AUTO_PAUSE] = value }

    suspend fun setDemoEnabled(value: Boolean) = edit { it[DEMO_ENABLED] = value }

    suspend fun setDemoSpeedFactor(factor: Int) = edit { it[DEMO_SPEED_FACTOR] = factor }

    /**
     * Set together, cleared together. A distance with no date cannot be periodised and a
     * date with no distance cannot be predicted, so neither half is a state worth having.
     */
    suspend fun setCoachTarget(distanceMeters: Int?, dateEpochDay: Long?) = edit {
        if (distanceMeters == null || dateEpochDay == null) {
            it.remove(COACH_TARGET_DISTANCE)
            it.remove(COACH_TARGET_DATE)
        } else {
            it[COACH_TARGET_DISTANCE] = distanceMeters
            it[COACH_TARGET_DATE] = dateEpochDay
        }
    }

    /**
     * Remembers how a week has been rearranged, and forgets weeks now in the past.
     *
     * Pruning here rather than on a schedule: this is the only place the set is written,
     * and a week that has been and gone cannot be rearranged again.
     */
    suspend fun setCoachDayOrder(weekStartEpochDay: Long, order: List<Int>?, keepFrom: Long) = edit {
        val current = DayOrders.decode(it[COACH_DAY_ORDERS]).toMutableMap()
        if (order == null || order == (0..6).toList()) {
            current.remove(weekStartEpochDay)
        } else {
            current[weekStartEpochDay] = order
        }
        current.keys.retainAll { week -> week >= keepFrom }
        val encoded = DayOrders.encode(current)
        if (encoded.isEmpty()) it.remove(COACH_DAY_ORDERS) else it[COACH_DAY_ORDERS] = encoded
    }

    suspend fun setCoachNudgeOffPace(value: Boolean) = edit { it[COACH_NUDGE_OFF_PACE] = value }

    /**
     * Writes the starting point, or clears it.
     *
     * One call for all of it: the answers describe one set of four weeks, and half of
     * them written against a [CoachBaseline.recordedOnEpochDay] from a different day
     * would describe four weeks that never happened.
     */
    suspend fun setCoachBaseline(baseline: CoachBaseline?) = edit {
        it[COACH_BASELINE_ASKED] = true
        if (baseline == null) {
            it.remove(COACH_BASELINE)
        } else {
            it[COACH_BASELINE] = CoachBaselines.encode(baseline)
        }
    }

    /**
     * Puts the coach back as a restored backup found it.
     *
     * One edit, and it overwrites rather than merges: the file describes one coherent
     * coach, and half of this phone's race goal beside half of the backup's is a state
     * neither of them was ever in.
     */
    suspend fun restoreCoach(coach: CoachSettings) = edit { prefs ->
        prefs.remove(COACH_TARGET_DISTANCE)
        prefs.remove(COACH_TARGET_DATE)
        prefs.remove(COACH_DAY_ORDERS)
        prefs.remove(COACH_BASELINE)
        coach.targetDistanceMeters?.let { prefs[COACH_TARGET_DISTANCE] = it }
        coach.targetDateEpochDay?.let { prefs[COACH_TARGET_DATE] = it }
        DayOrders.encode(coach.dayOrders).takeIf { it.isNotEmpty() }
            ?.let { prefs[COACH_DAY_ORDERS] = it }
        coach.baseline?.let { prefs[COACH_BASELINE] = CoachBaselines.encode(it) }
        prefs[COACH_NUDGE_OFF_PACE] = coach.nudgeOffPace
        prefs[COACH_BASELINE_ASKED] = coach.baselineAsked
    }

    suspend fun setBasemapUri(uri: String?) = edit {
        if (uri == null) it.remove(BASEMAP_URI) else it[BASEMAP_URI] = uri
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSettings() = Settings(
        voice = VoiceConfig(
            enabled = this[VOICE_ENABLED] ?: false,
            everyMeters = (this[ANNOUNCE_EVERY_METERS] ?: 1000f).toDouble(),
            everyMillis = (this[ANNOUNCE_EVERY_MINUTES] ?: 0) * 60_000L,
            speakElapsed = this[SPEAK_ELAPSED] ?: true,
            speakAveragePace = this[SPEAK_AVERAGE_PACE] ?: true,
            speakLastSplitPace = this[SPEAK_LAST_SPLIT] ?: false,
        ),
        exportFolderUri = this[EXPORT_FOLDER_URI],
        autoExportEnabled = this[AUTO_EXPORT] ?: true,
        keepScreenOn = this[KEEP_SCREEN_ON] ?: true,
        autoPauseEnabled = this[AUTO_PAUSE] ?: false,
        speechRate = this[SPEECH_RATE] ?: 1.0f,
        basemapUri = this[BASEMAP_URI],
        demo = DemoSettings(
            enabled = this[DEMO_ENABLED] ?: false,
            speedFactor = this[DEMO_SPEED_FACTOR] ?: 30,
        ),
        coach = CoachSettings(
            targetDistanceMeters = this[COACH_TARGET_DISTANCE],
            targetDateEpochDay = this[COACH_TARGET_DATE],
            dayOrders = DayOrders.decode(this[COACH_DAY_ORDERS]),
            nudgeOffPace = this[COACH_NUDGE_OFF_PACE] ?: false,
            baseline = CoachBaselines.decode(this[COACH_BASELINE]),
            baselineAsked = this[COACH_BASELINE_ASKED] ?: false,
        ),
    )

    private companion object {
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val ANNOUNCE_EVERY_METERS = floatPreferencesKey("announce_every_meters")
        val ANNOUNCE_EVERY_MINUTES = intPreferencesKey("announce_every_minutes")
        val SPEAK_ELAPSED = booleanPreferencesKey("speak_elapsed")
        val SPEAK_AVERAGE_PACE = booleanPreferencesKey("speak_average_pace")
        val SPEAK_LAST_SPLIT = booleanPreferencesKey("speak_last_split")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val EXPORT_FOLDER_URI = stringPreferencesKey("export_folder_uri")
        val AUTO_EXPORT = booleanPreferencesKey("auto_export")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val BASEMAP_URI = stringPreferencesKey("basemap_uri")
        val DEMO_ENABLED = booleanPreferencesKey("demo_enabled")
        val DEMO_SPEED_FACTOR = intPreferencesKey("demo_speed_factor")
        val COACH_TARGET_DISTANCE = intPreferencesKey("coach_target_distance")
        val COACH_TARGET_DATE = longPreferencesKey("coach_target_date")
        val COACH_DAY_ORDERS = stringPreferencesKey("coach_day_orders")
        val COACH_NUDGE_OFF_PACE = booleanPreferencesKey("coach_nudge_off_pace")
        val COACH_BASELINE = stringPreferencesKey("coach_baseline")
        val COACH_BASELINE_ASKED = booleanPreferencesKey("coach_baseline_asked")
    }
}
