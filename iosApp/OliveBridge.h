// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

// Shared C-ABI bridge between Rust (crates/olive-ios), Swift (iosApp/), and Kotlin/Native cinterop.
//
// String ownership rules:
// - Strings returned by Rust (`olive_ios_get_engine_stats`, `olive_ios_get_planets`,
//   `olive_ios_get_asteroids`, `olive_ios_get_comets`) must be freed with `olive_ios_free_string()`.
// - Strings returned by Swift (`olive_ios_get_available_cameras`,
//   `olive_ios_get_external_camera_formats`, `AppleStorageGetCallback`) are `strdup`-allocated
//   and must be freed with libc `free()`.
// - All `const char *` input arguments are borrowed only for the duration of the call.

#ifndef OliveBridge_h
#define OliveBridge_h

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include <sqlite3.h>
#include <ifaddrs.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <stdio.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Network Helpers ---
static inline void olive_ios_get_local_ip(char *out_buf, size_t buf_len) {
    if (!out_buf || buf_len == 0) {
        return;
    }
    snprintf(out_buf, buf_len, "%s", "127.0.0.1");
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces) == 0) {
        struct ifaddrs *temp = interfaces;
        while (temp != NULL) {
            if (temp->ifa_addr && temp->ifa_addr->sa_family == AF_INET) {
                if ((temp->ifa_flags & IFF_UP) && !(temp->ifa_flags & IFF_LOOPBACK)) {
                    const char *name = temp->ifa_name;
                    if (name && strncmp(name, "en", 2) == 0) {
                        struct sockaddr_in *p = (struct sockaddr_in *)temp->ifa_addr;
                        uint32_t addr = ntohl(p->sin_addr.s_addr);
                        // Skip APIPA link-local (169.254.0.0/16)
                        if ((addr >> 16) != 0xA9FE &&
                            inet_ntop(AF_INET, &p->sin_addr, out_buf, (socklen_t)buf_len) != NULL) {
                            break;
                        }
                    }
                }
            }
            temp = temp->ifa_next;
        }
        freeifaddrs(interfaces);
    }
}

// --- Camera Control Callback ---
// Invoked synchronously from Rust engine/UI threads; negative values mean "leave unchanged".
// Implementations must hop onto their own AVFoundation control queue.
typedef void (*AppleCameraControlCallback)(int32_t exposure_us, float gain, float focus, float zoom);

void olive_ios_register_camera_control_callback(AppleCameraControlCallback cb);
void olive_ios_unregister_camera_control_callback(void);

// --- Hardware Capture Lifecycle (Swift) ---
// Returned JSON strings are `strdup`-allocated; caller must release with libc `free()`.
const char *olive_ios_get_available_cameras(void);
const char *olive_ios_get_external_camera_formats(void);
bool olive_ios_has_connected_external_camera(void);
// Blocks up to 8s awaiting AVFoundation startup, then starts CoreMotion updates on success.
bool olive_ios_start_hardware_capture(
    const char *camera_id,
    int32_t width,
    int32_t height,
    int32_t fps,
    const char *format,
    float initial_zoom
);
void olive_ios_stop_hardware_capture(void);

void olive_ios_set_camera_hardware_limits(
    int32_t min_exposure_us,
    int32_t max_exposure_us,
    float min_gain,
    float max_gain
);

// --- Frame and IMU Push Pipelines ---
// `buffer` points to a locked 8-bit Y-plane (stride >= width), borrowed only during the call.
void olive_ios_push_camera_frame(
    const uint8_t *buffer,
    uint32_t width,
    uint32_t height,
    uint32_t stride,
    int64_t timestamp_ns,
    int32_t applied_exposure_us,
    float applied_iso,
    float zoom
);

// Raw CoreMotion gyroscope rates (rad/s, right-handed device-body frame) with monotonic ns timestamp.
void olive_ios_push_gyro_update(
    double gx, double gy, double gz,
    int64_t timestamp_ns
);

// --- Key-Value Storage Callbacks (Rust <-> Swift UserDefaults) ---
// Invoked from Rust background threads. Get callback returns a `strdup`-allocated string
// (freed by Rust via libc `free()`) or NULL.
typedef char *(*AppleStorageGetCallback)(const char *key);
typedef void (*AppleStorageSetCallback)(const char *key, const char *value);
typedef void (*AppleStorageRemoveCallback)(const char *key);

void olive_ios_register_storage_callbacks(
    AppleStorageGetCallback get_cb,
    AppleStorageSetCallback set_cb,
    AppleStorageRemoveCallback remove_cb
);

// --- Memory Management ---
// Frees C strings returned by Rust `olive_ios_*` functions (not Swift `strdup` strings).
void olive_ios_free_string(char *ptr);

// --- Engine Lifecycle & Control ---
bool olive_ios_start_engine(
    uint32_t width,
    uint32_t height,
    int32_t motion_sensor_mode,
    int32_t downsample_mode,
    double lat,
    double lon,
    bool auto_mode,
    float initial_fov_deg,
    const char *camera_id,
    float initial_zoom
);

void olive_ios_stop_engine(void);
char *olive_ios_get_engine_stats(void);

void olive_ios_set_hfr_enabled(bool enabled);
void olive_ios_set_engine_sigma(float sigma);
void olive_ios_set_engine_strict_match(bool strict);

// --- Camera Parameter Controls ---
void olive_ios_set_camera_exposure(int32_t exposure_us);
int32_t olive_ios_get_camera_exposure(void);

void olive_ios_set_camera_gain(float gain);
float olive_ios_get_camera_gain(void);
void olive_ios_get_camera_gain_range(float *min_gain, float *max_gain, float *default_gain);

void olive_ios_set_camera_focus(float distance);
float olive_ios_get_camera_focus(void);

void olive_ios_set_camera_zoom(float zoom);
float olive_ios_get_camera_zoom(void);

void olive_ios_set_camera_stack_count(size_t count);
void olive_ios_set_camera_is_ae(bool is_ae);
void olive_ios_set_camera_brightness(int32_t brightness);
int32_t olive_ios_get_camera_brightness(void);

// Writes `[width: u32 LE][height: u32 LE][RGBA8888 pixels...]` into `buffer`.
// Returns Unix epoch ms (> last_timestamp_ms when written, <= last_timestamp_ms if unchanged, 0 on error).
int64_t olive_ios_get_latest_frame_into(uint8_t *buffer, size_t capacity, int64_t last_timestamp_ms);

// --- Telescope & Ephemeris Controls ---
void olive_ios_set_goto_target(double ra, double dec, double epoch);
void olive_ios_clear_goto_target(void);
void olive_ios_set_align_target_pixel(double x, double y);
void olive_ios_clear_align_target_pixel(void);
void olive_ios_sync_telescope(void);
void olive_ios_sync_telescope_to_target(double ra, double dec);

char *olive_ios_get_planets(const char *bsp_path, int64_t time_ms, double latitude, double longitude);
char *olive_ios_get_asteroids(const char *bsp_path, int64_t time_ms, double latitude, double longitude, const char *asteroids_json);
char *olive_ios_get_comets(const char *bsp_path, int64_t time_ms, double latitude, double longitude, const char *comets_json);

// --- SQLite Helpers ---
// Wraps SQLITE_TRANSIENT for Kotlin/Native cinterop and registers SIN/COS on iOS SQLite builds.
static inline int olive_sqlite3_bind_text(sqlite3_stmt *stmt, int col, const char *val) {
    if (!val) {
        return sqlite3_bind_null(stmt, col);
    }
    return sqlite3_bind_text(stmt, col, val, -1, SQLITE_TRANSIENT);
}

static inline void olive_sqlite3_sin(sqlite3_context *ctx, int argc, sqlite3_value **argv) {
    if (argc != 1 || sqlite3_value_type(argv[0]) == SQLITE_NULL) {
        sqlite3_result_null(ctx);
        return;
    }
    sqlite3_result_double(ctx, sin(sqlite3_value_double(argv[0])));
}

static inline void olive_sqlite3_cos(sqlite3_context *ctx, int argc, sqlite3_value **argv) {
    if (argc != 1 || sqlite3_value_type(argv[0]) == SQLITE_NULL) {
        sqlite3_result_null(ctx);
        return;
    }
    sqlite3_result_double(ctx, cos(sqlite3_value_double(argv[0])));
}

static inline int olive_register_sqlite_math_functions(sqlite3 *db) {
    if (!db) {
        return SQLITE_MISUSE;
    }
    int rc_sin = sqlite3_create_function(
        db,
        "SIN",
        1,
        SQLITE_UTF8 | SQLITE_DETERMINISTIC,
        NULL,
        olive_sqlite3_sin,
        NULL,
        NULL
    );
    if (rc_sin != SQLITE_OK) {
        return rc_sin;
    }
    return sqlite3_create_function(
        db,
        "COS",
        1,
        SQLITE_UTF8 | SQLITE_DETERMINISTIC,
        NULL,
        olive_sqlite3_cos,
        NULL,
        NULL
    );
}

#ifdef __cplusplus
}
#endif

#endif /* OliveBridge_h */
