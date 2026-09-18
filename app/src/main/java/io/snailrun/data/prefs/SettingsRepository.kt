package io.snailrun.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
    val speechRate: Float = 1.0f,
    /** Local basemap file. Absent means runs are drawn as a plain trace. */
    val basemapUri: String? = null,
    val demo: DemoSettings = DemoSettings(),
)

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

    suspend fun setDemoEnabled(value: Boolean) = edit { it[DEMO_ENABLED] = value }

    suspend fun setDemoSpeedFactor(factor: Int) = edit { it[DEMO_SPEED_FACTOR] = factor }

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
        speechRate = this[SPEECH_RATE] ?: 1.0f,
        basemapUri = this[BASEMAP_URI],
        demo = DemoSettings(
            enabled = this[DEMO_ENABLED] ?: false,
            speedFactor = this[DEMO_SPEED_FACTOR] ?: 30,
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
        val BASEMAP_URI = stringPreferencesKey("basemap_uri")
        val DEMO_ENABLED = booleanPreferencesKey("demo_enabled")
        val DEMO_SPEED_FACTOR = intPreferencesKey("demo_speed_factor")
    }
}
