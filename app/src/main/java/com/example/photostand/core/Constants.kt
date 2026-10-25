// File: app/src/main/java/com/example/photostand/core/Constants.kt
package com.example.photostand.core

object Constants {
    const val DEFAULT_HTTP_PORT = 9876
    const val MDNS_SERVICE_TYPE = "_photostand._tcp."
    const val MDNS_SERVICE_NAME_PREFIX = "PhotoStand-"

    // BLE Presence — custom 128-bit service so we never collide with vendor beacons.
    // Base UUID: 9e3b1400-2f7a-4d9c-8a6e-0c5f3a91d000
    const val BLE_SERVICE_UUID = "9e3b1400-2f7a-4d9c-8a6e-0c5f3a91d001"
    const val BLE_TOKEN_CHAR_UUID = "9e3b1400-2f7a-4d9c-8a6e-0c5f3a91d002"
    const val BLE_ID_CHAR_UUID = "9e3b1400-2f7a-4d9c-8a6e-0c5f3a91d003"

    // Manufacturer Specific Data payload (scan response):
    // [0..5] 6-byte BLE id, [6..7] port (big endian)
    const val BLE_MANUFACTURER_ID = 0x07E1
    const val BLE_ADV_BYTES = 8
    const val BLE_SCAN_DURATION_MS = 25_000L
    const val BLE_SCAN_PAUSE_MS = 8_000L
    const val BLE_GATT_RETRY_COOLDOWN_MS = 60_000L

    // Transfer Protocol
    const val PROTOCOL_VERSION = 2
    const val TRANSFER_TIMEOUT_MS = 60_000L
    const val MAX_IMAGE_BYTES = 25 * 1024 * 1024 // 25MB max
    const val TRANSFER_MAX_RETRIES = 2

    // SharedPreferences Keys
    const val PREFS_NAME = "photostand_prefs"
    const val KEY_APP_MODE = "app_mode"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_DEVICE_SECRET = "device_secret"
    const val KEY_BLE_ID = "ble_id"
    const val KEY_PAIRED_DISPLAYS = "paired_displays"
    const val KEY_PAIRED_CONTROLLERS = "paired_controllers"
    const val KEY_BRIGHTNESS = "display_brightness"
    const val KEY_AUTO_DIM = "display_auto_dim"
    const val KEY_FIT_MODE = "display_fit_mode"
    const val KEY_ANIMATION_TYPE = "display_anim_type"
    const val KEY_SHOW_CLOCK = "display_show_clock"

    // Always-On Display (AOD)
    const val KEY_AOD_ENABLED = "aod_enabled"
    const val KEY_AOD_NIGHT_RED = "aod_night_red"
    const val KEY_AOD_TIMEOUT_SECONDS = "aod_timeout_seconds"
    const val AOD_DEFAULT_TIMEOUT_SECONDS = 45
    const val AOD_PIXEL_SHIFT_PERIOD_MS = 60_000L
    const val AOD_AMBIENT_BRIGHTNESS = 0.04f
    const val AOD_NIGHT_RED_COLOR = 0xFFFF6B6B.toInt()

    // Default auto-dim window (23:00 to 07:00)
    const val AUTO_DIM_START_HOUR = 23
    const val AUTO_DIM_END_HOUR = 7
    const val AUTO_DIM_BRIGHTNESS = 0.08f
}
