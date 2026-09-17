package io.snailrun.data.voice

import android.content.Context
import io.snailrun.R
import io.snailrun.domain.voice.SpeechVocabulary
import java.util.Locale

/**
 * Supplies the announcement wording from string resources, so the voice follows the
 * phone's language. Numbers stay as digits — engines read "5" correctly in any
 * language; it is "5:12" that they mangle.
 */
class ResourceSpeechVocabulary(private val context: Context) : SpeechVocabulary {

    override fun kilometres(value: Double): String {
        val text = if (value == value.toInt().toDouble()) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
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
}
