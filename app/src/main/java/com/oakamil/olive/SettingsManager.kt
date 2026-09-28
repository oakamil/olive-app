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
        get() = prefs.getInt("internal_fps", 2)
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

    var manualZoomRatio: Float
        get() = prefs.getFloat("manual_zoom_ratio", 1.0f)
        set(value) = prefs.edit { putFloat("manual_zoom_ratio", value) }

    var manualExposureUs: Float
        get() = prefs.getFloat("manual_exposure_us", 33000f)
        set(value) = prefs.edit { putFloat("manual_exposure_us", value) }

    var manualGain: Float
        get() = prefs.getFloat("manual_gain", -1f)
        set(value) = prefs.edit { putFloat("manual_gain", value) }

    var manualFocus: Float
        get() = prefs.getFloat("manual_focus", 0.0f)
        set(value) = prefs.edit { putFloat("manual_focus", value) }

    var manualStarSensitivity: Float
        get() = prefs.getFloat("manual_star_sensitivity", 50f)
        set(value) = prefs.edit { putFloat("manual_star_sensitivity", value) }

    var manualUvcStacking: Int
        get() = prefs.getInt("manual_uvc_stacking", 1)
        set(value) = prefs.edit { putInt("manual_uvc_stacking", value) }

    var manualNoiseReduction: Int
        get() = prefs.getInt("manual_noise_reduction", 0)
        set(value) = prefs.edit { putInt("manual_noise_reduction", value) }

    var manualHotPixelMode: Int
        get() = prefs.getInt("manual_hot_pixel_mode", 0)
        set(value) = prefs.edit { putInt("manual_hot_pixel_mode", value) }

    var manualUvcBrightness: Int
        get() = prefs.getInt("manual_uvc_brightness", 50)
        set(value) = prefs.edit { putInt("manual_uvc_brightness", value) }

    var catalogSortBy: String
        get() = prefs.getString("catalog_sort_by", "catalog ID") ?: "catalog ID"
        set(value) = prefs.edit { putString("catalog_sort_by", value) }

    var catalogVisibilityFilter: String
        get() = prefs.getString("catalog_visibility_filter", "Above Horizon") ?: "Above Horizon"
        set(value) = prefs.edit { putString("catalog_visibility_filter", value) }

    var catalogMagLimit: Double?
        get() {
            return if (prefs.contains("catalog_mag_limit")) {
                prefs.getFloat("catalog_mag_limit", 0f).toDouble()
            } else {
                null
            }
        }
        set(value) {
            prefs.edit {
                if (value != null) {
                    putFloat("catalog_mag_limit", value.toFloat())
                } else {
                    remove("catalog_mag_limit")
                }
            }
        }

    var catalogTypeFilters: Set<String>
        get() {
            if (!prefs.contains("catalog_type_filters")) {
                return MainViewModel.DEFAULT_CATALOG_TYPE_FILTERS
            }
            val set = prefs.getStringSet("catalog_type_filters", null)
            return set?.toSet() ?: MainViewModel.DEFAULT_CATALOG_TYPE_FILTERS
        }
        set(value) = prefs.edit { putStringSet("catalog_type_filters", value.toSet()) }

    var catalogSelectedCategory: String
        get() = prefs.getString("catalog_selected_category", "Nearest Objects") ?: "Nearest Objects"
        set(value) = prefs.edit { putString("catalog_selected_category", value) }

    var landscapeKeyboardOnRight: Boolean
        get() = prefs.getBoolean("landscape_keyboard_on_right", true)
        set(value) = prefs.edit { putBoolean("landscape_keyboard_on_right", value) }

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
