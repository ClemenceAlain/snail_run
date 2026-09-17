package io.snailrun.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import io.snailrun.domain.model.RawFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where fixes come from. An interface so a recorded trace can drive the whole app. */
interface LocationSource {
    fun fixes(intervalMs: Long = 1_000L): Flow<RawFix>
    fun isReady(): Boolean
}

/**
 * The platform GNSS provider, used directly.
 *
 * Not `play-services-location`: the phone runs LineageOS and may have no Play Services
 * at all, and the fused provider mixes in the network provider, which resolves Wi-Fi
 * and cell observations against Google's servers. `GPS_PROVIDER` talks to the chip.
 */
class PlatformLocationSource(context: Context) : LocationSource {

    private val manager = context.getSystemService(LocationManager::class.java)

    override fun isReady(): Boolean =
        manager != null && manager.isLocationEnabled &&
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun fixes(intervalMs: Long): Flow<RawFix> = callbackFlow {
        val manager = manager ?: run { close(); return@callbackFlow }

        val listener = LocationListener { location -> trySend(location.toRawFix()) }

        // minSdk 31, so the modern builder and the Executor overload are available
        // unconditionally: no LocationRequestCompat, no reflection.
        val request = android.location.LocationRequest.Builder(intervalMs)
            .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs)
            // Never let the framework gate on distance: auto-pause and the live pace
            // both need to see the fixes that show the runner has stopped.
            .setMinUpdateDistanceMeters(0f)
            // No batching: it saves power but freezes the screen and delays the voice.
            .setMaxUpdateDelayMillis(0L)
            .build()

        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            request,
            Runnable::run,
            listener,
        )

        awaitClose { manager.removeUpdates(listener) }
    }
}

/**
 * Age is derived from `elapsedRealtimeNanos` rather than
 * `getElapsedRealtimeAgeMillis()`, which needs API 33 while this app supports 31.
 * Same number, monotonic clock, no version branch.
 */
fun Location.toRawFix(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): RawFix = RawFix(
    epochMs = time,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    altitudeM = if (hasAltitude()) altitude else null,
    verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    ageMs = ((nowNanos - elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0),
    isMock = isMock,
)
