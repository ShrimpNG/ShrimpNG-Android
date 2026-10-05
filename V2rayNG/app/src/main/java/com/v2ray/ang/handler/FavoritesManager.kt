package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem

/**
 * Remembers which servers the user starred.
 *
 * Deliberately **not** keyed by profile GUID. Updating a subscription wipes its profiles
 * ([MmkvManager.removeServerViaSubid]) and recreates them with fresh GUIDs, so a GUID-keyed
 * favourite would silently vanish on every refresh. The subscription id plus the provider's own
 * name for the server survives that, and the name is what the user recognises anyway.
 *
 * The trade-off: two servers sharing one name inside a subscription are starred together. That is
 * rare, and the alternative — matching on address:port — breaks for balancer entries, which have
 * no single address.
 */
object FavoritesManager {

    /** Same shape for locally-added profiles, which simply have an empty subscription id. */
    fun keyOf(profile: ProfileItem): String = "${profile.subscriptionId}|${profile.remarks.trim()}"

    fun all(): Set<String> =
        MmkvManager.decodeSettingsStringSet(AppConfig.PREF_FAVORITE_SERVERS).orEmpty()

    fun isFavorite(profile: ProfileItem): Boolean = keyOf(profile) in all()

    /** @return the state after toggling. */
    fun toggle(profile: ProfileItem): Boolean {
        val key = keyOf(profile)
        val current = all().toMutableSet()
        val nowFavorite = if (key in current) {
            current.remove(key); false
        } else {
            current.add(key); true
        }
        MmkvManager.encodeSettings(AppConfig.PREF_FAVORITE_SERVERS, current)
        return nowFavorite
    }
}
