package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FirewallTunnelSelectionTest {

    @Test
    fun `allowlist adds firewall packages without mutating saved selection`() {
        val saved = linkedSetOf("already.tunneled")

        val effective = FirewallTunnelSelection.apply(
            selectedApps = saved,
            bypassMode = false,
            firewallApps = setOf("must.be.filtered"),
        )

        assertEquals(setOf("already.tunneled", "must.be.filtered"), effective)
        assertEquals(setOf("already.tunneled"), saved)
    }

    @Test
    fun `denylist removes firewall packages without mutating saved selection`() {
        val saved = linkedSetOf("outside.vpn", "must.be.filtered")

        val effective = FirewallTunnelSelection.apply(
            selectedApps = saved,
            bypassMode = true,
            firewallApps = setOf("must.be.filtered"),
        )

        assertEquals(setOf("outside.vpn"), effective)
        assertEquals(setOf("outside.vpn", "must.be.filtered"), saved)
    }
}
