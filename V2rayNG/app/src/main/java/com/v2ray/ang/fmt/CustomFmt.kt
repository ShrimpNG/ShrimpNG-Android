package com.v2ray.ang.fmt

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

object CustomFmt : FmtBase() {
    /**
     * Parses a JSON string into a ProfileItem object.
     *
     * @param str the JSON string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.CUSTOM)

        val fullConfig = JsonUtil.fromJson(str, V2rayConfig::class.java)
        val outbound = fullConfig?.getProxyOutbound()

        config.remarks = fullConfig?.remarks ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort()?.toString()
        config.customProtocol = outbound?.protocol?.takeIf { it.isNotBlank() }

        val balancer = fullConfig?.routing?.balancers?.firstOrNull()
        if (balancer != null) {
            val memberCount = fullConfig.outbounds.count { ob ->
                balancer.selector.any { prefix -> ob.tag.startsWith(prefix) }
            }
            if (memberCount >= 2) {
                config.balancerMemberCount = memberCount
                config.balancerStrategyType = balancer.strategy?.type ?: "random"
            }
        }

        return config
    }
}