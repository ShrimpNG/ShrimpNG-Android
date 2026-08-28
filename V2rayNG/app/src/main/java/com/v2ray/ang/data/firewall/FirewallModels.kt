package com.v2ray.ang.data.firewall

/** Per-app firewall posture (Rethink-inspired subset). */
enum class AppFirewallStatus {
    ALLOW,
    BLOCK,
    WIFI_ONLY,
    MOBILE_ONLY,
    ISOLATE,
    BYPASS_DIRECT,
    ;

    companion object {
        fun fromStorage(value: String?): AppFirewallStatus =
            entries.firstOrNull { it.name == value } ?: ALLOW
    }
}

/** Destination rule for a domain or IP (per-app or global). */
enum class DestRuleStatus {
    BLOCK,
    TRUST,
    ;

    companion object {
        fun fromStorage(value: String?): DestRuleStatus =
            entries.firstOrNull { it.name == value } ?: BLOCK
    }
}

data class AppPolicy(
    val packageName: String,
    val uid: Int = -1,
    val status: AppFirewallStatus = AppFirewallStatus.ALLOW,
)

data class CustomDomainRule(
    val domain: String,
    /** Package name, or [FirewallPolicyStore.SCOPE_GLOBAL]. */
    val uidScope: String,
    val status: DestRuleStatus,
    val wildcard: Boolean = false,
)

data class CustomIpRule(
    val ip: String,
    val uidScope: String,
    val status: DestRuleStatus,
)
