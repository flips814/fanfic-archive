package org.fanficarchive.android.screens.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Matches the website's Pico Violet theme (#7540bf) and Atkinson-like UI type.
private val Violet = Color(0xFF7540BF)
private val VioletDark = Color(0xFF9B7AE8)
private val ChromeDark = Color(0xFF13171F)
private val Ao3 = Color(0xFF990000)
private val Ffnet = Color(0xFF2B3A8F)
private val Wattpad = Color(0xFFB93B00)
private val Tumblr = Color(0xFF001935)

val SiteAo3 = Ao3
val SiteFfnet = Ffnet
val SiteWattpad = Wattpad
val SiteTumblr = Tumblr
val PillComplete = Color(0xFF2E7D32)
val PillOngoing = Color(0xFFB26A00)
val JobDone = Color(0xFF2E7D32)
val JobFailed = Color(0xFFB71C1C)
val JobRunning = Color(0xFFF9A825)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Color(0xFF5B21B6),
    onSecondary = Color.White,
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1F1F24),
    surface = Color.White,
    onSurface = Color(0xFF1F1F24),
    surfaceVariant = Color(0xFFEDE7F6),
    onSurfaceVariant = Color(0xFF4A4458),
    outline = Color(0xFFCAC4D0),
    error = Color(0xFFB71C1C),
)

private val DarkColors = darkColorScheme(
    primary = VioletDark,
    onPrimary = Color(0xFF1A1030),
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF1A1030),
    background = ChromeDark,
    onBackground = Color(0xFFE7E3DA),
    surface = Color(0xFF1A1F2A),
    onSurface = Color(0xFFE7E3DA),
    surfaceVariant = Color(0xFF2A3140),
    onSurfaceVariant = Color(0xFFC8C2D4),
    outline = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

@Composable
fun FanficTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

fun siteBadgeColor(site: String?): Color = when {
    site.isNullOrBlank() -> Violet
    site.contains("archiveofourown", true) -> Ao3
    site.contains("fanfiction", true) || site.contains("fictionpress", true) -> Ffnet
    site.contains("wattpad", true) -> Wattpad
    site.contains("tumblr", true) -> Tumblr
    else -> Violet
}

fun siteLabel(site: String?): String = when {
    site.isNullOrBlank() -> "Unknown"
    site.contains("archiveofourown", true) -> "AO3"
    site.contains("fanfiction.net", true) -> "FF.net"
    site.contains("fictionpress", true) -> "FictionPress"
    site.contains("wattpad", true) -> "Wattpad"
    site.contains("tumblr", true) -> "Tumblr"
    else -> site.removePrefix("www.")
}

fun isCompleteStatus(status: String?): Boolean =
    status?.lowercase()?.startsWith("complete") == true
