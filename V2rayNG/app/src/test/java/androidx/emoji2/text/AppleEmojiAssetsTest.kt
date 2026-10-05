package androidx.emoji2.text

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled emoji assets fit together: emoji2's own reader accepts the metadata-only font
 * (assets/emoji/emoji_meta.bin), and the sequences it knows find their Apple image through
 * apple_index.txt the way EmojiStyle.imageFor looks them up.
 */
class AppleEmojiAssetsTest {

    private val assets = File("src/main/assets/emoji")

    private val index: Map<String, String> by lazy {
        File(assets, "apple_index.txt").readLines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
    }

    private fun lookup(key: String): String? =
        index[key] ?: index[key.split('-').filter { it != "fe0f" }.joinToString("-")]

    @Test
    fun metadataParsesAndMapsToImages() {
        val list = File(assets, "emoji_meta.bin").inputStream().use { MetadataListReader.read(it) }
        val count = list.listLength()
        assertTrue("metadata has $count items", count > 3000)

        val missing = mutableListOf<String>()
        for (i in 0 until count) {
            val item = list.list(i)
            val key = (0 until item.codepointsLength()).joinToString("-") {
                Integer.toHexString(item.codepoints(it)).padStart(4, '0')
            }
            val file = lookup(key)
            if (file == null || !File(assets, "apple/$file.webp").exists()) missing += key
        }
        // Lone regional indicators (half a flag) and the like have no picture of their own.
        val notIndicators = missing.filterNot { it.length == 5 && it.startsWith("1f1") }
        println("emoji metadata: $count items, ${missing.size} without an Apple image; besides lone indicators: ${notIndicators.take(40)}")
        assertTrue("too many without an image: ${notIndicators.size}", notIndicators.size < count / 20)

        for (key in listOf("1f1f7-1f1fa", "1f1f3-1f1f1", "1f60a", "1f680", "2b50")) {
            assertTrue("no image for $key", lookup(key) != null)
        }
    }

    @Test
    fun exclusionsClearOnEveryRasterizer() {
        val repo = File(assets, "emoji_meta.bin").inputStream().use { MetadataRepo.create(android.graphics.Typeface.DEFAULT, it as java.io.InputStream) }
        val count = repo.metadataList.listLength()
        // What emoji2 does on Android 14+ for emoji the platform wants to draw itself.
        for (i in 0 until count step 7) repo.getOrCreateEmojiRasterizer(i).setExclusion(true)

        AppleEmojiExclusions.clear(repo)

        val stillExcluded = (0 until count).count { repo.getOrCreateEmojiRasterizer(it).isPreferredSystemRender }
        assertTrue("$stillExcluded still marked for system render", stillExcluded == 0)
    }

    /**
     * Why EmojiStyle reads the asset into a ByteBuffer: emoji2's stream path takes the table in a
     * single read(), and a compressed APK asset hands it over in pieces.
     */
    @Test
    fun streamPathFailsOnShortReadsButBufferPathDoesNot() {
        val bytes = File(assets, "emoji_meta.bin").readBytes()
        val chunky = object : java.io.FilterInputStream(bytes.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 8192))
            override fun read(b: ByteArray) = read(b, 0, b.size)
        }
        val streamFailed = runCatching { MetadataListReader.read(chunky) }.isFailure
        assertTrue("expected the single-read stream path to fail on short reads", streamFailed)

        val list = MetadataListReader.read(java.nio.ByteBuffer.wrap(bytes))
        assertTrue(list.listLength() > 3000)
    }
}
