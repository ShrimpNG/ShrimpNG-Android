package com.v2ray.ang.util

/** Pure allow/deny-list overlay used by VpnService for packages with firewall rules. */
object FirewallTunnelSelection {

    fun apply(
        selectedApps: Set<String>,
        bypassMode: Boolean,
        firewallApps: Set<String>,
    ): MutableSet<String> = selectedApps.toMutableSet().apply {
        if (bypassMode) {
            removeAll(firewallApps)
        } else {
            addAll(firewallApps)
        }
    }
}
