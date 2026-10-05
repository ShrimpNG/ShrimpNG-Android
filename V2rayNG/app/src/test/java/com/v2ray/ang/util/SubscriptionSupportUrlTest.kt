package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Support and the subscription's own page come from different headers and stay apart. */
class SubscriptionSupportUrlTest {

    private val sub = "https://panel.example/sub/abc"

    @Test
    fun webPageUrlIsNotSupport() {
        val m = SubscriptionProfileParser.resolveMetadata(mapOf("profile-web-page-url" to sub), sub)
        assertNull(m.supportUrl)
        assertEquals(sub, m.webPageUrl)
    }

    @Test
    fun supportUrlWinsEvenWhenWebPageIsSent() {
        val m = SubscriptionProfileParser.resolveMetadata(
            mapOf("Profile-Web-Page-Url" to sub, "Support-Url" to "https://t.me/help_bot"),
            sub,
        )
        assertEquals("https://t.me/help_bot", m.supportUrl)
        assertEquals(sub, m.webPageUrl)
    }

    @Test
    fun supportUrlPointingBackAtTheSubscriptionIsDropped() {
        val m = SubscriptionProfileParser.resolveMetadata(mapOf("support-url" to " $sub "), sub)
        assertNull(m.supportUrl)
    }
}
