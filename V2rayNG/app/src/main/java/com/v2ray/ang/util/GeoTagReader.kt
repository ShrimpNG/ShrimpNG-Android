package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.AppConfig
import java.io.File

/**
 * Reads the top-level tag names (`country_code` in Xray's GeoSiteList/GeoIPList proto schema)
 * out of the geosite.dat/geoip.dat asset files, for autocomplete suggestions when a user types
 * `geosite:`/`geoip:` in a routing rule. Both files share the same outer shape — a repeated
 * length-delimited entry, each starting with a field-1 string tag — so domain lists/CIDR lists
 * that follow the tag are skipped by their encoded length rather than parsed, which keeps this
 * fast even on the multi-megabyte official geoip.dat/geosite.dat.
 */
object GeoTagReader {

    private data class CacheEntry(val lastModified: Long, val tags: List<String>)

    private val cache = HashMap<String, CacheEntry>()

    fun readGeositeTags(context: Context): List<String> = readTags(context, AppConfig.GEOSITE_DAT)

    fun readGeoipTags(context: Context): List<String> = readTags(context, AppConfig.GEOIP_DAT)

    private fun readTags(context: Context, fileName: String): List<String> {
        val file = File(Utils.userAssetPath(context), fileName)
        if (!file.exists()) return emptyList()

        val cached = cache[file.absolutePath]
        if (cached != null && cached.lastModified == file.lastModified()) {
            return cached.tags
        }

        val tags = try {
            parseTopLevelTags(file.readBytes())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse geo tags from $fileName", e)
            emptyList()
        }
        cache[file.absolutePath] = CacheEntry(file.lastModified(), tags)
        return tags
    }

    private fun parseTopLevelTags(data: ByteArray): List<String> {
        val tags = mutableListOf<String>()
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos)
            pos = afterTag
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (fieldNum == 1 && wireType == 2) {
                val (length, afterLength) = readVarint(data, pos)
                val entryStart = afterLength
                val entryEnd = entryStart + length.toInt()
                extractCountryCode(data, entryStart, entryEnd)?.let { tags.add(it) }
                pos = entryEnd
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return tags.distinct()
    }

    /** Reads just the first field-1 string (country_code) out of one GeoSite/GeoIP submessage. */
    private fun extractCountryCode(data: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos < end) {
            val (tag, afterTag) = readVarint(data, pos)
            pos = afterTag
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (fieldNum == 1 && wireType == 2) {
                val (length, afterLength) = readVarint(data, pos)
                return String(data, afterLength, length.toInt(), Charsets.UTF_8)
            }
            pos = skipField(data, pos, wireType)
        }
        return null
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (true) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    private fun skipField(data: ByteArray, start: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> readVarint(data, start).second
            1 -> start + 8
            2 -> {
                val (length, afterLength) = readVarint(data, start)
                afterLength + length.toInt()
            }
            5 -> start + 4
            else -> throw IllegalStateException("Unknown protobuf wire type $wireType")
        }
    }
}
