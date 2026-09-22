package io.snailrun.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.snailrun.AppContainer
import io.snailrun.data.haptics.Haptics
import io.snailrun.data.haptics.buzz
import io.snailrun.data.voice.VoiceAnnouncer
import io.snailrun.domain.coach.StrengthCue
import io.snailrun.domain.coach.StrengthCursor
import io.snailrun.domain.coach.StrengthProgress
import io.snailrun.domain.coach.StrengthSession
import io.snailrun.domain.coach.Workout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StrengthUiState(
    val workout: Workout? = null,
    val progress: StrengthProgress? = null,
    val running: Boolean = false,
    /** Set once the last stage is done, so the screen can say so and offer the way out. */
    val complete: Boolean = false,
)

/**
 * Drives a session on the floor.
 *
 * The one place in this app that owns a clock, and worth saying why, because everything
 * else deliberately does not: a run is driven by GPS fixes, which arrive about once a
 * second and are the truth about how far and how long. A strength session has no fixes.
 * Nothing observable happens while somebody holds a plank, so a timer is not a shortcut
 * here — it is the only instrument there is.
 *
 * What it does *not* do is record anything. No location is read, no run row is written,
 * nothing reaches the history or the coach's volume arithmetic. A strength session is
 * twenty minutes on a mat: there is no distance to measure, no pace to report, and a
 * 0 km entry in the runs list would be a lie about a real piece of training.
 */
class StrengthViewModel(
    private val voice: VoiceAnnouncer,
    private val haptics: Haptics,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _ui = MutableStateFlow(StrengthUiState())
    val ui: StateFlow<StrengthUiState> = _ui.asStateFlow()

    private var session: StrengthSession? = null
    private var cursor = StrengthCursor()

    /** Milliseconds of session that have actually elapsed, paused time excluded. */
    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    private val elapsed: Long
        get() = accumulatedMs + (runningSince?.let { now() - it } ?: 0L)

    fun load(workout: Workout) {
        if (_ui.value.workout == workout) return
        session = StrengthSession(StrengthSession.stages(workout))
        cursor = StrengthCursor()
        accumulatedMs = 0L
        runningSince = null
        _ui.value = StrengthUiState(
            workout = workout,
            progress = session?.progressOf(cursor, 0L),
        )
    }

    fun start() {
        if (_ui.value.running || _ui.value.complete) return
        runningSince = now()
        _ui.value = _ui.value.copy(running = true)
        // A tick rather than a frame loop: the finest thing on screen is a whole second,
        // and four ticks a second is enough for the countdown to land on time without
        // waking the CPU for nothing.
        viewModelScope.launch {
            while (isActive && _ui.value.running) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun pause() {
        if (!_ui.value.running) return
        accumulatedMs = elapsed
        runningSince = null
        _ui.value = _ui.value.copy(running = false)
    }

    /**
     * The runner says this set is done.
     *
     * The only way a counted set ever ends — see [StrengthSession]. On a held set it is
     * "I have had enough of this plank", which is a decision they are entitled to.
     */
    fun next() {
        val active = session ?: return
        if (_ui.value.complete) return
        cursor = active.advance(elapsed, cursor)
        tick()
    }

    private fun tick() {
        val active = session ?: return
        val (progress, cues, next) = active.evaluate(elapsed, cursor)
        cursor = next
        cues.forEach { cue ->
            voice.speak(cue)
            cue.buzz()?.let(haptics::buzz)
        }
        if (progress.complete && !_ui.value.complete) {
            accumulatedMs = elapsed
            runningSince = null
        }
        _ui.value = _ui.value.copy(
            progress = progress,
            complete = progress.complete,
            running = _ui.value.running && !progress.complete,
        )
    }

    private companion object {
        const val TICK_MS = 250L
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StrengthViewModel(container.voiceAnnouncer, container.haptics) as T
    }
}
