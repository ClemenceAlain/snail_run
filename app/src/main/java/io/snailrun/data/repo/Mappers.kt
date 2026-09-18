package io.snailrun.data.repo

import io.snailrun.data.db.BestEffortEntity
import io.snailrun.data.db.SplitEntity
import io.snailrun.data.db.TrackPointEntity
import io.snailrun.data.db.WorkoutSegmentEntity
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.WorkoutSegment
import io.snailrun.domain.model.BestEffort
import io.snailrun.domain.model.Split
import io.snailrun.domain.model.TrackPoint

fun TrackPoint.toEntity(runId: Long) = TrackPointEntity(
    runId = runId,
    seq = seq,
    segment = segment,
    timestampMs = timestampMs,
    lat = lat,
    lon = lon,
    elevationM = elevationM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    cumulativeDistanceM = cumulativeDistanceM,
)

fun TrackPointEntity.toDomain() = TrackPoint(
    seq = seq,
    segment = segment,
    timestampMs = timestampMs,
    lat = lat,
    lon = lon,
    elevationM = elevationM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    cumulativeDistanceM = cumulativeDistanceM,
)

fun Split.toEntity(runId: Long) = SplitEntity(
    runId = runId,
    splitIndex = index,
    distanceMeters = distanceMeters,
    durationMs = durationMs,
    paceSecPerKm = paceSecPerKm,
    elevationGainM = elevationGainM,
    isPartial = isPartial,
)

fun SplitEntity.toDomain() = Split(
    index = splitIndex,
    distanceMeters = distanceMeters,
    durationMs = durationMs,
    elevationGainM = elevationGainM,
    isPartial = isPartial,
)

fun BestEffort.toEntity(runId: Long) = BestEffortEntity(
    runId = runId,
    distanceMeters = distanceMeters,
    durationMs = durationMs,
    startSeq = startSeq,
    endSeq = endSeq,
    startOffsetMs = startOffsetMs,
)

fun WorkoutSegment.toEntity(runId: Long) = WorkoutSegmentEntity(
    runId = runId,
    segmentIndex = index,
    label = label,
    kind = kind.name,
    targetMs = targetMs,
    targetM = targetM,
    paceLowSecPerKm = paceSecPerKm?.start,
    paceHighSecPerKm = paceSecPerKm?.endInclusive,
    repIndex = repIndex,
    repCount = repCount,
)

/**
 * An unrecognised kind falls back to work rather than throwing.
 *
 * The only way to meet one is to open a database written by a later build, which the
 * backup restore already refuses — but a crash on read would lose the whole run's screen
 * over a label, where guessing costs a slightly wrong colour.
 */
fun WorkoutSegmentEntity.toDomain() = WorkoutSegment(
    index = segmentIndex,
    label = label,
    kind = runCatching { SegmentKind.valueOf(kind) }.getOrDefault(SegmentKind.Work),
    targetMs = targetMs,
    targetM = targetM,
    paceSecPerKm = if (paceLowSecPerKm != null && paceHighSecPerKm != null) {
        paceLowSecPerKm..paceHighSecPerKm
    } else {
        null
    },
    repIndex = repIndex,
    repCount = repCount,
)
