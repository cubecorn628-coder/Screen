// File: app/src/main/java/com/example/photostand/core/Constants.kt
package com.example.photostand.core

object Constants {
    const val DEFAULT_HTTP_PORT = 9876
    const val MDNS_SERVICE_TYPE = "_photostand._tcp."
    const val MDNS_SERVICE_NAME_PREFIX = "PhotoStand-"
    
    // BLE Presence Beacon UUID
    const val BLE_SERVICE_UUID = "0000fe90-0000-1000-8000-00805f9b34fb"
    
    // Transfer Protocol
    const val PROTOCOL_VERSION = 1
    const val TRANSFER_TIMEOUT_MS = 30_000L // 30s timeout as requested
    const val MAX_IMAGE_BYTES = 25 * 1024 * 1024 // 25MB max
    
    // SharedPreferences Keys
    const val PREFS_NAME = "photostand_prefs"
    const val KEY_APP_MODE = "app_mode"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_DEVICE_SECRET = "device_secret"
    const val KEY_PAIRED_DISPLAYS = "paired_displays"
    const val KEY_PAIRED_CONTROLLERS = "paired_controllers"
    const val KEY_BRIGHTNESS = "display_brightness"
    const val KEY_AUTO_DIM = "display_auto_dim"
    const val KEY_FIT_MODE = "display_fit_mode"
    const val KEY_ANIMATION_TYPE = "display_anim_type"
    const val KEY_SHOW_CLOCK = "display_show_clock"

    // Default auto-dim window (23:00 to 07:00)
    const val AUTO_DIM_START_HOUR = 23
    const val AUTO_DIM_END_HOUR = 7
    const val AUTO_DIM_BRIGHTNESS = 0.08f
}
