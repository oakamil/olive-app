package com.oakamil.olive

import android.content.Context
import androidx.annotation.Keep
import androidx.core.content.edit

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("olive_settings", Context.MODE_PRIVATE)

    var cameraSelection: String
        get() = prefs.getString("camera_selection", "internal") ?: "internal"
        set(value) = prefs.edit { putString("camera_selection", value) }

    var cameraSettingsMode: String
        get() = prefs.getString("camera_settings_mode", "Auto") ?: "Auto"
        set(value) = prefs.edit { putString("camera_settings_mode", value) }

    var internalFps: Int
        get() = prefs.getInt("internal_fps", 5)
        set(value) = prefs.edit { putInt("internal_fps", value) }


    var guidanceMode: String
        get() = prefs.getString("guidance_mode", "alt_az") ?: "alt_az"
        set(value) = prefs.edit { putString("guidance_mode", value) }

    var motionSensorMode: Int
        get() = prefs.getInt("motion_sensor_mode", 0)
        set(value) = prefs.edit { putInt("motion_sensor_mode", value) }

    var uvcFps: Int
        get() = prefs.getInt("uvc_fps", 30)
        set(value) = prefs.edit { putInt("uvc_fps", value) }

    var extractor: String
        get() = prefs.getString("extractor", "Olive") ?: "Olive"
        set(value) = prefs.edit { putString("extractor", value) }

    var sensorMode: String
        get() = prefs.getString("sensor_mode", "Processed") ?: "Processed"
        set(value) = prefs.edit { putString("sensor_mode", value) }

    var downsampling: Int
        get() = prefs.getInt("downsampling", 0)
        set(value) = prefs.edit { putInt("downsampling", value) }

    var internalCameraId: String
        get() {
            val v = prefs.getString("internal_camera_id", "0") ?: "0"
            return if (v == "auto" || v == "demo") "0" else v
        }
        set(value) = prefs.edit { putString("internal_camera_id", value) }

    var zoomRatio: Float
        get() = prefs.getFloat("zoom_ratio", 1.0f)
        set(value) = prefs.edit { putFloat("zoom_ratio", value) }

    var cedarConnectionType: String
        get() = prefs.getString("cedar_connection_type", "wifi") ?: "wifi"
        set(value) = prefs.edit { putString("cedar_connection_type", value) }

    var cedarWifiIp: String
        get() = prefs.getString("cedar_wifi_ip", "192.168.4.1") ?: "192.168.4.1"
        set(value) = prefs.edit { putString("cedar_wifi_ip", value) }

    var cedarBtAddress: String
        get() = prefs.getString("cedar_bt_address", "") ?: ""
        set(value) = prefs.edit { putString("cedar_bt_address", value) }

    var cedarBtName: String
        get() = prefs.getString("cedar_bt_name", "Unknown Device") ?: "Unknown Device"
        set(value) = prefs.edit { putString("cedar_bt_name", value) }


    /* These are used from the native layer. */
    @Keep
    fun getString(key: String): String? {
        if (key == "imu_calibration" && cameraSelection == "external") {
            return null
        }
        return prefs.getString(key, null)
    }

    @Keep
    fun setString(key: String, value: String) {
        if (key == "imu_calibration" && cameraSelection == "external") {
            return
        }
        prefs.edit { putString(key, value) }
    }

    @Keep
    fun removeString(key: String) = prefs.edit { remove(key) }
}
