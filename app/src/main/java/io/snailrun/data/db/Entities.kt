package io.snailrun.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "runs",
    indices = [Index("startedAtEpochMs"), Index("localDate"), Index("distanceMeters")],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "RUN" today. Present from the first schema so a walk or a ride costs no migration. */
    val activityType: String = "RUN",
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    /** Recorded so a run logged abroad still groups under the day it happened. */
    val timeZoneId: String,
    /** "2026-09-17". Denormalised: calendar and month grouping become plain string ranges. */
    val localDate: String,
    val distanceMeters: Double,
    val elapsedTimeMs: Long,
    val movingTimeMs: Long,
    val avgPaceSecPerKm: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val pointCount: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val title: String?,
    val note: String?,
    val source: String,
    /** RECORDING while live. Anything left RECORDING at startup is an interrupted run. */
    val status: String,
    val distanceMilestonesAnnounced: Int = 0,
    val lastTimeAnnouncedActiveMs: Long = 0,
    val gpxExportedUri: String? = null,
    /**
     * Which version of the position filter the stored figures were derived with.
     * Zero means "before the filter existed"; anything behind the current version is
     * re-derived from the raw track on next launch.
     */
    val smootherVersion: Int = 0,
    /**
     * The session this run was guided through, or null — which is most runs.
     *
     * The prescription itself is in `workout_segments`; this is here so a row knows it
     * had one without a join, and so the history list can badge it.
     */
    val workoutType: String? = null,
    /**
     * Active-duration marks where the runner pressed Next, comma-separated.
     *
     * The only part of a guided run that cannot be worked out again from the track. Where
     * each segment ended is arithmetic; the runner deciding they were done with one is
     * not. Almost always null.
     */
    val workoutAdvancesActiveMs: String? = null,
)

@Entity(
    tableName = "track_points",
    primaryKeys = ["runId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackPointEntity(
    val runId: Long,
    val seq: Int,
    /** Increments on every pause, so no segment gap is ever drawn as a straight line. */
    val segment: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val accuracyM: Float?,
    val speedMps: Float?,
    /** Monotonic, written at insert time: splits and records become a linear scan. */
    val cumulativeDistanceM: Double,
)

@Entity(
    tableName = "splits",
    primaryKeys = ["runId", "splitIndex"],
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SplitEntity(
    val runId: Long,
    val splitIndex: Int,
    val distanceMeters: Double,
    val durationMs: Long,
    val paceSecPerKm: Double,
    val elevationGainM: Double,
    val isPartial: Boolean,
)

@Entity(
    tableName = "best_efforts",
    primaryKeys = ["runId", "distanceMeters"],
    indices = [Index("distanceMeters", "durationMs")],
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BestEffortEntity(
    val runId: Long,
    val distanceMeters: Int,
    val durationMs: Long,
    val startSeq: Int,
    val endSeq: Int,
    val startOffsetMs: Long,
)

/**
 * One segment of the session a run was guided through, as it was prescribed.
 *
 * Written once when the run starts, and never touched again. It has to be stored rather
 * than recomputed from the coach: the plan is rebuilt from the history every time it is
 * shown, so by next week the session this run was is not one the coach would still write.
 */
@Entity(
    tableName = "workout_segments",
    primaryKeys = ["runId", "segmentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorkoutSegmentEntity(
    val runId: Long,
    val segmentIndex: Int,
    val label: String,
    val kind: String,
    /** Exactly one of these is set: a segment ends on a time or on a distance. */
    val targetMs: Long?,
    val targetM: Double?,
    val paceLowSecPerKm: Double?,
    val paceHighSecPerKm: Double?,
    val repIndex: Int?,
    val repCount: Int?,
)
