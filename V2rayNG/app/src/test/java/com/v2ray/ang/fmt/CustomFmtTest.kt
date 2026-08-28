package com.v2ray.ang.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for CustomFmt's balancer-profile detection.
 */
class CustomFmtTest {

    private fun proxyOutbound(tag: String) = """{"tag":"$tag","protocol":"vless"}"""

    @Test
    fun test_parse_detectsBalancerWithTwelveMembers() {
        val proxyOutbounds = (1..12).joinToString(",") { i ->
            proxyOutbound(if (i == 1) "proxy" else "proxy-$i")
        }
        val json = """
            {
              "remarks": "Auto-select",
              "log": {},
              "inbounds": [],
              "outbounds": [$proxyOutbounds, {"tag":"DIRECT","protocol":"freedom"}, {"tag":"BLOCK","protocol":"blackhole"}],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [{"network":"tcp,udp","balancerTag":"AUTO"}],
                "balancers": [{"tag":"AUTO","selector":["proxy"],"fallbackTag":"proxy","strategy":{"type":"leastLoad"}}]
              }
            }
        """.trimIndent()

        val result = CustomFmt.parse(json)

        assertEquals(12, result.balancerMemberCount)
        assertEquals("leastLoad", result.balancerStrategyType)
    }

    @Test
    fun test_parse_normalConfigHasNoBalancerFields() {
        val json = """
            {
              "remarks": "Single Server",
              "log": {},
              "inbounds": [],
              "outbounds": [{"tag":"proxy","protocol":"vless"}],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": []
              }
            }
        """.trimIndent()

        val result = CustomFmt.parse(json)

        assertNull(result.balancerMemberCount)
        assertNull(result.balancerStrategyType)
    }

    @Test
    fun test_parse_degenerateSingleMemberBalancerIsNotFlagged() {
        val json = """
            {
              "remarks": "Fake Balancer",
              "log": {},
              "inbounds": [],
              "outbounds": [{"tag":"proxy","protocol":"vless"}],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [{"network":"tcp,udp","balancerTag":"AUTO"}],
                "balancers": [{"tag":"AUTO","selector":["proxy"],"fallbackTag":"proxy","strategy":{"type":"leastLoad"}}]
              }
            }
        """.trimIndent()

        val result = CustomFmt.parse(json)

        assertNull(result.balancerMemberCount)
        assertNull(result.balancerStrategyType)
    }
}
