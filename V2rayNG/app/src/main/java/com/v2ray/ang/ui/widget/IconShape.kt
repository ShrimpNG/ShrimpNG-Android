package com.v2ray.ang.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * App icons in the shape Huawei's launcher gives them: a rounded square.
 *
 * Icons are masked by the system's icon shape, and on EMUI that is a plain square — its themed
 * icons even come as ready-made square tiles inside a transparent margin. Every icon in the app
 * read as a hard-edged tile. Those are redrawn here: an adaptive icon's layers inside a rounded
 * square, a square tile cropped out of its margin and rounded. An icon that already has a shape
 * of its own — a Pixel's circle, a squircle, a legacy round icon — is returned as it was.
 */
object IconShape {
    private const val CORNER_FRACTION = 0.22f

    /** [sizeDp]: the largest size it is shown at, which the redrawn bitmap is made for. */
    fun shaped(context: Context, drawable: Drawable, sizeDp: Int): Drawable {
        val size = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val square = when {
            Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable ->
                if (hasSquareMask(drawable)) adaptiveLayers(drawable, size) else null
            else -> squareTile(drawable, size)
        } ?: return drawable
        return BitmapDrawable(context.resources, rounded(square))
    }

    fun shaped(context: Context, resId: Int, sizeDp: Int): Drawable? =
        ContextCompat.getDrawable(context, resId)?.let { shaped(context, it, sizeDp) }

    /** Whether the system's mask reaches into the corners, i.e. draws (nearly) a square. */
    private fun hasSquareMask(icon: AdaptiveIconDrawable): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        // The mask path is in a 100×100 space. A circle or squircle leaves the corner empty.
        val region = Region().apply { setPath(icon.iconMask, Region(0, 0, 100, 100)) }
        return region.contains(5, 5)
    }

    /** The two layers, unmasked; as in AdaptiveIconDrawable, they are 1.5× the visible icon. */
    private fun adaptiveLayers(icon: AdaptiveIconDrawable, size: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val inset = size / 4
        listOfNotNull(icon.background, icon.foreground).forEach { layer ->
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(canvas)
        }
        return bitmap
    }

    /**
     * A square tile out of its transparent margin, scaled to [size]; null when the icon isn't one
     * — its corners are see-through, so it has a shape of its own.
     */
    private fun squareTile(drawable: Drawable, size: Int): Bitmap? {
        val full = createBitmap(size, size)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(full))

        var left = size; var top = size; var right = -1; var bottom = -1
        val row = IntArray(size)
        for (y in 0 until size) {
            full.getPixels(row, 0, size, 0, y, size, 1)
            for (x in 0 until size) {
                if (Color.alpha(row[x]) > 24) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        val w = right - left + 1
        val h = bottom - top + 1
        if (w < size / 2 || h < size / 2 || kotlin.math.abs(w - h) > maxOf(w, h) / 10) return null
        // Just inside each corner of the tile: opaque there means square corners.
        val probe = (minOf(w, h) * 0.03f).roundToInt().coerceAtLeast(1)
        val corners = listOf(
            left + probe to top + probe, right - probe to top + probe,
            left + probe to bottom - probe, right - probe to bottom - probe,
        )
        if (corners.any { (x, y) -> Color.alpha(full.getPixel(x, y)) < 200 }) return null

        val tile = Bitmap.createBitmap(full, left, top, w, h)
        return Bitmap.createScaledBitmap(tile, size, size, true)
    }

    private fun rounded(source: Bitmap): Bitmap {
        val size = source.width
        val out = createBitmap(size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = size * CORNER_FRACTION
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return out
    }
}
