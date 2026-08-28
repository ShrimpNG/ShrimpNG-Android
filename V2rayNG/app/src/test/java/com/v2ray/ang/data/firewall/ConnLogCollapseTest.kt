package com.v2ray.ang.data.firewall

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnLogCollapseTest {

    @Test
    fun test_collapse_sumsHitsForSameDestination() {
        val raw = listOf(
            ConnLogCollapse.Raw(ts = 1, uid = 10123, network = "tcp", destIp = "1.2.3.4", destPort = 443, srcPort = 1000),
            ConnLogCollapse.Raw(ts = 2, uid = 10123, network = "TCP", destIp = "1.2.3.4", destPort = 443, srcPort = 1001),
            ConnLogCollapse.Raw(ts = 3, uid = 10123, network = "tcp", destIp = "1.2.3.4", destPort = 80, srcPort = 1002),
        )

        val collapsed = ConnLogCollapse.collapse(raw)
        assertEquals(2, collapsed.size)

        val https = collapsed.single { it.destPort == 443 }
        assertEquals(2, https.hits)
        assertEquals(2L, https.ts)
        assertEquals(1001, https.srcPort)
        assertEquals("tcp", https.network)

        val http = collapsed.single { it.destPort == 80 }
        assertEquals(1, http.hits)
    }

    @Test
    fun test_collapse_keepsDifferentUidsApart() {
        val raw = listOf(
            ConnLogCollapse.Raw(ts = 1, uid = 1, network = "udp", destIp = "8.8.8.8", destPort = 53, srcPort = 1),
            ConnLogCollapse.Raw(ts = 2, uid = 2, network = "udp", destIp = "8.8.8.8", destPort = 53, srcPort = 2),
        )
        val collapsed = ConnLogCollapse.collapse(raw)
        assertEquals(2, collapsed.size)
        assertEquals(setOf(1, 2), collapsed.map { it.uid }.toSet())
    }

    @Test
    fun test_collapseKey_isStable() {
        assertEquals(
            "10|tcp|1.1.1.1|443",
            ConnLogCollapse.collapseKey(10, "tcp", "1.1.1.1", 443)
        )
    }

    @Test
    fun test_collapse_prefersSniffedDomain() {
        val raw = listOf(
            ConnLogCollapse.Raw(
                ts = 1, uid = 1, network = "tcp", destIp = "1.2.3.4", destPort = 443,
                srcPort = 1000, domain = null,
            ),
            ConnLogCollapse.Raw(
                ts = 2, uid = 1, network = "tcp", destIp = "1.2.3.4", destPort = 443,
                srcPort = 1001, domain = "Example.COM.",
            ),
        )
        val collapsed = ConnLogCollapse.collapse(raw).single()
        assertEquals("example.com", collapsed.domain)
        assertEquals(2, collapsed.hits)
    }
}
