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
import com.example.photostand.core.ConnectionType
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BlePresence(private val context: Context) {
    private val tag = "BlePresence"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var isAdvertising = false

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false

    private val _nearbyDisplays = MutableStateFlow<List<DisplayDevice>>(emptyList())
    val nearbyDisplays: StateFlow<List<DisplayDevice>> = _nearbyDisplays.asStateFlow()

    private val serviceUuid = ParcelUuid(UUID.fromString(Constants.BLE_SERVICE_UUID))

    // region Advertising (Display Mode)
    @SuppressLint("MissingPermission")
    fun startAdvertising(deviceId: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.d(tag, "Bluetooth is disabled or not supported")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null || isAdvertising) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val dataBytes = deviceId.toByteArray(Charsets.UTF_8).take(8).toByteArray()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, dataBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                Log.d(tag, "BLE Presence Advertising started for $deviceId")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                Log.w(tag, "BLE Presence Advertising failed: $errorCode")
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
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
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null || isScanning) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                val record = result.scanRecord ?: return
                val serviceData = record.getServiceData(serviceUuid) ?: return
                val deviceId = String(serviceData, Charsets.UTF_8)
                val rssi = result.rssi

                val device = DisplayDevice(
                    id = deviceId,
                    name = "Display (BLE Proximity)",
                    ipAddress = "",
                    isOnline = true,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    connectionType = ConnectionType.BLE_BEACON,
                    bleRssi = rssi
                )

                val current = _nearbyDisplays.value.toMutableList()
                val index = current.indexOfFirst { it.id == deviceId }
                if (index >= 0) {
                    current[index] = current[index].copy(bleRssi = rssi, lastSeenTimestamp = System.currentTimeMillis())
                } else {
                    current.add(device)
                }
                _nearbyDisplays.value = current
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(tag, "BLE Scan failed: $errorCode")
                isScanning = false
            }
        }

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
        } catch (e: SecurityException) {
            Log.w(tag, "Missing BLE scan permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Error starting BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
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
    // endregion
}
