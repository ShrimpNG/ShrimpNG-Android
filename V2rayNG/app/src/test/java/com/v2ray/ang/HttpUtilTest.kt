package com.v2ray.ang

import com.v2ray.ang.util.HttpUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUtilTest {

    @Test
    fun withSubscriptionClientType_appendsJsonPath() {
        assertEquals(
            "https://sub.example.com/api/sub/abc/json",
            HttpUtil.withSubscriptionClientType("https://sub.example.com/api/sub/abc", "json"),
        )
        assertEquals(
            "https://sub.example.com/api/sub/abc/json?foo=1",
            HttpUtil.withSubscriptionClientType("https://sub.example.com/api/sub/abc?foo=1", "json"),
        )
    }

    @Test
    fun withSubscriptionClientType_replacesExistingClientType() {
        assertEquals(
            "https://sub.example.com/api/sub/abc/json",
            HttpUtil.withSubscriptionClientType("https://sub.example.com/api/sub/abc/clash", "json"),
        )
        assertNull(
            HttpUtil.withSubscriptionClientType("https://sub.example.com/api/sub/abc/json", "json"),
        )
    }

    @Test
    fun looksLikeXrayJsonSubscription_requiresFullTemplate() {
        assertTrue(
            HttpUtil.looksLikeXrayJsonSubscription(
                """{"inbounds":[],"outbounds":[],"routing":{}}""",
            ),
        )
        assertFalse(HttpUtil.looksLikeXrayJsonSubscription("vmess://abc"))
        assertFalse(HttpUtil.looksLikeXrayJsonSubscription("""{"outbounds":[]}"""))
    }

    @Test
    fun testIdnToASCII() {
        // Regular URL remains unchanged
        val regularUrl = "https://example.com/path"
        assertEquals(regularUrl, HttpUtil.toIdnUrl(regularUrl))

        // Non-ASCII URL converts to ASCII (Punycode)
        val nonAsciiUrl = "https://例子.测试/path"
        val expectedNonAscii = "https://xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedNonAscii, HttpUtil.toIdnUrl(nonAsciiUrl))

        // Mixed URL only converts the host part
        val mixedUrl = "https://例子.com/测试"
        val expectedMixed = "https://xn--fsqu00a.com/测试"
        assertEquals(expectedMixed, HttpUtil.toIdnUrl(mixedUrl))

        // URL with Basic Authentication using regular domain
        val basicAuthUrl = "https://user:password@example.com/path"
        assertEquals(basicAuthUrl, HttpUtil.toIdnUrl(basicAuthUrl))

        // URL with Basic Authentication using non-ASCII domain
        val basicAuthNonAscii = "https://user:password@例子.测试/path"
        val expectedBasicAuthNonAscii = "https://user:password@xn--fsqu00a.xn--0zwm56d/path"
        assertEquals(expectedBasicAuthNonAscii, HttpUtil.toIdnUrl(basicAuthNonAscii))

        // URL with non-ASCII username and password
        val nonAsciiAuth = "https://用户:密码@example.com/path"
        // Basic auth credentials should remain unchanged as they're percent-encoded separately
        assertEquals(nonAsciiAuth, HttpUtil.toIdnUrl(nonAsciiAuth))
    }


}