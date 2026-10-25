package com.v2ray.ang.util

import java.io.File
import java.io.RandomAccessFile

/**
 * The codes a geosite/geoip .dat holds, read the way the core looks them up, and whether the file
 * is a well-formed list at all.
 *
 * Both files are a GeoSiteList/GeoIPList: a run of field-1 length-delimited entries, each opening
 * with its field-1 `country_code`. The core finds a code by walking those entries and comparing
 * the code bytes as stored (it upper-cases the code from the rule, not the file), and rejects the
 * whole config when one is missing — so this reads the same headers and nothing else. The entries'
 * bodies, megabytes of domains and CIDRs, are stepped over by their length.
 *
 * A file is well-formed when the entries tile it exactly: an HTML error page saved under the
 * name, or a download cut short, fails on the first header or on the last length.
 */
object GeoDatIndex {

    private data class Key(val path: String, val length: Long, val lastModified: Long)

    private val cache = HashMap<Key, Set<String>?>()

    /**
     * The codes in [file], or null when it is missing, empty or not a well-formed list.
     * Cached against the file's size and modification time; pass [cached] = false for a file that
     * is read once, such as a download about to be renamed into place.
     */
    fun codes(file: File, cached: Boolean = true): Set<String>? {
        if (!file.isFile) return null
        if (!cached) return read(file)
        val key = Key(file.absolutePath, file.length(), file.lastModified())
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val codes = read(file)
        synchronized(cache) {
            cache.keys.removeAll { it.path == key.path }
            cache[key] = codes
        }
        return codes
    }

    private fun read(file: File): Set<String>? = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (e: Exception) {
        null
    }

    private fun parse(raf: RandomAccessFile): Set<String>? {
        val size = raf.length()
        if (size == 0L) return null
        val codes = HashSet<String>()
        // Enough for an entry header and any code the core can match: it compares the code's
        // length against a single byte, so a code is never longer than 127.
        val header = ByteArray(160)
        var pos = 0L
        while (pos < size) {
            raf.seek(pos)
            val n = raf.read(header, 0, minOf(header.size.toLong(), size - pos).toInt())
            if (n <= 0) return null
            val reader = HeaderReader(header, n)

            if (reader.varint() != 0x0AL) return null
            val bodyLength = reader.varint() ?: return null
            val bodyStart = pos + reader.offset
            val bodyEnd = bodyStart + bodyLength
            if (bodyLength < 0 || bodyEnd > size) return null

            if (bodyLength > 0 && reader.varint() == 0x0AL) {
                val codeLength = reader.varint()
                if (codeLength != null && codeLength in 1..127 &&
                    reader.offset + codeLength <= n && bodyStart + bodyLength >= pos + reader.offset + codeLength
                ) {
                    codes.add(String(header, reader.offset, codeLength.toInt(), Charsets.UTF_8))
                }
            }
            pos = bodyEnd
        }
        return codes.takeIf { pos == size && it.isNotEmpty() }
    }

    private class HeaderReader(private val bytes: ByteArray, private val limit: Int) {
        var offset = 0

        /** A protobuf varint, or null when it runs past what was read or past 64 bits. */
        fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (offset < limit && shift < 64) {
                val b = bytes[offset++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
            return null
        }
    }
}
