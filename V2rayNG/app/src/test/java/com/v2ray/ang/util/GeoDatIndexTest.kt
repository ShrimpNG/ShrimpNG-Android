package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class GeoDatIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ByteArrayOutputStream.varint(value: Int) {
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    /** A GeoSiteList with one entry per code, each carrying [bodyPadding] bytes of "domains". */
    private fun geoList(vararg codes: String, bodyPadding: Int = 300): ByteArray {
        val out = ByteArrayOutputStream()
        for (code in codes) {
            val entry = ByteArrayOutputStream()
            entry.write(0x0A)
            entry.varint(code.length)
            entry.write(code.toByteArray())
            entry.write(0x12)
            entry.varint(bodyPadding)
            entry.write(ByteArray(bodyPadding) { 'a'.code.toByte() })
            out.write(0x0A)
            out.varint(entry.size())
            out.write(entry.toByteArray())
        }
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun readsCodesOfWellFormedList() {
        val codes = GeoDatIndex.codes(file(geoList("CN", "CATEGORY-RU", "TBANK-RU")), cached = false)
        assertEquals(setOf("CN", "CATEGORY-RU", "TBANK-RU"), codes)
    }

    @Test
    fun keepsCaseAsStored() {
        // The core compares against the upper-cased rule code, so a lower-case entry is not a match.
        assertEquals(setOf("private"), GeoDatIndex.codes(file(geoList("private")), cached = false))
    }

    @Test
    fun rejectsTruncatedFile() {
        val bytes = geoList("CN", "RU")
        assertNull(GeoDatIndex.codes(file(bytes.copyOf(bytes.size - 10)), cached = false))
    }

    @Test
    fun rejectsHtmlAndEmpty() {
        assertNull(GeoDatIndex.codes(file("<!DOCTYPE html><html>blocked</html>".toByteArray()), cached = false))
        assertNull(GeoDatIndex.codes(file(ByteArray(0)), cached = false))
        assertNull(GeoDatIndex.codes(File(tmp.root, "missing.dat"), cached = false))
    }

    @Test
    fun rejectsTrailingGarbage() {
        assertNull(GeoDatIndex.codes(file(geoList("CN") + byteArrayOf(0x0A, 0x7F)), cached = false))
    }

    @Test
    fun cacheFollowsFileChanges() {
        val f = file(geoList("CN"))
        assertEquals(setOf("CN"), GeoDatIndex.codes(f))
        f.writeBytes(geoList("CN", "RU", bodyPadding = 10))
        f.setLastModified(f.lastModified() + 5_000)
        assertEquals(setOf("CN", "RU"), GeoDatIndex.codes(f))
    }
}
