package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil

/**
 * Decides whether to go looking for a uTLS fingerprint that connects, and which to try next.
 *
 * The search is deliberately dull and bounded. A fingerprint can only be changed by rebuilding the
 * config and restarting the core, so every attempt costs a reconnect; the point is to spend those
 * once, quietly, and never again for that server.
 *
 * State lives in memory rather than on disk on purpose. It records what has already been tried
 * *during this run*, which is exactly the scope that matters: the useful outcome — the fingerprint
 * that worked — is persisted by [FingerprintManager] and simply gets used on the next start.
 *
 * All of the decisions live here, apart from the core, so the awkward parts (does it stop? does it
 * loop?) can be reasoned about without a running tunnel.
 */
object FingerprintProbe {

    private data class Search(
        /** What was in effect before the search, to put back if nothing works. */
        val original: String?,
        val tried: MutableSet<String> = mutableSetOf(),
    )

    private val searches = mutableMapOf<String, Search>()

    /** Servers this run has already finished with, successfully or not. Never revisited. */
    private val finished = mutableSetOf<String>()

    fun isSearching(profile: ProfileItem): Boolean =
        FingerprintManager.keyOf(profile) in searches

    /**
     * Whether a failed connection to this server is worth answering with a fingerprint search.
     *
     * Only for a profile that actually pins one: without a fingerprint in the config there is
     * nothing being matched and nothing to change, and reconnecting seven times would just delay
     * an honest error for a server that is simply down.
     *
     * @param builtInFingerprint whatever the config itself pins — from
     *   [com.v2ray.ang.core.CoreRawFingerprintPatcher.currentFingerprint] for a raw config, or
     *   [ProfileItem.fingerPrint] for a typed one. Not the *effective* value: an override already
     *   in place is exactly what a resumed search needs to see as "already tried", not treated as
     *   absent.
     */
    fun appliesTo(profile: ProfileItem, builtInFingerprint: String?): Boolean {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_FINGERPRINT_AUTO_FALLBACK, false)) return false
        if (FingerprintManager.keyOf(profile) in finished) return false
        return builtInFingerprint != null
    }

    /**
     * Record that the current fingerprint did not work and choose the next to try.
     *
     * @param builtInFingerprint see [appliesTo]. Seeds the search the first time it is asked for a
     *   server: without it, a server whose config already pins "chrome" — the first candidate in
     *   the list — got that exact value handed straight back as the "next" thing to try on its
     *   very first attempt, changing nothing and wasting a reconnect on a value already known to
     *   fail. An override chosen by hand seeds it the same way once one exists, so a search resumed
     *   after that never revisits it either.
     * @return the next candidate, or null when they have all been tried — in which case whatever
     *   was in effect before the search has already been restored, so the server is left exactly
     *   as it was found rather than stuck on the last thing that failed.
     */
    fun nextCandidate(profile: ProfileItem, builtInFingerprint: String?): String? {
        val key = FingerprintManager.keyOf(profile)
        if (key in finished) return null

        val search = searches.getOrPut(key) {
            val original = FingerprintManager.overrideFor(profile)
            Search(original = original).also { (original ?: builtInFingerprint)?.let(it.tried::add) }
        }

        val next = FingerprintManager.CANDIDATES.firstOrNull { it !in search.tried }
        if (next == null) {
            LogUtil.w(AppConfig.TAG, "Fingerprint search: nothing worked for '${profile.remarks}', restoring")
            FingerprintManager.setOverride(profile, search.original)
            searches.remove(key)
            finished.add(key)
            return null
        }
        search.tried.add(next)
        LogUtil.i(AppConfig.TAG, "Fingerprint search: trying '$next' for '${profile.remarks}'")
        return next
    }

    /** The current fingerprint works; stop looking at this server for the rest of the run. */
    fun succeeded(profile: ProfileItem) {
        val key = FingerprintManager.keyOf(profile)
        if (searches.remove(key) != null) {
            LogUtil.i(
                AppConfig.TAG,
                "Fingerprint search: settled on '${FingerprintManager.overrideFor(profile)}' for '${profile.remarks}'"
            )
        }
        finished.add(key)
    }
}
