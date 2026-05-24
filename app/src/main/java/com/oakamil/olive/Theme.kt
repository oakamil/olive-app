package com.oakamil.olive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Night vision theme colors
val PureRed = Color(0xFFFF0000)
val PureBlack = Color(0xFF000000)

private val NightVisionColorScheme = darkColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    background = PureBlack,
    onBackground = PureRed,
    surface = PureBlack,
    onSurface = PureRed,
    secondary = PureRed,
    onSecondary = PureBlack,
    tertiary = PureRed,
    onTertiary = PureBlack,
    surfaceVariant = PureBlack,
    onSurfaceVariant = PureRed,
    outline = PureRed
)

@Composable
fun NightVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightVisionColorScheme,
        content = content
    )
}
