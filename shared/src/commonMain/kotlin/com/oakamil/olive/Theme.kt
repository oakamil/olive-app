// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val PureRed = Color(0xFFFF0000)
val DarkRed = Color(0xFF8B0000)
val PureBlack = Color(0xFF000000)
val DeepMaroon = Color(0xFF4A1010)

val LightSlate = Color(0xFFB0B8C4)
val MediumGray = Color(0xFF808080)
val DarkNavy = Color(0xFF080D1A)
val MidnightNavy = Color(0xFF14213D)
val SteelNavy = Color(0xFF384A68)

private val NightVisionColorScheme = darkColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    background = PureBlack,
    onBackground = PureRed,
    surface = PureBlack,
    onSurface = PureRed,
    secondary = DarkRed,
    onSecondary = PureBlack,
    tertiary = DarkRed,
    onTertiary = PureBlack,
    surfaceVariant = DeepMaroon,
    onSurfaceVariant = PureRed,
    outline = DarkRed,
    outlineVariant = DarkRed
)

private val DayVisionColorScheme = darkColorScheme(
    primary = LightSlate,
    onPrimary = DarkNavy,
    background = DarkNavy,
    onBackground = LightSlate,
    surface = DarkNavy,
    onSurface = LightSlate,
    secondary = MediumGray,
    onSecondary = DarkNavy,
    tertiary = MediumGray,
    onTertiary = DarkNavy,
    surfaceVariant = MidnightNavy,
    onSurfaceVariant = LightSlate,
    outline = SteelNavy,
    outlineVariant = SteelNavy
)

val LocalOliveColors = staticCompositionLocalOf { NightVisionColorScheme }

@Composable
fun OliveTheme(
    theme: String = "red",
    content: @Composable () -> Unit
) {
    val isDayVision = theme == "blue"
    val colorScheme = if (isDayVision) DayVisionColorScheme else NightVisionColorScheme
    CompositionLocalProvider(LocalOliveColors provides colorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

