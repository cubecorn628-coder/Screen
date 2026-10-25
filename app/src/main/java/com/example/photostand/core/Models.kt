// File: app/src/main/java/com/example/photostand/core/Models.kt
package com.example.photostand.core

import org.json.JSONObject

// region Enums
enum class AppMode {
    UNSELECTED,
    DISPLAY,
    CONTROLLER
}

enum class FitMode {
    CROP_FILL,
    CONTAIN_LETTERBOX,
    BLUR_BACKGROUND
}

enum class AnimationType {
    PAPER_SCREEN,
    TILE_GRID
}

enum class ConnectionType {
    WIFI_MDNS,
    BLE_BEACON,
    BLE_GATT,
    DIRECT_IP
}
// endregion

// region DisplayDevice Model
data class DisplayDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = Constants.DEFAULT_HTTP_PORT,
    val sharedKey: String = "",
    val isOnline: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val connectionType: ConnectionType = ConnectionType.WIFI_MDNS,
    val bleRssi: Int? = null,
    /** Stable 6-byte BLE identity (12 hex chars), advertised over Bluetooth. */
    val bleId: String = "",
    /** Trusted device: automatically re-connected whenever it is discovered. */
    val autoConnect: Boolean = true
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("ip", ipAddress)
        obj.put("port", port)
        obj.put("key", sharedKey)
        obj.put("ts", lastSeenTimestamp)
        obj.put("ble", bleId)
        obj.put("ct", connectionType.name)
        obj.put("ac", autoConnect)
        obj.put("v", Constants.PROTOCOL_VERSION)
        return obj.toString()
    }

    /**
     * Compact token embedded in QR codes and the BLE GATT characteristic. Omits fields
     * that are irrelevant for pairing (timestamps, local connection state) so the payload
     * stays well inside the QR byte-mode envelope and default ATT MTU limits.
     */
    fun toPairingToken(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("ip", ipAddress)
        obj.put("port", port)
        obj.put("key", sharedKey)
        if (bleId.isNotBlank()) obj.put("ble", bleId)
        obj.put("v", Constants.PROTOCOL_VERSION)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): DisplayDevice? {
            return try {
                val obj = JSONObject(jsonStr)
                val ct = try {
                    ConnectionType.valueOf(obj.optString("ct", ConnectionType.WIFI_MDNS.name))
                } catch (e: Exception) {
                    ConnectionType.WIFI_MDNS
                }
                DisplayDevice(
                    id = obj.getString("id"),
                    name = obj.optString("name", "PhotoStand Display"),
                    ipAddress = obj.optString("ip", ""),
                    port = obj.optInt("port", Constants.DEFAULT_HTTP_PORT),
                    sharedKey = obj.optString("key", ""),
                    lastSeenTimestamp = obj.optLong("ts", System.currentTimeMillis()),
                    connectionType = ct,
                    bleId = obj.optString("ble", ""),
                    autoConnect = obj.optBoolean("ac", true)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
// endregion

// region PairedController Model
data class PairedController(
    val controllerId: String,
    val label: String,
    val sharedKey: String,
    val pairedAtTimestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", controllerId)
        obj.put("label", label)
        obj.put("key", sharedKey)
        obj.put("ts", pairedAtTimestamp)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): PairedController? {
            return try {
                val obj = JSONObject(jsonStr)
                PairedController(
                    controllerId = obj.getString("id"),
                    label = obj.optString("label", "Controller"),
                    sharedKey = obj.getString("key"),
                    pairedAtTimestamp = obj.optLong("ts", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
// endregion

// region TransferPayload Model
sealed class TransferResult {
    data class Success(val message: String, val photoPath: String? = null) : TransferResult()
    data class Failure(val errorMessage: String, val isTimeout: Boolean = false) : TransferResult()
}

data class TransferMetadata(
    val version: Int = Constants.PROTOCOL_VERSION,
    val senderDeviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val signature: String,
    val contentType: String = "image/jpeg",
    val contentSizeBytes: Long
)
// endregion
