// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformCapabilities {
    val isApple: Boolean
    val supportsUsbCamera: Boolean
    val supportsPip: Boolean
    val defaultFocus: Float
    val focusRange: ClosedFloatingPointRange<Float>
    val exposureRange: ClosedFloatingPointRange<Float>
}

object AndroidPlatformCapabilities : PlatformCapabilities {
    override val isApple: Boolean = false
    override val supportsUsbCamera: Boolean = true
    override val supportsPip: Boolean = true
    override val defaultFocus: Float = 0.0f // 0.0 diopters = optical infinity
    override val focusRange: ClosedFloatingPointRange<Float> = 0.0f..10.0f // diopters (0.0=inf, 10.0=10cm)
    override val exposureRange: ClosedFloatingPointRange<Float> = 16667f..5000000f // 16.7ms to 5000ms
}

object ApplePlatformCapabilities : PlatformCapabilities {
    override val isApple: Boolean = true
    override val supportsUsbCamera: Boolean = false // set true on iPadOS 17+ in Phase 4
    override val supportsPip: Boolean = false
    override val defaultFocus: Float = 0.92f // Empirical optical infinity baseline (avoids 1.0 hyper-infinity)
    override val focusRange: ClosedFloatingPointRange<Float> = 0.0f..1.0f // AVFoundation lensPosition stroke
    override val exposureRange: ClosedFloatingPointRange<Float> = 16667f..1000000f // 16.7ms to 1000ms
}

val LocalPlatformCapabilities = staticCompositionLocalOf<PlatformCapabilities> {
    AndroidPlatformCapabilities
}
