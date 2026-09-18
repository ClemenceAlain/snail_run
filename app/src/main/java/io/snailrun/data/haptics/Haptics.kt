package io.snailrun.data.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.WorkoutCue

/**
 * A buzz at every change of step.
 *
 * Voice alone fails exactly when a session needs it most: phone in a pocket, no
 * headphones, wind, traffic. A pattern in the leg gets through all of that, and it does
 * not need the runner to have an opinion about text-to-speech engines.
 *
 * The patterns differ by what is starting, because the one thing worth knowing without
 * looking is whether the next three minutes are hard or easy.
 */
interface Haptics {
    fun cue(cue: WorkoutCue)
}

class AndroidHaptics(private val context: Context) : Haptics {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    override fun cue(cue: WorkoutCue) {
        val pattern = when (cue) {
            is WorkoutCue.StepStart ->
                if (cue.segment.kind == SegmentKind.Work) WORK else RECOVER
            WorkoutCue.Finished -> FINISHED
            // The countdown is spoken and the nudge is advisory. A buzz for either would
            // make the leg-level channel noisy, and then the two that matter stop
            // registering as anything.
            is WorkoutCue.Countdown, is WorkoutCue.OffPace -> return
        }
        buzz(pattern)
    }

    private fun buzz(timings: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        device.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private companion object {
        /** Wait, then buzz: one long pulse into a hard step. */
        val WORK = longArrayOf(0, 400)

        /** Two short ones into a recovery. */
        val RECOVER = longArrayOf(0, 120, 120, 120)

        /** And a pair of long ones at the end of the session. */
        val FINISHED = longArrayOf(0, 350, 200, 350)
    }
}
