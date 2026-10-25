package com.v2ray.ang.handler

import com.google.gson.JsonParser
import com.v2ray.ang.handler.GeoRuleGuard.GeoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoRuleGuardTest {

    @Test
    fun parsesDomainRefs() {
        assertEquals(GeoRef("geosite.dat", "CATEGORY-RU"), GeoRuleGuard.domainRef("geosite:category-ru"))
        assertEquals(GeoRef("geosite.dat", "CN"), GeoRuleGuard.domainRef("geosite:cn@!cn"))
        assertEquals(GeoRef("my.dat", "ADS"), GeoRuleGuard.domainRef("ext:my.dat:ads"))
        assertEquals(GeoRef("my.dat", "ADS"), GeoRuleGuard.domainRef("ext-site:my.dat:ads@x"))
        assertNull(GeoRuleGuard.domainRef("domain:vk.com"))
        assertNull(GeoRuleGuard.domainRef("regexp:\\.su$"))
    }

    @Test
    fun parsesIpRefs() {
        assertEquals(GeoRef("geoip.dat", "PRIVATE"), GeoRuleGuard.ipRef("geoip:private"))
        assertEquals(GeoRef("geoip.dat", "CN"), GeoRuleGuard.ipRef("geoip:!cn"))
        assertEquals(GeoRef("geoip.dat", "RU"), GeoRuleGuard.ipRef("!geoip:ru"))
        assertEquals(GeoRef("my.dat", "X"), GeoRuleGuard.ipRef("ext-ip:my.dat:x"))
        assertNull(GeoRuleGuard.ipRef("10.0.0.0/8"))
    }

    private val config = """
        {
          "dns": {"servers": [
            "1.1.1.1",
            {"address": "77.88.8.8", "domains": ["geosite:category-ru"]},
            {"address": "8.8.8.8", "domains": ["geosite:google", "domain:example.com"]}
          ]},
          "routing": {"rules": [
            {"outboundTag": "dns-out", "port": "53"},
            {"outboundTag": "direct", "domain": ["geosite:tbank-ru", "geosite:vk"]},
            {"outboundTag": "block", "domain": ["geosite:2gis"]},
            {"outboundTag": "direct", "ip": ["geoip:private"], "domain": ["geosite:wildberries"]},
            {"outboundTag": "direct", "ip": ["geoip:ru"]}
          ]}
        }
    """.trimIndent()

    @Test
    fun collectsEveryRef() {
        val refs = GeoRuleGuard.collectRefs(JsonParser.parseString(config).asJsonObject)
        assertEquals(
            setOf("CATEGORY-RU", "GOOGLE", "TBANK-RU", "VK", "2GIS", "PRIVATE", "WILDBERRIES", "RU"),
            refs.map { it.code }.toSet()
        )
    }

    @Test
    fun dropsMissingCodesAndRulesLeftEmpty() {
        val json = JsonParser.parseString(config).asJsonObject
        val missing = setOf("TBANK-RU", "2GIS", "WILDBERRIES", "CATEGORY-RU")
        assertTrue(GeoRuleGuard.removeUnavailable(json) { it.code !in missing })

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules")
        // The block rule left with no domains must go rather than block everything; so must the
        // rule whose only domain was missing even though it still had an IP list.
        assertEquals(3, rules.size())
        assertEquals(listOf("geosite:vk"), rules[1].asJsonObject.getAsJsonArray("domain").map { it.asString })
        assertEquals("geoip:ru", rules[2].asJsonObject.getAsJsonArray("ip")[0].asString)

        val servers = json.getAsJsonObject("dns").getAsJsonArray("servers")
        // The regional DNS server would otherwise answer for every domain.
        assertEquals(2, servers.size())
        assertEquals("1.1.1.1", servers[0].asString)
        assertEquals("8.8.8.8", servers[1].asJsonObject.get("address").asString)
    }

    @Test
    fun leavesConfigAloneWhenEverythingIsThere() {
        val json = JsonParser.parseString(config).asJsonObject
        assertFalse(GeoRuleGuard.removeUnavailable(json) { true })
        assertEquals(JsonParser.parseString(config), json)
    }
}
