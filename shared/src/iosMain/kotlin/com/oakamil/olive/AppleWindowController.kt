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

class AppleWindowController : PlatformWindowController {
    override var isLandscape: Boolean by mutableStateOf(false)
    private var rotationDegrees: Int by mutableIntStateOf(0)

    private var originalSystemBrightness: Double =
        UIScreen.mainScreen.brightness.takeIf { it > 0.05 } ?: 0.5
    private var customAppBrightness: Double? = null
    private var keepScreenOn: Boolean = false
    private val observerTokens = mutableListOf<platform.darwin.NSObjectProtocol>()

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
            customAppBrightness?.let {
                UIScreen.mainScreen.setBrightness(originalSystemBrightness)
            }
        }

        // 3. Re-apply custom dimming and keep-screen-on state when returning to foreground
        observerTokens += center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            val current = UIScreen.mainScreen.brightness
            if (customAppBrightness == null && current > 0.05) {
                originalSystemBrightness = current
            } else {
                customAppBrightness?.let {
                    UIScreen.mainScreen.setBrightness(it)
                }
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
        customAppBrightness?.let {
            UIScreen.mainScreen.setBrightness(originalSystemBrightness)
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
        return UIScreen.mainScreen.brightness.toFloat()
    }

    override fun setScreenBrightness(brightness: Float) {
        val current = UIScreen.mainScreen.brightness
        if (customAppBrightness == null && current > 0.05) {
            originalSystemBrightness = current
        }
        val target = brightness.toDouble().coerceIn(0.0, 1.0)
        customAppBrightness = target
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
