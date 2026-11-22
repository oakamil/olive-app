package com.oakamil.olive

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AndroidWindowController(private val activity: Activity) : PlatformWindowController {
    override var isLandscape: Boolean by mutableStateOf(
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    )
        internal set

    private var rotationDegrees: Int by mutableIntStateOf(readDisplayRotationDegrees())

    fun updateConfiguration(configuration: Configuration) {
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rotationDegrees = readDisplayRotationDegrees()
    }

    override fun getScreenBrightness(): Float {
        val currentOverride = activity.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (currentOverride in 0f..1f) {
            return currentOverride
        }
        return try {
            Settings.System.getFloat(
                activity.contentResolver,
                "screen_brightness_float"
            )
        } catch (_: Exception) {
            try {
                Settings.System.getInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (_: Exception) {
                0.5f
            }
        }
    }

    override fun setScreenBrightness(brightness: Float) {
        activity.window?.attributes = activity.window?.attributes?.apply {
            this.screenBrightness = brightness
        }
    }

    override fun restoreScreenBrightness() {
        activity.window?.attributes = activity.window?.attributes?.apply {
            this.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun setPreferredRefreshRate(rate: Float) {
        activity.window?.attributes = activity.window?.attributes?.apply {
            this.preferredRefreshRate = rate
        }
    }

    override fun getDisplayRotationDegrees(): Int =
        readDisplayRotationDegrees()

    private fun readDisplayRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    override fun getCameraSensorOrientation(cameraId: String): Int {
        return try {
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) {
            90
        }
    }

    override fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun getSystemBarsVerticalInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = activity.windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            return insets.top + insets.bottom
        }
        val decorView = activity.window?.decorView ?: return 0
        val insets = ViewCompat.getRootWindowInsets(decorView)
            ?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ) ?: return 0
        return insets.top + insets.bottom
    }
}
