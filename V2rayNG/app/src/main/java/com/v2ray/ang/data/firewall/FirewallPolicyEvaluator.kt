package com.v2ray.ang.data.firewall

import com.v2ray.ang.util.NetworkMeteredUtil

/** Shared catch-all verdict logic used by Xray rule generation and connection journaling. */
object FirewallPolicyEvaluator {

    fun shouldBlock(status: AppFirewallStatus, network: NetworkMeteredUtil.Kind): Boolean =
        when (status) {
            AppFirewallStatus.BLOCK, AppFirewallStatus.ISOLATE -> true
            AppFirewallStatus.WIFI_ONLY ->
                network == NetworkMeteredUtil.Kind.MOBILE ||
                    network == NetworkMeteredUtil.Kind.UNKNOWN
            AppFirewallStatus.MOBILE_ONLY ->
                network == NetworkMeteredUtil.Kind.WIFI ||
                    network == NetworkMeteredUtil.Kind.UNKNOWN
            AppFirewallStatus.ALLOW, AppFirewallStatus.BYPASS_DIRECT -> false
        }
}
