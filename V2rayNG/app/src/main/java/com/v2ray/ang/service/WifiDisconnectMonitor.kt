package com.v2ray.ang.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Monitors connected Wi-Fi while v2rayNG is active and stops the service for configured SSIDs.
 * This class deliberately never reconnects the VPN when leaving a matched network.
 */
class WifiDisconnectMonitor(
    context: Context,
    private val onMatched: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    @Volatile private var stopped = false

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    // Android 12+ redacts location-sensitive WifiInfo without this callback flag.
    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }

    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()
        constructor(flags: Int) : super(flags)

        override fun onAvailable(network: Network) {
            checkNetwork(network, null)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            checkNetwork(network, capabilities)
        }
    }

    fun register() {
        if (registered) return
        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi auto-disconnect: callback registration failed", e)
        }
    }

    fun unregister() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Wi-Fi auto-disconnect: callback unregister failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun checkNetwork(network: Network, caps: NetworkCapabilities?) {
        if (stopped || !MmkvManager.decodeSettingsBool(AppConfig.PREF_WIFI_AUTO_DISCONNECT, false)) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val targets = configuredSsids()
        if (targets.isEmpty()) return

        val capabilities = caps ?: connectivity.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            wifiManager.connectionInfo?.ssid
        }?.removeSurrounding("\"") ?: return

        if (ssid == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || ssid !in targets) return

        // Collapse duplicate onAvailable / onCapabilitiesChanged events.
        handler.post {
            if (stopped || !MmkvManager.decodeSettingsBool(AppConfig.PREF_WIFI_AUTO_DISCONNECT, false)) return@post
            if (ssid !in configuredSsids()) return@post
            stopped = true
            LogUtil.i(AppConfig.TAG, "Wi-Fi auto-disconnect: matched configured SSID; stopping service")
            onMatched()
        }
    }

    private fun configuredSsids(): Set<String> =
        MmkvManager.decodeSettingsString(AppConfig.PREF_WIFI_DISCONNECT_SSIDS, "")
            .orEmpty().lines()
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toSet()
}
