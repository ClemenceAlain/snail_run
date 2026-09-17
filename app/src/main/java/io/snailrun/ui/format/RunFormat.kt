package io.snailrun.ui.format

import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-screen formatting. Kilometres and min/km throughout.
 *
 * Distinct from the speech formatter on purpose: the screen wants "5:12", the voice
 * must never be given a colon to read.
 */
object RunFormat {

    /** "4.82" — two decimals, so the number does not jump width as it climbs. */
    fun distanceKm(meters: Double): String =
        String.format(Locale.getDefault(), "%.2f", meters / 1000.0)

    /** "24:17", or "1:04:17" once the run passes an hour. */
    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /** "5:02", or an em-dash placeholder when the runner is not moving. */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm <= 0 || secPerKm.isNaN() || secPerKm.isInfinite()) {
            return PACE_PLACEHOLDER
        }
        val total = secPerKm.roundToInt()
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    fun elevation(meters: Double): String =
        String.format(Locale.getDefault(), "%d", meters.roundToInt())

    const val PACE_PLACEHOLDER = "--:--"
}
