package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Rewrites the uTLS fingerprint inside a raw provider config.
 *
 * The typed path can just pass a different value when it builds the outbound. A CUSTOM profile has
 * no build step — its JSON is the provider's and is used nearly verbatim — so the value has to be
 * found and replaced where it sits, in every outbound that has one.
 *
 * The stored config is never touched: the patch is applied to the parsed copy on its way to the
 * core, so the provider's original survives, and dropping the override goes straight back to it.
 */
object CoreRawFingerprintPatcher {

    /** Both places Xray keeps it — plain TLS, and Reality. */
    private val SETTINGS_HOLDING_FINGERPRINT = listOf("tlsSettings", "realitySettings")

    /**
     * The fingerprint already pinned in this config, read from the first outbound that has one.
     *
     * Used to seed a fingerprint search with the value that is already in effect, so the search
     * never "tries" the exact thing that was just found to be failing — which is what happened
     * when the search only knew about a manually-chosen override and had nothing to go on for a
     * config's own built-in value: its first pick was always the first candidate in the list,
     * "chrome", and for a server whose own config already pinned chrome that pick changed nothing.
     */
    fun currentFingerprint(json: JsonObject): String? {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        outbounds.forEach { elem ->
            val stream = elem.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@forEach
            SETTINGS_HOLDING_FINGERPRINT.forEach { name ->
                val fp = stream.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("fingerprint")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf { it.isNotBlank() }
                if (fp != null) return fp
            }
        }
        return null
    }

    /**
     * @return how many outbounds were changed; zero means the config pins no fingerprint at all,
     *   in which case there was nothing to override.
     */
    fun apply(json: JsonObject, fingerprint: String): Int {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return 0
        var patched = 0

        outbounds.forEach { elem ->
            val stream = elem.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@forEach
            SETTINGS_HOLDING_FINGERPRINT.forEach { name ->
                val settings = stream.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@forEach
                // Only where one is already set. Adding a fingerprint to an outbound that never
                // had one would change how it looks on the wire for no reason the user asked for.
                if (settings.has("fingerprint")) {
                    settings.addProperty("fingerprint", fingerprint)
                    patched++
                }
            }
        }

        if (patched > 0) {
            LogUtil.d(AppConfig.TAG, "Raw config: fingerprint overridden to '$fingerprint' in $patched outbound(s)")
        }
        return patched
    }
}
