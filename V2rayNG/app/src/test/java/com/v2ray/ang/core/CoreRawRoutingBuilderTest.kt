package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers target resolution against the config shapes a provider subscription actually delivers.
 *
 * Only [CoreRawRoutingBuilder.retargetRule] is exercised: it is the whole of the decision and,
 * unlike the paths that log or read the profile store, it needs neither MMKV nor Android.
 */
class CoreRawRoutingBuilderTest {

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    private fun rule(tag: String): JsonObject =
        json("""{"type":"field","domain":["domain:example.com"],"outboundTag":"$tag"}""")

    @Test
    fun test_retarget_keepsAnOutboundTagThatExists() {
        val config = json("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")
        val r = rule("proxy")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        assertEquals("proxy", r.get("outboundTag").asString)
    }

    @Test
    fun test_retarget_sendsProxyToTheBalancerWhenThereIsNoProxyOutbound() {
        val config = json(
            """
            {
              "outbounds":[{"tag":"node-1","protocol":"vless"}],
              "routing":{"balancers":[{"tag":"AUTO","selector":["node-"]}]}
            }
            """.trimIndent()
        )
        val r = rule("proxy")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        // A rule reaches a balancer through balancerTag; the two fields are mutually exclusive.
        assertEquals("AUTO", r.get("balancerTag").asString)
        assertFalse(r.has("outboundTag"))
    }

    @Test
    fun test_retarget_fallsBackToTheFirstTransportOutbound() {
        val config = json(
            """
            {"outbounds":[
              {"tag":"dns-out","protocol":"dns"},
              {"tag":"bypass","protocol":"freedom"},
              {"tag":"vps","protocol":"trojan"}
            ]}
            """.trimIndent()
        )
        val r = rule("proxy")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        // freedom/dns/blackhole are never the proxy, however early they appear.
        assertEquals("vps", r.get("outboundTag").asString)
    }

    @Test
    fun test_retarget_refusesWhenNothingCanStandInForProxy() {
        val config = json("""{"outbounds":[{"tag":"bypass","protocol":"freedom"}]}""")

        // Emitting the rule anyway would black-hole the traffic: Xray closes connections whose
        // rule names a tag that does not exist rather than falling back to the default outbound.
        assertFalse(CoreRawRoutingBuilder.retargetRule(config, rule("proxy")))
    }

    @Test
    fun test_retarget_prefersTheProvidersOwnSpellingOfDirect() {
        val config = json("""{"outbounds":[{"tag":"DIRECT","protocol":"freedom"}]}""")
        val r = rule("direct")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        assertEquals("DIRECT", r.get("outboundTag").asString)
        // Tags are case-sensitive, so the existing outbound is referenced, not shadowed.
        assertEquals(1, config.getAsJsonArray("outbounds").size())
    }

    @Test
    fun test_retarget_appendsBlackholeAndNeverReordersOutbounds() {
        val config = json("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")
        val r = rule("block")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("blackhole", outbounds.single { it.get("tag").asString == "block" }.get("protocol").asString)
        // The first outbound is Xray's default handler, so it has to stay first.
        assertEquals("proxy", outbounds.first().get("tag").asString)
    }

    @Test
    fun test_retarget_leavesARuleThatAlreadyAimsAtABalancer() {
        val config = json("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")
        val r = json("""{"type":"field","network":"tcp,udp","balancerTag":"AUTO"}""")

        assertTrue(CoreRawRoutingBuilder.retargetRule(config, r))
        assertEquals("AUTO", r.get("balancerTag").asString)
    }
}
