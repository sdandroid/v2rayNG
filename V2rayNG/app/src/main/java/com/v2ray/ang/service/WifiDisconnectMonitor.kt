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
 * Disconnects a running service on matching Wi-Fi SSIDs.
 *
 * Network callbacks are the fast path. Explicit rechecks and a 30-second fallback
 * cover permission/settings changes and OEMs that suppress SSID callback updates.
 * This monitor does not start or reconnect a stopped service.
 */
class WifiDisconnectMonitor(
    context: Context,
    private val onMatched: () -> Unit,
) {
    private companion object {
        const val RECHECK_INTERVAL_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    @Volatile private var stopped = false

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val periodicCheck = object : Runnable {
        override fun run() {
            if (stopped || !registered) return
            checkCurrentWifi("periodic")
            handler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    // Android 12+ needs the flag to receive SSID in callback NetworkCapabilities.
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
            // Do not synchronously query network capabilities from callback threads:
            // the framework may not have finished publishing the new capabilities.
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (stopped || !enabled() || !hasFineLocation()) return
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (capabilities.transportInfo as? WifiInfo)?.ssid
            } else {
                null
            }
            if (knownSsid(ssid)) {
                maybeDisconnect(ssid!!.removeSurrounding("\""))
            } else {
                refresh()
            }
        }
    }

    fun register() {
        if (registered || stopped) return
        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
            refresh()
            handler.postDelayed(periodicCheck, RECHECK_INTERVAL_MS)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi auto-disconnect: registration failed", e)
        }
    }

    fun unregister() {
        stopped = true
        handler.removeCallbacks(periodicCheck)
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi auto-disconnect: unregister failed", e)
        }
    }

    /** Called after settings/permission changes or when the main UI resumes. */
    fun refresh() {
        if (stopped || !registered) return
        handler.post {
            if (!stopped && registered) checkCurrentWifi("refresh")
        }
    }

    @Suppress("DEPRECATION")
    private fun checkCurrentWifi(reason: String) {
        if (!enabled()) return
        if (!hasFineLocation()) {
            LogUtil.w(AppConfig.TAG, "Wi-Fi auto-disconnect: location permission missing")
            return
        }
        if (configuredSsids().isEmpty()) {
            LogUtil.w(AppConfig.TAG, "Wi-Fi auto-disconnect: SSID list is empty")
            return
        }

        try {
            // WifiManager reflects the actual currently associated access point.
            val currentSsid = wifiManager.connectionInfo?.ssid
            if (knownSsid(currentSsid)) {
                maybeDisconnect(currentSsid!!.removeSurrounding("\""))
                return
            }

            // Some Android versions expose the SSID only through network capabilities.
            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val ssid = (capabilities.transportInfo as? WifiInfo)?.ssid
                if (knownSsid(ssid)) {
                    maybeDisconnect(ssid!!.removeSurrounding("\""))
                    return
                }
            }
            LogUtil.w(AppConfig.TAG, "Wi-Fi auto-disconnect: SSID unavailable ($reason). Check precise and background location permissions, and system location.")
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi auto-disconnect: cannot read Wi-Fi SSID ($reason)", e)
        } catch (e: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "Wi-Fi auto-disconnect: Wi-Fi state check failed ($reason)", e)
        }
    }

    private fun maybeDisconnect(ssid: String) {
        if (ssid !in configuredSsids()) return
        handler.post {
            if (stopped || !registered || !enabled() || ssid !in configuredSsids()) return@post
            stopped = true
            handler.removeCallbacks(periodicCheck)
            LogUtil.i(AppConfig.TAG, "Wi-Fi auto-disconnect: configured SSID matched; stopping the service")
            onMatched()
        }
    }

    private fun enabled() = MmkvManager.decodeSettingsBool(AppConfig.PREF_WIFI_AUTO_DISCONNECT, false)

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun knownSsid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.removeSurrounding("\"")
        return normalized.isNotEmpty() && normalized != WifiManager.UNKNOWN_SSID.removeSurrounding("\"")
    }

    private fun configuredSsids(): Set<String> =
        MmkvManager.decodeSettingsString(AppConfig.PREF_WIFI_DISCONNECT_SSIDS, "")
            .orEmpty().lines()
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toSet()
}
