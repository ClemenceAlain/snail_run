package io.snailrun.data.location

import io.snailrun.domain.demo.DemoRoute
import io.snailrun.domain.demo.DemoRunProfile
import io.snailrun.domain.model.RawFix
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fixes with no GPS chip behind them, for trying the app indoors.
 *
 * The trace comes from [DemoRoute]; this class only decides when to hand each fix over.
 * Fix timestamps stay one second apart whatever [speedFactor] is, because every metric
 * in the app is derived from them: a run played at 30x is a real hour of running, it
 * just arrives in two minutes. Only the wall clock between emissions is compressed.
 */
class DemoLocationSource(
    private val speedFactor: () -> Int,
    private val profile: DemoRunProfile = DemoRunProfile(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LocationSource {

    /** Nothing to wait for, so the record screen never shows "Location off". */
    override fun isReady(): Boolean = true

    override fun fixes(intervalMs: Long): Flow<RawFix> = flow {
        val factor = speedFactor().coerceIn(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR)
        // A coroutine delay below about ten milliseconds costs more in scheduling than
        // it buys in realism, so past that the wait is shared across a small batch.
        val perTick = ceil(factor * MIN_TICK_MS / intervalMs.toDouble()).toInt().coerceAtLeast(1)
        val tickMs = intervalMs * perTick / factor

        var sent = 0
        DemoRoute.fixes(profile, startEpochMs = nowMs()).forEach { fix ->
            emit(fix)
            sent++
            if (sent % perTick == 0) delay(tickMs)
        }
    }

    private companion object {
        const val MIN_TICK_MS = 10L
        const val MIN_SPEED_FACTOR = 1
        const val MAX_SPEED_FACTOR = 120
    }
}
