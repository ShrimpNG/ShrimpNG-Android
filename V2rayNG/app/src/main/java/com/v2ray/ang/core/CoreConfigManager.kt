package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.FingerprintManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.WifiSsidUtil

object CoreConfigManager {
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    // Fixed synthetic tag for the trusted-Wi-Fi direct override injected into raw CUSTOM configs
    // (buildV2rayCustomConfig) — a raw/subscription-supplied config's own outbound tags are
    // arbitrary (e.g. "DIRECT" rather than the app's own AppConfig.TAG_DIRECT="direct"), so this
    // adds its own freedom outbound instead of guessing at an existing one.
    private const val TAG_TRUSTED_WIFI_DIRECT = "shrimp-trusted-wifi-direct"

    //region get config function

    /**
     * Build the runtime configuration for normal startup.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            return toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     *
     * The core flow is reused, then non-essential sections are removed.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)

            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config for speedtest: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build configuration for custom profiles.
     */
    private fun buildV2rayCustomConfig(configContext: CoreConfigContext): ConfigResult {
        val context = configContext.context
        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(status = false, guid = configContext.guid, errorMessage = "Custom config is empty")
        val result = ConfigResult(true, configContext.guid, raw)

        // Trusted-Wi-Fi direct override must be checked even when a tun inbound isn't otherwise
        // needed, so it can't reuse the needTun() gate below that skips the JSON parse entirely.
        val onTrustedWifi = WifiSsidUtil.isOnTrustedWifi(context)
        // The fingerprint the user (or the automatic search) settled on for this server, if any.
        val fingerprintOverride = MmkvManager.decodeServerConfig(configContext.guid)
            ?.let { FingerprintManager.overrideFor(it) }
        // Expert-mode user rules have to be applied to raw configs too, and they are independent
        // of both conditions below — checked here so the JSON is still left untouched in the
        // common case where there is nothing to add.
        val hasUserRoutingRules = CoreRawRoutingBuilder.hasEnabledUserRules()
        val speedEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true
        if (!needTun() && !onTrustedWifi && !hasUserRoutingRules && fingerprintOverride == null && !speedEnabled) {
            return result
        }

        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result

        fingerprintOverride?.let { CoreRawFingerprintPatcher.apply(json, it) }

        if (speedEnabled) {
            enableOutboundTrafficStats(json)
        }

        // Injected first of the three, because each of the prepends below lands ahead of it and
        // first match wins. That reproduces the precedence configureRouting() gives typed
        // profiles: trusted Wi-Fi over the firewall, the firewall over the user's own rules.
        if (hasUserRoutingRules) {
            CoreRawRoutingBuilder.injectInto(json, context)
        }

        // Prepended before the trusted-Wi-Fi override so a blocked app is blocked either way.
        // The firewall requires the Xray tun backend, so needTun() is necessarily true here and
        // the early return above can't have skipped us.
        if (SettingsManager.isFirewallEffective()) {
            CoreFirewallBuilder.injectInto(json, context)
        }

        if (onTrustedWifi) {
            applyTrustedWifiOverride(json)
        }

        if (needTun()) {
            // Check whether package names need to be replaced with UIDs.
            //
            // Unlike the typed path in appendRoutingUserRule, skipping the rewrite here is safe:
            // the rule keeps its package names, Xray compares `process` against UID strings, so it
            // simply never matches and the rule goes inert. Do not "fix" this by stripping the
            // field — that is exactly what made the typed path widen a per-app block into a
            // block-everything rule.
            if (SettingsManager.canUseProcessRouting()) {
                val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: JsonArray()

                for (elem in rulesJson) {
                    val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    val packages = process.mapNotNull {
                        it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    }.takeIf { it.isNotEmpty() } ?: continue
                    val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue

                    rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
                }
            }

            // check if tun inbound exists
            val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: JsonArray().also { json.add("inbounds", it) }
            val tunNotExists = inboundsJson.none { elem ->
                elem.isJsonObject && elem.asJsonObject.get("protocol")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString == "tun"
            }

            if (tunNotExists) {
                // add tun inbound from template
                val templateConfig = initV2rayConfig(configContext)
                templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                    inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                    inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
                }
            }

            // After the tun inbound exists, so it is covered too. Without this a raw config's
            // own sniffing settings win and per-app rules silently stop matching.
            if (SettingsManager.isFirewallEffective()) {
                CoreFirewallBuilder.applyRouteOnlySniffing(
                    json,
                    enableDestinationSniffForLog = FirewallManager.isLogEnabled(),
                )
            }
        }

        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    /**
     * Turn on the per-outbound traffic counters the speed notification reads. Typed profiles get
     * them from the config template; a provider's raw config rarely carries them, and without
     * them the core keeps no counters at all, so the notification sat at zero forever. Only the
     * two system switches are touched — the provider's own policy levels stay as they are.
     */
    private fun enableOutboundTrafficStats(json: JsonObject) {
        if (json.get("stats")?.isJsonObject != true) {
            json.add("stats", JsonObject())
        }
        val policy = json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { json.add("policy", it) }
        val system = policy.get("system")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { policy.add("system", it) }
        system.addProperty("statsOutboundUplink", true)
        system.addProperty("statsOutboundDownlink", true)
    }

    /**
     * Force a raw CUSTOM config's traffic direct while on trusted Wi-Fi.
     *
     * Note: unlike the normal-profile trusted-Wi-Fi path (configureDns), this does not also force
     * DNS direct — a raw config may omit its own "dns" section entirely (as balancer-style
     * subscription entries do) or use arbitrary DNS tags/fakedns setups that aren't safe to
     * rewrite generically for unknown third-party JSON shapes.
     */
    private fun applyTrustedWifiOverride(json: JsonObject) {
        val outboundsJson = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("outbounds", it) }
        val directTagExists = outboundsJson.any { elem ->
            elem.isJsonObject && elem.asJsonObject.get("tag")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == TAG_TRUSTED_WIFI_DIRECT
        }
        if (!directTagExists) {
            outboundsJson.add(JsonObject().apply {
                addProperty("protocol", "freedom")
                addProperty("tag", TAG_TRUSTED_WIFI_DIRECT)
            })
        }

        val routingJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { json.add("routing", it) }
        val rulesJson = routingJson.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray()

        // Prepended so it wins under Xray's first-match rule semantics, ahead of the config's own
        // rules (e.g. a balancer's catch-all balancerTag rule).
        val directRule = JsonObject().apply {
            addProperty("network", "tcp,udp")
            addProperty("outboundTag", TAG_TRUSTED_WIFI_DIRECT)
        }
        val newRules = JsonArray().apply {
            add(directRule)
            rulesJson.forEach { add(it) }
        }
        routingJson.add("rules", newRules)
    }

    /**
     * Build one unified configuration for every non-custom profile type.
     *
     * The analyzed outbound plan is consumed in order and converted to concrete
     * outbounds before routing, DNS, and runtime extras are assembled.
     */
    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()

        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks

        configureInbounds(v2rayConfig)

        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds.removeAt(0)
        }
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()

        // resolvedOutbounds is a single ordered plan: index 0 is primary and must be prepended,
        // the rest are routing outbounds and can be appended.
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(
                resolvedOutbound = spec,
                prepend = index == 0,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }

        // User routing rules (policyGroupBalancerTags rewrites TAG_PROXY→balancer when main is POLICYGROUP).
        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        configureDns(configContext, v2rayConfig, policyGroupBalancerTags)
        configureLocalDns(configContext, v2rayConfig)
        configureRootModeDns(v2rayConfig)

        // (added by getDns / getCustomLocalDns) to use the balancer, then add
        // the catch-all balancer rule.
        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = arrayListOf("0.0.0.0/0", "::/0"),
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            }
        }

        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)
        resolveOutboundDomainsToHosts(v2rayConfig)

        return v2rayConfig
    }

    /**
     * Convert one analyzed outbound entry into concrete outbounds and register
     * them to the runtime configuration.
     */
    private fun buildOutbounds(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        if (resolvedOutbound.tag in existingTags) {
            LogUtil.w(AppConfig.TAG, "Resolved outbound tag '${resolvedOutbound.tag}' already exists, skipping duplicated entry")
            return
        }

        when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }
    }

    /**
     * Build and insert a single-node outbound entry.
     */
    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: run {
            LogUtil.w(AppConfig.TAG, "NORMAL resolved outbound '${resolvedOutbound.tag}' has empty resolvedProfiles, skipping")
            return
        }
        val outbound = convertProfile2Outbound(profile) ?: run {
            LogUtil.w(AppConfig.TAG, "Could not convert NORMAL resolved outbound '${resolvedOutbound.tag}' profile to outbound, skipping")
            return
        }
        outbound.tag = resolvedOutbound.tag
        if (prepend) {
            v2rayConfig.outbounds.add(0, outbound)
        } else {
            v2rayConfig.outbounds.add(outbound)
        }
        existingTags.add(resolvedOutbound.tag)
    }

    /**
     * Build and insert a multi-hop chain entry.
     */
    private fun handleProxyChainResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val chainOutbounds = resolvedOutbound.resolvedProfiles
            .mapNotNull { convertProfile2Outbound(it) }
            .toMutableList()
        if (chainOutbounds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has no valid profiles, skipping")
            return
        }
        if (chainOutbounds.size == 1) {
            val outbound = chainOutbounds.first()
            outbound.tag = resolvedOutbound.tag
            if (prepend) {
                v2rayConfig.outbounds.add(0, outbound)
            } else {
                v2rayConfig.outbounds.add(outbound)
            }
            existingTags.add(resolvedOutbound.tag)
            return
        }

        val chainTags = chainOutbounds.mapIndexed { index, _ ->
            if (index == 0) {
                resolvedOutbound.tag
            } else {
                "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index"
            }
        }
        if (chainTags.any { it in existingTags }) {
            LogUtil.w(
                AppConfig.TAG,
                "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has colliding hop tags, skipping"
            )
            return
        }

        chainOutbounds.forEachIndexed { index, outbound ->
            outbound.tag = chainTags[index]
        }
        for (i in 0 until chainOutbounds.size - 1) {
            chainOutbounds[i].ensureSockopt().dialerProxy = chainOutbounds[i + 1].tag
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, chainOutbounds)
        } else {
            v2rayConfig.outbounds.addAll(chainOutbounds)
        }
        chainOutbounds.forEach { existingTags.add(it.tag) }
    }

    /**
     * Build and insert a policy-group entry and its balancer metadata.
     */
    private fun handlePolicyGroupResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile ->
            convertProfile2Outbound(profile)?.let { ob -> ob to profile }
        }
        if (memberPairs.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' has no valid member outbounds, skipping")
            return
        }

        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) {
                return@forEachIndexed
            }
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }

        if (membersToAdd.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' produced no unique member tags, skipping"
            )
            return
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, membersToAdd)
        } else {
            v2rayConfig.outbounds.addAll(membersToAdd)
        }

        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) {
            AppConfig.TAG_BALANCER
        } else {
            "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        }
        val strategy = buildBalancerStrategy(
            policyGroupType = resolvedOutbound.profile.policyGroupType,
            selector = listOf(memberTagPrefix),
            balancerTag = balancerTag,
        )
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
    }

    /**
     * Trim runtime sections that are not needed for latency testing.
     */
    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
        v2rayConfig.policy = null
        v2rayConfig.outbounds.forEach { key -> key.mux = null }
    }

    /**
     * Serialize a runtime configuration into a standard result object.
     */
    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(
            status = true,
            guid = configContext.guid,
            content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
        )
    }

    /**
     * Load the base template from cache or assets and parse it.
     */
    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets: String
        if (needTun()) {
            assets = initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config_with_tun.json")
            }
            initConfigCacheWithTun = assets
        } else {
            assets = initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config.json")
            }
            initConfigCache = assets
        }
        return JsonUtil.fromJson(assets, V2rayConfig::class.java)
            ?: error("Failed to parse config template")
    }


    //endregion


    //region some sub function

    private fun needTun(): Boolean {
        return SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    }

    /**
     * Configure inbound listeners and related runtime options.
     */
    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev
        val forcedBySocksRoot = SettingsManager.isRootMode()
                || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)

        val enableLocalProxy = forcedByHev || forcedBySocksRoot || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)

        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) {
            inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) {
            inbound1.listen = AppConfig.LOOPBACK
        }
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        if (socksUsername != null && socksPassword != null) {
            inbound1.settings?.auth = "password"
            inbound1.settings?.accounts = listOf(
                V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(
                    user = socksUsername,
                    pass = socksPassword
                )
            )
        } else {
            inbound1.settings?.auth = "noauth"
            inbound1.settings?.accounts = null
        }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) {
            inbound1.sniffing?.destOverride?.clear()
        }
        if (fakedns) {
            inbound1.sniffing?.destOverride?.add("fakedns")
        }

        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
                ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase()
            inbound2.port = SettingsManager.getHttpPort()
            inbound2.protocol = EConfigType.HTTP.name.lowercase()
            inbound2.settings?.auth = null
            inbound2.settings?.udp = null
            v2rayConfig.inbounds.add(inbound2)
        }

        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }

        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }

    /**
     * Enable fake DNS when local DNS and fake DNS are both enabled.
     */
    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    /**
     * Collect domain rules that target one outbound tag.
     */
    private fun collectUserRuleDomainsByTag(tag: String): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && key.outboundTag == tag && !key.domain.isNullOrEmpty()) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Collect domain rules that target non-builtin outbound tags.
     */
    private fun collectCustomOutboundDomains(): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && !AppConfig.BUILTIN_OUTBOUND_TAGS.contains(key.outboundTag)
                && !key.domain.isNullOrEmpty()
            ) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Configure local DNS inbounds, outbounds, and routing rules.
     */
    private fun configureLocalDns(configContext: CoreConfigContext, v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) {
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN)
            val routingDomains = configContext.routingDomainRules
                .asSequence()
                .filter { it.outboundTag != AppConfig.TAG_BLOCKED }
                .flatMap { it.domain.asSequence() }
                .toList()
                .distinct()
            val finalDomain = geositeCn + routingDomains
            // fakedns with all domains to make it always top priority
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(
                    address = "fakedns",
                    domains = finalDomain
                )
            )
        }

        if (SettingsManager.isVpnMode()) {
            if (SettingsManager.isUsingHevTun()) {
                //hev-socks5-tunnel dns routing
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("socks"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("tun"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            }
        }

        // DNS outbound
        if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * In the root mode the whole device's traffic (incl. raw DNS) is funneled
     * into the core's SOCKS inbound, exactly like the VPN+hev path. Hijack port-53 to the
     * core's DNS module so queries are resolved via the configured resolver through the
     * proxy instead of leaking to (or being mis-resolved by) the local network resolver.
     * Independent of the local-DNS toggle, which is not exposed for root mode.
     */
    private fun configureRootModeDns(v2rayConfig: V2rayConfig) {
        if (!SettingsManager.isRootMode()) return

        if (v2rayConfig.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = arrayListOf("socks"),
                    outboundTag = "dns-out",
                    port = "53",
                )
            )
        }
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Remove speed-test runtime sections when the feature is disabled.
     */
    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy = null
        }
    }

    /*
    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val hosts = mutableMapOf<String, Any>()
        val servers = ArrayList<Any>()

        //remote Dns
        val remoteDns = SettingsManager.getRemoteDnsServers()
        val proxyDomain = (collectUserRuleDomainsByTag(AppConfig.TAG_PROXY) + collectCustomOutboundDomains()).distinct()
        remoteDns.forEach {
            servers.add(it)
        }
        if (proxyDomain.isNotEmpty()) {
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = remoteDns.first(),
                    domains = proxyDomain,
                )
            )
        }

        // domestic DNS
        val domesticDns = SettingsManager.getDomesticDnsServers()
        val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
        val isCnRoutingMode = directDomain.contains(AppConfig.GEOSITE_CN)
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val finalDirectDomain = if (isCnRoutingMode) directDomain.filterNot {
            cnRegionFilter(it)
        } else directDomain
        val domesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, element ->
            val tag = AppConfig.TAG_DOMESTIC_DNS + index
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = element,
                    domains = finalDirectDomain,
                    skipFallback = true,
                    tag = tag
                )
            )
            domesticDnsTags.add(tag)
        }
        if (isCnRoutingMode) {
            val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
            val cnRegionDomain = directDomain.filter { cnRegionFilter(it) }
            domesticDns.forEachIndexed { index, element ->
                val geositeCnDnsTag = AppConfig.TAG_DOMESTIC_DNS + index + "_cn_expect"
                servers.add(
                    V2rayConfig.DnsBean.ServersBean(
                        address = element,
                        domains = cnRegionDomain,
                        expectIPs = geoipCn,
                        skipFallback = true,
                        tag = geositeCnDnsTag
                    )
                )
                domesticDnsTags.add(geositeCnDnsTag)
            }
        }

        //block dns
        val blkDomain = collectUserRuleDomainsByTag(AppConfig.TAG_BLOCKED)
        if (blkDomain.isNotEmpty()) {
            hosts.putAll(blkDomain.map { it to AppConfig.LOOPBACK })
        }

        // hardcode googleapi rule to fix play store problems
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN

        // hardcode popular Android Private DNS rule to fix localhost DNS problem
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        //User DNS hosts
        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.filter { it.contains(":") }
                ?.associate { it.split(":").let { (k, v) -> k to v } }
            if (userHostsMap != null) {
                hosts.putAll(userHostsMap)
            }
        }

        // DNS dns
        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null
        )

        // DNS routing
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                outboundTag = AppConfig.TAG_DIRECT,
                inboundTag = domesticDnsTags,
                domain = null
            )
        )
        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }
    */

    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val servers = ArrayList<Any>()
        // On trusted Wi-Fi, Xray's own resolver must also use the router's DHCP DNS — otherwise
        // sniffed-domain connections routed to the freedom outbound would resolve via the app's
        // configured DNS, bypassing router-side DNS-based policy routing (OpenWrt/podkop).
        val remoteDns = if (WifiSsidUtil.isOnTrustedWifi(configContext.context)) {
            WifiSsidUtil.wifiDnsServers(configContext.context).ifEmpty { SettingsManager.getRemoteDnsServers() }
        } else {
            SettingsManager.getRemoteDnsServers()
        }
        val domesticDns = SettingsManager.getDomesticDnsServers()

        remoteDns.forEach { servers.add(it) }

        val hosts = buildDnsHostsFromRoutingRules(configContext)
        val cnDomesticDnsTags = buildDnsCnModeFromRoutingRules(configContext, servers, domesticDns)
        val domesticDnsTags = buildDnsFromRoutingRules(
            configContext = configContext,
            servers = servers,
            remoteDns = remoteDns,
            domesticDns = domesticDns
        )
        domesticDnsTags.addAll(cnDomesticDnsTags)

        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null
        )

        if (domesticDnsTags.isNotEmpty()) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_DIRECT,
                    inboundTag = ArrayList(domesticDnsTags),
                    domain = null
                )
            )
        }

        // On trusted Wi-Fi, DNS must follow data traffic direct too — otherwise queries would
        // still leak through the tunnel even though everything else routes direct. Belt-and-
        // suspenders alongside configureRouting()'s own catch-all: correct even if some other
        // rule inserted ahead of it (e.g. local/root DNS hijack, which is inserted at index 0)
        // ever changed how rule precedence plays out here.
        if (WifiSsidUtil.isOnTrustedWifi(configContext.context)) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_DIRECT,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
            return
        }

        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }

    private fun buildDnsHostsFromRoutingRules(configContext: CoreConfigContext): MutableMap<String, Any> {
        val hosts = mutableMapOf<String, Any>()

        val blockDomains = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_BLOCKED }
            .flatMap { it.domain.asSequence() }
            .toList()
        if (blockDomains.isNotEmpty()) {
            hosts.putAll(blockDomains.map { it to AppConfig.LOOPBACK })
        }

        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",").orEmpty()
                .filter { it.isNotEmpty() }
                .filter { it.contains(":") }
                .associate { it.split(":").let { (k, v) -> k to v } }
            hosts.putAll(userHostsMap)
        }

        return hosts
    }

    private fun buildDnsCnModeFromRoutingRules(configContext: CoreConfigContext, servers: ArrayList<Any>, domesticDns: List<String>,    ): List<String> {
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val isCnRoutingMode = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_DIRECT }
            .flatMap { it.domain.asSequence() }
            .any { it == AppConfig.GEOSITE_CN }

        if (!isCnRoutingMode) {
            return emptyList()
        }

        val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
        val cnDomains = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_DIRECT }
            .flatMap { it.domain.asSequence() }
            .filter { cnRegionFilter(it) }
            .toList()
        if (cnDomains.isEmpty()) {
            return emptyList()
        }

        val cnDomesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, address ->
            val cnDomesticDnsTag = "${AppConfig.TAG_DOMESTIC_DNS}_cn_expect_${index}"
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = address,
                    domains = cnDomains,
                    expectIPs = geoipCn,
                    skipFallback = true,
                    tag = cnDomesticDnsTag
                )
            )
            cnDomesticDnsTags.add(cnDomesticDnsTag)
        }
        return cnDomesticDnsTags
    }

    private fun buildDnsFromRoutingRules(
        configContext: CoreConfigContext,
        servers: ArrayList<Any>,
        remoteDns: List<String>,
        domesticDns: List<String>,
    ): MutableList<String> {
        val domesticDnsTags = mutableListOf<String>()
        configContext.routingDomainRules.forEachIndexed { ruleIndex, rule ->
            when (rule.outboundTag) {
                AppConfig.TAG_DIRECT -> {
                    domesticDns.forEachIndexed { dnsIndex, address ->
                        val tag = "${AppConfig.TAG_DOMESTIC_DNS}_${ruleIndex}_$dnsIndex"
                        servers.add(
                            V2rayConfig.DnsBean.ServersBean(
                                address = address,
                                domains = rule.domain,
                                skipFallback = true,
                                tag = tag
                            )
                        )
                        domesticDnsTags.add(tag)
                    }
                }
                AppConfig.TAG_BLOCKED -> Unit
                else -> {
                    servers.add(
                        V2rayConfig.DnsBean.ServersBean(
                            address = remoteDns.first(),
                            domains = rule.domain,
                        )
                    )
                }
            }
        }
        return domesticDnsTags
    }

    //endregion


    //region outbound related functions


    /**
     * Resolve outbound domains to IPs and write resolved hosts to DNS map.
     */
    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") {
            return
        }

        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty()) {
                continue
            }

            if (newHosts.containsKey(domain)) {
                item.ensureSockopt().domainStrategy = "UseIP"
                item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                    prioritizeIPv6 = preferIpv6,
                    interleave = 2
                )
                continue
            }

            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6)
            if (resolvedIps.isNullOrEmpty()) {
                continue
            }

            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                prioritizeIPv6 = preferIpv6,
                interleave = 2
            )
            newHosts[domain] = if (resolvedIps.size == 1) {
                resolvedIps[0]
            } else {
                resolvedIps
            }
        }

        dns.hosts = newHosts
    }

    /**
     * Convert one profile object into one outbound object.
     */
    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? {
        return CoreOutboundBuilder.convert(profileItem)
    }

    //endregion


    //region routing related functions


    /**
     * Merge probe settings from all balancer strategies into the runtime config.
     */
    private fun applyObservability(v2rayConfig: V2rayConfig, strategies: List<BalancerStrategy>) {
        val allObsSelectors = strategies
            .mapNotNull { it.observatory?.subjectSelector }
            .flatten()
            .distinct()
        val obsTemplate = strategies.firstNotNullOfOrNull { it.observatory }
        if (obsTemplate != null && allObsSelectors.isNotEmpty()) {
            v2rayConfig.observatory = V2rayConfig.ObservatoryObject(
                subjectSelector = allObsSelectors,
                probeUrl = obsTemplate.probeUrl,
                probeInterval = obsTemplate.probeInterval,
                enableConcurrency = obsTemplate.enableConcurrency
            )
        }

        val allBurstSelectors = strategies
            .mapNotNull { it.burstObservatory?.subjectSelector }
            .flatten()
            .distinct()
        val burstTemplate = strategies.firstNotNullOfOrNull { it.burstObservatory }
        if (burstTemplate != null && allBurstSelectors.isNotEmpty()) {
            v2rayConfig.burstObservatory = V2rayConfig.BurstObservatoryObject(
                subjectSelector = allBurstSelectors,
                pingConfig = burstTemplate.pingConfig
            )
        }
    }

    /**
     * Configure routing domain strategy and append enabled user rules.
     */
    private fun configureRouting(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {

        v2rayConfig.routing.domainStrategy =
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
                ?: "AsIs"

        // Firewall first, before every early return below. Both branches that follow return
        // without ever reading the user ruleset — Simple mode by design, trusted Wi-Fi by
        // override — so anything emitted after this point would be invisible in the app's default
        // configuration. Blocks also deliberately outrank trusted Wi-Fi: a blocked app has no
        // business reaching the network just because the SSID is trusted.
        if (SettingsManager.isFirewallEffective()) {
            v2rayConfig.routing.rules.addAll(CoreFirewallBuilder.buildRules(configContext.context))
        }

        // Trusted Wi-Fi overrides whichever routing mode (Simple or Expert) is chosen below —
        // evaluated fresh against the currently-connected SSID on every config build, so no
        // separate persisted "currently forced direct" state is needed.
        if (WifiSsidUtil.isOnTrustedWifi(configContext.context)) {
            // Xray rejects a RulesBean with zero matcher fields ("no effective fields"), and an
            // ip=0.0.0.0/0 catch-all misses sniffed connections (sniffing rewrites the destination
            // to a domain, which IP rules don't match under AsIs). network="tcp,udp" matches every
            // connection regardless of domain/IP destination — same idiom as the POLICYGROUP
            // catch-all below.
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    network = "tcp,udp",
                    outboundTag = AppConfig.TAG_DIRECT,
                )
            )
            return
        }

        if (MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_UI_MODE) != AppConfig.ROUTING_UI_MODE_EXPERT) {
            appendSimpleModeRouting(configContext.context, v2rayConfig)
            return
        }

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            appendRoutingUserRule(configContext, key, v2rayConfig, policyGroupBalancerTags)
        }
    }

    /**
     * Simple mode: a friendly proxyAll/proxySelected/bypassSelected choice instead of the
     * free-form Expert rule list. "Proxy all" needs no rules at all — an empty rule list already
     * routes everything to the first (proxy) outbound. The other two modes need exactly one rule
     * matching the listed domains plus a catch-all for everything else.
     *
     * Ready-made scenarios (RU-direct / VPN-services) are independent fixed-tag rules appended
     * first, before the general mode rule — so they take effect no matter which of the 3 modes
     * is selected, e.g. "everything via proxy except these Russian services" is just Proxy All
     * (zero general rules) plus the RU-direct scenario alone.
     */
    private fun appendSimpleModeRouting(context: Context, v2rayConfig: V2rayConfig) {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_SIMPLE_MODE)
            ?: AppConfig.ROUTING_MODE_PROXY_ALL

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_SCENARIO_RU_DIRECT, true)) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(domain = AppConfig.ROUTING_SCENARIO_RU_DIRECT_TAGS, outboundTag = AppConfig.TAG_DIRECT)
            )
            // The same scenario by app rather than by domain: a bank or marketplace app talks to
            // hosts no geosite category lists. Xray matches `process` against UIDs, and only
            // learns them on the core's own tun — through hev-socks5-tunnel every connection
            // arrives from the tunnel itself — so the rule exists only where it could match.
            if (SettingsManager.canUseProcessRouting()) {
                val uids = PackageUidResolver.packageNamesToUids(context, SettingsManager.ruDirectScenarioPackages(context))
                if (uids.isNotEmpty()) {
                    v2rayConfig.routing.rules.add(
                        V2rayConfig.RoutingBean.RulesBean(process = uids, outboundTag = AppConfig.TAG_DIRECT)
                    )
                }
            }
        }
        // Only makes sense paired with "Only selected sites through the proxy" — the UI already
        // disables/forces this switch off outside that mode, this is just belt-and-suspenders.
        if (mode == AppConfig.ROUTING_MODE_PROXY_SELECTED &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_SCENARIO_VPN_SERVICES, false)
        ) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(domain = AppConfig.ROUTING_SCENARIO_VPN_SERVICES_TAGS, outboundTag = AppConfig.TAG_PROXY)
            )
        }

        if (mode == AppConfig.ROUTING_MODE_PROXY_ALL) return

        val domains = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_SIMPLE_DOMAINS)
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (domains.isEmpty()) return

        val selectedTag = if (mode == AppConfig.ROUTING_MODE_PROXY_SELECTED) AppConfig.TAG_PROXY else AppConfig.TAG_DIRECT
        val defaultTag = if (mode == AppConfig.ROUTING_MODE_PROXY_SELECTED) AppConfig.TAG_DIRECT else AppConfig.TAG_PROXY

        v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(domain = domains, outboundTag = selectedTag))
        // Xray rejects a RulesBean with zero matcher fields ("no effective fields"), and an
        // ip=0.0.0.0/0 catch-all misses sniffed (domain-destination) connections under AsIs —
        // network="tcp,udp" matches everything, same idiom as the POLICYGROUP catch-all.
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(network = "tcp,udp", outboundTag = defaultTag)
        )
    }

    /**
     * Convert one rule item and append it to routing rules.
     */
    private fun appendRoutingUserRule(
        configContext: CoreConfigContext,
        item: RulesetItem?,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        val context = configContext.context
        if (item == null || !item.enabled) {
            return
        }

        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return

        // Replace specific geoip rules with ext versions
        rule.ip?.let { ipList ->
            val updatedIpList = ArrayList<String>()
            ipList.forEach { ip ->
                when (ip) {
                    AppConfig.GEOIP_CN -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                    AppConfig.GEOIP_PRIVATE -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                    else -> updatedIpList.add(ip)
                }
            }
            rule.ip = updatedIpList
        }

        // A rule that names apps is dropped outright when the app list can't be honoured, rather
        // than having `process` cleared. Clearing it leaves the remaining fields behind, so
        // {process:[X], outboundTag:"block"} would silently widen into "block everything" —
        // Xray either rejects the matcher-less rule and the config fails to start, or, if the
        // rule carried a port or domain too, it starts applying to every app on the device.
        val declaredProcess = rule.process
        if (!declaredProcess.isNullOrEmpty()) {
            if (!SettingsManager.canUseProcessRouting()) {
                LogUtil.w(AppConfig.TAG, "Dropping rule '${item.remarks}': per-app routing unavailable")
                return
            }
            val uids = PackageUidResolver.packageNamesToUids(context, declaredProcess)
            if (uids.isEmpty()) {
                LogUtil.w(AppConfig.TAG, "Dropping rule '${item.remarks}': none of its packages resolved")
                return
            }
            rule.process = uids
        } else {
            rule.process = null
        }

        val outboundTag = rule.outboundTag

        // Route rules targeting a custom policy-group tag should hit its balancer.
        policyGroupBalancerTags[outboundTag]?.let { balancerTag ->
            rule.outboundTag = null
            rule.balancerTag = balancerTag
        }

        // If the outbound tag is a custom one that failed to inject, fall back to proxy
        if (!outboundTag.isNullOrBlank()
            && outboundTag !in policyGroupBalancerTags
            && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS
            && v2rayConfig.outbounds.none { it.tag == outboundTag }
        ) {
            LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'")
            rule.outboundTag = AppConfig.TAG_PROXY
        }

        v2rayConfig.routing.rules.add(rule)
    }


    /**
     * Build balancer and probe settings from one policy-group strategy value.
     */
    private fun buildBalancerStrategy(
        policyGroupType: String?,
        selector: List<String>,
        balancerTag: String = AppConfig.TAG_BALANCER,
    ): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val strategyType = BalancerStrategyType.from(policyGroupType)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(
            tag = balancerTag,
            selector = selector,
            strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType)
        )
        val observatory = if (strategyType.requiresObservatory) {
            V2rayConfig.ObservatoryObject(
                subjectSelector = selector,
                probeUrl = probeUrl,
                probeInterval = "3m",
                enableConcurrency = true
            )
        } else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) {
            V2rayConfig.BurstObservatoryObject(
                subjectSelector = selector,
                pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(
                    destination = probeUrl,
                    interval = "5m",
                    sampling = 2,
                    timeout = "30s"
                )
            )
        } else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    /**
     * Carry balancer data plus optional probe settings for later merge.
     */
    private data class BalancerStrategy(
        val balancer: V2rayConfig.RoutingBean.BalancerBean,
        val observatory: V2rayConfig.ObservatoryObject? = null,
        val burstObservatory: V2rayConfig.BurstObservatoryObject? = null,
    )

    //endregion
}