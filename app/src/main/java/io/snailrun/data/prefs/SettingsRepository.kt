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
    }
}
