package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.firewall.AppFirewallStatus
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.data.firewall.FirewallPolicyEvaluator
import com.v2ray.ang.data.firewall.FirewallPolicyStore
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NetworkMeteredUtil
import com.v2ray.ang.util.PackageUidResolver

/**
 * Turns Rethink-like firewall policies into Xray routing rules.
 *
 * Order matters (first match). Trust before block; per-app before global catch-alls; app
 * catch-all / bypass after destination rules. Emitted ahead of trusted-Wi-Fi and simple/expert
 * routing so blocks are never swallowed by a catch-all.
 */
object CoreFirewallBuilder {

    fun buildRules(context: Context): List<V2rayConfig.RoutingBean.RulesBean> {
        val rules = mutableListOf<V2rayConfig.RoutingBean.RulesBean>()
        val net = NetworkMeteredUtil.currentKind(context)

        if (FirewallManager.isLogEnabled()) {
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = listOf(FirewallManager.SENTINEL_UID),
                    outboundTag = AppConfig.TAG_BLOCKED,
                    network = "tcp",
                )
            )
        }

        val policies = FirewallManager.allNonDefaultPolicies()
        val uidByPackage = HashMap<String, String>()
        fun uidOf(packageName: String): String? {
            uidByPackage[packageName]?.let { return it }
            val uid = PackageUidResolver.packageNamesToUids(context, listOf(packageName)).firstOrNull()
            if (uid != null) uidByPackage[packageName] = uid
            return uid
        }

        // Per-app TRUST destinations
        for (domain in FirewallManager.allDomains().filter {
            it.status == DestRuleStatus.TRUST && it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL
        }) {
            val uid = uidOf(domain.uidScope) ?: continue
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = listOf(uid),
                    domain = listOf(formatDomain(domain.domain, domain.wildcard)),
                    outboundTag = AppConfig.TAG_PROXY,
                )
            )
        }
        for (ip in FirewallManager.allIps().filter {
            it.status == DestRuleStatus.TRUST && it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL
        }) {
            val uid = uidOf(ip.uidScope) ?: continue
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = listOf(uid),
                    ip = listOf(ip.ip),
                    outboundTag = AppConfig.TAG_PROXY,
                )
            )
        }

        // Global TRUST
        FirewallManager.globalDomains().filter { it.status == DestRuleStatus.TRUST }.forEach { d ->
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    domain = listOf(formatDomain(d.domain, d.wildcard)),
                    outboundTag = AppConfig.TAG_PROXY,
                )
            )
        }
        FirewallManager.globalIps().filter { it.status == DestRuleStatus.TRUST }.forEach { ip ->
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    ip = listOf(ip.ip),
                    outboundTag = AppConfig.TAG_PROXY,
                )
            )
        }

        // Per-app BLOCK destinations
        for (domain in FirewallManager.allDomains().filter {
            it.status == DestRuleStatus.BLOCK && it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL
        }) {
            val uid = uidOf(domain.uidScope) ?: continue
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = listOf(uid),
                    domain = listOf(formatDomain(domain.domain, domain.wildcard)),
                    outboundTag = AppConfig.TAG_BLOCKED,
                )
            )
        }
        for (ip in FirewallManager.allIps().filter {
            it.status == DestRuleStatus.BLOCK && it.uidScope != FirewallPolicyStore.SCOPE_GLOBAL
        }) {
            val uid = uidOf(ip.uidScope) ?: continue
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = listOf(uid),
                    ip = listOf(ip.ip),
                    outboundTag = AppConfig.TAG_BLOCKED,
                )
            )
        }

        // Global BLOCK
        FirewallManager.globalDomains().filter { it.status == DestRuleStatus.BLOCK }.map { it.domain }
            .takeIf { it.isNotEmpty() }?.let { domains ->
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        domain = domains.map { formatDomain(it, false) },
                        outboundTag = AppConfig.TAG_BLOCKED,
                    )
                )
            }
        FirewallManager.globalIps().filter { it.status == DestRuleStatus.BLOCK }.map { it.ip }
            .takeIf { it.isNotEmpty() }?.let { ips ->
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = ips,
                        outboundTag = AppConfig.TAG_BLOCKED,
                    )
                )
            }

        // App catch-all BLOCK / ISOLATE / network-restricted
        val blockUids = mutableListOf<String>()
        for (policy in policies) {
            if (!FirewallPolicyEvaluator.shouldBlock(policy.status, net)) continue
            val uid = uidOf(policy.packageName) ?: continue
            blockUids.add(uid)
        }
        if (blockUids.isNotEmpty()) {
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = blockUids.distinct(),
                    outboundTag = AppConfig.TAG_BLOCKED,
                )
            )
        } else if (policies.any { it.status == AppFirewallStatus.BLOCK || it.status == AppFirewallStatus.ISOLATE }) {
            LogUtil.w(AppConfig.TAG, "Firewall: blocked/isolate packages failed to resolve UIDs")
        }

        // BYPASS_DIRECT — keep last among firewall rules so destination blocks still apply
        val bypassUids = policies
            .filter { it.status == AppFirewallStatus.BYPASS_DIRECT }
            .mapNotNull { uidOf(it.packageName) }
            .distinct()
        if (bypassUids.isNotEmpty()) {
            rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    process = bypassUids,
                    outboundTag = AppConfig.TAG_DIRECT,
                )
            )
        }

        return rules
    }

    /** Pure rule-list injection for unit tests (no Context / MMKV). */
    fun injectInto(json: JsonObject, context: Context) = injectRules(json, buildRules(context))

    fun injectRules(json: JsonObject, rules: List<V2rayConfig.RoutingBean.RulesBean>) {
        if (rules.isEmpty()) return

        val routingJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { json.add("routing", it) }
        val existing = routingJson.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        val merged = JsonArray()
        rules.forEach { bean ->
            val rule = JsonUtil.parseString(JsonUtil.toJson(bean))?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@forEach
            // These rules are written against this app's tag names, but a provider's config need
            // not contain them — a balancer entry usually has no "proxy" outbound, and may spell
            // direct as "DIRECT". Xray does not fall back for an unknown target, it drops the
            // connection, so an unresolved TRUST rule would black-hole precisely the traffic it
            // was meant to let through. Retarget, and drop the rule if it cannot be aimed.
            if (!CoreRawRoutingBuilder.retargetRule(json, rule)) {
                LogUtil.w(AppConfig.TAG, "Firewall: dropping rule with unresolvable target in raw config")
                return@forEach
            }
            merged.add(rule)
        }
        existing.forEach { merged.add(it) }
        routingJson.add("rules", merged)
    }

    fun applyRouteOnlySniffing(json: JsonObject, enableDestinationSniffForLog: Boolean = false) {
        val inbounds = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        inbounds.forEach { elem ->
            val inbound = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val sniffing = inbound.get("sniffing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { inbound.add("sniffing", it) }
            // routeOnly keeps the original IP for getConnectionOwnerUid while still exposing the
            // sniffed hostname via GetTargetDomain() — required for per-app rules and the journal.
            sniffing.addProperty("routeOnly", true)
            if (enableDestinationSniffForLog) {
                sniffing.addProperty("enabled", true)
                val overrides = sniffing.get("destOverride")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: JsonArray().also { sniffing.add("destOverride", it) }
                fun ensureOverride(name: String) {
                    for (item in overrides) {
                        if (item.isJsonPrimitive && item.asString == name) return
                    }
                    overrides.add(name)
                }
                ensureOverride("http")
                ensureOverride("tls")
                ensureOverride("quic")
            }
        }
    }

    private fun formatDomain(domain: String, wildcard: Boolean): String {
        val d = domain.trim().lowercase()
        if (wildcard || d.startsWith("domain:") || d.startsWith("full:") || d.startsWith("regexp:") ||
            d.startsWith("geosite:") || d.startsWith("ext:")
        ) {
            return d
        }
        // Prefer suffix match for plain hostnames (covers www. and subdomains).
        return if (d.startsWith(".")) "domain:${d.drop(1)}" else "domain:$d"
    }
}
