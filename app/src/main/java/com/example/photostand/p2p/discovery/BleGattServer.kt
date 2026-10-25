// File: app/src/main/java/com/example/photostand/p2p/discovery/BleGattServer.kt
package com.example.photostand.p2p.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log

import com.example.photostand.core.Constants
import java.util.UUID

/**
 * Display-side BLE GATT server.
 *
 * Exposes the full pairing token (device id, name, IP, port, shared key, BLE id) over a
 * readable characteristic so a Controller can pair through Bluetooth even when the two
 * devices are not on the same WiFi network yet. The token is the same JSON printed as the
 * on-screen QR code, so QR and BLE pairing are fully interchangeable.
 */
class BleGattServer(
    private val context: Context,
    private val tokenProvider: () -> String
) {
    private val tag = "BleGattServer"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = bluetoothManager ?: run {
            Log.d(tag, "Bluetooth unsupported; GATT server skipped")
            return
        }
        if (running) return
        if (manager.adapter?.isEnabled != true) {
            Log.d(tag, "Bluetooth disabled; GATT server skipped")
            return
        }

        val service = BluetoothGattService(
            UUID.fromString(Constants.BLE_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Pairing token — full JSON, read only.
        val tokenCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(Constants.BLE_TOKEN_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Short BLE id characteristic, handy for quick correlation.
        val idCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(Constants.BLE_ID_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(tokenCharacteristic)
        service.addCharacteristic(idCharacteristic)

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                Log.d(tag, "GATT connection state: $status -> $newState")
            }

            // API 33+: request id is provided explicitly.
            override fun onCharacteristicRead(
                device: BluetoothDevice,
                characteristic: BluetoothGattCharacteristic,
                requestId: Int,
                value: ByteArray
            ) {
                sendReadResponse(device, characteristic, requestId)
            }

            // API 24-32 legacy path: request id is implicit (single outstanding read).
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                device: BluetoothDevice?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                if (device != null && characteristic != null) {
                    sendReadResponse(device, characteristic, LEGACY_REQUEST_ID)
                }
            }
        }

        try {
            gattServer = manager.openGattServer(context, callback)
            val added = gattServer?.addService(service) == true
            running = added
            Log.d(tag, "GATT server started: $added")
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start GATT server", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReadResponse(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        requestId: Int
    ) {
        val server = gattServer ?: return
        val payload = when (characteristic.uuid?.toString()) {
            Constants.BLE_TOKEN_CHAR_UUID -> tokenProvider().toByteArray(Charsets.UTF_8)
            Constants.BLE_ID_CHAR_UUID -> {
                val bleMatch = Regex("\"ble\":\"([0-9a-fA-F]{12})\"").find(tokenProvider())
                (bleMatch?.groupValues?.get(1) ?: "").toByteArray(Charsets.UTF_8)
            }
            else -> ByteArray(0)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to send GATT read response", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // Ignore
        }
        gattServer = null
        running = false
    }

    private companion object {
        const val LEGACY_REQUEST_ID = 0
    }
}
