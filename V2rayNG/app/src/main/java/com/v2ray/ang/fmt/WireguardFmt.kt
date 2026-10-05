package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.removeWhiteSpace
import com.v2ray.ang.util.Utils
import java.net.URI

object WireguardFmt : FmtBase() {
    /**
     * Parses a URI string into a ProfileItem object.
     *
     * @param str the URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"
        config.allowedIps = queryParam["allowedips"]?.nullIfBlank()

        return config
    }

    /**
     * Whether [str] looks like a standard wg-quick config (what the WireGuard app exports and
     * shows as a QR code) rather than a wireguard:// link. Leading comments ("# Name"), blank
     * lines and a BOM are common in generated configs, so this doesn't insist the text *starts*
     * with "[Interface]".
     */
    fun isWireguardConfFile(str: String?): Boolean {
        if (str.isNullOrBlank()) return false
        return str.contains("[Interface]", ignoreCase = true)
            && str.contains("[Peer]", ignoreCase = true)
            && str.contains("PrivateKey", ignoreCase = true)
    }

    /**
     * Parses a standard wg-quick configuration ([Interface] / [Peer] sections) into a profile.
     *
     * Keys that may repeat (Address, AllowedIPs) are joined with commas, as wg-quick does. Only
     * the first [Peer] is used: one Xray WireGuard outbound is one peer here. Fields Xray has no
     * use for (DNS, the Amnezia obfuscation keys, PostUp…) are ignored.
     *
     * @param str the configuration text
     * @return the parsed profile, or null when the private key, peer public key or endpoint is
     * missing
     */
    fun parseWireguardConfFile(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val interfaceParams: MutableMap<String, String> = mutableMapOf()
        val peerParams: MutableMap<String, String> = mutableMapOf()
        var peerCount = 0
        var name: String? = null

        var currentSection: String? = null

        str.removePrefix("\uFEFF").lines().forEach { line ->
            val trimmedLine = line.trim()

            if (trimmedLine.isEmpty()) return@forEach
            if (trimmedLine.startsWith("#")) {
                // The WireGuard app and most generators put the tunnel name in a leading comment.
                if (currentSection == null && name == null) {
                    name = NAME_COMMENT.matchEntire(trimmedLine)?.groupValues?.get(1)?.trim()?.nullIfBlank()
                }
                return@forEach
            }

            when {
                trimmedLine.startsWith("[Interface]", ignoreCase = true) -> currentSection = "Interface"
                trimmedLine.startsWith("[Peer]", ignoreCase = true) -> {
                    peerCount++
                    currentSection = if (peerCount == 1) "Peer" else null
                }
                trimmedLine.startsWith("[") -> currentSection = null
                else -> {
                    val target = when (currentSection) {
                        "Interface" -> interfaceParams
                        "Peer" -> peerParams
                        else -> return@forEach
                    }
                    // limit = 2: base64 keys end in "=" padding.
                    val parts = trimmedLine.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2 && parts[0].isNotEmpty()) {
                        val key = parts[0].lowercase()
                        val value = parts[1]
                        target[key] = if (key in REPEATABLE_KEYS && target.containsKey(key)) {
                            target.getValue(key) + "," + value
                        } else {
                            value
                        }
                    }
                }
            }
        }

        val privateKey = interfaceParams["privatekey"].orEmpty()
        val publicKey = peerParams["publickey"].orEmpty()
        val (host, port) = splitEndpoint(peerParams["endpoint"].orEmpty())
        if (privateKey.isEmpty() || publicKey.isEmpty() || host.isEmpty()) return null

        config.secretKey = privateKey
        config.localAddress = interfaceParams["address"]?.splitToSequence(",")?.map { it.trim() }
            ?.filter { it.isNotEmpty() }?.joinToString(",")?.nullIfBlank()
            ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = publicKey
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        config.server = host
        config.serverPort = port.ifEmpty { DEFAULT_PORT }
        config.reserved = peerParams["reserved"] ?: "0,0,0"
        config.allowedIps = peerParams["allowedips"]?.nullIfBlank()
        config.remarks = name ?: "WireGuard $host"

        return config
    }

    /** "host:port", "[v6]:port" or a bare host; the host comes back without brackets. */
    private fun splitEndpoint(endpoint: String): Pair<String, String> {
        val value = endpoint.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close > 0) {
                return value.substring(1, close) to value.substring(close + 1).removePrefix(":").trim()
            }
        }
        val colon = value.lastIndexOf(':')
        // More than one colon without brackets is a bare IPv6 address, not host:port.
        if (colon > 0 && value.indexOf(':') == colon) {
            return value.substring(0, colon).trim() to value.substring(colon + 1).trim()
        }
        return value to ""
    }

    private val REPEATABLE_KEYS = setOf("address", "allowedips")
    private val NAME_COMMENT = Regex("""#\s*(?:Name\s*=\s*)?(.+)""", RegexOption.IGNORE_CASE)
    private const val DEFAULT_PORT = "51820"

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        dicQuery["publickey"] = config.publicKey.orEmpty()
        if (config.reserved != null) {
            dicQuery["reserved"] = config.reserved.removeWhiteSpace().orEmpty()
        }
        dicQuery["address"] = config.localAddress.removeWhiteSpace().orEmpty()
        if (config.mtu != null) {
            dicQuery["mtu"] = config.mtu.toString()
        }
        if (config.preSharedKey != null) {
            dicQuery["presharedkey"] = config.preSharedKey.removeWhiteSpace().orEmpty()
        }
        // Carried so an exported profile keeps the peer's subnets, which the VPN routes need.
        if (config.allowedIps != null) {
            dicQuery["allowedips"] = config.allowedIps.removeWhiteSpace().orEmpty()
        }

        return toUri(config, config.secretKey, dicQuery)
    }
}
