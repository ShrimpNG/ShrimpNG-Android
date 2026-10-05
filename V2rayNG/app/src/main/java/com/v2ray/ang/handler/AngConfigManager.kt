package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.SubscriptionProfileParser
import com.v2ray.ang.util.Utils
import java.net.URI

object AngConfigManager {

    // Parser mapping for different config types (lazy initialized)
    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse
        )
    }

    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = CoreConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }

        var newSubIds = parseBatchSubscription(server)
        if (newSubIds.isEmpty()) {
            newSubIds = parseBatchSubscription(Utils.decode(server))
        }
        // Only the subscriptions just added get fetched. Refreshing every saved subscription here
        // made adding one link wait on all the others, and hit servers the user never asked about.
        newSubIds.forEach { guid ->
            MmkvManager.decodeSubscription(guid)?.let { updateConfigViaSub(SubscriptionCache(guid, it)) }
        }

        return count to newSubIds.size
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    /** @return guids of the subscriptions this call created; already-saved urls are skipped. */
    private fun parseBatchSubscription(servers: String?): List<String> {
        try {
            if (servers == null) {
                return emptyList()
            }

            return servers.lines()
                .distinct()
                .filter { Utils.isValidSubUrl(it) }
                .mapNotNull { importUrlAsSubscription(it) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return emptyList()
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            // Find the currently selected server that belongs to the same subscription before replacement.
            val removedSelected = getRemovedSelectedProfile(subid, append)

            val subItem = MmkvManager.decodeSubscription(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    val config = parseConfig(it, subid, subItem)
                    if (config != null) {
                        configs.add(config)
                    }
                }

            // Batch save all parsed configs (only one serverList read/write)
            if (configs.isNotEmpty()) {
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val keyToProfile = batchSaveConfigs(configs, subid)
                restoreSelection(keyToProfile, removedSelected, MmkvManager.decodeServerList(subid))
            }

            return configs.size
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    /**
     * Batch save configurations to reduce serverList read/write operations.
     * Reads serverList once, saves all configs, then writes serverList once.
     *
     * @param configs The list of ProfileItem to save.
     * @param subid The subscription ID.
     * @return Map of generated keys to their corresponding ProfileItem.
     */
    private fun batchSaveConfigs(configs: List<ProfileItem>, subid: String): Map<String, ProfileItem> {
        val keyToProfile = mutableMapOf<String, ProfileItem>()

        // Read serverList once
        val serverList = MmkvManager.decodeServerList(subid)

        configs.forEach { config ->
            val key = Utils.getUuid()
            // Save profile directly without updating serverList
            MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(config))

            if (!serverList.contains(key)) {
                serverList.add(0, key)
            }
            keyToProfile[key] = config
        }

        // Write serverList once
        MmkvManager.encodeServerList(serverList, subid)
        return keyToProfile
    }

    /**
     * Finds a matched profile key from the given key-profile map using multi-level matching.
     * Matching priority (from highest to lowest):
     * 1. Exact match: server + port + password
     * 2. Match by remarks (exact match)
     * 3. Match by server + port
     * 4. Match by server only
     *
     * @param keyToProfile Map of server keys to their ProfileItem
     * @param target Target profile to match
     * @return Matched key or null
     */
    private fun findMatchedProfileKey(keyToProfile: Map<String, ProfileItem>, target: ProfileItem?): String? {
        if (keyToProfile.isEmpty()) return null
        if (target == null) return null

        // Level 0: Full match (remarks + server + port + password)
        if (target.remarks.isNotBlank()) {
            keyToProfile.entries.firstOrNull { (_, saved) ->
                isSameText(saved.remarks, target.remarks) &&
                        isSameText(saved.server, target.server) &&
                        isSameText(saved.serverPort, target.serverPort) &&
                        isSameText(saved.password, target.password)
            }?.key?.let { return it }
        }

        // Level 1: Match by remarks
        if (target.remarks.isNotBlank()) {
            keyToProfile.entries.firstOrNull { (_, saved) ->
                isSameText(saved.remarks, target.remarks)
            }?.key?.let { return it }
        }

        // Level 2: Exact match (server + port + password)
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort) &&
                    isSameText(saved.password, target.password)
        }?.key?.let { return it }

        // Level 3: Match by server + port
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort)
        }?.key?.let { return it }

        // Level 4: Match by server only
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server)
        }?.key?.let { return it }

        // If old selected node cannot be matched, fall back to the first imported config.
        return keyToProfile.keys.firstOrNull()
    }

    /**
     * Point the selection back at the server it was on, or somewhere sensible if it cannot.
     *
     * A refresh deletes every profile the subscription owns and writes the replacements under new
     * GUIDs, so the selection is re-pointed by hand afterwards. When the server that was selected
     * is no longer in the list — or nothing was selected to begin with — the *first* server is
     * taken, so the app is never left with nothing to connect to and never picks arbitrarily.
     *
     * @param guids the subscription's servers in display order.
     */
    private fun restoreSelection(
        keyToProfile: Map<String, ProfileItem>,
        removedSelected: ProfileItem?,
        guids: List<String>,
    ) {
        findMatchedProfileKey(keyToProfile, removedSelected)?.let {
            LogUtil.i(AppConfig.TAG, "Subscription: selection kept on '${removedSelected?.remarks}' as $it")
            MmkvManager.setSelectServer(it)
            return
        }
        if (MmkvManager.getSelectServer() == null) {
            LogUtil.w(
                AppConfig.TAG,
                "Subscription: '${removedSelected?.remarks}' has no match after refresh, selecting the first server"
            )
            guids.firstOrNull()?.let { MmkvManager.setSelectServer(it) }
        }
    }

    /**
     * Returns the currently selected profile if it belongs to the target subscription and will be replaced.
     */
    private fun getRemovedSelectedProfile(subid: String, append: Boolean): ProfileItem? {
        if (subid.isBlank() || append) return null

        return MmkvManager.getSelectServer()
            .takeIf { it?.isNotBlank() == true }
            ?.let { MmkvManager.decodeServerConfig(it) }
            ?.takeIf { it.subscriptionId == subid }
    }

    /**
     * Case-insensitive trimmed string comparison.
     *
     * @param left First string
     * @param right Second string
     * @return True if both are non-empty and equal (case-insensitive, trimmed)
     */
    private fun isSameText(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    val removedSelected = getRemovedSelectedProfile(subid, append)
                    if (!append) {
                        MmkvManager.removeServerViaSubid(subid)
                    }
                    var count = 0
                    val keyToProfile = mutableMapOf<String, ProfileItem>()
                    // Written directly, the way parseBatchConfig does, rather than through
                    // encodeServerConfig: that one also selects the profile it is handed whenever
                    // nothing is currently selected, which during a rebuild means the first one
                    // written. The list is walked in reverse so the provider's order survives, so
                    // the first one written is the one shown last — which is where "the bottom
                    // server selects itself after an update" came from.
                    val guids = MmkvManager.decodeServerList(subid)
                    for (srv in serverList.reversed()) {
                        val config = CustomFmt.parse(JsonUtil.toJson(srv)) ?: continue
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        val key = Utils.getUuid()
                        MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(config))
                        MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(srv) ?: "")
                        if (!guids.contains(key)) {
                            guids.add(0, key)
                        }
                        keyToProfile[key] = config
                        count += 1
                    }
                    MmkvManager.encodeServerList(guids, subid)
                    if (count > 0) {
                        restoreSelection(keyToProfile, removedSelected, guids)
                    }
                    return count
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server) ?: return 0
                config.subscriptionId = subid
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (WireguardFmt.isWireguardConfFile(server)) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server) ?: return 0
                // Into the subscription it was imported into, like every other single import
                // (parseConfig does the same); it used to land in the hidden built-in list
                // instead. No raw copy: this is a typed WIREGUARD profile, and a stored raw config
                // is what marks a provider's custom profile elsewhere in the app.
                config.subscriptionId = subid
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                MmkvManager.encodeServerConfig("", config)
                return 1
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
                if (str.startsWith(scheme)) parser(str) else null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            // A link whose host cannot be parsed — java.net.URI returns no host at all for one
            // with an underscore in it, for instance — comes out as a profile with no server,
            // which the start path refuses as an invalid profile. Dropped here rather than
            // stored: kept, it can be selected in place of a working profile by remarks alone
            // after a refresh, and nothing would then connect until the next one.
            if (!config.configType.isComplexType() && !hasUsableServer(config)) {
                LogUtil.w(
                    AppConfig.TAG,
                    "Subscription: dropping '${config.remarks}' (${config.configType}): unusable server '${config.server}' in $str"
                )
                return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /** The same test the start path applies before it will connect to a typed profile. */
    private fun hasUsableServer(config: ProfileItem): Boolean =
        Utils.isValidUrl(config.server) || Utils.isPureIpAddress(config.server.orEmpty())

    /** One word for the log about what a subscription body turned out to be. */
    private fun describeBody(body: String): String {
        val text = body.trim()
        return when {
            HttpUtil.looksLikeXrayJsonSubscription(text) -> "xray-json"
            text.startsWith("{") || text.startsWith("[") -> "other-json"
            text.contains("://") -> "links"
            Utils.decode(text).contains("://") -> "base64-links"
            else -> "unrecognised"
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    /**
     * @param reschedule whether to (re)schedule the subscription's periodic work once the
     *   provider's metadata has been applied. False from inside the worker itself: rescheduling
     *   from there cancels the very run that is in progress, and the replacement it enqueues — due
     *   at once, since lastUpdated has not been advanced yet — starts a second refresh of the same
     *   subscription while the first is still writing. The two interleave their delete-and-rewrite
     *   of the profile list, and the selection ends up on a profile that one of them deleted.
     */
    fun updateConfigViaSub(it: SubscriptionCache, reschedule: Boolean = true): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            LogUtil.i(AppConfig.TAG, url)
            val userAgent = it.subscription.userAgent
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()

            var configText = ""
            var responseHeaders = emptyMap<String, String>()
            var route = "proxy"
            try {
                val httpPort = SettingsManager.getHttpPort()
                val fetchResult = HttpUtil.fetchSubscriptionPreferringJson(
                    UrlContentRequest(
                        url = url,
                        userAgent = userAgent,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
                configText = fetchResult.body
                responseHeaders = fetchResult.headers
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
            }
            if (configText.isEmpty()) {
                route = "direct"
                try {
                    val fetchResult = HttpUtil.fetchSubscriptionPreferringJson(
                        UrlContentRequest(
                            url = url,
                            userAgent = userAgent
                        )
                    )
                    configText = fetchResult.body
                    responseHeaders = fetchResult.headers
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e)
                }
            }
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            // Enough to tell, from a log alone, whether two refreshes of the same subscription
            // were handed the same thing: the body can differ by route (a provider may answer
            // a request arriving from its own server's address differently) and by format.
            LogUtil.i(
                AppConfig.TAG,
                "Subscription fetched: ${it.subscription.remarks}, via $route, ${configText.length} chars, ${describeBody(configText)}"
            )

            applyRemoteSubscriptionTitle(it, configText, responseHeaders)
            applyRemoteSubscriptionMetadata(it, responseHeaders, reschedule)

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                val selected = MmkvManager.getSelectServer()?.let { guid -> MmkvManager.decodeServerConfig(guid) }
                LogUtil.i(
                    AppConfig.TAG,
                    "Subscription updated: ${it.subscription.remarks}, $count configs, " +
                        "selected now '${selected?.remarks}' (${selected?.configType}, server '${selected?.server}')"
                )
                // Every profile now has a new GUID. A UI process that is showing the old ones
                // has to be told, or it keeps drawing a list in which nothing is selected — the
                // selection moved to a GUID it has never seen — and a tap on any row selects a
                // GUID that no longer exists.
                MessageUtil.sendMsg2UI(AngApplication.application, AppConfig.MSG_SUBSCRIPTION_UPDATED, it.guid)
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        // Prefer the full XRAY_JSON response before attempting legacy URI/Base64 parsing.
        // Otherwise a permissive batch parser can consume fragments of a JSON template and lose
        // its routing, balancers, DNS and other subscription-level configuration.
        var count = parseCustomConfigServer(server, subid, append)
        if (count <= 0) {
            count = parseBatchConfig(Utils.decode(server), subid, append)
        }
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        return count
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    /** @return the guid of the newly created subscription, or null when this url is already saved. */
    private fun importUrlAsSubscription(url: String): String? {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return null
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        // Placeholder only: the first update replaces it with the provider's own title, which
        // isAutoRemarks() recognises this exact value in order to allow.
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.remarksUserSet = false
        subItem.url = url
        return MmkvManager.encodeSubscription("", subItem)
    }

    private fun applyRemoteSubscriptionTitle(
        cache: SubscriptionCache,
        body: String,
        headers: Map<String, String>,
    ) {
        val subItem = cache.subscription
        if (subItem.remarksUserSet) return
        if (!SubscriptionProfileParser.isAutoRemarks(subItem.remarks, subItem.url)) return

        val remoteTitle = SubscriptionProfileParser.resolveTitle(body, headers) ?: return
        if (remoteTitle.isBlank()) return

        subItem.remarks = remoteTitle
        MmkvManager.encodeSubscription(cache.guid, subItem)
        LogUtil.i(AppConfig.TAG, "Subscription title updated: $remoteTitle")
    }

    private fun applyRemoteSubscriptionMetadata(
        cache: SubscriptionCache,
        headers: Map<String, String>,
        reschedule: Boolean,
    ) {
        val subItem = cache.subscription
        val metadata = SubscriptionProfileParser.resolveMetadata(headers, subItem.url)

        // The provider knows how often its own list changes, so its stated cadence wins. With no
        // header, a subscription that has never been configured gets the default cadence; one the
        // user has already tuned is left alone rather than reset on every refresh.
        val defaults = SubscriptionItem()
        val untouched = !subItem.autoUpdate && subItem.updateInterval == defaults.updateInterval
        val targetInterval = metadata.updateIntervalMinutes
            ?: if (untouched) SubscriptionProfileParser.DEFAULT_UPDATE_INTERVAL_MINUTES else subItem.updateInterval
        // Switched on for a subscription that has never been configured — including ones added
        // before this existed. Not forced on every refresh: someone who deliberately turned
        // auto-update off should not have it switched back on behind their back.
        val targetAutoUpdate = subItem.autoUpdate || untouched

        val changed = subItem.description != metadata.description ||
            subItem.supportUrl != metadata.supportUrl ||
            subItem.webPageUrl != metadata.webPageUrl ||
            subItem.trafficUsedBytes != metadata.trafficUsedBytes ||
            subItem.trafficTotalBytes != metadata.trafficTotalBytes ||
            subItem.expireAtSeconds != metadata.expireAtSeconds ||
            subItem.updateInterval != targetInterval ||
            subItem.autoUpdate != targetAutoUpdate
        if (!changed) return

        subItem.description = metadata.description
        subItem.supportUrl = metadata.supportUrl
        subItem.webPageUrl = metadata.webPageUrl
        subItem.trafficUsedBytes = metadata.trafficUsedBytes
        subItem.trafficTotalBytes = metadata.trafficTotalBytes
        subItem.expireAtSeconds = metadata.expireAtSeconds
        subItem.updateInterval = targetInterval
        subItem.autoUpdate = targetAutoUpdate
        MmkvManager.encodeSubscription(cache.guid, subItem)

        // Persisting the flag isn't enough — the periodic work has to be (re)scheduled for it to
        // mean anything, and the interval it was scheduled with may have just changed. Not from
        // the worker, though: it reschedules itself once it is done (see updateConfigViaSub).
        if (reschedule) {
            SubscriptionUpdater.syncOne(subId = cache.guid)
        }
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // A balancer has no single address worth showing, and spelling out its strategy and member
        // count just adds noise to the row — the profile's own name says enough.
        if ((profile.balancerMemberCount ?: 0) >= 2) return ""

        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
