package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireguardRoutesTest {

    @Test
    fun test_normalizeToNetwork_masksHostBits() {
        assertEquals("10.66.66.0/24", WireguardRoutes.normalizeToNetwork("10.66.66.7/24"))
        assertEquals("10.0.0.0/8", WireguardRoutes.normalizeToNetwork("10.66.66.7/8"))
        assertEquals("192.168.1.0/30", WireguardRoutes.normalizeToNetwork("192.168.1.2/30"))
    }

    @Test
    fun test_normalizeToNetwork_rejectsNonsense() {
        assertNull(WireguardRoutes.normalizeToNetwork("10.66.66.7"))
        assertNull(WireguardRoutes.normalizeToNetwork("10.66.66.7/33"))
        assertNull(WireguardRoutes.normalizeToNetwork("10.66.999.7/24"))
        assertNull(WireguardRoutes.normalizeToNetwork("fd00::1/64"))
    }

    @Test
    fun test_specificAllowedIpsWin() {
        val routes = WireguardRoutes.privateRoutesFor(
            allowedIps = "10.66.66.0/24, 192.168.9.0/24, 1.1.1.1/32",
            localAddress = "10.66.66.7/32",
        )
        // Public entries are irrelevant here: bypass-LAN already routes those into the tunnel.
        assertEquals(listOf("10.66.66.0/24", "192.168.9.0/24"), routes)
    }

    @Test
    fun test_catchAllAllowedIpsFallsBackToTheInterfaceSubnet() {
        // The common shape: Address = .../32 and AllowedIPs = 0.0.0.0/0, which says nothing about
        // the peer's own subnet, so the surrounding /24 is assumed.
        val routes = WireguardRoutes.privateRoutesFor(
            allowedIps = "0.0.0.0/0, ::/0",
            localAddress = "10.66.66.7/32",
        )
        assertEquals(listOf("10.66.66.0/24"), routes)
    }

    @Test
    fun test_interfacePrefixIsUsedWhenItCarriesTheSubnet() {
        val routes = WireguardRoutes.privateRoutesFor(
            allowedIps = "0.0.0.0/0",
            localAddress = "10.66.0.2/16",
        )
        assertEquals(listOf("10.66.0.0/16"), routes)
    }

    @Test
    fun test_noPrivateNetworkInvolved() {
        assertTrue(
            WireguardRoutes.privateRoutesFor(allowedIps = "0.0.0.0/0", localAddress = null).isEmpty()
        )
        assertTrue(
            WireguardRoutes.privateRoutesFor(allowedIps = null, localAddress = "203.0.113.5/32").isEmpty()
        )
    }

    @Test
    fun test_bareAddressWithoutPrefixStillYieldsASubnet() {
        val routes = WireguardRoutes.privateRoutesFor(allowedIps = null, localAddress = "10.66.66.7")
        assertEquals(listOf("10.66.66.0/24"), routes)
    }
}
