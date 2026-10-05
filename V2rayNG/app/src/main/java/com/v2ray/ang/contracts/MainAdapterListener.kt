package com.v2ray.ang.contracts

import com.v2ray.ang.dto.entities.ProfileItem

interface MainAdapterListener : BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    /** Put the server's full config, as the core would receive it, on the clipboard. */
    fun onCopyJson(guid: String) {}

    /** Put the server's share link (vless://…, and the like) on the clipboard. */
    fun onCopyLink(guid: String) {}

    /**
     * Starring reorders the list, so the host has to rebuild it rather than the adapter redrawing
     * one row. Defaulted to a no-op so other implementers are unaffected.
     */
    fun onToggleFavorite(profile: ProfileItem) {}

    /** Measure this one server. Defaulted to a no-op so other implementers are unaffected. */
    fun onPingServer(guid: String) {}

}