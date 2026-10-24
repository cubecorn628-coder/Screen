// File: app/src/main/java/com/example/photostand/p2p/discovery/MdnsDiscovery.kt
package com.example.photostand.p2p.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.photostand.core.ConnectionType
import com.example.photostand.core.Constants
import com.example.photostand.core.DisplayDevice
import com.example.photostand.p2p.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MdnsDiscovery(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "MdnsDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredDisplays = MutableStateFlow<List<DisplayDevice>>(emptyList())
    val discoveredDisplays: StateFlow<List<DisplayDevice>> = _discoveredDisplays.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var isRegistered = false

    // region Display Service Registration
    fun registerDisplayService(deviceId: String, deviceName: String, port: Int) {
        if (nsdManager == null) return
        unregisterDisplayService()

        NetworkUtils.acquireMulticastLock(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${Constants.MDNS_SERVICE_NAME_PREFIX}$deviceId"
            serviceType = Constants.MDNS_SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("v", Constants.PROTOCOL_VERSION.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                isRegistered = true
                Log.d(tag, "PhotoStand Display mDNS registered: ${info?.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                isRegistered = false
                Log.e(tag, "PhotoStand mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo?) {
                isRegistered = false
                Log.d(tag, "PhotoStand mDNS unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.e(tag, "PhotoStand mDNS unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Exception registering mDNS", e)
        }
    }

    fun unregisterDisplayService() {
        if (nsdManager != null && isRegistered && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering mDNS", e)
            } finally {
                registrationListener = null
                isRegistered = false
            }
        }
    }
    // endregion

    // region Controller Discovery
    fun startDiscovery() {
        if (nsdManager == null || isDiscovering) return
        NetworkUtils.acquireMulticastLock(context)
        isDiscovering = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                Log.d(tag, "Service discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                if (service == null) return
                if (service.serviceType.contains("photostand") || service.serviceName.startsWith(Constants.MDNS_SERVICE_NAME_PREFIX)) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                if (service == null) return
                scope.launch(Dispatchers.IO) {
                    val current = _discoveredDisplays.value.toMutableList()
                    val toRemove = current.filter { it.name == service.serviceName || service.serviceName.contains(it.id) }
                    if (toRemove.isNotEmpty()) {
                        current.removeAll(toRemove)
                        _discoveredDisplays.value = current
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                isDiscovering = false
                Log.d(tag, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isDiscovering = false
                Log.e(tag, "Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isDiscovering = false
                Log.e(tag, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(Constants.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Exception starting mDNS discovery", e)
            isDiscovering = false
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo?, errorCode: Int) {
                Log.w(tag, "Resolve failed for ${service?.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                if (resolved == null) return
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                val attributes = resolved.attributes
                val deviceId = attributes["id"]?.let { String(it) }
                    ?: resolved.serviceName.removePrefix(Constants.MDNS_SERVICE_NAME_PREFIX)
                val deviceName = attributes["name"]?.let { String(it) } ?: resolved.serviceName

                scope.launch(Dispatchers.IO) {
                    val device = DisplayDevice(
                        id = deviceId,
                        name = deviceName,
                        ipAddress = host,
                        port = port,
                        isOnline = true,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        connectionType = ConnectionType.WIFI_MDNS
                    )

                    val current = _discoveredDisplays.value.toMutableList()
                    val index = current.indexOfFirst { it.id == device.id }
                    if (index >= 0) {
                        current[index] = device
                    } else {
                        current.add(device)
                    }
                    _discoveredDisplays.value = current
                }
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(tag, "Exception resolving mDNS service", e)
        }
    }

    fun stopDiscovery() {
        if (nsdManager != null && isDiscovering && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(tag, "Error stopping discovery", e)
            } finally {
                discoveryListener = null
                isDiscovering = false
            }
        }
        NetworkUtils.releaseMulticastLock()
    }
    // endregion
}
