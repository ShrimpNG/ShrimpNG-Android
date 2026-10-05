package com.v2ray.ang.util

import android.util.Base64
import java.net.URI

data class SubscriptionMetadata(
    val description: String? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val trafficUsedBytes: Long? = null,
    val trafficTotalBytes: Long? = null,
    val expireAtSeconds: Long? = null,
    /** Already converted to minutes, the unit [SubscriptionItem.updateInterval] uses. */
    val updateIntervalMinutes: Long? = null,
)

object SubscriptionProfileParser {

    private val PROFILE_TITLE_HEADER = Regex("^profile-?title$", RegexOption.IGNORE_CASE)
    private val USERINFO_HEADER = Regex("^subscription-userinfo$", RegexOption.IGNORE_CASE)
    private val WEB_PAGE_URL_HEADER = Regex("^profile-web-page-url$", RegexOption.IGNORE_CASE)
    private val SUPPORT_URL_HEADER = Regex("^support-url$", RegexOption.IGNORE_CASE)
    private val ANNOUNCE_HEADER = Regex("^announce$", RegexOption.IGNORE_CASE)
    private val UPDATE_INTERVAL_HEADER = Regex("^profile-update-interval$", RegexOption.IGNORE_CASE)

    /** Cadence to fall back on when the provider doesn't state one: 3 hours. */
    const val DEFAULT_UPDATE_INTERVAL_MINUTES = 180L

    /**
     * Reads `Profile-Update-Interval`, which the Clash/sing-box convention the panels follow
     * expresses in **hours**, and converts it to the minutes that [SubscriptionItem.updateInterval]
     * stores.
     *
     * @return null when the header is missing, unparseable, or so far out of range that it is more
     * likely a different unit than a real cadence — callers then use
     * [DEFAULT_UPDATE_INTERVAL_MINUTES] rather than scheduling something absurd.
     */
    fun parseUpdateIntervalMinutes(raw: String?): Long? {
        val hours = raw?.trim()?.toDoubleOrNull() ?: return null
        if (hours <= 0.0 || hours > 24 * 30) return null
        return (hours * 60).toLong().coerceAtLeast(1L)
    }

    /**
     * Reads whatever optional metadata the subscription response provides. Every field is
     * null when the provider doesn't send the corresponding header — callers must not
     * fabricate placeholder values for missing fields.
     */
    fun resolveMetadata(headers: Map<String, String>, subscriptionUrl: String? = null): SubscriptionMetadata {
        val userinfo = headers.entries.firstOrNull { (key, _) -> USERINFO_HEADER.matches(key) }?.value
        val (used, total, expire) = parseUserinfo(userinfo)

        // Two different things, kept apart. Profile-Web-Page-Url is the subscription's own page
        // (panels like Remnawave and Marzban fill it with the subscription link itself); only
        // Support-Url is where to get help. Preferring the first is what made the support button
        // open the subscription for so many providers.
        val webPageUrl = headers.entries.firstOrNull { (key, _) -> WEB_PAGE_URL_HEADER.matches(key) }?.value
            ?.trim()?.takeIf { it.isNotBlank() }
        val supportUrl = headers.entries.firstOrNull { (key, _) -> SUPPORT_URL_HEADER.matches(key) }?.value
            ?.trim()?.takeIf { it.isNotBlank() && it != webPageUrl && it != subscriptionUrl?.trim() }

        val description = headers.entries.firstOrNull { (key, _) -> ANNOUNCE_HEADER.matches(key) }?.value
            ?.let { decodeTitleValue(it) }
            ?.takeIf { it.isNotBlank() }

        val updateIntervalMinutes = parseUpdateIntervalMinutes(
            headers.entries.firstOrNull { (key, _) -> UPDATE_INTERVAL_HEADER.matches(key) }?.value
        )

        return SubscriptionMetadata(
            description = description,
            supportUrl = supportUrl,
            webPageUrl = webPageUrl,
            trafficUsedBytes = used,
            trafficTotalBytes = total,
            expireAtSeconds = expire,
            updateIntervalMinutes = updateIntervalMinutes,
        )
    }

    /**
     * Parses the Clash/sing-box convention `Subscription-Userinfo` header, e.g.
     * `upload=123; download=456; total=1000000000; expire=1735689600`.
     */
    private fun parseUserinfo(raw: String?): Triple<Long?, Long?, Long?> {
        if (raw.isNullOrBlank()) return Triple(null, null, null)
        val fields = raw.split(";").mapNotNull { part ->
            val pieces = part.trim().split("=", limit = 2)
            if (pieces.size != 2) return@mapNotNull null
            pieces[0].trim().lowercase() to pieces[1].trim()
        }.toMap()

        val upload = fields["upload"]?.toLongOrNull()
        val download = fields["download"]?.toLongOrNull()
        val total = fields["total"]?.toLongOrNull()
        val expire = fields["expire"]?.toLongOrNull()

        val used = if (upload != null || download != null) (upload ?: 0L) + (download ?: 0L) else null
        return Triple(used, total, expire)
    }

    fun isAutoRemarks(remarks: String, url: String): Boolean {
        if (remarks.isBlank()) return true
        if (remarks == "import sub" || remarks == "Default") return true
        val fragment = runCatching { URI(Utils.fixIllegalUrl(url)).fragment }.getOrNull()
        return fragment != null && remarks == fragment
    }

    fun resolveTitle(body: String, headers: Map<String, String>): String? {
        parseFromHeaders(headers)?.takeIf { it.isNotBlank() }?.let { return it }
        return parseFromBody(body)?.takeIf { it.isNotBlank() }
    }

    fun parseFromHeaders(headers: Map<String, String>): String? {
        val raw = headers.entries.firstOrNull { (key, _) -> PROFILE_TITLE_HEADER.matches(key) }?.value
            ?: return null
        return decodeTitleValue(raw)
    }

    fun parseFromBody(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val line = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#profile-title:", ignoreCase = true) }
            ?: return null
        return decodeTitleValue(line.substringAfter(":").trim())
    }

    fun decodeTitleValue(raw: String): String {
        var value = raw.trim().trim('"')
        if (value.equals("NULL", ignoreCase = true)) return ""
        if (value.startsWith("base64:", ignoreCase = true)) {
            val encoded = value.substringAfter(":").trim()
            return decodeBase64Utf8(encoded)
        }
        if (looksLikeBase64(value)) {
            decodeBase64Utf8(value).takeIf { it.isNotBlank() }?.let { return it }
        }
        return value
    }

    private fun decodeBase64Utf8(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun looksLikeBase64(value: String): Boolean {
        if (value.length < 4 || value.length % 4 != 0) return false
        return value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }
}
