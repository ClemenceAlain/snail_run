package io.snailrun.data.repo

import io.snailrun.data.db.BestEffortEntity
import io.snailrun.data.db.SplitEntity
import io.snailrun.data.db.TrackPointEntity
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
