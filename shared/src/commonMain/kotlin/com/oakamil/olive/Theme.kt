// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Night vision theme colors
val PureRed = Color(0xFFFF0000)
val DarkRed = Color(0xFF8B0000)
val PureBlack = Color(0xFF000000)
val DeepMaroon = Color(0xFF4A1010)

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

@Composable
fun NightVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightVisionColorScheme,
        content = content
    )
}
