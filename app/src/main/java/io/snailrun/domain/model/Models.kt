package io.snailrun.domain.model

/** A coordinate. No Android types anywhere in `domain`, by rule. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * A location update as it arrives, before filtering. Mirrors the fields an
 * `android.location.Location` carries, so the platform mapper is a straight copy and
 * every decision made about a fix can be reproduced in a JVM test.
 */
data class RawFix(
    val epochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    /** `Location.getElapsedRealtimeAgeMillis()` — how stale the fix was on arrival. */
    val ageMs: Long = 0,
    val isMock: Boolean = false,
)

/** A fix that passed the filter and belongs to the track. */
data class TrackPoint(
    val seq: Int,
    val segment: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    /** Monotonic. Makes splits and best efforts a linear scan instead of a re-walk. */
    val cumulativeDistanceM: Double = 0.0,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

data class Split(
    val index: Int,
    val distanceMeters: Double,
    val durationMs: Long,
    val elevationGainM: Double,
    val isPartial: Boolean,
) {
    val paceSecPerKm: Double
        get() = if (distanceMeters <= 0.0) 0.0 else durationMs / 1000.0 / (distanceMeters / 1000.0)
}

data class BestEffort(
    val distanceMeters: Int,
    val durationMs: Long,
    val startSeq: Int,
    val endSeq: Int,
    val startOffsetMs: Long,
)

enum class RunStatus { RECORDING, PAUSED_MANUAL, PAUSED_AUTO, COMPLETE }

enum class GpsQuality { NO_FIX, POOR, OK, GOOD }

enum class ActivityType { RUN }
