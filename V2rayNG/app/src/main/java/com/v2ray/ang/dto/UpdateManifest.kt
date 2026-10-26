package com.v2ray.ang.dto

/**
 * `update.json` / `update-beta.json` next to the APKs on ng.shrimp.fish, written by the release
 * script (local/publish-release.py). One entry in [builds] per APK we publish — the client only
 * ever takes its own: see AppUpdateManager.buildId.
 */
data class UpdateManifest(
    val versionName: String = "",
    /** The version code without the ABI prefix the universal APKs carry (131, not 4000131). */
    val versionCode: Long = 0,
    val date: String = "",
    /** A security or breakage fix: the client reminds more insistently. */
    val critical: Boolean = false,
    val builds: Map<String, Build> = emptyMap(),
    /** Bullet lines per language ("ru", "en"). */
    val notes: Map<String, List<String>> = emptyMap(),
    /** Earlier releases, newest first, for the changelog on the Updates screen. */
    val history: List<Release> = emptyList(),
) {
    data class Build(
        val url: String = "",
        val sha256: String = "",
        val size: Long = 0,
        val minSdk: Int = 0,
    )

    data class Release(
        val versionName: String = "",
        val versionCode: Long = 0,
        val date: String = "",
        val notes: Map<String, List<String>> = emptyMap(),
    )
}
