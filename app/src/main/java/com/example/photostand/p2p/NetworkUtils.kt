// File: app/src/main/java/com/example/photostand/p2p/NetworkUtils.kt
package com.example.photostand.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lockHolders = 0

    fun getLocalIpAddress(): String {
        // First pass: prefer a private LAN IPv4 (WiFi / WiFi-Direct group interface).
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: ""
                        if (isPrivateIpv4(hostAddress)) return hostAddress
                    }
                }
            }

            // Fallback: any non-loopback IPv4 (covers hotspot / unusual tether setups).
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val iface = interfaces2.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }

    private fun isPrivateIpv4(host: String): Boolean {
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            return second != null && second in 16..31
        }
        return false
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
    }

    @Synchronized
    fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                multicastLock = wifi.createMulticastLock("PhotoStandMdnsLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            lockHolders++
        } catch (e: Exception) {
            // Ignore on devices where multicast lock fails
        }
    }

    @Synchronized
    fun releaseMulticastLock() {
        if (lockHolders > 0) lockHolders--
        if (lockHolders == 0) {
            try {
                multicastLock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                // Ignore
            } finally {
                multicastLock = null
            }
        }
    }
}
