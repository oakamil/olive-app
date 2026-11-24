// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIScreen
import platform.darwin.NSObjectProtocol

/**
 * iOS [PlatformWindowController] managing device orientation state, keep-screen-on idle timer,
 * and night-vision screen brightness.
 *
 * Original system brightness is captured before custom dimming and restored automatically when
 * the app resigns active or [dispose] is called. Callers must invoke [dispose] to unregister
 * `NSNotificationCenter` observers and balance `beginGeneratingDeviceOrientationNotifications()`.
 */
class AppleWindowController : PlatformWindowController {
    override var isLandscape: Boolean by mutableStateOf(false)
    private var rotationDegrees: Int by mutableIntStateOf(0)

    private var originalSystemBrightness: Double? = null
    private var customAppBrightness: Double? = null
    private var restoredOnResign: Boolean = false
    private var keepScreenOn: Boolean = false
    private val observerTokens = mutableListOf<NSObjectProtocol>()

    private fun currentSystemBrightness(): Double? =
        UIScreen.mainScreen.brightness.takeIf { !it.isNaN() && it in 0.0..1.0 }

    init {
        UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        updateOrientationState()

        val center = NSNotificationCenter.defaultCenter

        // 1. Observe device orientation changes
        observerTokens += center.addObserverForName(
            name = UIDeviceOrientationDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            updateOrientationState()
        }

        // 2. Restore system brightness and idle timer when leaving app / backgrounding
        observerTokens += center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            UIApplication.sharedApplication.idleTimerDisabled = false
            if (customAppBrightness != null && !restoredOnResign) {
                originalSystemBrightness?.let {
                    UIScreen.mainScreen.setBrightness(it)
                    restoredOnResign = true
                }
            }
        }

        // 3. Re-apply custom dimming and keep-screen-on state when returning to foreground
        observerTokens += center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            if (restoredOnResign) {
                currentSystemBrightness()?.let { originalSystemBrightness = it }
                restoredOnResign = false
            }
            customAppBrightness?.let {
                UIScreen.mainScreen.setBrightness(it)
            }
            if (keepScreenOn) {
                UIApplication.sharedApplication.idleTimerDisabled = true
            }
        }
    }

    fun dispose() {
        val center = NSNotificationCenter.defaultCenter
        observerTokens.forEach { center.removeObserver(it) }
        observerTokens.clear()
        UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
        UIApplication.sharedApplication.idleTimerDisabled = false
        if (customAppBrightness != null && !restoredOnResign) {
            originalSystemBrightness?.let {
                UIScreen.mainScreen.setBrightness(it)
                restoredOnResign = true
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun isScreenLandscape(): Boolean {
        return UIScreen.mainScreen.bounds.useContents { size.width > size.height }
    }

    private fun updateOrientationState() {
        val orientation = UIDevice.currentDevice.orientation
        when (orientation) {
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> {
                isLandscape = true
                rotationDegrees = 90
            }

            UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> {
                isLandscape = true
                rotationDegrees = 270
            }

            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> {
                isLandscape = false
                rotationDegrees = 180
            }

            UIDeviceOrientation.UIDeviceOrientationPortrait -> {
                isLandscape = false
                rotationDegrees = 0
            }

            else -> {
                // If device is flat (FaceUp/FaceDown), mounted pointing at zenith, or uninitialized:
                // Fall back to interface screen geometry
                val screenLandscape = isScreenLandscape()
                isLandscape = screenLandscape
                if (screenLandscape && rotationDegrees % 180 == 0) {
                    rotationDegrees = 90
                } else if (!screenLandscape && rotationDegrees % 180 != 0) {
                    rotationDegrees = 0
                }
            }
        }
    }

    override fun getScreenBrightness(): Float {
        return (currentSystemBrightness() ?: customAppBrightness ?: 0.5).toFloat()
    }

    override fun setScreenBrightness(brightness: Float) {
        if (brightness.isNaN()) return
        if (customAppBrightness == null) {
            originalSystemBrightness = currentSystemBrightness()
        }
        val target = brightness.toDouble().coerceIn(0.0, 1.0)
        customAppBrightness = target
        restoredOnResign = false
        UIScreen.mainScreen.setBrightness(target)
    }

    override fun restoreScreenBrightness() {
        // Leave brightness at the newly adjusted setting while inside the app,
        // avoiding sudden jarring brightness jumps or blackouts when stopping the engine.
        // Original system brightness will be restored when Olive is backgrounded or closed.
    }

    override fun setPreferredRefreshRate(rate: Float) {
        // iOS manages display refresh rates dynamically
    }

    override fun getDisplayRotationDegrees(): Int {
        return rotationDegrees
    }

    override fun getCameraSensorOrientation(cameraId: String): Int {
        return 90
    }

    override fun setKeepScreenOn(keepOn: Boolean) {
        keepScreenOn = keepOn
        UIApplication.sharedApplication.idleTimerDisabled = keepOn
    }
}
