package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the JSON half of the firewall against the shape a subscription actually delivers: a
 * balancer profile whose own routing ends in a catch-all that would swallow everything.
 */
class CoreFirewallBuilderTest {

    private fun balancerConfig(): JsonObject = JsonParser.parseString(
        """
        {
          "inbounds": [],
          "outbounds": [
            {"tag":"proxy","protocol":"vless"},
            {"tag":"DIRECT","protocol":"freedom"}
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [{"network":"tcp,udp","balancerTag":"AUTO"}],
            "balancers": [{"tag":"AUTO","selector":["proxy"]}]
          }
        }
        """.trimIndent()
    ).asJsonObject

    @Test
    fun test_injectRules_putsBlockRulesAheadOfTheCatchAll() {
        val json = balancerConfig()
        val block = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("10123"),
            outboundTag = "block",
        )
        CoreFirewallBuilder.injectRules(json, listOf(block))

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(2, rules.size())
        assertEquals("block", rules[0].asJsonObject.get("outboundTag").asString)
        assertEquals("AUTO", rules[1].asJsonObject.get("balancerTag").asString)
    }

    @Test
    fun test_injectRules_addsBlackholeOutboundWhenMissing() {
        val json = balancerConfig()
        CoreFirewallBuilder.injectRules(
            json,
            listOf(V2rayConfig.RoutingBean.RulesBean(process = listOf("10123"), outboundTag = "block"))
        )

        val outbounds = json.getAsJsonArray("outbounds")
        val block = outbounds.map { it.asJsonObject }.single { it.get("tag").asString == "block" }
        assertEquals("blackhole", block.get("protocol").asString)
    }

    @Test
    fun test_injectRules_doesNotDuplicateAnExistingBlockOutbound() {
        val json = balancerConfig()
        json.getAsJsonArray("outbounds").add(
            JsonParser.parseString("""{"tag":"block","protocol":"blackhole"}""")
        )
        CoreFirewallBuilder.injectRules(
            json,
            listOf(V2rayConfig.RoutingBean.RulesBean(process = listOf("10123"), outboundTag = "block"))
        )

        val blocks = json.getAsJsonArray("outbounds").count { it.asJsonObject.get("tag").asString == "block" }
        assertEquals(1, blocks)
    }

    @Test
    fun test_injectRules_leavesConfigAloneWhenNothingIsBlocked() {
        val json = balancerConfig()
        CoreFirewallBuilder.injectRules(json, emptyList())

        assertEquals(1, json.getAsJsonObject("routing").getAsJsonArray("rules").size())
        assertEquals(2, json.getAsJsonArray("outbounds").size())
    }

    @Test
    fun test_injectRules_orderTrustThenBlockThenBypass() {
        val sentinel = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("4294967294"),
            outboundTag = "block",
            network = "tcp",
        )
        val trust = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("10123"),
            domain = listOf("domain:example.com"),
            outboundTag = "proxy",
        )
        val blockDest = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("10123"),
            ip = listOf("1.2.3.4"),
            outboundTag = "block",
        )
        val catchAll = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("10123"),
            outboundTag = "block",
        )
        val bypass = V2rayConfig.RoutingBean.RulesBean(
            process = listOf("20456"),
            outboundTag = "direct",
        )
        val json = balancerConfig()
        CoreFirewallBuilder.injectRules(json, listOf(sentinel, trust, blockDest, catchAll, bypass))

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(6, rules.size())
        assertEquals("tcp", rules[0].asJsonObject.get("network").asString)
        assertEquals("proxy", rules[1].asJsonObject.get("outboundTag").asString)
        assertEquals("block", rules[2].asJsonObject.get("outboundTag").asString)
        assertEquals("block", rules[3].asJsonObject.get("outboundTag").asString)
        assertEquals("direct", rules[4].asJsonObject.get("outboundTag").asString)
        assertEquals("AUTO", rules[5].asJsonObject.get("balancerTag").asString)
    }

    @Test
    fun test_injectRules_addsDirectOutboundWhenMissingForBypass() {
        val json = JsonParser.parseString(
            """
            {
              "outbounds":[{"tag":"proxy","protocol":"vless"}],
              "routing":{"rules":[]}
            }
            """.trimIndent()
        ).asJsonObject
        CoreFirewallBuilder.injectRules(
            json,
            listOf(V2rayConfig.RoutingBean.RulesBean(process = listOf("1"), outboundTag = "direct"))
        )
        val tags = json.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(tags.contains("direct"))
        assertTrue(tags.contains("block"))
    }

    @Test
    fun test_applyRouteOnlySniffing_coversEveryInboundIncludingOneWithoutSniffing() {
        val json = JsonParser.parseString(
            """
            {"inbounds":[
              {"tag":"socks","protocol":"socks","sniffing":{"enabled":true}},
              {"tag":"tun","protocol":"tun"}
            ]}
            """.trimIndent()
        ).asJsonObject

        CoreFirewallBuilder.applyRouteOnlySniffing(json)

        json.getAsJsonArray("inbounds").forEach { elem ->
            val sniffing = elem.asJsonObject.getAsJsonObject("sniffing")
            assertTrue("routeOnly missing on ${elem.asJsonObject.get("tag")}", sniffing.get("routeOnly").asBoolean)
        }
    }

    @Test
    fun test_applyRouteOnlySniffing_enablesDestOverrideWhenJournalNeedsDomains() {
        val json = JsonParser.parseString(
            """{"inbounds":[{"tag":"tun","protocol":"tun"}]}"""
        ).asJsonObject

        CoreFirewallBuilder.applyRouteOnlySniffing(json, enableDestinationSniffForLog = true)

        val sniffing = json.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
        assertTrue(sniffing.get("enabled").asBoolean)
        assertTrue(sniffing.get("routeOnly").asBoolean)
        val overrides = sniffing.getAsJsonArray("destOverride").map { it.asString }.toSet()
        assertTrue(overrides.containsAll(listOf("http", "tls", "quic")))
    }
}
