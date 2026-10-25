// File: app/src/main/java/com/example/photostand/p2p/discovery/BleGattClient.kt
package com.example.photostand.p2p.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller-side BLE GATT client.
 *
 * Consumes [BleSight]s from [BlePresence], connects to each nearby display one at a time,
 * negotiates a large ATT MTU and reads the full pairing token characteristic. Successfully
 * read tokens are emitted as [DisplayDevice]s — the exact same payload the display renders
 * as a QR code — enabling fully automatic trusted pairing over Bluetooth.
 *
 * A per-device cooldown prevents reconnect loops; the whole flow is best-effort and never
 * blocks WiFi/mDNS discovery.
 */
class BleGattClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "BleGattClient"

    private val _pairingTokens = MutableStateFlow<List<DisplayDevice>>(emptyList())
    val pairingTokens: StateFlow<List<DisplayDevice>> = _pairingTokens.asStateFlow()

    private val queue = ArrayDeque<BleSight>()
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private var activeGatt: BluetoothGatt? = null
    private var workerJob: Job? = null
    private var timeoutJob: Job? = null

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive && !stopped) {
                val next = nextSightDue()
                if (next == null) {
                    delay(1_500L)
                    continue
                }
                connectAndRead(next)
            }
        }
    }

    fun stop() {
        stopped = true
        workerJob?.cancel()
        workerJob = null
        timeoutJob?.cancel()
        closeGatt()
        queue.clear()
    }

    /** Called by the ViewModel whenever BLE scanning produces a sighting. */
    fun offer(sight: BleSight) {
        val now = System.currentTimeMillis()
        val until = cooldowns[sight.bleId] ?: 0L
        if (now < until) return
        if (queue.any { it.bleId == sight.bleId }) return
        if (_pairingTokens.value.any { it.bleId.equals(sight.bleId, ignoreCase = true) }) return
        queue.addLast(sight)
    }

    private fun nextSightDue(): BleSight? {
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            if (now < (cooldowns[candidate.bleId] ?: 0L)) continue
            return candidate
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun connectAndRead(sight: BleSight) {
        val device = sight.device
        Log.d(tag, "Connecting GATT to ${sight.bleId} (${device.address})")
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(tag, "GATT connected, requesting MTU")
                    gatt.requestMtu(GATT_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(tag, "GATT disconnected status=$status")
                    finishWithCooldown(sight.bleId, GATT_RETRY_COOLDOWN_SHORT)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(tag, "MTU negotiated: $mtu")
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(UUID.fromString(Constants.BLE_SERVICE_UUID))
                val characteristic = service?.getCharacteristic(UUID.fromString(Constants.BLE_TOKEN_CHAR_UUID))
                if (characteristic == null) {
                    Log.w(tag, "Token characteristic not found")
                    finishWithCooldown(sight.bleId, GATT_RETRY_COOLDOWN_SHORT)
                    return
                }
                readToken(gatt, characteristic)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                @Suppress("DEPRECATION")
                handleRead(gatt, characteristic, characteristic.value, status, sight)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleRead(gatt, characteristic, value, status, sight)
            }
        }

        activeGatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLUETOOTH_CONNECT", e)
            return
        }

        timeoutJob = scope.launch {
            delay(GATT_OPERATION_TIMEOUT)
            Log.w(tag, "GATT timeout for ${sight.bleId}")
            finishWithCooldown(sight.bleId, GATT_RETRY_COOLDOWN_SHORT)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readToken(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            // The single-arg read works on every API level; on API 33+ its result is
            // delivered through the memory-safe onCharacteristicRead(...,value,status).
            @Suppress("DEPRECATION")
            val queued = gatt.readCharacteristic(characteristic)
            if (!queued) Log.w(tag, "readCharacteristic was not queued")
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLUETOOTH_CONNECT", e)
            closeGatt()
        }
    }

    private fun handleRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
        sight: BleSight
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.isEmpty()) {
            finishWithCooldown(sight.bleId, GATT_RETRY_COOLDOWN_SHORT)
            return
        }
        val json = String(value, Charsets.UTF_8)
        val device = DisplayDevice.fromJson(json)
        if (device == null) {
            Log.w(tag, "Unparseable GATT token (${value.size} bytes)")
            finishWithCooldown(sight.bleId, GATT_RETRY_COOLDOWN_SHORT)
            return
        }
        val enriched = device.copy(
            isOnline = true,
            connectionType = com.example.photostand.core.ConnectionType.BLE_GATT,
            bleRssi = sight.rssi,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        val current = _pairingTokens.value.toMutableList()
        val index = current.indexOfFirst { it.id == enriched.id }
        if (index >= 0) current[index] = enriched else current.add(enriched)
        _pairingTokens.value = current
        Log.d(tag, "Pairing token received over GATT for ${enriched.name}")
        finishWithCooldown(sight.bleId, Constants.BLE_GATT_RETRY_COOLDOWN_MS)
    }

    private fun finishWithCooldown(bleId: String, cooldownMs: Long) {
        cooldowns[bleId] = System.currentTimeMillis() + cooldownMs
        timeoutJob?.cancel()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            activeGatt?.close()
        } catch (e: Exception) {
            // Ignore
        }
        activeGatt = null
    }

    private companion object {
        const val GATT_MTU = 256
        const val GATT_OPERATION_TIMEOUT = 12_000L
        const val GATT_RETRY_COOLDOWN_SHORT = 20_000L
    }
}
