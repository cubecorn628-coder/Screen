// File: app/src/main/java/com/example/photostand/display/DisplayViewModel.kt
package com.example.photostand.display

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photostand.core.AnimationType
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.FitMode
import com.example.photostand.p2p.NetworkUtils
import com.example.photostand.p2p.discovery.BlePresence
import com.example.photostand.p2p.discovery.MdnsDiscovery
import com.example.photostand.p2p.pairing.PairingStore
import com.example.photostand.p2p.transfer.DisplayReceiveServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

import com.example.photostand.core.db.PhotoStandDatabase
import com.example.photostand.core.db.SharedPhotoEntity
import com.example.photostand.core.db.SharedPhotoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

data class DisplayUiState(
    val currentPhotoPath: String? = null,
    val brightness: Float = 0.85f,
    val autoDimEnabled: Boolean = true,
    val effectiveBrightness: Float = 0.85f,
    val fitMode: FitMode = FitMode.CROP_FILL,
    val animationType: AnimationType = AnimationType.PAPER_SCREEN,
    val showClock: Boolean = true,
    val showSettingsSheet: Boolean = false,
    val showPairingQrDialog: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    val localIp: String = "127.0.0.1",
    val port: Int = Constants.DEFAULT_HTTP_PORT,
    val pairingToken: String = "",
    val newPhotoTrigger: Long = 0L,
    val isServerRunning: Boolean = false,
    val isWifiConnected: Boolean = true
)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val pairingStore = PairingStore(application)
    private val mdnsDiscovery = MdnsDiscovery(application, viewModelScope)
    private val blePresence = BlePresence(application)

    private val database = PhotoStandDatabase.getDatabase(application)
    val photoRepository = SharedPhotoRepository(database.sharedPhotoDao())

    val savedPhotos: StateFlow<List<SharedPhotoEntity>> = photoRepository.allPhotos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(
        DisplayUiState(
            brightness = pairingStore.getBrightness(),
            autoDimEnabled = pairingStore.isAutoDimEnabled(),
            fitMode = pairingStore.getFitMode(),
            animationType = pairingStore.getAnimationType(),
            showClock = pairingStore.isShowClock(),
            deviceId = pairingStore.getOrCreateDeviceId(),
            deviceName = pairingStore.getDeviceName()
        )
    )
    val uiState: StateFlow<DisplayUiState> = _uiState.asStateFlow()

    private val receiveServer = DisplayReceiveServer(
        context = application,
        pairingStore = pairingStore,
        photoRepository = photoRepository,
        scope = viewModelScope,
        onPhotoReceived = { path ->
            onNewPhotoReceived(path)
        }
    )

    init {
        observeLatestPhoto()
        startServices()
        startAutoDimScheduler()
    }

    private fun observeLatestPhoto() {
        viewModelScope.launch {
            photoRepository.latestPhoto.collect { photo ->
                if (photo != null) {
                    _uiState.update { it.copy(currentPhotoPath = photo.filePath) }
                } else {
                    loadFallbackDiskPhoto()
                }
            }
        }
    }

    private fun loadFallbackDiskPhoto() {
        val photosDir = File(getApplication<Application>().filesDir, "photos")
        if (photosDir.exists()) {
            val photos = photosDir.listFiles { file -> file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png")) }
            val latest = photos?.maxByOrNull { it.lastModified() }
            if (latest != null) {
                _uiState.update { it.copy(currentPhotoPath = latest.absolutePath) }
            }
        }
    }

    fun startServices() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getLocalIpAddress()
            val deviceId = pairingStore.getOrCreateDeviceId()
            val deviceName = pairingStore.getDeviceName()
            val secret = pairingStore.getOrCreateDeviceSecret()
            val port = Constants.DEFAULT_HTTP_PORT
            val isWifi = NetworkUtils.isWifiConnected(getApplication())

            val pairingDevice = DisplayDevice(
                id = deviceId,
                name = deviceName,
                ipAddress = ip,
                port = port,
                sharedKey = secret
            )

            // Start HTTP Receive Server
            receiveServer.start(port)

            // Start mDNS Registration
            mdnsDiscovery.registerDisplayService(deviceId, deviceName, port)

            // Start BLE Presence Broadcast
            blePresence.startAdvertising(deviceId)

            _uiState.update {
                it.copy(
                    localIp = ip,
                    port = port,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    pairingToken = pairingDevice.toJson(),
                    isServerRunning = receiveServer.isRunning,
                    isWifiConnected = isWifi,
                    effectiveBrightness = computeEffectiveBrightness(it.brightness, it.autoDimEnabled)
                )
            }
        }
    }

    private fun onNewPhotoReceived(path: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    currentPhotoPath = path,
                    newPhotoTrigger = System.currentTimeMillis()
                )
            }
        }
    }

    // region Settings Actions
    fun setBrightness(value: Float) {
        val clamped = value.coerceIn(0.05f, 1.0f)
        pairingStore.setBrightness(clamped)
        _uiState.update {
            it.copy(
                brightness = clamped,
                effectiveBrightness = computeEffectiveBrightness(clamped, it.autoDimEnabled)
            )
        }
    }

    fun setAutoDimEnabled(enabled: Boolean) {
        pairingStore.setAutoDimEnabled(enabled)
        _uiState.update {
            it.copy(
                autoDimEnabled = enabled,
                effectiveBrightness = computeEffectiveBrightness(it.brightness, enabled)
            )
        }
    }

    fun setFitMode(mode: FitMode) {
        pairingStore.setFitMode(mode)
        _uiState.update { it.copy(fitMode = mode) }
    }

    fun setAnimationType(type: AnimationType) {
        pairingStore.setAnimationType(type)
        _uiState.update { it.copy(animationType = type) }
    }

    fun toggleShowClock() {
        val next = !_uiState.value.showClock
        pairingStore.setShowClock(next)
        _uiState.update { it.copy(showClock = next) }
    }

    fun toggleSettingsSheet() {
        _uiState.update { it.copy(showSettingsSheet = !it.showSettingsSheet) }
    }

    fun setShowPairingQr(show: Boolean) {
        _uiState.update { it.copy(showPairingQrDialog = show) }
    }

    fun updateDeviceName(name: String) {
        pairingStore.setDeviceName(name)
        _uiState.update { it.copy(deviceName = name) }
        // Re-announce mDNS
        mdnsDiscovery.registerDisplayService(uiState.value.deviceId, name, uiState.value.port)
    }
    // endregion

    private fun startAutoDimScheduler() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L) // Check every minute
                val current = _uiState.value
                val newEffective = computeEffectiveBrightness(current.brightness, current.autoDimEnabled)
                if (newEffective != current.effectiveBrightness) {
                    _uiState.update { it.copy(effectiveBrightness = newEffective) }
                }
            }
        }
    }

    private fun computeEffectiveBrightness(baseBrightness: Float, autoDim: Boolean): Float {
        if (!autoDim) return baseBrightness
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour >= Constants.AUTO_DIM_START_HOUR || hour < Constants.AUTO_DIM_END_HOUR
        return if (isNight) Constants.AUTO_DIM_BRIGHTNESS else baseBrightness
    }

    override fun onCleared() {
        super.onCleared()
        receiveServer.stop()
        mdnsDiscovery.unregisterDisplayService()
        blePresence.stopAdvertising()
    }
}
