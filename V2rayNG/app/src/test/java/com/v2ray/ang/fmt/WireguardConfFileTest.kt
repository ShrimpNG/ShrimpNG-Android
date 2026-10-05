package com.v2ray.ang.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for importing standard wg-quick configs (what the WireGuard app exports and shows
 * as a QR code), as opposed to wireguard:// links.
 */
class WireguardConfFileTest {

    private val generated = """
        # Name = Home router
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/32
        Address = fd00::2/128
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        PresharedKey = 4l5VDKqcJuDpsv1HwyAYpW7oNDYNC2Fw5AiTfCRaV2U=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51821
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun test_detects_configWithLeadingCommentAndBom() {
        assertTrue(WireguardFmt.isWireguardConfFile("﻿$generated"))
        assertTrue(WireguardFmt.isWireguardConfFile("\n  $generated"))
        assertFalse(WireguardFmt.isWireguardConfFile("wireguard://key@host:51820"))
        assertFalse(WireguardFmt.isWireguardConfFile("[Interface]\nAddress = 10.0.0.2/32"))
    }

    @Test
    fun test_parse_readsAllFields() {
        val config = WireguardFmt.parseWireguardConfFile("﻿$generated")

        assertNotNull(config)
        config!!
        assertEquals("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", config.secretKey)
        assertEquals("xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=", config.publicKey)
        assertEquals("4l5VDKqcJuDpsv1HwyAYpW7oNDYNC2Fw5AiTfCRaV2U=", config.preSharedKey)
        assertEquals("10.8.0.2/32,fd00::2/128", config.localAddress)
        assertEquals(1280, config.mtu)
        assertEquals("vpn.example.com", config.server)
        assertEquals("51821", config.serverPort)
        assertEquals("0.0.0.0/0, ::/0", config.allowedIps)
        assertEquals("Home router", config.remarks)
    }

    @Test
    fun test_parse_ipv6EndpointAndCrlf() {
        val conf = "[Interface]\r\nPrivateKey = a=\r\n[Peer]\r\nPublicKey = b=\r\nEndpoint = [2001:db8::1]:443\r\n"

        val config = WireguardFmt.parseWireguardConfFile(conf)!!

        assertEquals("2001:db8::1", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("WireGuard 2001:db8::1", config.remarks)
    }

    @Test
    fun test_parse_usesFirstPeerOnly() {
        val conf = """
            [Interface]
            PrivateKey = a=
            [Peer]
            PublicKey = first=
            Endpoint = one.example:1
            [Peer]
            PublicKey = second=
            Endpoint = two.example:2
        """.trimIndent()

        val config = WireguardFmt.parseWireguardConfFile(conf)!!

        assertEquals("first=", config.publicKey)
        assertEquals("one.example", config.server)
    }

    @Test
    fun test_parse_missingEndpointIsRejected() {
        assertNull(WireguardFmt.parseWireguardConfFile("[Interface]\nPrivateKey = a=\n[Peer]\nPublicKey = b="))
    }
}
