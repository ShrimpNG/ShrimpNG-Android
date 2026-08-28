package com.v2ray.ang.util

import android.util.LruCache

/**
 * Short-lived IP→domain map for enriching the connection journal.
 * Filled from sniffed/routing hostnames observed on the ProcessFinder path (preferred) and
 * reverse-DNS fallbacks.
 */
object DomainIpCache {

    private val cache = LruCache<String, String>(2048)

    fun put(ip: String, domain: String) {
        val cleanIp = normalizeIp(ip) ?: return
        val cleanDomain = normalizeDomain(domain) ?: return
        if (cleanDomain == cleanIp) return
        cache.put(cleanIp, cleanDomain)
    }

    fun get(ip: String): String? {
        val cleanIp = normalizeIp(ip) ?: return null
        return cache.get(cleanIp)
    }

    fun normalizeDomain(domain: String?): String? {
        val clean = domain?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (clean.isEmpty()) return null
        // Reject IP literals and obvious non-host tokens from the sniffer.
        if (clean == "localhost") return null
        if (clean.indexOf(':') >= 0) return null // IPv6
        if (clean.all { it.isDigit() || it == '.' }) return null // IPv4
        if (!clean.any { it.isLetter() }) return null
        return clean
    }

    private fun normalizeIp(ip: String): String? {
        val clean = ip.trim().removePrefix("/").substringBefore('%')
        return clean.takeIf { it.isNotEmpty() }
    }
}
