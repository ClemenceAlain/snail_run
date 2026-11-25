package io.snailrun.data.sound

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.snailrun.data.haptics.Buzz
import io.snailrun.data.haptics.buzz
import io.snailrun.domain.coach.StrengthCue
import io.snailrun.domain.coach.WorkoutCue
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * High-pitched pips at every change of step.
 *
 * The voice says what is happening, the buzz gets through a pocket, and the pips cover
 * the gap between them: a phone speaker with no earphones, in wind or traffic, where a
 * spoken "jog" is lost but a 2–3 kHz pip still carries. The pips tell the steps apart by
 * direction — rising into work, falling into a recovery — so they need no words.
 */
interface Beeper {
    /** Plays the pattern and returns how long it lasts, in ms. 0 when nothing plays. */
    fun beep(buzz: Buzz): Long

    /** One short pip per count of a 3-2-1. */
    fun beepCountdown(): Long
}

/** The pips a running cue deserves. Off-pace nudges stay silent, as they do in the leg. */
fun Beeper.beep(cue: WorkoutCue): Long = when (cue) {
    is WorkoutCue.Countdown -> beepCountdown()
    else -> cue.buzz()?.let(::beep) ?: 0
}

/** The same, for a session on the floor. */
fun Beeper.beep(cue: StrengthCue): Long = when (cue) {
    is StrengthCue.Countdown -> beepCountdown()
    else -> cue.buzz()?.let(::beep) ?: 0
}

/** A pip: a pitch held for a time. */
data class Tone(val hz: Int, val ms: Int)

/**
 * The patterns and the samples, apart from the speaker so they can be tested.
 */
object BeepPatterns {
    const val SAMPLE_RATE = 44_100
    const val GAP_MS = 60
    private const val FADE_MS = 5
    private const val AMPLITUDE = 0.8

    /** Three rising pips: something hard starts now. */
    val WORK = listOf(Tone(1_800, 90), Tone(2_400, 90), Tone(3_000, 90))

    /** Two falling pips: ease off. */
    val EASE = listOf(Tone(2_400, 120), Tone(1_600, 120))

    /** One long pip: that was the last of it. */
    val DONE = listOf(Tone(2_400, 600))

    val COUNT = listOf(Tone(2_000, 60))

    fun of(buzz: Buzz): List<Tone> = when (buzz) {
        Buzz.Work -> WORK
        Buzz.Ease -> EASE
        Buzz.Done -> DONE
    }

    fun durationMs(tones: List<Tone>): Long =
        tones.sumOf { it.ms.toLong() } + GAP_MS.toLong() * (tones.size - 1).coerceAtLeast(0)

    /** 16-bit mono PCM, each pip faded in and out so it starts and stops without a click. */
    fun pcm(tones: List<Tone>, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val gap = samples(GAP_MS, sampleRate)
        val fade = samples(FADE_MS, sampleRate)
        val out = ArrayList<Short>()
        tones.forEachIndexed { index, tone ->
            if (index > 0) repeat(gap) { out += 0 }
            val n = samples(tone.ms, sampleRate)
            for (i in 0 until n) {
                val envelope = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                val value = AMPLITUDE * envelope * sin(2 * PI * tone.hz * i / sampleRate)
                out += (value * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private fun samples(ms: Int, sampleRate: Int) = ms * sampleRate / 1000
}

class AndroidBeeper(
    context: Context,
    enabled: Flow<Boolean>,
) : Beeper {

    @Volatile
    private var on = true

    init {
        enabled.onEach { on = it }.launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The same route and volume as the voice, so a headset hears both. */
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()

    private val cache = HashMap<List<Tone>, ShortArray>()
    private var playing: AudioTrack? = null

    override fun beep(buzz: Buzz): Long = play(BeepPatterns.of(buzz))

    override fun beepCountdown(): Long = play(BeepPatterns.COUNT)

    /** Any failure is silence: a pip is never worth disturbing the recording for. */
    @Synchronized
    private fun play(tones: List<Tone>): Long {
        if (!on) return 0
        return runCatching {
            val pcm = cache.getOrPut(tones) { BeepPatterns.pcm(tones) }
            release()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(BeepPatterns.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            audioManager?.requestAudioFocus(focusRequest)
            track.play()
            playing = track
            val duration = BeepPatterns.durationMs(tones)
            main.postDelayed({ finished(track) }, duration + RELEASE_MARGIN_MS)
            duration
        }.getOrElse {
            release()
            0
        }
    }

    @Synchronized
    private fun finished(track: AudioTrack) {
        if (playing === track) release()
    }

    private fun release() {
        playing?.let { runCatching { it.stop(); it.release() } }
        playing = null
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    private companion object {
        const val RELEASE_MARGIN_MS = 200L
    }
}
