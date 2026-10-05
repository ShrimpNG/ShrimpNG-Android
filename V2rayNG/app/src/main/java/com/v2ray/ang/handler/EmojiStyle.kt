package com.v2ray.ang.handler

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.LruCache
import androidx.emoji2.text.AppleEmojiExclusions
import androidx.emoji2.text.AppleEmojiSpan
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import androidx.emoji2.text.TypefaceEmojiRasterizer
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Which emoji the app draws: the bundled Apple set ("Standard", the default — country flags in
 * server names are the point) or the system font's.
 *
 * Apple: EmojiCompat finds the emoji in every AppCompat text view (labels, chips, fields, toolbar
 * titles), using only the metadata table of the Noto compat font (assets/emoji/emoji_meta.bin,
 * 160 KB instead of the 10 MB font), and [AppleEmojiSpan] draws each one from
 * assets/emoji/apple/<codepoints>.webp. System: EmojiCompat is never set up, and text views
 * render emoji as they always did. Anything Android draws itself — notifications, the widget —
 * keeps system emoji either way.
 *
 * EmojiCompat can't be torn down once initialised, so a change applies from the next app start.
 */
object EmojiStyle {

    enum class Style(val prefValue: String) {
        APPLE("apple"),
        SYSTEM("system");

        companion object {
            fun from(value: String?): Style = entries.firstOrNull { it.prefValue == value } ?: APPLE
        }
    }

    private const val META_ASSET = "emoji/emoji_meta.bin"
    private const val INDEX_ASSET = "emoji/apple_index.txt"
    private const val IMAGE_DIR = "emoji/apple"

    fun current(): Style = Style.from(MmkvManager.decodeSettingsString(AppConfig.PREF_EMOJI_STYLE))

    fun set(style: Style) = MmkvManager.encodeSettings(AppConfig.PREF_EMOJI_STYLE, style.prefValue)

    /** The style this process was started with — what is on screen until the app restarts. */
    var active: Style = Style.SYSTEM
        private set

    private lateinit var appContext: Context

    /** Codepoint key ("1f1f3-1f1f1") → image file name; FE0F-less and non-qualified forms included. */
    private val index: Map<String, String> by lazy {
        runCatching {
            appContext.assets.open(INDEX_ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val space = line.indexOf(' ')
                    if (space > 0) line.substring(0, space) to line.substring(space + 1) else null
                }.toMap(HashMap(6000))
            }
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "EmojiStyle: index failed", it)
            emptyMap()
        }
    }

    // 64×64 ARGB is 16 KB each: ~4 MB for a screenful of distinct emoji, several times over.
    private val bitmaps = LruCache<String, Bitmap>(256)
    private val missing = HashSet<String>()

    fun install(application: Application) {
        appContext = application
        active = current()
        if (active != Style.APPLE) return

        val loader = EmojiCompat.MetadataRepoLoader { callback ->
            loaderRan = true
            try {
                // The typeface is never drawn with — AppleEmojiSpan draws images — but MetadataRepo
                // wants one; the default spares loading the 10 MB emoji font for nothing.
                // Whole into memory first. emoji2's stream reader takes the table in one read() and
                // fails on a short one ("Needed N bytes, got M") — which is what a compressed asset
                // gives back. That failure was why the first builds still showed system emoji.
                val bytes = application.assets.open(META_ASSET).use { it.readBytes() }
                val repo = MetadataRepo.create(Typeface.DEFAULT, ByteBuffer.wrap(bytes))
                metadataRepo = repo
                callback.onLoaded(repo)
                // Building the processor inside onLoaded marked most emoji "system render".
                clearExclusions()
            } catch (t: Throwable) {
                LogUtil.e(AppConfig.TAG, "EmojiStyle: metadata failed", t)
                loadError = t.message ?: t.javaClass.simpleName
                callback.onFailed(t)
            }
        }
        val config = object : EmojiCompat.Config(loader) {}
            // Without this, emoji2 1.7 is a no-op from Android 15 (API 35) on — it reports itself
            // loaded without ever calling the loader, and processes nothing. That, not the loader,
            // is why the first builds showed system emoji on current phones.
            .setUseAfterUpdatableSystemFonts(true)
            // Every emoji, not only those the system font lacks: the point is a consistent set.
            .setReplaceAll(true)
            .setSpanFactory { rasterizer -> AppleEmojiSpan(rasterizer, ::imageFor) }
            // Again once initialised, in case the processor was built after onLoaded returned;
            // views re-process their text on this callback anyway.
            .registerInitCallback(object : EmojiCompat.InitCallback() {
                override fun onInitialized() = clearExclusions()

                override fun onFailed(throwable: Throwable?) {
                    LogUtil.e(AppConfig.TAG, "EmojiStyle: EmojiCompat failed: ${throwable?.message}")
                    loadError = throwable?.message ?: throwable?.javaClass?.simpleName ?: "unknown"
                }
            })
        EmojiCompat.init(config)
        // Warm the index off the main thread; the first emoji drawn would otherwise read it.
        thread(name = "EmojiIndex") { index.size }
    }

    @Volatile
    private var metadataRepo: MetadataRepo? = null

    /** Whether EmojiCompat ever asked for the metadata; if not, it went no-op and draws nothing. */
    @Volatile
    var loaderRan = false
        private set

    /** Why the Apple set isn't on screen, if it failed to load; shown on Themes & icons. */
    @Volatile
    var loadError: String? = null
        private set

    private fun clearExclusions() {
        val repo = metadataRepo ?: return
        runCatching { AppleEmojiExclusions.clear(repo) }
            .onFailure { LogUtil.e(AppConfig.TAG, "EmojiStyle: clearing exclusions failed", it) }
    }

    private fun imageFor(rasterizer: TypefaceEmojiRasterizer): Bitmap? {
        val key = buildString {
            for (i in 0 until rasterizer.codepointsLength) {
                if (i > 0) append('-')
                // Not String.format: under an Arabic or Persian locale it emits non-ASCII digits.
                append(Integer.toHexString(rasterizer.getCodepointAt(i)).padStart(4, '0'))
            }
        }
        bitmaps.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }

        val file = index[key] ?: index[key.split('-').filter { it != "fe0f" }.joinToString("-")]
        val bitmap = file?.let {
            runCatching {
                appContext.assets.open("$IMAGE_DIR/$it.webp").use { s -> BitmapFactory.decodeStream(s) }
            }.getOrNull()
        }
        if (bitmap == null) {
            synchronized(missing) { missing += key }
        } else {
            bitmaps.put(key, bitmap)
        }
        return bitmap
    }
}
