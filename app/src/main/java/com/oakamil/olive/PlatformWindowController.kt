package com.oakamil.olive

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformWindowController {
    val isLandscape: Boolean
    fun getScreenBrightness(): Float
    fun setScreenBrightness(brightness: Float)
    fun restoreScreenBrightness()
    fun setPreferredRefreshRate(rate: Float)
    fun getDisplayRotationDegrees(): Int
    fun getCameraSensorOrientation(cameraId: String): Int
}

class AndroidWindowController(private val activity: Activity) : PlatformWindowController {
    override var isLandscape: Boolean by mutableStateOf(
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    )
        internal set

    fun updateConfiguration(configuration: Configuration) {
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun getScreenBrightness(): Float {
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

    override fun getDisplayRotationDegrees(): Int {
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
}

val LocalPlatformWindowController = staticCompositionLocalOf<PlatformWindowController> {
    error("No PlatformWindowController provided")
}
