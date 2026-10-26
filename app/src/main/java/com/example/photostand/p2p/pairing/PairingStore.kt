// File: app/src/main/java/com/example/photostand/p2p/pairing/PairingStore.kt
package com.example.photostand.p2p.pairing

import android.content.Context
import android.content.SharedPreferences
import com.example.photostand.core.AnimationType
import com.example.photostand.core.AppMode
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.FitMode
import com.example.photostand.core.PairedController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class PairingStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val controllerLock = Any()
    private val replayLock = Any()
    private val replayNoncePattern = Regex("[0-9a-f]{32}")

    private val _pairedDisplays = MutableStateFlow<List<DisplayDevice>>(loadPairedDisplays())
    val pairedDisplays: StateFlow<List<DisplayDevice>> = _pairedDisplays.asStateFlow()

    fun pairedDisplaysSnapshot(): List<DisplayDevice> = _pairedDisplays.value

    private val _pairedControllers = MutableStateFlow<List<PairedController>>(loadPairedControllers())
    val pairedControllers: StateFlow<List<PairedController>> = _pairedControllers.asStateFlow()

    // region Device Identity
    fun getOrCreateDeviceId(): String {
        var id = prefs.getString(Constants.KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = "ps-" + PairingCrypto.generateDeviceId()
            prefs.edit().putString(Constants.KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(): String {
        return prefs.getString(Constants.KEY_DEVICE_NAME, null) ?: ("PhotoStand " + android.os.Build.MODEL)
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString(Constants.KEY_DEVICE_NAME, name).apply()
    }

    fun getOrCreateDeviceSecret(): String {
        var secret = prefs.getString(Constants.KEY_DEVICE_SECRET, null)
        if (secret.isNullOrEmpty()) {
            secret = PairingCrypto.generateRandomKey()
            prefs.edit().putString(Constants.KEY_DEVICE_SECRET, secret).apply()
        }
        return secret
    }

    /**
     * Stable 6-byte BLE identity (12 hex chars). Survives app restarts so trusted
     * controllers can recognise this display purely from its Bluetooth advertisement.
     */
    fun getOrCreateBleId(): String {
        var bleId = prefs.getString(Constants.KEY_BLE_ID, null)
        if (bleId.isNullOrEmpty()) {
            bleId = PairingCrypto.generateDeviceId()
            prefs.edit().putString(Constants.KEY_BLE_ID, bleId).apply()
        }
        return bleId
    }
    // endregion

    // region App Mode
    fun getAppMode(): AppMode {
        val modeStr = prefs.getString(Constants.KEY_APP_MODE, AppMode.UNSELECTED.name)
        return try {
            AppMode.valueOf(modeStr ?: AppMode.UNSELECTED.name)
        } catch (e: Exception) {
            AppMode.UNSELECTED
        }
    }

    fun setAppMode(mode: AppMode) {
        prefs.edit().putString(Constants.KEY_APP_MODE, mode.name).apply()
    }
    // endregion

    // region Paired Displays (Controller Side)
    fun addOrUpdateDisplay(device: DisplayDevice) {
        val current = _pairedDisplays.value.toMutableList()
        val index = current.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            val existing = current[index]
            current[index] = device.copy(
                name = if (device.name.isNotBlank()) device.name else existing.name,
                sharedKey = if (device.sharedKey.isNotBlank()) device.sharedKey else existing.sharedKey
            )
        } else {
            current.add(device)
        }
        saveDisplays(current)
    }

    fun renameDisplay(deviceId: String, newName: String) {
        val current = _pairedDisplays.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(name = newName)
            saveDisplays(current)
        }
    }

    fun removeDisplay(deviceId: String) {
        val current = _pairedDisplays.value.filter { it.id != deviceId }
        saveDisplays(current)
    }

    fun updateDisplayIp(deviceId: String, ip: String, port: Int = Constants.DEFAULT_HTTP_PORT) {
        val current = _pairedDisplays.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(
                ipAddress = ip,
                port = port,
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            saveDisplays(current)
        }
    }

    fun updateDisplayOnlineStatus(deviceId: String, isOnline: Boolean) {
        val current = _pairedDisplays.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(isOnline = isOnline)
            _pairedDisplays.value = current
        }
    }

    /** Trusted display lookup by its stable BLE identity (used by BLE auto-connect). */
    fun findPairedDisplayByBleId(bleId: String): DisplayDevice? =
        _pairedDisplays.value.find { it.bleId.isNotBlank() && it.bleId.equals(bleId, ignoreCase = true) }

    fun isTrustedDisplay(deviceId: String): Boolean =
        _pairedDisplays.value.any { it.id == deviceId && it.autoConnect }

    private fun loadPairedDisplays(): List<DisplayDevice> {
        val raw = prefs.getString(Constants.KEY_PAIRED_DISPLAYS, "[]") ?: "[]"
        val list = mutableListOf<DisplayDevice>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = DisplayDevice.fromJson(arr.getString(i))
                if (item != null) list.add(item)
            }
        } catch (e: Exception) {
            // Ignore corrupted json
        }
        return list
    }

    private fun saveDisplays(list: List<DisplayDevice>) {
        _pairedDisplays.value = list
        val arr = JSONArray()
        for (item in list) {
            arr.put(item.toJson())
        }
        prefs.edit().putString(Constants.KEY_PAIRED_DISPLAYS, arr.toString()).apply()
    }
    // endregion

    // region Paired Controllers (Display Side)
    /** Enrolls only a new controller proving the device secret, or refreshes an existing one
     * proving its already stored key. Existing keys are never replaced by this operation.
     */
    fun registerAuthenticatedController(controllerId: String, label: String, provenKey: String): Boolean =
        synchronized(controllerLock) {
            if (controllerId.isBlank() || provenKey.isBlank()) return@synchronized false
            val current = _pairedControllers.value.toMutableList()
            val index = current.indexOfFirst { it.controllerId == controllerId }
            if (index >= 0) {
                val existing = current[index]
                if (!PairingCrypto.constantTimeEquals(existing.sharedKey, provenKey)) return@synchronized false
                current[index] = existing.copy(label = label.ifBlank { existing.label })
            } else {
                val deviceSecret = getOrCreateDeviceSecret()
                if (!PairingCrypto.constantTimeEquals(deviceSecret, provenKey)) return@synchronized false
                current.add(PairedController(controllerId, label, deviceSecret))
            }
            saveControllers(current)
        }

    fun isControllerAuthorized(controllerId: String): Boolean = synchronized(controllerLock) {
        controllerId.isNotBlank() && _pairedControllers.value.any { it.controllerId == controllerId }
    }

    fun pairedControllersSnapshot(): List<PairedController> = synchronized(controllerLock) {
        _pairedControllers.value.toList()
    }

    fun getControllerKey(controllerId: String): String? = synchronized(controllerLock) {
        val found = _pairedControllers.value.find { it.controllerId == controllerId }
        found?.sharedKey
    }

    /** Atomically consumes and durably records a nonce before transfer side effects occur. */
    fun consumeTransferNonce(controllerId: String, nonce: String, nowMs: Long, maxAgeMs: Long): Boolean =
        synchronized(replayLock) {
            if (controllerId.isBlank() || !replayNoncePattern.matches(nonce) || maxAgeMs <= 0L) {
                return@synchronized false
            }

            val raw = prefs.getString(Constants.KEY_TRANSFER_REPLAY_STATE, "[]") ?: "[]"
            val retained = JSONArray()
            val cutoff = nowMs - (maxAgeMs * 2L)
            try {
                val stored = JSONArray(raw)
                for (index in 0 until stored.length()) {
                    val entry = stored.getJSONObject(index)
                    val seenAt = entry.optLong("seenAt", Long.MIN_VALUE)
                    if (seenAt >= cutoff) {
                        if (entry.optString("controllerId") == controllerId && entry.optString("nonce") == nonce) {
                            return@synchronized false
                        }
                        retained.put(entry)
                    }
                }
            } catch (e: Exception) {
                // Corrupt replay state fails closed instead of silently reopening old requests.
                return@synchronized false
            }

            if (retained.length() >= Constants.MAX_RECORDED_TRANSFER_NONCES) return@synchronized false
            retained.put(org.json.JSONObject().apply {
                put("controllerId", controllerId)
                put("nonce", nonce)
                put("seenAt", nowMs)
            })
            prefs.edit().putString(Constants.KEY_TRANSFER_REPLAY_STATE, retained.toString()).commit()
        }

    private fun loadPairedControllers(): List<PairedController> {
        val raw = prefs.getString(Constants.KEY_PAIRED_CONTROLLERS, "[]") ?: "[]"
        val list = mutableListOf<PairedController>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = PairedController.fromJson(arr.getString(i))
                if (item != null) list.add(item)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return list
    }

    private fun saveControllers(list: List<PairedController>): Boolean {
        val arr = JSONArray()
        for (item in list) {
            arr.put(item.toJson())
        }
        if (!prefs.edit().putString(Constants.KEY_PAIRED_CONTROLLERS, arr.toString()).commit()) return false
        _pairedControllers.value = list
        return true
    }
    // endregion

    // region Display Settings
    fun getBrightness(): Float = prefs.getFloat(Constants.KEY_BRIGHTNESS, 0.85f)
    fun setBrightness(value: Float) = prefs.edit().putFloat(Constants.KEY_BRIGHTNESS, value).apply()

    fun isAutoDimEnabled(): Boolean = prefs.getBoolean(Constants.KEY_AUTO_DIM, true)
    fun setAutoDimEnabled(enabled: Boolean) = prefs.edit().putBoolean(Constants.KEY_AUTO_DIM, enabled).apply()

    fun getFitMode(): FitMode {
        val str = prefs.getString(Constants.KEY_FIT_MODE, FitMode.CROP_FILL.name)
        return try { FitMode.valueOf(str ?: FitMode.CROP_FILL.name) } catch (e: Exception) { FitMode.CROP_FILL }
    }
    fun setFitMode(mode: FitMode) = prefs.edit().putString(Constants.KEY_FIT_MODE, mode.name).apply()

    fun getAnimationType(): AnimationType {
        val str = prefs.getString(Constants.KEY_ANIMATION_TYPE, AnimationType.PAPER_SCREEN.name)
        return try { AnimationType.valueOf(str ?: AnimationType.PAPER_SCREEN.name) } catch (e: Exception) { AnimationType.PAPER_SCREEN }
    }
    fun setAnimationType(type: AnimationType) = prefs.edit().putString(Constants.KEY_ANIMATION_TYPE, type.name).apply()

    fun isShowClock(): Boolean = prefs.getBoolean(Constants.KEY_SHOW_CLOCK, true)
    fun setShowClock(show: Boolean) = prefs.edit().putBoolean(Constants.KEY_SHOW_CLOCK, show).apply()

    // region Always-On Display (AOD)
    fun isAodEnabled(): Boolean = prefs.getBoolean(Constants.KEY_AOD_ENABLED, true)
    fun setAodEnabled(enabled: Boolean) = prefs.edit().putBoolean(Constants.KEY_AOD_ENABLED, enabled).apply()

    fun isAodNightRedEnabled(): Boolean = prefs.getBoolean(Constants.KEY_AOD_NIGHT_RED, true)
    fun setAodNightRedEnabled(enabled: Boolean) = prefs.edit().putBoolean(Constants.KEY_AOD_NIGHT_RED, enabled).apply()

    fun getAodTimeoutSeconds(): Int =
        prefs.getInt(Constants.KEY_AOD_TIMEOUT_SECONDS, Constants.AOD_DEFAULT_TIMEOUT_SECONDS)
    fun setAodTimeoutSeconds(seconds: Int) =
        prefs.edit().putInt(Constants.KEY_AOD_TIMEOUT_SECONDS, seconds).apply()
    // endregion
}
