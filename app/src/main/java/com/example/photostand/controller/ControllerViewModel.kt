// File: app/src/main/java/com/example/photostand/controller/ControllerViewModel.kt
package com.example.photostand.controller

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.TransferResult
import com.example.photostand.p2p.NetworkUtils
import com.example.photostand.p2p.discovery.BlePresence
import com.example.photostand.p2p.discovery.MdnsDiscovery
import com.example.photostand.p2p.pairing.PairingStore
import com.example.photostand.p2p.transfer.PhotoTransferClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream

data class ControllerUiState(
    val pairedDisplays: List<DisplayDevice> = emptyList(),
    val discoveredDisplays: List<DisplayDevice> = emptyList(),
    val isScanning: Boolean = false,
    val isSending: Boolean = false,
    val sendingMessage: String = "",
    val lastTransferResult: TransferResult? = null,
    val sharedImageUri: Uri? = null,
    val showPairingDialog: Boolean = false,
    val showPickerSheet: Boolean = false,
    val selectedDisplayForShare: DisplayDevice? = null,
    val renameDialogDevice: DisplayDevice? = null,
    val isWifiConnected: Boolean = true
)

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "ControllerViewModel"
    private val pairingStore = PairingStore(application)
    private val mdnsDiscovery = MdnsDiscovery(application, viewModelScope)
    private val blePresence = BlePresence(application)
    private val transferClient = PhotoTransferClient(application)

    private val _uiState = MutableStateFlow(ControllerUiState())
    val uiState: StateFlow<ControllerUiState> = _uiState.asStateFlow()

    init {
        // Collect paired displays
        viewModelScope.launch {
            pairingStore.pairedDisplays.collect { displays ->
                _uiState.update { it.copy(pairedDisplays = displays) }
                checkOnlineStatuses(displays)
            }
        }

        // Collect discovered displays from mDNS
        viewModelScope.launch {
            mdnsDiscovery.discoveredDisplays.collect { discovered ->
                _uiState.update { it.copy(discoveredDisplays = discovered) }
                // Auto-update IP of any paired display that moved
                for (disp in discovered) {
                    val paired = _uiState.value.pairedDisplays.find { it.id == disp.id }
                    if (paired != null && (paired.ipAddress != disp.ipAddress || !paired.isOnline)) {
                        pairingStore.updateDisplayIp(disp.id, disp.ipAddress, disp.port)
                    }
                }
            }
        }

        startDiscovery()
        startPeriodicPing()
    }

    fun startDiscovery() {
        val isWifi = NetworkUtils.isWifiConnected(getApplication())
        _uiState.update { it.copy(isScanning = true, isWifiConnected = isWifi) }
        mdnsDiscovery.startDiscovery()
        blePresence.startScanning()
    }

    fun stopDiscovery() {
        _uiState.update { it.copy(isScanning = false) }
        mdnsDiscovery.stopDiscovery()
        blePresence.stopScanning()
    }

    private fun startPeriodicPing() {
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L) // Ping displays every 15s
                checkOnlineStatuses(_uiState.value.pairedDisplays)
            }
        }
    }

    private fun checkOnlineStatuses(displays: List<DisplayDevice>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (display in displays) {
                if (display.ipAddress.isNotBlank()) {
                    val isOnline = transferClient.pingDisplay(display)
                    pairingStore.updateDisplayOnlineStatus(display.id, isOnline)
                }
            }
        }
    }

    // region Pairing Flow
    fun pairDiscoveredDisplay(discovered: DisplayDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSending = true, sendingMessage = "Pairing with ${discovered.name}…") }
            val controllerId = pairingStore.getOrCreateDeviceId()
            val controllerLabel = pairingStore.getDeviceName()

            // Perform cryptographic handshake
            val success = transferClient.performHandshake(discovered, controllerId, controllerLabel)
            if (success) {
                pairingStore.addOrUpdateDisplay(discovered.copy(isOnline = true))
                _uiState.update {
                    it.copy(
                        isSending = false,
                        showPairingDialog = false,
                        lastTransferResult = TransferResult.Success("Paired with ${discovered.name}!")
                    )
                }
            } else {
                // Save anyway with available info
                pairingStore.addOrUpdateDisplay(discovered)
                _uiState.update {
                    it.copy(
                        isSending = false,
                        showPairingDialog = false,
                        lastTransferResult = TransferResult.Success("Added ${discovered.name} to paired list.")
                    )
                }
            }
        }
    }

    fun pairFromToken(tokenJson: String): Boolean {
        val display = DisplayDevice.fromJson(tokenJson) ?: return false
        pairDiscoveredDisplay(display)
        return true
    }

    fun pairManualIp(ip: String, port: Int, name: String) {
        val device = DisplayDevice(
            id = "disp-" + ip.replace(".", ""),
            name = if (name.isNotBlank()) name else "Display ($ip)",
            ipAddress = ip,
            port = port,
            sharedKey = pairingStore.getOrCreateDeviceSecret(),
            isOnline = true
        )
        pairDiscoveredDisplay(device)
    }

    fun renameDisplay(deviceId: String, newName: String) {
        pairingStore.renameDisplay(deviceId, newName)
        _uiState.update { it.copy(renameDialogDevice = null) }
    }

    fun removeDisplay(deviceId: String) {
        pairingStore.removeDisplay(deviceId)
    }
    // endregion

    // region Photo Transfer Flow
    fun onSharedImageReceived(uri: Uri) {
        _uiState.update {
            it.copy(
                sharedImageUri = uri,
                showPickerSheet = true
            )
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

            val senderId = pairingStore.getOrCreateDeviceId()
            val result = transferClient.sendPhoto(display, senderId, imageBytes)

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
