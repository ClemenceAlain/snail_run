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
import io.snailrun.domain.coach.PairMode
import io.snailrun.domain.coach.Pairing
import io.snailrun.domain.coach.Partner
import io.snailrun.domain.coach.RaceGoal
import io.snailrun.domain.coach.TrainingPlan
import io.snailrun.domain.coach.Vma
import io.snailrun.domain.coach.VmaSource
import java.time.LocalDate
import io.snailrun.domain.voice.VoiceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val voice: VoiceConfig = VoiceConfig(enabled = false),
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
 * What the coach keeps about the runner.
 *
 * [targetDistanceMeters] and [targetDateEpochDay] are the race goal from before plans
 * existed. Nothing sets them any more; they are read once, to start the plan wizard
 * from the race the runner had already entered, and carried through backups so an old
 * file still restores the same.
 */
data class CoachSettings(
    val targetDistanceMeters: Int? = null,
    val targetDateEpochDay: Long? = null,
    /**
     * Weeks the runner has rearranged by hand, keyed on the week's first day.
     *
     * The weeks ahead are planned afresh every time they are shown. What is stored is
     * the one thing that cannot be: the fact that somebody dragged Tuesday's session to
     * Wednesday because they are at work on Tuesday. A permutation survives a replan.
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
    /** People who run some sessions alongside, by VMA. See [Partner]. */
    val partners: List<Partner> = emptyList(),
    /**
     * Sessions shared with a partner, keyed on the epoch day the session was *planned*
     * for — before any rearranging — so a session dragged to another day takes its
     * partner with it, and putting the week back brings both home.
     */
    val pairings: Map<Long, Pairing> = emptyMap(),
    /** The plan the runner started in the Coach tab. Null until they start one. */
    val plan: TrainingPlan? = null,
    /** Their VMA, typed in or measured by the six-minute test. */
    val vma: Vma? = null,
)

/**
 * `1;17.5;Alex|2;14.0;Sam`
 *
 * The name goes last so that it is the one field allowed to hold anything but the two
 * separators, which are taken out of it on the way in.
 */
internal object PartnerCodec {

    fun clean(name: String): String = name.replace("|", " ").replace(";", " ").trim().take(30)

    fun encode(partners: List<Partner>): String =
        partners.joinToString("|") { "${it.id};${it.vmaKmh};${clean(it.name)}" }

    fun decode(raw: String?): List<Partner> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(";", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val vma = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Partner(id, parts[2], vma)
        }
    }
}

/** `20353:1:Together|20356:2:Mirror` — planned day, partner, mode. */
internal object PairingCodec {

    fun encode(pairings: Map<Long, Pairing>): String =
        pairings.entries.sortedBy { it.key }
            .joinToString("|") { (day, p) -> "$day:${p.partnerId}:${p.mode.name}" }

    fun decode(raw: String?): Map<Long, Pairing> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val day = parts[0].toLongOrNull() ?: return@mapNotNull null
            val partner = parts[1].toIntOrNull() ?: return@mapNotNull null
            val mode = runCatching { PairMode.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
            day to Pairing(partner, mode)
        }.toMap()
    }
}

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
    PartnerCodec.encode(partners).takeIf { it.isNotEmpty() }?.let { put("partners", it) }
    PairingCodec.encode(pairings).takeIf { it.isNotEmpty() }?.let { put("pairings", it) }
    plan?.let { put("plan", TrainingPlans.encode(it)) }
    vma?.let { put("vma", CoachVmas.encode(it)) }
}

fun coachSettingsFrom(rows: Map<String, String>): CoachSettings = CoachSettings(
    targetDistanceMeters = rows["target_distance"]?.toIntOrNull(),
    targetDateEpochDay = rows["target_date"]?.toLongOrNull(),
    dayOrders = DayOrders.decode(rows["day_orders"]),
    nudgeOffPace = rows["nudge_off_pace"].toBoolean(),
    baseline = CoachBaselines.decode(rows["baseline"]),
    baselineAsked = rows["baseline_asked"].toBoolean(),
    partners = PartnerCodec.decode(rows["partners"]),
    pairings = PairingCodec.decode(rows["pairings"]),
    plan = TrainingPlans.decode(rows["plan"]),
    vma = CoachVmas.decode(rows["vma"]),
)

/**
 * `1,startDay,raceM,raceDay,targetMs,sessions,createdDay`, zero for absent.
 *
 * The leading version is there for the day a field is added: a decoder that sees a
 * version it does not know reads no plan, and the Coach tab offers to start one, rather
 * than reading a plan that means something else.
 */
internal object TrainingPlans {

    fun encode(plan: TrainingPlan): String = listOf(
        1L,
        plan.startWeek.toEpochDay(),
        (plan.race?.distanceMeters ?: 0).toLong(),
        plan.race?.date?.toEpochDay() ?: 0L,
        plan.targetTimeMs ?: 0L,
        plan.sessionsPerWeek.toLong(),
        plan.createdOn.toEpochDay(),
    ).joinToString(",")

    fun decode(raw: String?): TrainingPlan? {
        if (raw.isNullOrBlank()) return null
        val n = raw.split(",").map { it.trim().toLongOrNull() ?: return null }
        if (n.size != 7 || n[0] != 1L) return null
        val race = if (n[2] > 0 && n[3] != 0L) RaceGoal(n[2].toInt(), LocalDate.ofEpochDay(n[3])) else null
        return TrainingPlan(
            startWeek = LocalDate.ofEpochDay(n[1]),
            race = race,
            targetTimeMs = n[4].takeIf { it > 0 },
            sessionsPerWeek = n[5].toInt().coerceIn(TrainingPlan.MIN_SESSIONS, TrainingPlan.MAX_SESSIONS),
            createdOn = LocalDate.ofEpochDay(n[6]),
        )
    }
}

/** `1,tenthsOfKmh,day,Test|Typed,runId`, zero run id for none. */
internal object CoachVmas {

    fun encode(vma: Vma): String = listOf(
        "1",
        Math.round(vma.kmh * 10).toString(),
        vma.measuredOn.toEpochDay().toString(),
        vma.source.name,
        (vma.testRunId ?: 0L).toString(),
    ).joinToString(",")

    fun decode(raw: String?): Vma? {
        if (raw.isNullOrBlank()) return null
        val p = raw.split(",").map(String::trim)
        if (p.size != 5 || p[0] != "1") return null
        val tenths = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Vma(
            kmh = tenths / 10.0,
            measuredOn = LocalDate.ofEpochDay(p[2].toLongOrNull() ?: return null),
            source = runCatching { VmaSource.valueOf(p[3]) }.getOrNull() ?: return null,
            testRunId = p[4].toLongOrNull()?.takeIf { it > 0 },
        )
    }
}

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

    suspend fun setKeepScreenOn(value: Boolean) = edit { it[KEEP_SCREEN_ON] = value }

    suspend fun setAutoPauseEnabled(value: Boolean) = edit { it[AUTO_PAUSE] = value }

    suspend fun setDemoEnabled(value: Boolean) = edit { it[DEMO_ENABLED] = value }

    suspend fun setDemoSpeedFactor(factor: Int) = edit { it[DEMO_SPEED_FACTOR] = factor }

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

    /** Adds the partner, or replaces the one with the same id. */
    suspend fun savePartner(partner: Partner) = edit {
        val current = PartnerCodec.decode(it[COACH_PARTNERS])
        val clean = partner.copy(name = PartnerCodec.clean(partner.name))
        val next = if (current.any { p -> p.id == clean.id }) {
            current.map { p -> if (p.id == clean.id) clean else p }
        } else {
            current + clean
        }
        it[COACH_PARTNERS] = PartnerCodec.encode(next)
    }

    /** Forgets the partner, and every session that was shared with them. */
    suspend fun removePartner(id: Int) = edit {
        val next = PartnerCodec.decode(it[COACH_PARTNERS]).filterNot { p -> p.id == id }
        if (next.isEmpty()) it.remove(COACH_PARTNERS) else it[COACH_PARTNERS] = PartnerCodec.encode(next)
        val pairings = PairingCodec.decode(it[COACH_PAIRINGS]).filterValues { p -> p.partnerId != id }
        writePairings(it, pairings)
    }

    /**
     * Shares a session, or stops sharing it, and forgets sessions now in the past.
     * [plannedEpochDay] is the day the session was planned on, before any reordering.
     */
    suspend fun setPairing(plannedEpochDay: Long, pairing: Pairing?, keepFrom: Long) = edit {
        val current = PairingCodec.decode(it[COACH_PAIRINGS]).toMutableMap()
        if (pairing == null) current.remove(plannedEpochDay) else current[plannedEpochDay] = pairing
        current.keys.retainAll { day -> day >= keepFrom }
        writePairings(it, current)
    }

    private fun writePairings(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        pairings: Map<Long, Pairing>,
    ) {
        val encoded = PairingCodec.encode(pairings)
        if (encoded.isEmpty()) prefs.remove(COACH_PAIRINGS) else prefs[COACH_PAIRINGS] = encoded
    }

    /** A new plan and the VMA it starts from, written together. */
    suspend fun startCoachPlan(plan: TrainingPlan, vma: Vma?) = edit {
        it.remove(COACH_DAY_ORDERS)
        it[COACH_PLAN] = TrainingPlans.encode(plan)
        if (vma == null) it.remove(COACH_VMA) else it[COACH_VMA] = CoachVmas.encode(vma)
    }

    suspend fun setCoachVma(vma: Vma?) = edit {
        if (vma == null) it.remove(COACH_VMA) else it[COACH_VMA] = CoachVmas.encode(vma)
    }

    suspend fun setCoachNudgeOffPace(value: Boolean) = edit { it[COACH_NUDGE_OFF_PACE] = value }

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
        prefs.remove(COACH_PARTNERS)
        prefs.remove(COACH_PAIRINGS)
        PartnerCodec.encode(coach.partners).takeIf { it.isNotEmpty() }?.let { prefs[COACH_PARTNERS] = it }
        PairingCodec.encode(coach.pairings).takeIf { it.isNotEmpty() }?.let { prefs[COACH_PAIRINGS] = it }
        prefs.remove(COACH_PLAN)
        prefs.remove(COACH_VMA)
        coach.plan?.let { prefs[COACH_PLAN] = TrainingPlans.encode(it) }
        coach.vma?.let { prefs[COACH_VMA] = CoachVmas.encode(it) }
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
            partners = PartnerCodec.decode(this[COACH_PARTNERS]),
            pairings = PairingCodec.decode(this[COACH_PAIRINGS]),
            plan = TrainingPlans.decode(this[COACH_PLAN]),
            vma = CoachVmas.decode(this[COACH_VMA]),
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
        val COACH_PARTNERS = stringPreferencesKey("coach_partners")
        val COACH_PAIRINGS = stringPreferencesKey("coach_pairings")
        val COACH_PLAN = stringPreferencesKey("coach_plan")
        val COACH_VMA = stringPreferencesKey("coach_vma")
    }
}
