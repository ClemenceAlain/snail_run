package io.snailrun.data.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.snailrun.domain.coach.SegmentKind
import io.snailrun.domain.coach.StrengthCue
import io.snailrun.domain.coach.StrengthStageKind
import io.snailrun.domain.coach.WorkoutCue

/**
 * What a buzz means, rather than what fires it.
 *
 * Three patterns, because three is what a leg can tell apart without being taught. The
 * enum exists rather than the patterns being chosen from a cue because there are now two
 * kinds of session — a run and a floor session — and they want the same three signals
 * for different reasons. Matching on both cue types in here would put the guided-run
 * rules and the strength rules in the vibrator.
 */
enum class Buzz {
    /** Something hard starts now. */
    Work,

    /** Something easy starts now: a jog, a rest between sets. */
    Ease,

    /** That was the last of it. */
    Done,
}

/**
 * A buzz at every change of step.
 *
 * Voice alone fails exactly when a session needs it most: phone in a pocket, no
 * headphones, wind, traffic — or, on the floor, face down on the mat while you are upside
 * down in a plank. A pattern in the leg gets through all of that, and it does not need the
 * runner to have an opinion about text-to-speech engines.
 */
interface Haptics {
    fun buzz(buzz: Buzz)
}

/**
 * The buzz a running cue deserves.
 *
 * Here rather than in [Haptics] so the vibrator knows nothing about sessions. The
 * countdown is spoken and the nudge is advisory: a buzz for either would make the
 * leg-level channel noisy, and then the two that matter stop registering as anything.
 */
fun WorkoutCue.buzz(): Buzz? = when (this) {
    is WorkoutCue.StepStart -> if (segment.kind == SegmentKind.Work) Buzz.Work else Buzz.Ease
    WorkoutCue.Finished -> Buzz.Done
    is WorkoutCue.Countdown, is WorkoutCue.OffPace -> null
}

/** The same, for a session on the floor. */
fun StrengthCue.buzz(): Buzz? = when (this) {
    is StrengthCue.StageStart ->
        if (stage.kind == StrengthStageKind.Work) Buzz.Work else Buzz.Ease
    StrengthCue.Finished -> Buzz.Done
    is StrengthCue.Countdown -> null
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

    override fun buzz(buzz: Buzz) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val timings = when (buzz) {
            Buzz.Work -> WORK
            Buzz.Ease -> EASE
            Buzz.Done -> FINISHED
        }
        device.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private companion object {
        /** Wait, then buzz: one long pulse into a hard step. */
        val WORK = longArrayOf(0, 400)

        /** Two short ones into a recovery. */
        val EASE = longArrayOf(0, 120, 120, 120)

        /** And a pair of long ones at the end of the session. */
        val FINISHED = longArrayOf(0, 350, 200, 350)
    }
}
