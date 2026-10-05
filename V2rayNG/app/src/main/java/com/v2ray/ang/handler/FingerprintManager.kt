package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil

/**
 * Remembers a uTLS fingerprint to use for a server instead of the one its config carries.
 *
 * Why this exists at all: a fingerprint is what the TLS handshake looks like on the wire, and a
 * blocked one fails to connect while everything else about the server is fine. Providers ship
 * whatever they shipped, and waiting for them to change it is the only other option.
 *
 * Keyed by subscription plus the provider's name for the server, not by profile GUID or by
 * editing the stored config — both are destroyed and rebuilt on every subscription refresh, so a
 * choice recorded there would silently vanish the next time the subscription updated. Same
 * reasoning, and same key shape, as [FavoritesManager].
 */
object FingerprintManager {

    /**
     * What to try, in order, when searching for one that connects.
     *
     * Ordered by how ordinary the resulting handshake looks. "random"/"randomized" are last on
     * purpose: a randomised ClientHello is not a safe default, it is its own signature — useful
     * as a last resort, not as a starting point.
     */
    val CANDIDATES = listOf("chrome", "firefox", "safari", "edge", "ios", "android", "randomized")

    fun keyOf(profile: ProfileItem): String = "${profile.subscriptionId}|${profile.remarks.trim()}"

    fun all(): Map<String, String> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_FINGERPRINT_OVERRIDES)
        if (json.isNullOrBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return runCatching {
            JsonUtil.fromJson(json, Map::class.java) as? Map<String, String>
        }.getOrNull().orEmpty()
    }

    /** The fingerprint chosen for this server, or null to use whatever its config says. */
    fun overrideFor(profile: ProfileItem): String? =
        all()[keyOf(profile)]?.takeIf { it.isNotBlank() }

    /** Pass null or blank to go back to the config's own value. */
    fun setOverride(profile: ProfileItem, fingerprint: String?) {
        val current = all().toMutableMap()
        val key = keyOf(profile)
        if (fingerprint.isNullOrBlank()) current.remove(key) else current[key] = fingerprint
        MmkvManager.encodeSettings(AppConfig.PREF_FINGERPRINT_OVERRIDES, JsonUtil.toJson(current))
    }

    /**
     * The fingerprint a typed profile should actually be built with: the override if one was
     * chosen, otherwise the profile's own.
     */
    fun effectiveFingerprint(profile: ProfileItem): String? =
        overrideFor(profile) ?: profile.fingerPrint
}
