package com.oakamil.olive

class
SettingsManager(
    private val storage: KeyValueStorage,
    val defaultFocus: Float = 0.0f
) {
    var cameraSelection: String
        get() = storage.getString("camera_selection", "internal") ?: "internal"
        set(value) = storage.putString("camera_selection", value)

    var cameraSettingsMode: String
        get() = storage.getString("camera_settings_mode", "Auto") ?: "Auto"
        set(value) = storage.putString("camera_settings_mode", value)

    var internalFps: Int
        get() = storage.getInt("internal_fps", 2)
        set(value) = storage.putInt("internal_fps", value)

    var guidanceMode: String
        get() = storage.getString("guidance_mode", "alt_az") ?: "alt_az"
        set(value) = storage.putString("guidance_mode", value)

    var lowPowerTimeoutSeconds: Int
        get() = storage.getInt("low_power_timeout_seconds", 60)
        set(value) = storage.putInt("low_power_timeout_seconds", value)

    val hasExplicitMotionSensorMode: Boolean
        get() = storage.contains("motion_sensor_mode")

    var motionSensorMode: Int
        get() {
            if (storage.contains("motion_sensor_mode")) {
                return storage.getInt("motion_sensor_mode", 0)
            }
            return if (cameraSelection == "internal") 3 else 0
        }
        set(value) = storage.putInt("motion_sensor_mode", value)

    var uvcFps: Int
        get() = storage.getInt("uvc_fps", 30)
        set(value) = storage.putInt("uvc_fps", value)

    var uvcFormat: String
        get() = storage.getString("uvc_format", "MJPEG") ?: "MJPEG"
        set(value) = storage.putString("uvc_format", value)

    var uvcWidth: Int
        get() = storage.getInt("uvc_width", 1920)
        set(value) = storage.putInt("uvc_width", value)

    var uvcHeight: Int
        get() = storage.getInt("uvc_height", 1080)
        set(value) = storage.putInt("uvc_height", value)

    var extractor: String
        get() = storage.getString("extractor", "Olive") ?: "Olive"
        set(value) = storage.putString("extractor", value)

    var sensorMode: String
        get() = storage.getString("sensor_mode", "Processed") ?: "Processed"
        set(value) = storage.putString("sensor_mode", value)

    var downsampling: Int
        get() = storage.getInt("downsampling", 0)
        set(value) = storage.putInt("downsampling", value)

    var internalCameraId: String
        get() {
            val v = storage.getString("internal_camera_id", "0") ?: "0"
            return if (v == "auto" || v == "demo") "0" else v
        }
        set(value) = storage.putString("internal_camera_id", value)

    var manualZoomRatio: Float
        get() = storage.getFloat("manual_zoom_ratio", 1.0f)
        set(value) = storage.putFloat("manual_zoom_ratio", value)

    var autoZoomRatio: Float
        get() = storage.getFloat("auto_zoom_ratio", 1.0f)
        set(value) = storage.putFloat("auto_zoom_ratio", value)

    var manualExposureUs: Float
        get() = storage.getFloat("manual_exposure_us", 33000f)
        set(value) = storage.putFloat("manual_exposure_us", value)

    var manualGain: Float
        get() = storage.getFloat("manual_gain", -1f)
        set(value) = storage.putFloat("manual_gain", value)

    var manualFocus: Float
        get() = storage.getFloat("manual_focus", defaultFocus)
        set(value) = storage.putFloat("manual_focus", value)

    var manualStarSensitivity: Float
        get() = storage.getFloat("manual_star_sensitivity", 50f)
        set(value) = storage.putFloat("manual_star_sensitivity", value)

    var manualUvcStacking: Int
        get() = storage.getInt("manual_uvc_stacking", 1)
        set(value) = storage.putInt("manual_uvc_stacking", value)

    var manualNoiseReduction: Int
        get() = storage.getInt("manual_noise_reduction", 0)
        set(value) = storage.putInt("manual_noise_reduction", value)

    var manualHotPixelMode: Int
        get() = storage.getInt("manual_hot_pixel_mode", 0)
        set(value) = storage.putInt("manual_hot_pixel_mode", value)

    var manualUvcBrightness: Int
        get() = storage.getInt("manual_uvc_brightness", 50)
        set(value) = storage.putInt("manual_uvc_brightness", value)

    var catalogSortBy: String
        get() = storage.getString("catalog_sort_by", "catalog ID") ?: "catalog ID"
        set(value) = storage.putString("catalog_sort_by", value)

    var catalogVisibilityFilter: String
        get() = storage.getString("catalog_visibility_filter", "Above Horizon") ?: "Above Horizon"
        set(value) = storage.putString("catalog_visibility_filter", value)

    var catalogMagLimit: Double?
        get() {
            return if (storage.contains("catalog_mag_limit")) {
                storage.getFloat("catalog_mag_limit", 0f).toDouble()
            } else {
                null
            }
        }
        set(value) {
            if (value != null) {
                storage.putFloat("catalog_mag_limit", value.toFloat())
            } else {
                storage.remove("catalog_mag_limit")
            }
        }

    var catalogTypeFilters: Set<String>
        get() {
            if (!storage.contains("catalog_type_filters")) {
                return DEFAULT_CATALOG_TYPE_FILTERS
            }
            val set = storage.getStringSet("catalog_type_filters", null)
                ?: return DEFAULT_CATALOG_TYPE_FILTERS
            if ("Double Star" in set) {
                val migrated = (set - "Double Star" + "Multiple Star").toSet()
                storage.putStringSet("catalog_type_filters", migrated)
                return migrated
            }
            return set.toSet()
        }
        set(value) = storage.putStringSet("catalog_type_filters", value.toSet())

    var catalogSelectedCategory: String
        get() = storage.getString("catalog_selected_category", "Nearest Objects")
            ?: "Nearest Objects"
        set(value) = storage.putString("catalog_selected_category", value)

    var landscapeKeyboardOnRight: Boolean
        get() = storage.getBoolean("landscape_keyboard_on_right", true)
        set(value) = storage.putBoolean("landscape_keyboard_on_right", value)

    var locationMode: String
        get() = storage.getString("location_mode", "Auto") ?: "Auto"
        set(value) = storage.putString("location_mode", value)

    fun saveLocation(latitude: Float, longitude: Float) {
        storage.putFloat("saved_latitude", latitude)
        storage.putFloat("saved_longitude", longitude)
    }

    val savedLocation: Pair<Double, Double>?
        get() {
            if (!storage.contains("saved_latitude") || !storage.contains("saved_longitude")) return null
            val lat = storage.getFloat("saved_latitude", 0f)
            val lon = storage.getFloat("saved_longitude", 0f)
            return if (lat in -90.0f..90.0f && lon in -180.0f..180.0f) {
                Pair(lat.toDouble(), lon.toDouble())
            } else null
        }

    /* Native layer callbacks */
    @Keep
    fun getString(key: String): String? {
        if (key == "imu_calibration" && cameraSelection == "external") {
            return null
        }
        return storage.getString(key)
    }

    @Keep
    fun setString(key: String, value: String) {
        if (key == "imu_calibration" && cameraSelection == "external") {
            return
        }
        storage.putString(key, value)
    }

    @Keep
    fun removeString(key: String) = storage.remove(key)
}
