package com.v2ray.ang.handler

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.util.GeoDatIndex
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * Keeps a config the core is about to load from failing on its geo files.
 *
 * The core checks every `geosite:`/`geoip:`/`ext:` code in the routing and DNS sections against
 * the files on disk and refuses the entire config when one is missing — a single stale tag takes
 * down every profile at once. That happens whenever the file on disk and the rules drift apart:
 * the copy bundled with the APK predates tags the routing scenarios use, a file is replaced by
 * something that is not a geo list, or a user's source simply lacks a category.
 *
 * So before a config goes to the core: a built-in geo file that is missing or unreadable is put
 * back from the copy in the APK, and any rule entry naming a code its file does not have is
 * dropped (the whole rule, when that leaves it with nothing to match on) — the connection comes up
 * without that rule instead of not at all. Either kind of repair also asks for fresh files at once,
 * which brings the dropped rules back on the next start.
 */
object GeoRuleGuard {

    /** A geo code one config entry names: the file it is looked up in and the code itself. */
    data class GeoRef(val file: String, val code: String)

    private val domainKeys = listOf("domain", "domains")
    private val ipKeys = listOf("ip", "source", "sourceIP", "expectIPs", "expectedIPs", "unexpectedIPs")

    /** [result] with its config made loadable; returned unchanged on any failure of its own. */
    fun apply(context: Context, result: ConfigResult): ConfigResult {
        if (!result.status || result.content.isBlank()) return result
        return try {
            val json = JsonUtil.parseString(result.content) ?: return result
            val assetDir = File(Utils.userAssetPath(context))
            if (assetDir.path.isEmpty()) return result

            val refs = collectRefs(json)
            if (refs.isEmpty()) return result
            GeoAssetUpdater.recordCodesInUse(refs)

            val repaired = refs.map { it.file }.distinct()
                .filter { GeoAssetUpdater.isBuiltInGeoFile(it) && GeoDatIndex.codes(File(assetDir, it)) == null }
                .filter { GeoAssetUpdater.restoreBundled(context, it) }

            val available = HashMap<String, Set<String>>()
            fun has(ref: GeoRef) = available.getOrPut(ref.file) {
                GeoDatIndex.codes(File(assetDir, ref.file)).orEmpty()
            }.contains(ref.code)

            val dropped = linkedSetOf<GeoRef>()
            val changed = removeUnavailable(json) { ref -> has(ref).also { if (!it) dropped.add(ref) } }

            if (repaired.isNotEmpty()) {
                LogUtil.w(AppConfig.TAG, "GeoRuleGuard: restored $repaired from the APK")
            }
            if (dropped.isNotEmpty()) {
                LogUtil.w(
                    AppConfig.TAG,
                    "GeoRuleGuard: skipping rules for codes missing from the geo files: " +
                            dropped.joinToString { "${it.file}:${it.code}" }
                )
            }
            if (repaired.isNotEmpty() || dropped.isNotEmpty()) {
                GeoAssetUpdater.requestRepair(context)
            }

            if (!changed) return result
            val content = JsonUtil.toJsonPretty(json) ?: return result
            result.copy(content = content)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GeoRuleGuard: check failed, config left as built", e)
            result
        }
    }

    /** The geo file and code [entry] refers to in a domain list, or null for a plain domain. */
    fun domainRef(entry: String): GeoRef? {
        val value = when {
            entry.startsWith("geosite:") -> AppConfig.GEOSITE_DAT + ":" + entry.removePrefix("geosite:")
            else -> listOf("ext:", "ext-domain:", "ext-site:").firstOrNull { entry.startsWith(it) }
                ?.let { entry.removePrefix(it) }
                ?: return null
        }
        val file = value.substringBefore(':', "")
        val code = value.substringAfter(':', "").substringBefore('@')
        if (file.isEmpty() || code.isEmpty()) return null
        return GeoRef(file, code.uppercase())
    }

    /** The geo file and code [entry] refers to in an IP list, or null for a plain address. */
    fun ipRef(entry: String): GeoRef? {
        val rule = entry.removePrefix("!")
        val value = when {
            rule.startsWith("geoip:") -> AppConfig.GEOIP_DAT + ":" + rule.removePrefix("geoip:")
            else -> listOf("ext:", "ext-ip:").firstOrNull { rule.startsWith(it) }
                ?.let { rule.removePrefix(it) }
                ?: return null
        }
        val file = value.substringBefore(':', "")
        val code = value.substringAfter(':', "").removePrefix("!")
        if (file.isEmpty() || code.isEmpty()) return null
        return GeoRef(file, code.uppercase())
    }

    /** Every geo code the routing rules and DNS servers of [json] name. */
    fun collectRefs(json: JsonObject): List<GeoRef> {
        val refs = mutableListOf<GeoRef>()
        forEachMatcher(json) { obj ->
            domainKeys.forEach { key -> obj.strings(key).mapNotNullTo(refs, ::domainRef) }
            ipKeys.forEach { key -> obj.strings(key).mapNotNullTo(refs, ::ipRef) }
        }
        return refs
    }

    /**
     * Drops the entries of [json] whose code [isAvailable] rejects. A routing rule or DNS server
     * whose list that empties goes as a whole: with the list gone it would match far more than it
     * was written to — a block rule left with no domains, a regional DNS server left answering
     * for everything. Returns whether anything was removed.
     */
    fun removeUnavailable(json: JsonObject, isAvailable: (GeoRef) -> Boolean): Boolean {
        var changed = false
        fun prune(obj: JsonObject): Boolean {
            var emptied = false
            for (key in domainKeys + ipKeys) {
                val list = obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val kept = JsonArray()
                list.forEach { item ->
                    val text = item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    val ref = text?.let { if (key in domainKeys) domainRef(it) else ipRef(it) }
                    if (ref == null || isAvailable(ref)) kept.add(item)
                }
                if (kept.size() != list.size()) {
                    changed = true
                    obj.add(key, kept)
                    if (kept.size() == 0) emptied = true
                }
            }
            return emptied
        }

        json.getAsJsonObjectOrNull("routing")?.getAsJsonArrayOrNull("rules")?.let { rules ->
            json.getAsJsonObject("routing").add("rules", rules.filterObjects { !prune(it) })
        }
        json.getAsJsonObjectOrNull("dns")?.getAsJsonArrayOrNull("servers")?.let { servers ->
            json.getAsJsonObject("dns").add("servers", servers.filterObjects { !prune(it) })
        }
        return changed
    }

    private fun forEachMatcher(json: JsonObject, action: (JsonObject) -> Unit) {
        json.getAsJsonObjectOrNull("routing")?.getAsJsonArrayOrNull("rules")
            ?.forEach { if (it.isJsonObject) action(it.asJsonObject) }
        json.getAsJsonObjectOrNull("dns")?.getAsJsonArrayOrNull("servers")
            ?.forEach { if (it.isJsonObject) action(it.asJsonObject) }
    }

    /** Non-object entries (a DNS server given as a plain address) are kept as they are. */
    private fun JsonArray.filterObjects(keep: (JsonObject) -> Boolean): JsonArray {
        val out = JsonArray()
        forEach { if (!it.isJsonObject || keep(it.asJsonObject)) out.add(it) }
        return out
    }

    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
            .orEmpty()

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
