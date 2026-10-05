package io.snailrun.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours the Material scheme has no slot for. */
data class SnailExtendedColors(
    val trace: Color,
    val traceMuted: Color,
    /** [PersonTone]s: the runner at 0, partners after. See [SnailTheme.person]. */
    val people: List<PersonTone> = PeopleLight,
)

private val LocalExtendedColors = staticCompositionLocalOf {
    SnailExtendedColors(trace = ColorSchemeTraceLight, traceMuted = Color.Gray)
}

object SnailTheme {
    val extended: SnailExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    /** The runner's own colour. */
    val you: PersonTone
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.people.first()

    /**
     * A partner's colour, read off their id so it never changes and nothing extra is
     * stored. Ids are handed out in order, so the first four partners never share one.
     */
    @Composable
    @ReadOnlyComposable
    fun person(partnerId: Int): PersonTone {
        val people = LocalExtendedColors.current.people
        val others = people.size - 1
        return people[1 + Math.floorMod(partnerId - 1, others)]
    }
}

/**
 * Dynamic colour is deliberately absent: it would swap Snail Green for whatever is on
 * the wallpaper and leave the app with no identity of its own.
 */
@Composable
fun SnailRunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) {
        SnailExtendedColors(trace = ColorSchemeTraceDark, traceMuted = Color(0xFF3A413C), people = PeopleDark)
    } else {
        SnailExtendedColors(trace = ColorSchemeTraceLight, traceMuted = Color(0xFFC9D0C9), people = PeopleLight)
    }

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = SnailTypography,
            shapes = SnailShapes,
            content = content,
        )
    }
}
