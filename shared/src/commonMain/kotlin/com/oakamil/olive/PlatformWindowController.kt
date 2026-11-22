package com.oakamil.olive

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformWindowController {
    val isLandscape: Boolean
    fun getScreenBrightness(): Float
    fun setScreenBrightness(brightness: Float)
    fun restoreScreenBrightness()
    fun setPreferredRefreshRate(rate: Float)
    fun getDisplayRotationDegrees(): Int
    fun getCameraSensorOrientation(cameraId: String): Int
    fun setKeepScreenOn(keepOn: Boolean)
    fun getSystemBarsVerticalInsetPx(): Int = 0
}

val LocalPlatformWindowController = staticCompositionLocalOf<PlatformWindowController> {
    error("No PlatformWindowController provided")
}
