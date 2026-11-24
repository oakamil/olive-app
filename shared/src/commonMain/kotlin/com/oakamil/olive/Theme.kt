// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

val PureRed = Color(0xFFFF0000)
val DarkRed = Color(0xFF8B0000)
val PureBlack = Color(0xFF000000)
val DeepMaroon = Color(0xFF4A0000)

val CosmicNavyBackground = Color(0xFF0B101D)
val CosmicNavySurface = Color(0xFF131C2E)
val CosmicNavyContainer = Color(0xFF1A253B)
val CosmicNavyContainerHigh = Color(0xFF22304C)
val CosmicNavyContainerHighest = Color(0xFF2B3D5E)
val CosmicNavyBorder = Color(0xFF2D3E5B)
val CosmicNavyDivider = Color(0xFF1E2C42)
val SteelNavy = Color(0xFF384A68)
val TwilightSlate = Color(0xFF475569)
val SlateBlueLabel = Color(0xFF64748B)
val SlateGrayMuted = Color(0xFF94A3B8)
val PlatinumWhite = Color(0xFFE2E8F0)

val CelestialAmber = Color(0xFFF59E0B)
val SkyCyan = Color(0xFF7DD3FC)
val SoftLavender = Color(0xFFA78BFA)
val CoralStop = Color(0xFFF87171)

// Astronomical spectrum accents for daytime catalog & sky-map distinction
val LilacGalaxy = Color(0xFFA5B4FC)
val CyanNebula = Color(0xFF38BDF8)
val GoldCluster = Color(0xFFFBBF24)
val OchrePlanet = Color(0xFFFB923C)
val MintComet = Color(0xFF34D399)
val IceDoubleStar = Color(0xFF67E8F9)

val CosmicNavyPrimaryContainer = Color(0xFF2D2315)
val CosmicNavySecondaryContainer = Color(0xFF152A3C)

private const val BELOW_HORIZON_NIGHT_ALPHA = 0.85f
private const val BELOW_HORIZON_DAY_ALPHA = 0.75f
private const val SKY_MAP_OVERLAY_ALPHA = 0.5f

/** Extended semantic colors for sky-map rendering, HUD overlays, and catalog object categories. */
@Immutable
data class OliveExtendedColors(
    val actionBarBorder: Color,
    val mapOverlaySurface: Color,
    val hudCardBorder: Color,
    val hudStatsBorder: Color,
    val mapButtonDisabledSurface: Color,
    val mapButtonActiveSurface: Color,
    val mapButtonBorder: Color,
    val mapButtonDisabledBorder: Color,
    val mapButtonDisabledIcon: Color,
    val lowPowerCardSurface: Color,
    val lowPowerCardBorder: Color?,
    val numpadKeyBorder: Color,
    val subtleBorder: Color,
    val dialogDivider: Color,
    val ctaStop: Color,
    val drawerSelectedContainer: Color,
    val secondaryText: Color,
    val telemetryAccent: Color,
    val hudSecondaryStat: Color,
    val scrollbarThumb: Color,
    val skyBackground: Color,
    val starAboveHorizon: Color,
    val skyBelowHorizon: Color,
    val constellationLine: Color,
    val constellationLabel: Color,
    val horizonLine: Color,
    val cardinalLabel: Color,
    val zenithNadir: Color,
    val fovBoxSolved: Color,
    val fovBoxEstimate: Color,
    val alignReticle: Color,
    val targetMarker: Color,
    val targetGuideLine: Color,
    val selectedRing: Color,
    val catGalaxy: Color,
    val catNebula: Color,
    val catCluster: Color,
    val catSolarSystem: Color,
    val catCometAsteroid: Color,
    val catDoubleStar: Color,
    val catDarkNebula: Color,
    val catStar: Color
)

private val NightVisionExtendedColors = OliveExtendedColors(
    actionBarBorder = DarkRed.copy(alpha = 0.4f),
    mapOverlaySurface = PureBlack.copy(alpha = SKY_MAP_OVERLAY_ALPHA),
    hudCardBorder = DarkRed,
    hudStatsBorder = PureRed,
    mapButtonDisabledSurface = PureBlack.copy(alpha = SKY_MAP_OVERLAY_ALPHA),
    mapButtonActiveSurface = PureRed.copy(alpha = 0.25f),
    mapButtonBorder = DarkRed,
    mapButtonDisabledBorder = DarkRed.copy(alpha = 0.4f),
    mapButtonDisabledIcon = DarkRed.copy(alpha = 0.4f),
    lowPowerCardSurface = PureBlack,
    lowPowerCardBorder = null,
    numpadKeyBorder = PureRed,
    subtleBorder = PureRed.copy(alpha = 0.5f),
    dialogDivider = PureRed.copy(alpha = 0.2f),
    ctaStop = PureRed,
    drawerSelectedContainer = DeepMaroon,
    secondaryText = PureRed.copy(alpha = 0.7f),
    telemetryAccent = PureRed,
    hudSecondaryStat = DarkRed,
    scrollbarThumb = PureRed,
    skyBackground = PureBlack,
    starAboveHorizon = PureRed,
    skyBelowHorizon = DarkRed.copy(alpha = BELOW_HORIZON_NIGHT_ALPHA),
    constellationLine = DarkRed,
    constellationLabel = DarkRed.copy(alpha = 0.85f),
    horizonLine = DarkRed,
    cardinalLabel = PureRed,
    zenithNadir = DarkRed,
    fovBoxSolved = PureRed,
    fovBoxEstimate = DarkRed.copy(alpha = 0.7f),
    alignReticle = DarkRed,
    targetMarker = PureRed,
    targetGuideLine = PureRed.copy(alpha = 0.5f),
    selectedRing = PureRed,
    catGalaxy = PureRed,
    catNebula = PureRed,
    catCluster = PureRed,
    catSolarSystem = PureRed,
    catCometAsteroid = PureRed,
    catDoubleStar = PureRed,
    catDarkNebula = DarkRed,
    catStar = PureRed
)

private val DayVisionExtendedColors = OliveExtendedColors(
    actionBarBorder = CosmicNavyDivider,
    mapOverlaySurface = CosmicNavyContainer.copy(alpha = SKY_MAP_OVERLAY_ALPHA),
    hudCardBorder = CosmicNavyBorder,
    hudStatsBorder = SkyCyan.copy(alpha = 0.65f),
    mapButtonDisabledSurface = CosmicNavySurface.copy(alpha = SKY_MAP_OVERLAY_ALPHA),
    mapButtonActiveSurface = CelestialAmber.copy(alpha = 0.20f),
    mapButtonBorder = CosmicNavyBorder,
    mapButtonDisabledBorder = CosmicNavyBorder.copy(alpha = 0.4f),
    mapButtonDisabledIcon = SlateGrayMuted.copy(alpha = 0.45f),
    lowPowerCardSurface = CosmicNavySurface,
    lowPowerCardBorder = CosmicNavyBorder,
    numpadKeyBorder = CosmicNavyBorder,
    subtleBorder = CosmicNavyBorder,
    dialogDivider = CosmicNavyDivider,
    ctaStop = CoralStop,
    drawerSelectedContainer = CosmicNavyContainerHigh,
    secondaryText = SlateGrayMuted,
    telemetryAccent = SkyCyan,
    hudSecondaryStat = SkyCyan,
    scrollbarThumb = TwilightSlate,
    skyBackground = CosmicNavyBackground,
    starAboveHorizon = PlatinumWhite,
    skyBelowHorizon = TwilightSlate.copy(alpha = BELOW_HORIZON_DAY_ALPHA),
    constellationLine = SteelNavy,
    constellationLabel = SlateBlueLabel,
    horizonLine = CosmicNavyBorder,
    cardinalLabel = SkyCyan,
    zenithNadir = SkyCyan.copy(alpha = 0.85f),
    fovBoxSolved = CelestialAmber,
    fovBoxEstimate = SkyCyan.copy(alpha = 0.75f),
    alignReticle = SkyCyan,
    targetMarker = CelestialAmber,
    targetGuideLine = CelestialAmber.copy(alpha = 0.6f),
    selectedRing = CelestialAmber,
    catGalaxy = LilacGalaxy,
    catNebula = CyanNebula,
    catCluster = GoldCluster,
    catSolarSystem = OchrePlanet,
    catCometAsteroid = MintComet,
    catDoubleStar = IceDoubleStar,
    catDarkNebula = SlateBlueLabel,
    catStar = PlatinumWhite
)

private val NightVisionColorScheme = darkColorScheme(
    primary = PureRed,
    onPrimary = PureBlack,
    primaryContainer = DeepMaroon,
    onPrimaryContainer = PureRed,
    inversePrimary = DarkRed,
    background = PureBlack,
    onBackground = PureRed,
    surface = PureBlack,
    onSurface = PureRed,
    surfaceTint = PureRed,
    inverseSurface = DeepMaroon,
    inverseOnSurface = PureRed,
    surfaceDim = PureBlack,
    surfaceBright = PureBlack,
    surfaceContainerLowest = PureBlack,
    surfaceContainerLow = PureBlack,
    surfaceContainer = PureBlack,
    surfaceContainerHigh = PureBlack,
    surfaceContainerHighest = PureBlack,
    secondary = DarkRed,
    onSecondary = PureBlack,
    secondaryContainer = DeepMaroon,
    onSecondaryContainer = PureRed,
    tertiary = DarkRed,
    onTertiary = PureBlack,
    tertiaryContainer = DeepMaroon,
    onTertiaryContainer = PureRed,
    surfaceVariant = DeepMaroon,
    onSurfaceVariant = PureRed,
    outline = DarkRed,
    outlineVariant = DarkRed,
    scrim = PureBlack,
    error = PureRed,
    onError = PureBlack,
    errorContainer = DeepMaroon,
    onErrorContainer = PureRed
)

private val DayVisionColorScheme = darkColorScheme(
    primary = CelestialAmber,
    onPrimary = CosmicNavyBackground,
    primaryContainer = CosmicNavyPrimaryContainer,
    onPrimaryContainer = CelestialAmber,
    background = CosmicNavyBackground,
    onBackground = PlatinumWhite,
    surface = CosmicNavySurface,
    onSurface = PlatinumWhite,
    surfaceDim = CosmicNavyBackground,
    surfaceBright = CosmicNavyContainerHigh,
    surfaceContainerLowest = CosmicNavyBackground,
    surfaceContainerLow = CosmicNavySurface,
    surfaceContainer = CosmicNavyContainer,
    surfaceContainerHigh = CosmicNavyContainerHigh,
    surfaceContainerHighest = CosmicNavyContainerHighest,
    secondary = SkyCyan,
    onSecondary = CosmicNavyBackground,
    secondaryContainer = CosmicNavySecondaryContainer,
    onSecondaryContainer = SkyCyan,
    tertiary = SoftLavender,
    onTertiary = CosmicNavyBackground,
    surfaceVariant = CosmicNavyContainer,
    onSurfaceVariant = SlateGrayMuted,
    outline = CosmicNavyBorder,
    outlineVariant = CosmicNavyDivider,
    error = CoralStop,
    onError = CosmicNavyBackground,
    errorContainer = CosmicNavyContainer,
    onErrorContainer = CoralStop
)

enum class OliveTextSize(val key: String, val scale: Float) {
    SMALL("small", 0.85f),
    NORMAL("normal", 1.0f),
    LARGE("large", 1.15f),
    EXTRA_LARGE("extra_large", 1.30f);

    companion object {
        fun fromKey(key: String?): OliveTextSize =
            entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

val LocalOliveExtendedColors = staticCompositionLocalOf { NightVisionExtendedColors }
val LocalOliveTextScale = staticCompositionLocalOf { OliveTextSize.NORMAL.scale }
private val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Application Material3 + [OliveExtendedColors] theme.
 * Supports `"red"` (default Night Vision: pure red on pure black for dark adaptation) and
 * `"blue"` (Day Vision: cosmic navy surfaces with distinct spectral category accents).
 */
@Composable
fun OliveTheme(
    theme: String = "red",
    textScale: Float = OliveTextSize.NORMAL.scale,
    content: @Composable () -> Unit
) {
    val isDayVision = theme == "blue"
    val colorScheme = if (isDayVision) DayVisionColorScheme else NightVisionColorScheme
    val extendedColors = if (isDayVision) DayVisionExtendedColors else NightVisionExtendedColors
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalOliveExtendedColors provides extendedColors,
        LocalOliveTextScale provides textScale,
        LocalBaseDensity provides baseDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

/**
 * Applies [LocalOliveTextScale] relative to the root platform [Density] (idempotent across
 * nested dialogs on iOS and new dialog windows on Android, preserving Android 14+ non-linear
 * font scaling when `textScale == 1.0f`).
 */
@Composable
fun ProvideScaledText(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current ?: LocalDensity.current
    val textScale = LocalOliveTextScale.current
    val target = remember(base, textScale) {
        if (textScale == 1.0f) {
            base
        } else {
            Density(density = base.density, fontScale = base.fontScale * textScale)
        }
    }
    CompositionLocalProvider(LocalDensity provides target, content = content)
}

/**
 * Restores the root platform [Density] verbatim so compact chrome (icon-bar buttons,
 * fixed-height keyboard/numpad keys) stays unscaled inside [ProvideScaledText] subtrees.
 */
@Composable
fun ProvideUnscaledText(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current ?: LocalDensity.current
    CompositionLocalProvider(LocalDensity provides base, content = content)
}

