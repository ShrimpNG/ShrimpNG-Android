package com.v2ray.ang.data.firewall

import com.v2ray.ang.util.NetworkMeteredUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallPolicyEvaluatorTest {

    @Test
    fun `block and isolate always block`() {
        NetworkMeteredUtil.Kind.entries.forEach { network ->
            assertTrue(FirewallPolicyEvaluator.shouldBlock(AppFirewallStatus.BLOCK, network))
            assertTrue(FirewallPolicyEvaluator.shouldBlock(AppFirewallStatus.ISOLATE, network))
        }
    }

    @Test
    fun `wifi only blocks mobile and unknown networks`() {
        assertFalse(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.WIFI_ONLY,
                NetworkMeteredUtil.Kind.WIFI,
            )
        )
        assertTrue(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.WIFI_ONLY,
                NetworkMeteredUtil.Kind.MOBILE,
            )
        )
        assertTrue(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.WIFI_ONLY,
                NetworkMeteredUtil.Kind.UNKNOWN,
            )
        )
    }

    @Test
    fun `mobile only blocks wifi and unknown networks`() {
        assertFalse(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.MOBILE_ONLY,
                NetworkMeteredUtil.Kind.MOBILE,
            )
        )
        assertTrue(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.MOBILE_ONLY,
                NetworkMeteredUtil.Kind.WIFI,
            )
        )
        assertTrue(
            FirewallPolicyEvaluator.shouldBlock(
                AppFirewallStatus.MOBILE_ONLY,
                NetworkMeteredUtil.Kind.UNKNOWN,
            )
        )
    }

    @Test
    fun `allow and bypass never block`() {
        NetworkMeteredUtil.Kind.entries.forEach { network ->
            assertFalse(FirewallPolicyEvaluator.shouldBlock(AppFirewallStatus.ALLOW, network))
            assertFalse(FirewallPolicyEvaluator.shouldBlock(AppFirewallStatus.BYPASS_DIRECT, network))
        }
    }
}
