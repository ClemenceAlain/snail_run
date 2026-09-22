package io.snailrun.ui.format

import java.util.Locale

/**
 * The language every date, month and number on screen is written in.
 *
 * English, on every phone. Not an oversight and not a stopgap: every string in this app
 * is English, so a French phone was rendering "mar. 23 sept." under a card headed
 * "Tempo", and "6,3 km" beside "Threshold". Half-translating an interface is worse than
 * not translating it — the reader gets two languages and has to parse both.
 *
 * This is the on-screen twin of [io.snailrun.data.voice.SPEECH_LOCALE], which fixed the
 * same bug in the voice. Both should move together on the day the app ships a second
 * language, and neither before.
 *
 * **Not** what the calendar starts its week on. That is
 * `WeekFields.of(Locale.getDefault()).firstDayOfWeek`, and it stays on the system locale
 * deliberately: which day a week begins on is a regional convention rather than a
 * language, a French-speaking reader in Paris and one in Montreal disagree about it, and
 * a grid starting on the wrong day is misread at a glance rather than noticed.
 */
val UiLocale: Locale = Locale.ENGLISH
