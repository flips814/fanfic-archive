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

private val DisplayFont = FontFamily.Serif
private val BodyFont = FontFamily.SansSerif

private val Forest = Color(0xFF1B3A2F)
private val Leaf = Color(0xFF3D6B54)
private val Parchment = Color(0xFFF4EFE6)
private val Ink = Color(0xFF1A1A1A)
private val SoftInk = Color(0xFF3A3A3A)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Parchment,
    secondary = Leaf,
    onSecondary = Parchment,
    background = Parchment,
    onBackground = Ink,
    surface = Color(0xFFFAF7F0),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E1D4),
    onSurfaceVariant = SoftInk,
    error = Color(0xFF8B2E2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FBF9F),
    onPrimary = Color(0xFF0E1F18),
    secondary = Color(0xFFA8C4B4),
    onSecondary = Color(0xFF0E1F18),
    background = Color(0xFF121A16),
    onBackground = Color(0xFFE8EFEA),
    surface = Color(0xFF1A2420),
    onSurface = Color(0xFFE8EFEA),
    surfaceVariant = Color(0xFF2A3832),
    onSurfaceVariant = Color(0xFFC5D2CB),
    error = Color(0xFFE08A8A),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
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
