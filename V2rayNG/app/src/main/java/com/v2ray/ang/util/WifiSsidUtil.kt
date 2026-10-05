package com.v2ray.ang.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * Reads the currently-connected Wi-Fi SSID and checks it against the user's trusted-Wi-Fi list.
 * Used by both [com.v2ray.ang.core.CoreConfigManager] (to decide routing at config-build time)
 * and [com.v2ray.ang.service.CoreVpnService] (to detect SSID changes and trigger a restart).
 */
object WifiSsidUtil {

    /**
     * Returns the current Wi-Fi SSID (unquoted), or null if not connected to Wi-Fi, the SSID is
     * unavailable, or ACCESS_FINE_LOCATION isn't granted (Android hides the real SSID without it —
     * fails closed, meaning the trusted-Wi-Fi feature simply won't trigger rather than misfire).
     */
    fun currentSsid(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: ACCESS_FINE_LOCATION not granted, cannot read SSID")
            return null
        }
        val locationManager = context.getSystemService(LocationManager::class.java)
        if (locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager)) {
            // The system-wide Location toggle is independent of the app's own permission grant —
            // Android withholds SSID from every app while it's off, regardless of what the app holds.
            LogUtil.w(AppConfig.TAG, "TrustedWifi: system Location services are OFF — SSID will stay unavailable")
        }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: no ConnectivityManager")
            return null
        }
        val network = connectivityManager.activeNetwork
        if (network == null) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: no active network")
            return null
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: no capabilities for active network")
            return null
        }
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: active network is not Wi-Fi")
            return null
        }
        // WifiManager.getConnectionInfo() (not NetworkCapabilities.getTransportInfo()) deliberately —
        // Android 12+ applies a separate "redaction" layer to capabilities delivered via
        // ConnectivityManager that can withhold SSID/BSSID even with the right permissions granted;
        // the older WifiManager path doesn't carry that extra redaction behavior.
        @Suppress("DEPRECATION")
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: no WifiManager")
            return null
        }
        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid
        if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) {
            LogUtil.w(AppConfig.TAG, "TrustedWifi: SSID unavailable (raw=\"$ssid\") — check system Location toggle is ON and app has \"Allow all the time\"")
            return null
        }
        val resolved = ssid.trim('"')
        LogUtil.w(AppConfig.TAG, "TrustedWifi: current SSID resolved to \"$resolved\"")
        return resolved
    }

    /**
     * DNS servers of the current (non-VPN) Wi-Fi network, as DHCP handed them out — usually the
     * router itself. On trusted Wi-Fi these replace the app's configured DNS so resolution flows
     * through the router exactly as it would with the VPN off (critical for router-side
     * DNS-based policy routing like OpenWrt/podkop, whose ipsets are populated by dnsmasq —
     * resolving via 1.1.1.1 directly would bypass it and break the router's own tunnel routing).
     * Scoped link-local addresses (fe80::…%wlan0) are dropped — VpnService.Builder rejects them.
     */
    fun wifiDnsServers(context: Context): List<String> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val dns = connectivityManager.getLinkProperties(network)?.dnsServers.orEmpty()
                .mapNotNull { it.hostAddress }
                .filter { !it.contains('%') }
            if (dns.isNotEmpty()) {
                LogUtil.w(AppConfig.TAG, "TrustedWifi: Wi-Fi DHCP DNS servers = $dns")
                return dns
            }
        }
        LogUtil.w(AppConfig.TAG, "TrustedWifi: no Wi-Fi DHCP DNS servers found, falling back to app DNS settings")
        return emptyList()
    }

    /** Parses the user's comma/newline-separated trusted SSID list, trimmed and non-empty only. */
    fun trustedSsids(): List<String> =
        MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_TRUSTED_WIFI_SSIDS)
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** True if the trusted-Wi-Fi feature is enabled and the device is currently on one of the listed SSIDs. */
    fun isOnTrustedWifi(context: Context): Boolean {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_TRUSTED_WIFI_ENABLED, false)) return false
        val ssid = currentSsid(context)
        val trusted = trustedSsids()
        val result = ssid != null && trusted.any { it.equals(ssid, ignoreCase = true) }
        LogUtil.w(AppConfig.TAG, "TrustedWifi: enabled=true ssid=\"$ssid\" trustedList=$trusted -> onTrustedWifi=$result")
        return result
    }
}
