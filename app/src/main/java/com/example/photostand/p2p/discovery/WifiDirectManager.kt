// File: app/src/main/java/com/example/photostand/p2p/discovery/WifiDirectManager.kt
package com.example.photostand.p2p.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(private val context: Context) {
    private val tag = "WifiDirectManager"
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    init {
        channel = p2pManager?.initialize(context, context.mainLooper, null)
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(onSuccess: () -> Unit = {}, onFailure: (Int) -> Unit = {}) {
        if (p2pManager == null || channel == null) return
        try {
            p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "WiFi Direct peer discovery started")
                    onSuccess()
                }

                override fun onFailure(reason: Int) {
                    Log.w(tag, "WiFi Direct discovery failed: $reason")
                    onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.w(tag, "Missing WiFi Direct permissions", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestPeers() {
        if (p2pManager == null || channel == null) return
        try {
            p2pManager.requestPeers(channel) { peerList: WifiP2pDeviceList? ->
                _peers.value = peerList?.deviceList?.toList() ?: emptyList()
            }
        } catch (e: SecurityException) {
            Log.w(tag, "Missing WiFi Direct permission", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice, onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        if (p2pManager == null || channel == null) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        try {
            p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onSuccess()
                    requestConnectionInfo()
                }

                override fun onFailure(reason: Int) {
                    onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            onFailure(-1)
        }
    }

    fun requestConnectionInfo() {
        if (p2pManager == null || channel == null) return
        p2pManager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
            _connectionInfo.value = info
        }
    }
}
