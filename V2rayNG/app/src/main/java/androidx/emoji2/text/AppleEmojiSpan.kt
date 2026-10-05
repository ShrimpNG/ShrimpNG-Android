package androidx.emoji2.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * An emoji drawn as an image (ShrimpNG's bundled Apple set) instead of as a glyph of the emoji
 * font. Lives in androidx.emoji2.text because [EmojiSpan]'s constructor is package-private: the
 * public way in is EmojiCompat.Config.setSpanFactory, which still wants an EmojiSpan back.
 *
 * Sizing is EmojiSpan's own — the font's line height, the metadata's aspect ratio — so lines lay
 * out exactly as they would with the emoji font. [image] returns null for a sequence the set has
 * no picture of; that one is drawn as text, i.e. by the system font.
 */
class AppleEmojiSpan(
    rasterizer: TypefaceEmojiRasterizer,
    private val image: (TypefaceEmojiRasterizer) -> Bitmap?,
) : EmojiSpan(rasterizer) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val bitmap = image(typefaceRasterizer)
        if (bitmap == null) {
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            return
        }
        val metrics = paint.fontMetrics
        val size = metrics.descent - metrics.ascent
        val left = x + (width - size) / 2f
        val imageTop = y + metrics.ascent
        bounds.set(left, imageTop, left + size, imageTop + size)
        // Follow the text's own alpha, so emoji in disabled or faded text fade with it.
        bitmapPaint.alpha = paint.alpha
        canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
    }
}

/**
 * Undoes emoji2's "preferred system render" marks. From Android 14 emoji2 asks the platform
 * (EmojiConsistency.getEmojiConsistencySet) which emoji it would rather draw itself — on current
 * systems nearly all of them — and skips those before the SpanFactory is ever asked, replaceAll
 * or not. The marks are set once, when the processor is built, on the repo's rasterizers; clearing
 * them afterwards lets every emoji reach [AppleEmojiSpan]. Same package for the same reason as the
 * span: getOrCreateEmojiRasterizer is package-private.
 */
object AppleEmojiExclusions {
    fun clear(repo: MetadataRepo) {
        val count = repo.metadataList.listLength()
        for (i in 0 until count) {
            repo.getOrCreateEmojiRasterizer(i).setExclusion(false)
        }
    }
}
