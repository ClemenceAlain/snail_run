package io.snailrun.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
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

        val listener = LocationListenerCompat { location -> trySend(location.toRawFix()) }

        // The compat request, because the app runs on Android 9: from Android 12 it
        // becomes the platform request below; before, the legacy time-and-distance call.
        // The interval and the zero distance carry over either way, which is what matters.
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs)
            // Never let the framework gate on distance: auto-pause and the live pace
            // both need to see the fixes that show the runner has stopped.
            .setMinUpdateDistanceMeters(0f)
            // No batching: it saves power but freezes the screen and delays the voice.
            .setMaxUpdateDelayMillis(0L)
            .build()

        LocationManagerCompat.requestLocationUpdates(
            manager,
            LocationManager.GPS_PROVIDER,
            request,
            Runnable::run,
            listener,
        )

        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }
}

/**
 * Age is derived from `elapsedRealtimeNanos` rather than
 * `getElapsedRealtimeAgeMillis()`, which needs API 33 while this app supports 28.
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
    isMock = LocationCompat.isMock(this),
)
