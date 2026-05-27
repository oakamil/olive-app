package com.oakamil.olive

import android.content.Context
import androidx.core.content.edit

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("olive_settings", Context.MODE_PRIVATE)

    var cameraSelection: String
        get() = prefs.getString("camera_selection", "internal") ?: "internal"
        set(value) = prefs.edit { putString("camera_selection", value) }

    var guidanceMode: String
        get() = prefs.getString("guidance_mode", "alt_az") ?: "alt_az"
        set(value) = prefs.edit { putString("guidance_mode", value) }

    var imuIntegration: Boolean
        get() = prefs.getBoolean("imu_integration", false)
        set(value) = prefs.edit { putBoolean("imu_integration", value) }

    var uvcFps: Int
        get() = prefs.getInt("uvc_fps", 20)
        set(value) = prefs.edit { putInt("uvc_fps", value) }

    var extractor: String
        get() = prefs.getString("extractor", "Olive") ?: "Olive"
        set(value) = prefs.edit { putString("extractor", value) }

    var internalCameraId: String
        get() {
            val v = prefs.getString("internal_camera_id", "0") ?: "0"
            return if (v == "auto") "0" else v
        }
        set(value) = prefs.edit { putString("internal_camera_id", value) }

    /* These are used from the native layer. */
    @androidx.annotation.Keep
    fun getString(key: String): String? = prefs.getString(key, null)

    @androidx.annotation.Keep
    fun setString(key: String, value: String) = prefs.edit { putString(key, value) }

    @androidx.annotation.Keep
    fun removeString(key: String) = prefs.edit { remove(key) }
}
