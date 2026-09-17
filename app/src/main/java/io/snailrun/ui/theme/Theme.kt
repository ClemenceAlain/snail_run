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
)

private val LocalExtendedColors = staticCompositionLocalOf {
    SnailExtendedColors(trace = ColorSchemeTraceLight, traceMuted = Color.Gray)
}

object SnailTheme {
    val extended: SnailExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
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
        SnailExtendedColors(trace = ColorSchemeTraceDark, traceMuted = Color(0xFF3A413C))
    } else {
        SnailExtendedColors(trace = ColorSchemeTraceLight, traceMuted = Color(0xFFC9D0C9))
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
