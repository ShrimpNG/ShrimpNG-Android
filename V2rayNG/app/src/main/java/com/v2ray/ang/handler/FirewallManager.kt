package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.firewall.AppFirewallStatus
import com.v2ray.ang.data.firewall.AppPolicy
import com.v2ray.ang.data.firewall.ConnLogDao
import com.v2ray.ang.data.firewall.CustomDomainRule
import com.v2ray.ang.data.firewall.CustomIpRule
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.data.firewall.FirewallPolicyEvaluator
import com.v2ray.ang.data.firewall.FirewallPolicyStore
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils

/**
 * Firewall facade: tunnel-backend takeover plus Rethink-like per-app / destination policies.
 *
 * Policies live in SQLite ([FirewallPolicyStore]). Whether rules are *emitted* is
 * [SettingsManager.isFirewallEffective], not the enable flag alone.
 */
object FirewallManager {

    /**
     * A UID no process can own, used by the connection log as a rule that always evaluates and
     * never matches. Deliberately not 0 (root), -1 (our "not found") or 1000 (system).
     */
    const val SENTINEL_UID = "4294967294"

    private fun store(context: Context = AngApplication.application): FirewallPolicyStore =
        FirewallPolicyStore.get(context)

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREWALL_ENABLED, false)

    /** Takes over the tunnel backend while preserving the user's previous tunnel settings. */
    fun enable() {
        if (!isEnabled()) {
            MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_SAVED_HEV, SettingsManager.isUsingHevTun())
            MmkvManager.encodeSettings(
                AppConfig.PREF_FIREWALL_SAVED_ROUTE_ONLY,
                MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
            )
        }
        MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTE_ONLY_ENABLED, true)
        MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_ENABLED, true)
        store().migrateFromMmkvIfNeeded()
    }

    fun disable() {
        if (isLogEnabled()) setLogEnabled(false)
        MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_ENABLED, false)
        MmkvManager.encodeSettings(
            AppConfig.PREF_USE_HEV_TUNNEL,
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREWALL_SAVED_HEV, true)
        )
        MmkvManager.encodeSettings(
            AppConfig.PREF_ROUTE_ONLY_ENABLED,
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREWALL_SAVED_ROUTE_ONLY, false)
        )
    }

    //region connection journal

    fun isLogEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREWALL_LOG_ENABLED, false)

    fun setLogEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_LOG_ENABLED, enabled)
        val app = AngApplication.application
        if (!enabled) {
            ConnLogDao.get(app).deleteAll()
            ConnectionLogMaintenance.cancel(app)
        } else {
            ConnectionLogMaintenance.schedule(app)
        }
    }

    fun logRetentionDays(): Int = MmkvManager.decodeSettingsInt(
        AppConfig.PREF_FIREWALL_LOG_RETENTION_DAYS,
        AppConfig.FIREWALL_LOG_DEFAULT_RETENTION_DAYS,
    ).coerceAtLeast(1)

    fun logMaxRows(): Int = MmkvManager.decodeSettingsInt(
        AppConfig.PREF_FIREWALL_LOG_MAX_ROWS,
        AppConfig.FIREWALL_LOG_DEFAULT_MAX_ROWS,
    ).coerceAtLeast(1000)

    fun clearConnectionLog() {
        ConnLogDao.get(AngApplication.application).deleteAll()
    }

    //endregion

    //region app policies

    fun appStatus(packageName: String): AppFirewallStatus =
        store().getAppPolicy(packageName).status

    fun setAppStatus(packageName: String, status: AppFirewallStatus) =
        store().setAppStatus(packageName, status)

    fun allNonDefaultPolicies(): List<AppPolicy> = store().allNonDefaultPolicies()

    fun hasNetworkRestrictedPolicies(): Boolean = store().hasNetworkRestrictedPolicies()

    /** Packages currently in BLOCK (or net-restricted catch-all that is active). For summaries. */
    fun blockedAppCount(): Int =
        allNonDefaultPolicies().count {
            it.status == AppFirewallStatus.BLOCK ||
                it.status == AppFirewallStatus.ISOLATE ||
                it.status == AppFirewallStatus.WIFI_ONLY ||
                it.status == AppFirewallStatus.MOBILE_ONLY
        }

    //endregion

    //region destination rules

    fun globalDomains(): List<CustomDomainRule> = store().domains(FirewallPolicyStore.SCOPE_GLOBAL)

    fun globalIps(): List<CustomIpRule> = store().ips(FirewallPolicyStore.SCOPE_GLOBAL)

    fun appDomains(packageName: String): List<CustomDomainRule> = store().domains(packageName)

    fun appIps(packageName: String): List<CustomIpRule> = store().ips(packageName)

    fun allDomains(): List<CustomDomainRule> = store().allDomains()

    fun allIps(): List<CustomIpRule> = store().allIps()

    fun setDomainRule(domain: String, scope: String, status: DestRuleStatus, wildcard: Boolean = false) {
        store().setDomainRule(
            CustomDomainRule(
                domain = domain,
                uidScope = scope,
                status = status,
                wildcard = wildcard,
            )
        )
    }

    fun removeDomainRule(domain: String, scope: String) {
        store().removeDomainRule(domain, scope)
    }

    fun setIpRule(ip: String, scope: String, status: DestRuleStatus) {
        store().setIpRule(CustomIpRule(ip = ip, uidScope = scope, status = status))
    }

    fun removeIpRule(ip: String, scope: String) {
        store().removeIpRule(ip, scope)
    }

    /** Domains that should be pinned to 127.0.0.1 in DNS hosts (all BLOCK rules). */
    fun blockedDomainsForDns(): List<String> =
        allDomains().filter { it.status == DestRuleStatus.BLOCK }.map { it.domain }.distinct()

    data class VerdictSnapshot(
        val domains: List<CustomDomainRule>,
        val ips: List<CustomIpRule>,
        val blockedUids: Set<Int>,
    )

    fun verdictSnapshot(context: Context): VerdictSnapshot = VerdictSnapshot(
        domains = allDomains(),
        ips = allIps(),
        blockedUids = currentlyBlockedUids(context),
    )

    /**
     * Mirrors Xray's firewall order for journal verdicts: scoped/global TRUST, scoped/global
     * BLOCK, then the app catch-all. Domain matching is best-effort because the journal's domain
     * may come from PTR rather than Xray's original target.
     */
    fun isConnectionBlocked(
        snapshot: VerdictSnapshot,
        uid: Int,
        packageName: String?,
        destIp: String,
        domain: String?,
    ): Boolean {
        fun domainMatches(scope: String, status: DestRuleStatus): Boolean =
            snapshot.domains.any {
                it.uidScope == scope && it.status == status &&
                    matchesDomain(it.domain, domain)
            }
        fun ipMatches(scope: String, status: DestRuleStatus): Boolean =
            snapshot.ips.any {
                it.uidScope == scope && it.status == status && matchesIp(it.ip, destIp)
            }

        if (packageName != null &&
            (domainMatches(packageName, DestRuleStatus.TRUST) ||
                ipMatches(packageName, DestRuleStatus.TRUST))
        ) return false
        if (domainMatches(FirewallPolicyStore.SCOPE_GLOBAL, DestRuleStatus.TRUST) ||
            ipMatches(FirewallPolicyStore.SCOPE_GLOBAL, DestRuleStatus.TRUST)
        ) return false
        if (packageName != null &&
            (domainMatches(packageName, DestRuleStatus.BLOCK) ||
                ipMatches(packageName, DestRuleStatus.BLOCK))
        ) return true
        if (domainMatches(FirewallPolicyStore.SCOPE_GLOBAL, DestRuleStatus.BLOCK) ||
            ipMatches(FirewallPolicyStore.SCOPE_GLOBAL, DestRuleStatus.BLOCK)
        ) return true
        return uid in snapshot.blockedUids
    }

    private fun matchesIp(rule: String, ip: String): Boolean =
        if ('/' in rule) Utils.isIpInCidr(ip, rule) else rule.equals(ip, ignoreCase = true)

    private fun matchesDomain(rule: String, observed: String?): Boolean {
        val domain = observed?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return false
        val normalized = rule.trim().trimEnd('.').lowercase()
        if (normalized.startsWith("full:")) return domain == normalized.removePrefix("full:")
        if (normalized.startsWith("regexp:") || normalized.startsWith("geosite:") ||
            normalized.startsWith("ext:")
        ) return false
        val suffix = normalized.removePrefix("domain:").removePrefix("*.").removePrefix(".")
        return domain == suffix || domain.endsWith(".$suffix")
    }

    //endregion

    /**
     * Apps with a non-default status or a package-scoped destination rule must stay inside the
     * Android VPN. [CoreVpnService] overlays this set on the platform allow/deny list at runtime,
     * without rewriting the user's saved per-app proxy preferences.
     */
    fun packagesRequiringTunnel(): Set<String> {
        if (!SettingsManager.isFirewallEffective()) return emptySet()
        return buildSet {
            allNonDefaultPolicies().mapTo(this) { it.packageName }
            allDomains()
                .filterTo(mutableListOf()) { it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL }
                .mapTo(this) { it.uidScope }
            allIps()
                .filterTo(mutableListOf()) { it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL }
                .mapTo(this) { it.uidScope }
        }
    }

    /** UIDs whose catch-all policy is currently blocking (for journal verdict). */
    fun currentlyBlockedUids(context: Context): Set<Int> {
        val net = com.v2ray.ang.util.NetworkMeteredUtil.currentKind(context)
        val out = HashSet<Int>()
        for (policy in allNonDefaultPolicies()) {
            if (!FirewallPolicyEvaluator.shouldBlock(policy.status, net)) continue
            val uid = if (policy.uid >= 0) {
                policy.uid
            } else {
                PackageUidResolver.packageNamesToUids(context, listOf(policy.packageName))
                    .firstOrNull()?.toIntOrNull()
            }
            if (uid != null && uid >= 0) out.add(uid)
        }
        return out
    }
}
