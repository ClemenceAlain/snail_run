package io.snailrun.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.snailrun.R

/**
 * Inter, bundled as subset TTFs.
 *
 * Not `ui-text-google-fonts`: downloadable fonts fetch over the network, and this app
 * has no INTERNET permission. Not the system default either — whether a given build of
 * Roboto exposes tabular figures varies, and the timer is exactly where a missing
 * `tnum` shows up, as digits changing width every second.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Fixed advance width per digit. Verified present in the bundled subset. */
private const val TABULAR = "tnum"

object SnailType {

    /** The live elapsed time: has to be readable at arm's length, mid-stride. */
    val metricHero = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 72.sp,
        lineHeight = 76.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TABULAR,
    )

    val metricLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR,
    )

    val metricSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULAR,
    )

    /** The "DISTANCE" / "PACE" captions that sit under each number. */
    val metricCaption = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    )
}

// 17sp body rather than Material's 16 is the single change that most makes a Compose
// app feel like an Apple one.
val SnailTypography = Typography(
    displayLarge = SnailType.metricHero,
    displayMedium = SnailType.metricLarge,
    headlineMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = SnailType.metricCaption,
)
