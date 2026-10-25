// File: app/src/main/java/com/example/photostand/controller/ControllerViewModel.kt
package com.example.photostand.controller

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photostand.core.ConnectionType
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.TransferResult
import com.example.photostand.p2p.NetworkUtils
import com.example.photostand.p2p.discovery.BleGattClient
import com.example.photostand.p2p.discovery.BlePresence
import com.example.photostand.p2p.discovery.BleSight
import com.example.photostand.p2p.discovery.MdnsDiscovery
import com.example.photostand.p2p.pairing.PairingStore
import com.example.photostand.p2p.transfer.HandshakeResult
import com.example.photostand.p2p.transfer.PhotoTransferClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ControllerUiState(
    val pairedDisplays: List<DisplayDevice> = emptyList(),
    val discoveredDisplays: List<DisplayDevice> = emptyList(),
    val isScanning: Boolean = false,
    val isSending: Boolean = false,
    val sendingMessage: String = "",
    val lastTransferResult: TransferResult? = null,
    val sharedImageUri: Uri? = null,
    val showPairingDialog: Boolean = false,
    val showQrScanner: Boolean = false,
    val showPickerSheet: Boolean = false,
    val selectedDisplayForShare: DisplayDevice? = null,
    val renameDialogDevice: DisplayDevice? = null,
    val isWifiConnected: Boolean = true,
    val bluetoothEnabled: Boolean = true
)

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "ControllerViewModel"
    private val pairingStore = PairingStore(application)
    private val mdnsDiscovery = MdnsDiscovery(application, viewModelScope)
    private val blePresence = BlePresence(application, viewModelScope)
    private val bleGattClient = BleGattClient(application, viewModelScope)
    private val transferClient = PhotoTransferClient(application)

    private val _uiState = MutableStateFlow(ControllerUiState())
    val uiState: StateFlow<ControllerUiState> = _uiState.asStateFlow()

    // Presence bookkeeping for auto-connect.
    private val lastBleSeen = HashMap<String, Long>()
    private val autoHandshakeAttempted = HashSet<String>()

    init {
        viewModelScope.launch {
            pairingStore.pairedDisplays.collect { displays ->
                _uiState.update { it.copy(pairedDisplays = displays) }
            }
        }

        viewModelScope.launch {
            mdnsDiscovery.discoveredDisplays.collect { mdns ->
                onMdnsDiscovered(mdns)
            }
        }

        viewModelScope.launch {
            blePresence.nearbySights.collect { sights ->
                onBleSights(sights)
            }
        }

        viewModelScope.launch {
            blePresence.bluetoothEnabled.collect { enabled ->
                _uiState.update { it.copy(bluetoothEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            bleGattClient.pairingTokens.collect { gattDevices ->
                onGattTokens(gattDevices)
            }
        }

        startDiscovery()
        startPeriodicMaintenance()
    }

    // region Discovery lifecycle
    fun startDiscovery() {
        val isWifi = NetworkUtils.isWifiConnected(getApplication())
        _uiState.update { it.copy(isScanning = true, isWifiConnected = isWifi) }
        mdnsDiscovery.startDiscovery()
        blePresence.startScanning()
        bleGattClient.start()
    }

    fun stopDiscovery() {
        _uiState.update { it.copy(isScanning = false) }
        mdnsDiscovery.stopDiscovery()
        blePresence.stopScanning()
        bleGattClient.stop()
    }

    private fun startPeriodicMaintenance() {
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                refreshOnlineStatuses()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(90_000L)
                // Re-attempt handshakes periodically so trusted displays recover after reboots.
                autoHandshakeAttempted.clear()
                autoConnectTrustedDisplays()
            }
        }
    }

    private fun refreshOnlineStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (display in _uiState.value.pairedDisplays) {
                var online = false
                if (display.ipAddress.isNotBlank()) {
                    online = transferClient.pingDisplay(display)
                }
                val bleLast = display.bleId.takeIf { it.isNotBlank() }?.let { lastBleSeen[it] }
                val bleFresh = bleLast != null && now - bleLast < 45_000L
                val finalOnline = online || bleFresh
                if (finalOnline != display.isOnline) {
                    pairingStore.updateDisplayOnlineStatus(display.id, finalOnline)
                }
            }
        }
    }
    // endregion

    // region Discovery merging & trusted auto-connection
    private fun onMdnsDiscovered(mdns: List<DisplayDevice>) {
        // Auto-refresh trusted displays as soon as mDNS resolves them.
        for (disp in mdns) {
            val paired = _uiState.value.pairedDisplays.find { it.id == disp.id }
            if (paired != null) {
                if (paired.ipAddress != disp.ipAddress || paired.port != disp.port ||
                    (disp.bleId.isNotBlank() && paired.bleId != disp.bleId)
                ) {
                    pairingStore.addOrUpdateDisplay(
                        paired.copy(
                            ipAddress = disp.ipAddress,
                            port = disp.port,
                            bleId = disp.bleId.ifBlank { paired.bleId },
                            isOnline = true,
                            connectionType = ConnectionType.WIFI_MDNS,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    )
                } else {
                    pairingStore.updateDisplayOnlineStatus(paired.id, true)
                }
            }
        }
        rebuildDiscoveredList(mdns, blePresence.nearbySights.value, bleGattClient.pairingTokens.value)
        autoConnectTrustedDisplays()
    }

    private fun onBleSights(sights: List<BleSight>) {
        val now = System.currentTimeMillis()
        for (sight in sights) {
            lastBleSeen[sight.bleId] = sight.lastSeen
            bleGattClient.offer(sight)

            val paired = pairingStore.findPairedDisplayByBleId(sight.bleId)
            if (paired != null) {
                // Trusted display physically nearby — mark present even before WiFi resolves.
                pairingStore.updateDisplayOnlineStatus(paired.id, true)
            }
        }
        rebuildDiscoveredList(
            mdnsDiscovery.discoveredDisplays.value,
            sights,
            bleGattClient.pairingTokens.value
        )
        // Prune stale sight bookkeeping.
        lastBleSeen.entries.removeAll { now - it.value > 60_000L }
    }

    private fun onGattTokens(gattDevices: List<DisplayDevice>) {
        for (device in gattDevices) {
            val pairedById = _uiState.value.pairedDisplays.find { it.id == device.id }
            val pairedByBle = _uiState.value.pairedDisplays
                .find { it.bleId.isNotBlank() && it.bleId == device.bleId }
            val paired = pairedById ?: pairedByBle
            if (paired != null) {
                // The GATT token may carry the canonical device id while the placeholder
                // was stored under "ble:<id>" — drop the stale entry first.
                if (paired.id != device.id) pairingStore.removeDisplay(paired.id)
                // Trusted device re-introduced itself over Bluetooth: adopt its fresh
                // network endpoint and key without user interaction.
                pairingStore.addOrUpdateDisplay(
                    paired.copy(
                        id = device.id,
                        name = device.name.ifBlank { paired.name },
                        ipAddress = device.ipAddress.ifBlank { paired.ipAddress },
                        port = device.port,
                        sharedKey = device.sharedKey.ifBlank { paired.sharedKey },
                        bleId = device.bleId.ifBlank { paired.bleId },
                        isOnline = true,
                        connectionType = ConnectionType.BLE_GATT,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                )
                autoHandshakeAttempted.add(device.id)
            }
        }
        rebuildDiscoveredList(
            mdnsDiscovery.discoveredDisplays.value,
            blePresence.nearbySights.value,
            gattDevices
        )
    }

    /**
     * Builds one de-duplicated discovery list from all three transports:
     * GATT tokens (richest, contain IP + key) win over mDNS, which wins over raw BLE beacons.
     */
    private fun rebuildDiscoveredList(
        mdns: List<DisplayDevice>,
        bleSights: List<BleSight>,
        gattDevices: List<DisplayDevice>
    ) {
        val merged = LinkedHashMap<String, DisplayDevice>()

        for (sight in bleSights) {
            val id = "ble:${sight.bleId}"
            merged[id] = DisplayDevice(
                id = id,
                name = "PhotoStand BLE ${sight.bleId.takeLast(4)}",
                ipAddress = "",
                port = sight.port,
                isOnline = true,
                connectionType = ConnectionType.BLE_BEACON,
                bleRssi = sight.rssi,
                bleId = sight.bleId
            )
        }

        for (disp in mdns) {
            val key = disp.bleId.takeIf { it.isNotBlank() }?.let { "ble:$it" } ?: disp.id
            val existing = merged[key]
            merged[key] = if (existing != null) {
                disp.copy(bleRssi = existing.bleRssi, bleId = existing.bleId)
            } else {
                disp
            }
        }

        for (gatt in gattDevices) {
            val key = gatt.bleId.takeIf { it.isNotBlank() }?.let { "ble:$it" } ?: gatt.id
            merged[key] = gatt
        }

        _uiState.update { it.copy(discoveredDisplays = merged.values.toList()) }
    }

    private fun autoConnectTrustedDisplays() {
        val trusted = _uiState.value.pairedDisplays.filter { it.autoConnect }
        for (trustedDevice in trusted) {
            if (autoHandshakeAttempted.contains(trustedDevice.id)) continue
            if (trustedDevice.ipAddress.isBlank()) continue
            autoHandshakeAttempted.add(trustedDevice.id)
            viewModelScope.launch(Dispatchers.IO) {
                val result = transferClient.performHandshake(
                    trustedDevice,
                    pairingStore.getOrCreateDeviceId(),
                    pairingStore.getDeviceName()
                )
                if (result.success) {
                    pairingStore.addOrUpdateDisplay(
                        trustedDevice.copy(
                            sharedKey = result.sharedKey.ifBlank { trustedDevice.sharedKey },
                            bleId = result.bleId.ifBlank { trustedDevice.bleId },
                            isOnline = true
                        )
                    )
                    Log.d(tag, "Auto-connected trusted display ${trustedDevice.name}")
                } else {
                    // Allow a retry on the next maintenance window.
                    autoHandshakeAttempted.remove(trustedDevice.id)
                }
            }
        }
    }
    // endregion

    // region Pairing Flow
    fun pairDiscoveredDisplay(discovered: DisplayDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isSending = true, sendingMessage = "Pairing with ${discovered.name}…")
            }
            val controllerId = pairingStore.getOrCreateDeviceId()
            val controllerLabel = pairingStore.getDeviceName()

            val result: HandshakeResult = if (discovered.ipAddress.isNotBlank()) {
                transferClient.performHandshake(discovered, controllerId, controllerLabel)
            } else {
                HandshakeResult(false)
            }

            val paired = if (result.success) {
                discovered.copy(
                    id = result.displayId.ifBlank { discovered.id },
                    name = result.displayName.ifBlank { discovered.name },
                    sharedKey = result.sharedKey.ifBlank { discovered.sharedKey },
                    bleId = result.bleId.ifBlank { discovered.bleId },
                    isOnline = true,
                    autoConnect = true
                )
            } else {
                // BLE-only devices (no IP yet) are still saved as trusted; a GATT token
                // or mDNS resolution will fill the network endpoint automatically.
                discovered.copy(isOnline = discovered.ipAddress.isNotBlank(), autoConnect = true)
            }
            pairingStore.addOrUpdateDisplay(paired)
            autoHandshakeAttempted.add(paired.id)

            _uiState.update {
                it.copy(
                    isSending = false,
                    showPairingDialog = false,
                    showQrScanner = false,
                    lastTransferResult = TransferResult.Success("Paired with ${paired.name}!")
                )
            }
        }
    }

    fun pairFromToken(tokenJson: String): Boolean {
        val display = DisplayDevice.fromJson(tokenJson) ?: return false
        pairDiscoveredDisplay(display)
        return true
    }

    fun reportPairingError(message: String) {
        _uiState.update {
            it.copy(lastTransferResult = TransferResult.Failure(message))
        }
    }

    fun pairManualIp(ip: String, port: Int, name: String) {
        val device = DisplayDevice(
            id = "disp-" + ip.replace(".", ""),
            name = if (name.isNotBlank()) name else "Display ($ip)",
            ipAddress = ip,
            port = port,
            // No static key: the handshake negotiates the display's real secret.
            sharedKey = "",
            isOnline = true,
            connectionType = ConnectionType.DIRECT_IP
        )
        pairDiscoveredDisplay(device)
    }

    fun renameDisplay(deviceId: String, newName: String) {
        pairingStore.renameDisplay(deviceId, newName)
        _uiState.update { it.copy(renameDialogDevice = null) }
    }

    fun removeDisplay(deviceId: String) {
        pairingStore.removeDisplay(deviceId)
        autoHandshakeAttempted.remove(deviceId)
    }
    // endregion

    // region Photo Transfer Flow
    fun onSharedImageReceived(uri: Uri) {
        _uiState.update {
            it.copy(sharedImageUri = uri, showPickerSheet = true)
        }
    }

    fun sendPhotoToDisplay(display: DisplayDevice, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isSending = true,
                    sendingMessage = "Printing photo to ${display.name}…",
                    showPickerSheet = false
                )
            }

            val imageBytes = transferClient.readBytesFromUri(imageUri)
            if (imageBytes == null || imageBytes.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        lastTransferResult = TransferResult.Failure("Failed to read image data")
                    )
                }
                return@launch
            }

            // Always use the freshest stored endpoint (IP may have changed via discovery).
            val freshTarget = pairingStore.pairedDisplaysSnapshot()
                .find { it.id == display.id } ?: display
            val senderId = pairingStore.getOrCreateDeviceId()
            val result = transferClient.sendPhoto(freshTarget, senderId, imageBytes)

            _uiState.update {
                it.copy(
                    isSending = false,
                    lastTransferResult = result,
                    sharedImageUri = if (result is TransferResult.Success) null else it.sharedImageUri
                )
            }
        }
    }

    fun dismissTransferResult() {
        _uiState.update { it.copy(lastTransferResult = null) }
    }

    fun setShowPairingDialog(show: Boolean) {
        _uiState.update { it.copy(showPairingDialog = show) }
    }

    fun setShowQrScanner(show: Boolean) {
        _uiState.update { it.copy(showQrScanner = show) }
    }

    fun setShowPickerSheet(show: Boolean) {
        _uiState.update { it.copy(showPickerSheet = show) }
    }

    fun setRenameDialogDevice(device: DisplayDevice?) {
        _uiState.update { it.copy(renameDialogDevice = device) }
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
