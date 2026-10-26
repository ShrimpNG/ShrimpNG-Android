package com.v2ray.ang.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manifest local/publish-release.py writes, read the way the client reads it. */
class UpdateManifestTest {

    private val json = """
        {
          "versionName": "0.3.2",
          "versionCode": 132,
          "date": "2026-10-27",
          "critical": true,
          "builds": {
            "standard": {"url": "https://ng.shrimp.fish/releases/ShrimpNG_0.3.2_universal.apk",
                         "sha256": "1497826754a5ce25f3d36ca8afa5a5b4e1b17426ea35044dbdb3ed17f9c6d7bb",
                         "size": 48558768, "minSdk": 31},
            "legacy": {"url": "https://ng.shrimp.fish/releases/ShrimpNG-Legacy_0.3.2_universal.apk",
                       "sha256": "8e6aa5f52de03b321d7326781571c56302e2cd27e88d35f504c5e41ecc345df4",
                       "size": 48660040, "minSdk": 26}
          },
          "notes": {"ru": ["Обновления в приложении"], "en": ["In-app updates"]},
          "history": [
            {"versionName": "0.3.1", "versionCode": 131, "date": "2026-10-26", "notes": {"ru": ["Ядро"], "en": ["Core"]}}
          ]
        }
    """.trimIndent()

    @Test
    fun readsWhatThePublishScriptWrites() {
        val m = Gson().fromJson(json, UpdateManifest::class.java)
        assertEquals("0.3.2", m.versionName)
        assertEquals(132L, m.versionCode)
        assertTrue(m.critical)
        assertEquals(26, m.builds.getValue("legacy").minSdk)
        assertEquals(48558768L, m.builds.getValue("standard").size)
        assertEquals(listOf("In-app updates"), m.notes["en"])
        assertEquals(131L, m.history.single().versionCode)
        assertEquals(listOf("Ядро"), m.history.single().notes["ru"])
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val m = Gson().fromJson("""{"versionName": "0.3.2", "versionCode": 132}""", UpdateManifest::class.java)
        assertFalse(m.critical)
        assertTrue(m.builds.isEmpty())
        assertTrue(m.history.isEmpty())
    }
}
