package io.snailrun.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Snail Green sits mid-luminance, so one hue family yields a dark tone that reads on
// white and a light tone that reads on near-black. Shell Coral is the only warm
// colour, reserved for records and for stopping a run.
// Contrast ratios in the comments are WCAG 2.x, computed against the paired surface.

val LightScheme = lightColorScheme(
    primary = Color(0xFF0A7C4F),              // white text 5.24:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F2E4),
    onPrimaryContainer = Color(0xFF06301F),   // 12.25:1
    secondary = Color(0xFFC53F22),            // white text 5.11:1
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE1D7),
    onSecondaryContainer = Color(0xFF3A0D00),
    tertiary = Color(0xFF8A5A00),             // white text 5.93:1
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFBF7),           // warm off-white, never pure #FFF
    onBackground = Color(0xFF16181A),         // 17.16:1
    surface = Color(0xFFFBFBF7),
    onSurface = Color(0xFF16181A),
    surfaceContainerLow = Color(0xFFF4F5F0),
    surfaceContainer = Color(0xFFF0F2EB),
    surfaceContainerHigh = Color(0xFFEAEDE4),
    surfaceVariant = Color(0xFFE6E9E2),
    onSurfaceVariant = Color(0xFF44504A),     // 8.12:1, safe down to 12sp
    outline = Color(0xFF6B756E),              // 4.61:1
    outlineVariant = Color(0xFFC9D0C9),       // hairlines only, never text
    error = Color(0xFFB3261E),                // white text 6.54:1
    onError = Color(0xFFFFFFFF),
)

val DarkScheme = darkColorScheme(
    primary = Color(0xFF4BE39B),              // on dark surface 11.36:1
    onPrimary = Color(0xFF00281A),            // on primary 9.68:1
    primaryContainer = Color(0xFF1E4634),
    onPrimaryContainer = Color(0xFFCFEFDF),   // 8.62:1
    secondary = Color(0xFFFF9478),            // 8.69:1
    onSecondary = Color(0xFF3A0D00),          // 7.89:1
    secondaryContainer = Color(0xFF6B2410),
    onSecondaryContainer = Color(0xFFFFE1D7),
    tertiary = Color(0xFFE8B75C),
    onTertiary = Color(0xFF3B2800),
    background = Color(0xFF101311),           // near-black with a faint green cast
    onBackground = Color(0xFFE8EAE6),         // 15.44:1
    surface = Color(0xFF101311),
    onSurface = Color(0xFFE8EAE6),
    surfaceContainerLow = Color(0xFF161A17),
    surfaceContainer = Color(0xFF1A1E1B),
    surfaceContainerHigh = Color(0xFF232824),
    surfaceVariant = Color(0xFF2B312D),
    onSurfaceVariant = Color(0xFFA9B3AC),     // 8.66:1
    outline = Color(0xFF8A948C),              // 5.96:1
    outlineVariant = Color(0xFF3A413C),
    error = Color(0xFFFFB4AB),                // 11.01:1
    onError = Color(0xFF690005),
)

/**
 * The route polyline and chart strokes. Graphics need 3:1, and the saturated
 * #1FA76E only reaches 2.97:1 on the light surface, so light mode uses a darker tone.
 */
val ColorSchemeTraceLight = Color(0xFF0A8F5C)  // 3.97:1
val ColorSchemeTraceDark = Color(0xFF4BE39B)   // 11.36:1
