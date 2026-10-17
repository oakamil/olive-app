package com.oakamil.olive

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformCapabilities {
    val isApple: Boolean
    val supportsUsbCamera: Boolean
    val supportsPip: Boolean
    val supportsLocalhostTelescope: Boolean
    val supportsPreferredRefreshRate: Boolean
    val platformName: String
}

object AndroidPlatformCapabilities : PlatformCapabilities {
    override val isApple: Boolean = false
    override val supportsUsbCamera: Boolean = true
    override val supportsPip: Boolean = true
    override val supportsLocalhostTelescope: Boolean = true
    override val supportsPreferredRefreshRate: Boolean = true
    override val platformName: String = "Android"
}

val LocalPlatformCapabilities = staticCompositionLocalOf<PlatformCapabilities> {
    AndroidPlatformCapabilities
}
