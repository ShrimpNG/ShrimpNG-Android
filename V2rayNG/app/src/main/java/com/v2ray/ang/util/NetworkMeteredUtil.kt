package com.v2ray.ang.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the active default network looks like Wi‑Fi/unmetered vs cellular/metered. */
object NetworkMeteredUtil {

    enum class Kind {
        WIFI,
        MOBILE,
        UNKNOWN,
    }

    fun currentKind(context: Context): Kind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Kind.UNKNOWN
        val network = cm.activeNetwork ?: return Kind.UNKNOWN
        val caps = cm.getNetworkCapabilities(network) ?: return Kind.UNKNOWN
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            wifi || unmetered -> Kind.WIFI
            cellular -> Kind.MOBILE
            else -> Kind.UNKNOWN
        }
    }
}
