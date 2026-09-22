package io.snailrun.data.voice

import android.content.Context
import android.content.res.Configuration
import io.snailrun.R
import io.snailrun.domain.voice.RunNotice
import io.snailrun.domain.voice.SpeechVocabulary
import java.util.Locale

/**
 * The one language snail run speaks aloud.
 *
 * English, on every phone, whatever the system language is set to. Not a simplification:
 * every announcement string in this app exists only in English, so following the phone's
 * locale meant a French phone picking a French voice and then handing it "average pace 5
 * minutes 12 seconds per kilometre" — an English sentence read with French phonemes,
 * which is neither language and is close to unintelligible at a run.
 *
 * Two things have to agree for the voice to work, and this constant is what makes them:
 * the words come from the English resources below, and [io.snailrun.data.voice
 * .AndroidVoiceAnnouncer] asks the engine for an English voice to read them.
 *
 * The day the app ships a `values-fr`, this becomes a real choice and both ends of it
 * move together. Until then there is only one right answer.
 */
val SPEECH_LOCALE: Locale = Locale.ENGLISH

/**
 * Supplies the announcement wording from string resources, in [SPEECH_LOCALE].
 *
 * Numbers stay as digits — engines read "5" correctly in any language; it is "5:12" that
 * they mangle.
 */
class ResourceSpeechVocabulary(context: Context) : SpeechVocabulary {

    /**
     * Resources forced to English, rather than the caller's context.
     *
     * Reading `values/` happens to give English today because that is the only folder
     * there is. Pinning it means a translation added later cannot silently start feeding
     * French words to an English voice.
     */
    private val context: Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(SPEECH_LOCALE) }
    )

    override fun kilometres(value: Double): String {
        val text = if (value == value.toInt().toDouble()) {
            value.toInt().toString()
        } else {
            // English, so the decimal separator is a point. A French "2,5" read by an
            // English voice comes out as "two comma five" on the literal engines.
            String.format(SPEECH_LOCALE, "%.1f", value)
        }
        return context.resources.getQuantityString(
            R.plurals.speech_kilometres, value.toInt().coerceAtLeast(1), text,
        )
    }

    override fun minutes(value: Int): String =
        context.resources.getQuantityString(R.plurals.speech_minutes, value, value)

    override fun seconds(value: Int): String =
        context.resources.getQuantityString(R.plurals.speech_seconds, value, value)

    override fun hours(value: Int): String =
        context.resources.getQuantityString(R.plurals.speech_hours, value, value)

    override val timeLabel: String get() = context.getString(R.string.speech_time)
    override val averagePaceLabel: String get() = context.getString(R.string.speech_average_pace)
    override val lastSplitLabel: String get() = context.getString(R.string.speech_last_split)
    override val perKilometre: String get() = context.getString(R.string.speech_per_kilometre)

    override fun repOf(index: Int, count: Int): String =
        context.getString(R.string.speech_rep_of, index, count)

    override fun countdown(seconds: Int): String =
        context.getString(R.string.speech_countdown, seconds)

    override val easeDown: String get() = context.getString(R.string.speech_ease_down)
    override val pickItUp: String get() = context.getString(R.string.speech_pick_it_up)
    override val sessionComplete: String
        get() = context.getString(R.string.speech_session_complete)
    override val forLabel: String get() = context.getString(R.string.speech_for)
    override val betweenLabel: String get() = context.getString(R.string.speech_between)
    override val andLabel: String get() = context.getString(R.string.speech_and)
    override val nextLabel: String get() = context.getString(R.string.speech_next)

    override fun notice(notice: RunNotice): String = context.getString(
        when (notice) {
            RunNotice.AutoPaused -> R.string.speech_auto_paused
            RunNotice.AutoResumed -> R.string.speech_auto_resumed
        }
    )
}
