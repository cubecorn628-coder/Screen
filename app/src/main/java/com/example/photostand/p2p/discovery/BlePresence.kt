// File: app/src/main/java/com/example/photostand/p2p/discovery/BlePresence.kt
package com.example.photostand.p2p.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.photostand.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** A single nearby BLE sighting of a PhotoStand display. */
data class BleSight(
    val bleId: String,
    val port: Int,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis(),
    // Transient only — never serialised. Lets the GATT client connect without re-scanning.
    val device: android.bluetooth.BluetoothDevice
)

/**
 * BLE presence layer.
 *
 * Display side: advertises the PhotoStand service UUID plus a scan-response containing
 * the stable 6-byte BLE identity and the HTTP port (manufacturer-specific data, 8 bytes).
 *
 * Controller side: continuously cycles low-latency scans, parses those payloads and
 * exposes them as [nearbySights] so the ViewModel can correlate BLE proximity with
 * mDNS results and trusted (paired) devices.
 */
class BlePresence(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "BlePresence"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var isAdvertising = false

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private var scanCycleJob: Job? = null

    private val _nearbySights = MutableStateFlow<List<BleSight>>(emptyList())
    val nearbySights: StateFlow<List<BleSight>> = _nearbySights.asStateFlow()

    private val _bluetoothAvailable = MutableStateFlow(bluetoothAdapter != null)
    val bluetoothAvailable: StateFlow<Boolean> = _bluetoothAvailable.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val serviceUuid = ParcelUuid(UUID.fromString(Constants.BLE_SERVICE_UUID))

    // region Advertising (Display Mode)
    @SuppressLint("MissingPermission")
    fun startAdvertising(bleId: String, port: Int) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.d(tag, "Bluetooth is disabled or not supported; advertising skipped")
            _bluetoothEnabled.value = false
            return
        }
        _bluetoothEnabled.value = true

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null || isAdvertising) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true) // GATT clients must be able to connect for token exchange
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // Main advertisement: service UUID only (keeps payload small).
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .build()

        // Scan response: 8-byte manufacturer payload = 6-byte BLE id + 2-byte port.
        val payload = encodeManufacturerPayload(bleId, port)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Constants.BLE_MANUFACTURER_ID, payload)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                Log.d(tag, "BLE advertising started for bleId=$bleId port=$port")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                Log.w(tag, "BLE advertising failed: $errorCode")
            }
        }

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLE advertise permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Error starting BLE advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (advertiser != null && isAdvertising && advertiseCallback != null) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                // Ignore
            } finally {
                isAdvertising = false
                advertiseCallback = null
            }
        }
    }
    // endregion

    // region Scanning (Controller Mode)
    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null) {
            _bluetoothAvailable.value = false
            return
        }
        _bluetoothAvailable.value = true
        _bluetoothEnabled.value = bluetoothAdapter.isEnabled
        if (!bluetoothAdapter.isEnabled || scanCycleJob?.isActive == true) return

        scanCycleJob = scope.launch {
            while (isActive) {
                if (bluetoothAdapter.isEnabled) {
                    beginScan()
                    delay(Constants.BLE_SCAN_DURATION_MS)
                    stopScanInternal()
                } else {
                    _bluetoothEnabled.value = false
                }
                // Pause between scans to respect Android scan throttling.
                delay(Constants.BLE_SCAN_PAUSE_MS)
            }
        }

        // Evict stale sights periodically.
        scope.launch {
            while (isActive) {
                delay(10_000L)
                val cutoff = System.currentTimeMillis() - 45_000L
                _nearbySights.value = _nearbySights.value.filter { it.lastSeen >= cutoff }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner
        if (scanner == null || isScanning) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val sight = parseResult(result) ?: return
                upsight(sight)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { parseResult(it)?.let(::upsight) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(tag, "BLE scan failed: $errorCode")
                isScanning = false
            }
        }

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            _bluetoothEnabled.value = true
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLE scan permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Error starting BLE scan", e)
        }
    }

    private fun parseResult(result: ScanResult?): BleSight? {
        result ?: return null
        val record = result.scanRecord ?: return null
        val raw = record.getManufacturerSpecificData(Constants.BLE_MANUFACTURER_ID) ?: return null
        if (raw.size < Constants.BLE_ADV_BYTES) return null
        val bleId = raw.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
        val port = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        return BleSight(bleId = bleId, port = port, rssi = result.rssi, device = result.device)
    }

    private fun upsight(sight: BleSight) {
        val current = _nearbySights.value.toMutableList()
        val index = current.indexOfFirst { it.bleId == sight.bleId }
        if (index >= 0) current[index] = sight else current.add(sight)
        _nearbySights.value = current
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (scanner != null && isScanning && scanCallback != null) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                // Ignore
            } finally {
                isScanning = false
                scanCallback = null
            }
        }
    }

    fun stopScanning() {
        scanCycleJob?.cancel()
        scanCycleJob = null
        stopScanInternal()
    }
    // endregion

    companion object {
        fun encodeManufacturerPayload(bleId: String, port: Int): ByteArray {
            val idBytes = ByteArray(6)
            val clean = bleId.lowercase().filter { it.isLetterOrDigit() }.take(12).padStart(12, '0')
            for (i in 0 until 6) {
                idBytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return byteArrayOf(
                idBytes[0], idBytes[1], idBytes[2], idBytes[3], idBytes[4], idBytes[5],
                ((port shr 8) and 0xFF).toByte(),
                (port and 0xFF).toByte()
            )
        }
    }
}
