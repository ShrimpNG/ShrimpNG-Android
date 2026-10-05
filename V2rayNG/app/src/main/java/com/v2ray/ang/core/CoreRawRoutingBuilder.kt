package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver

/**
 * Applies the user's Expert-mode routing rules to a raw CUSTOM (provider-supplied) config.
 *
 * The typed profile path builds its config from scratch and emits these rules in
 * [CoreConfigManager.configureRouting]. A CUSTOM profile has no such build step — its JSON is
 * passed through nearly verbatim — so before this existed a rule like "check-host.net -> direct"
 * simply had no effect whenever the active profile came from a provider subscription: the
 * provider's own routing decided everything.
 *
 * The hard part is not emitting the rules, it is naming their target. A rule reaches an outbound
 * by tag, and a provider config does not have to contain the tags this app uses: a balancer-style
 * subscription entry typically has no outbound named "proxy" at all, because its proxy *is* a
 * balancer, which rules reach through `balancerTag` rather than `outboundTag`.
 *
 * Getting that wrong fails loudly in the traffic and silently in the log. Xray does not validate
 * rule targets when it loads a config, and it deliberately does not fall back to the default
 * outbound when a rule names a tag that does not exist — the dispatcher closes the connection
 * instead. So a rule pointing at an absent tag does not disable the rule, it black-holes exactly
 * the traffic the user was trying to redirect. Every rule emitted here therefore resolves its
 * target against this specific config, and any rule whose target cannot be proven to exist is
 * dropped rather than guessed at.
 */
object CoreRawRoutingBuilder {

    /**
     * How one of this app's logical tags has to be written inside one particular raw config:
     * either `outboundTag` naming a real outbound, or `balancerTag` naming a balancer.
     */
    private data class RuleTarget(val field: String, val value: String)

    /** Protocols that are never the config's "proxy", used to find one when nothing is tagged. */
    private val NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    /**
     * Whether anything would be injected at all — checked before the caller parses the raw JSON,
     * which it otherwise skips entirely in the common case.
     *
     * Expert mode only, matching [CoreConfigManager.configureRouting]: Simple mode's rules end in
     * a catch-all, and prepending a catch-all to a provider's config would override its whole
     * routing table rather than add to it.
     */
    fun hasEnabledUserRules(): Boolean = enabledUserRules().isNotEmpty()

    private fun enabledUserRules(): List<RulesetItem> {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_UI_MODE) != AppConfig.ROUTING_UI_MODE_EXPERT) {
            return emptyList()
        }
        return MmkvManager.decodeRoutingRulesets().orEmpty().filter { it.enabled }
    }

    /**
     * Prepend the user's rules to the config's own, so they win under first-match semantics.
     *
     * @return how many rules were actually emitted; the rest were dropped and logged.
     */
    fun injectInto(json: JsonObject, context: Context): Int {
        val items = enabledUserRules()
        if (items.isEmpty()) return 0

        val emitted = JsonArray()
        for (item in items) {
            val rule = buildRule(json, item, context) ?: continue
            emitted.add(rule)
        }
        if (emitted.size() == 0) return 0

        val routingJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { json.add("routing", it) }
        val existing = routingJson.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        existing.forEach { emitted.add(it) }
        routingJson.add("rules", emitted)

        LogUtil.d(AppConfig.TAG, "Raw config: injected ${emitted.size() - existing.size()} user routing rule(s)")
        return emitted.size() - existing.size()
    }

    /**
     * Rewrite an already-built rule's target so it names something this config actually contains,
     * creating the freedom/blackhole outbounds if they are missing.
     *
     * Shared with the firewall builder, whose rules are produced against this app's own tag names
     * and are just as capable of naming a "proxy" outbound a provider config does not have — with
     * the same consequence of dropping the traffic instead of proxying it.
     *
     * @return false when the target cannot be resolved, meaning the rule must not be emitted.
     */
    fun retargetRule(json: JsonObject, rule: JsonObject): Boolean {
        val tag = rule.get("outboundTag")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return true // already aimed at a balancer, or carries no target of ours to fix
        val target = resolveTarget(json, tag) ?: return false
        rule.remove("outboundTag")
        rule.addProperty(target.field, target.value)
        return true
    }

    private fun buildRule(json: JsonObject, item: RulesetItem, context: Context): JsonObject? {
        val target = resolveTarget(json, item.outboundTag) ?: run {
            LogUtil.w(
                AppConfig.TAG,
                "Dropping rule '${item.remarks}': outbound '${item.outboundTag}' does not exist in this provider config"
            )
            return null
        }

        val rule = JsonObject()
        rule.addProperty("type", "field")
        rule.addProperty(target.field, target.value)

        var hasMatcher = false

        item.domain?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { domains ->
            rule.add("domain", JsonArray().apply { domains.forEach { add(it.trim()) } })
            hasMatcher = true
        }
        // Same geoip substitution as the typed path: the bundled dat file carries only the cn and
        // private sets, so the plain geoip: names would not resolve.
        item.ip?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { ips ->
            rule.add("ip", JsonArray().apply {
                ips.forEach {
                    when (val v = it.trim()) {
                        AppConfig.GEOIP_CN -> add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                        AppConfig.GEOIP_PRIVATE -> add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                        else -> add(v)
                    }
                }
            })
            hasMatcher = true
        }
        item.protocol?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { protocols ->
            rule.add("protocol", JsonArray().apply { protocols.forEach { add(it.trim()) } })
            hasMatcher = true
        }
        item.port?.takeIf { it.isNotBlank() }?.let {
            rule.addProperty("port", it.trim())
            hasMatcher = true
        }
        item.network?.takeIf { it.isNotBlank() }?.let {
            rule.addProperty("network", it.trim())
            hasMatcher = true
        }

        // Resolved here rather than left to the package-name rewrite the caller runs over the whole
        // config: that rewrite only runs on the Xray tun backend, and a rule that keeps package
        // names on the hev backend would never match. As in the typed path, a rule naming apps is
        // dropped whole when the app list cannot be honoured — clearing `process` alone would leave
        // the other matchers behind and widen a per-app rule into an everyone rule.
        val declaredProcess = item.process?.filter { it.isNotBlank() }
        if (!declaredProcess.isNullOrEmpty()) {
            if (!SettingsManager.canUseProcessRouting()) {
                LogUtil.w(AppConfig.TAG, "Dropping rule '${item.remarks}': per-app routing unavailable")
                return null
            }
            val uids = PackageUidResolver.packageNamesToUids(context, declaredProcess)
            if (uids.isEmpty()) {
                LogUtil.w(AppConfig.TAG, "Dropping rule '${item.remarks}': none of its packages resolved")
                return null
            }
            rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            hasMatcher = true
        }

        if (!hasMatcher) {
            // Xray refuses to build a rule with no matcher fields ("this rule has no effective
            // fields") and the whole config fails to load with it, taking the VPN down.
            LogUtil.w(AppConfig.TAG, "Dropping rule '${item.remarks}': it has no conditions")
            return null
        }
        return rule
    }

    /**
     * Map one of this app's tags onto something that exists in this config, creating the trivial
     * freedom/blackhole outbounds when they are missing and importing another profile's outbound
     * when a rule names one.
     */
    private fun resolveTarget(json: JsonObject, tag: String): RuleTarget? = when (tag) {
        AppConfig.TAG_DIRECT -> RuleTarget("outboundTag", ensureOutbound(json, AppConfig.TAG_DIRECT, "freedom", "DIRECT"))
        AppConfig.TAG_BLOCKED -> RuleTarget("outboundTag", ensureOutbound(json, AppConfig.TAG_BLOCKED, "blackhole", "BLOCK"))
        AppConfig.TAG_PROXY -> resolveProxyTarget(json)
        else -> resolveProfileTarget(json, tag)
    }

    /**
     * Make another saved profile reachable from inside this provider's config by building its
     * outbound and appending it under the rule's own tag.
     *
     * The typed config path gets this for free — it assembles every outbound itself — but a raw
     * config is the provider's, and nothing in it knows about the user's other servers. Without
     * this, a rule like "send one domain through server B" could not be expressed at all while a
     * subscription profile was selected, which is the common case for provider subscriptions that
     * ship every server as its own raw config.
     */
    private fun resolveProfileTarget(json: JsonObject, tag: String): RuleTarget? {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("outbounds", it) }

        // Several rules may share one target; the first of them imported it.
        if (outbounds.any { tagOf(it) == tag }) {
            return RuleTarget("outboundTag", tag)
        }
        // A tag colliding with one of the provider's own outbounds must not be overwritten: that
        // outbound is already what the tag means inside this config.
        val profile = SettingsManager.getServerViaRemarks(tag) ?: return null
        val outboundJson = liftOutbound(profile) ?: return null

        outboundJson.addProperty("tag", tag)
        outbounds.add(outboundJson)
        return RuleTarget("outboundTag", tag)
    }

    /**
     * The target profile's outbound, as JSON ready to drop into another config.
     *
     * A raw provider profile is copied verbatim out of its stored config rather than parsed into
     * the app's outbound model and written back. That model describes the flattened shape the app
     * writes itself — address and port at the top of "settings" — while providers commonly use the
     * classic "vnext" form, which has nowhere to land and is silently dropped. The result parses
     * without error and comes out with no server in it, and the core rejects such an outbound
     * outright: the whole config fails to load and the VPN does not start at all, over one routing
     * rule. Copying the object untouched sidesteps the question of which shapes are understood.
     */
    private fun liftOutbound(profile: ProfileItem): JsonObject? {
        if (profile.configType != EConfigType.CUSTOM) {
            val outbound = CoreOutboundBuilder.convert(profile) ?: return null
            return JsonUtil.parseString(JsonUtil.toJson(outbound))?.takeIf { it.isJsonObject }?.asJsonObject
        }

        // A balancer is a set of outbounds plus the balancer that picks between them; one of its
        // members is not a stand-in for it.
        if ((profile.balancerMemberCount ?: 0) >= 2) return null

        val raw = SettingsManager.getCustomServerRawViaRemarks(profile.remarks) ?: return null
        val outbounds = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val proxy = outbounds.firstOrNull { elem ->
            val protocol = elem.takeIf { it.isJsonObject }?.asJsonObject?.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            protocol != null && EConfigType.entries.any { it.name.equals(protocol, true) }
        } ?: return null

        // Copied, not referenced: the tag is about to be rewritten, and the source config is
        // shared with whatever else reads it.
        return proxy.deepCopy().asJsonObject
    }

    /**
     * Find what "proxy" means in this config, in descending order of confidence: an outbound
     * already tagged "proxy"; the balancer a balancer-style config sends everything through; or
     * the first outbound whose protocol is an actual transport.
     */
    private fun resolveProxyTarget(json: JsonObject): RuleTarget? {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        if (outbounds.any { tagOf(it) == AppConfig.TAG_PROXY }) {
            return RuleTarget("outboundTag", AppConfig.TAG_PROXY)
        }

        val balancerTag = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject?.get("tag")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotBlank() }
        if (balancerTag != null) {
            return RuleTarget("balancerTag", balancerTag)
        }

        val proxyTag = outbounds.firstOrNull { elem ->
            val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstOrNull false
            val protocol = obj.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.lowercase()
            protocol != null && protocol !in NON_PROXY_PROTOCOLS && !tagOf(elem).isNullOrBlank()
        }?.let { tagOf(it) }

        // An untagged transport outbound is the config's default handler and reachable only by
        // being first — naming it is impossible, and tagging it here could break the provider's
        // own rules that rely on the default.
        return proxyTag?.let { RuleTarget("outboundTag", it) }
    }

    private fun tagOf(elem: com.google.gson.JsonElement): String? = elem.takeIf { it.isJsonObject }
        ?.asJsonObject?.get("tag")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /**
     * @param alias a differently-cased spelling providers commonly use for the same role; tags are
     *   case-sensitive in Xray, so an existing "DIRECT" has to be referenced as written rather than
     *   shadowed by a second outbound.
     * @return the tag that ended up in the config and can safely be named by a rule.
     */
    private fun ensureOutbound(json: JsonObject, tag: String, protocol: String, alias: String): String {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("outbounds", it) }

        outbounds.forEach { elem ->
            when (tagOf(elem)) {
                tag -> return tag
                alias -> return alias
            }
        }
        // Appended, never prepended: the first outbound is Xray's default handler, and reordering
        // a provider's outbounds would silently change where its unmatched traffic goes.
        outbounds.add(JsonObject().apply {
            addProperty("protocol", protocol)
            addProperty("tag", tag)
        })
        return tag
    }
}
