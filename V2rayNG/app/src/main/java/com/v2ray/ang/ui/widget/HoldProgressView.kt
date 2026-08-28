package com.v2ray.ang.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/**
 * Draws a stroke that grows around the host card's rounded rectangle as [progress] goes 0→1,
 * used as the "hold to edit" affordance on a server row. The two ends start together at
 * top-center and sweep in opposite directions, meeting at bottom-center exactly when the hold
 * completes, so the ring visibly closes rather than just ending somewhere arbitrary.
 *
 * Purely decorative: it never takes touches (the row below stays clickable) and draws nothing
 * at all while [progress] is 0.
 */
class HoldProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val fullPath = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val corner = RectF()
    private var pathLength = 0f

    /** Matches the host MaterialCardView's corner radius so the stroke traces its actual edge. */
    var cornerRadiusPx: Float = 16f * resources.displayMetrics.density
        set(value) {
            field = value
            rebuildPath()
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    fun setStrokeColor(@ColorInt color: Int) {
        paint.color = color
        invalidate()
    }

    init {
        // Decoration only — let every touch fall through to the row underneath.
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f || pathLength <= 0f) return

        val half = pathLength / 2f
        val swept = half * progress

        segment.reset()
        measure.getSegment(0f, swept, segment, true)
        canvas.drawPath(segment, paint)

        segment.reset()
        measure.getSegment(pathLength - swept, pathLength, segment, true)
        canvas.drawPath(segment, paint)
    }

    /**
     * Builds the rounded rect manually rather than via [Path.addRoundRect] so the path *starts*
     * at top-center — [PathMeasure] segments are taken from the path's own start point, and the
     * symmetric sweep only reads as symmetric if that point is the top middle.
     */
    private fun rebuildPath() {
        fullPath.reset()
        pathLength = 0f
        if (width <= 0 || height <= 0) return

        val inset = paint.strokeWidth / 2f
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        val centerX = width / 2f
        // Shrink the corner by the same inset as the edges, so the whole stroke stays parallel
        // to the card's own outline instead of cutting across its corners.
        val r = (cornerRadiusPx - inset)
            .coerceAtLeast(0f)
            .coerceAtMost(minOf(right - left, bottom - top) / 2f)

        fullPath.moveTo(centerX, top)
        fullPath.lineTo(right - r, top)
        corner.set(right - 2 * r, top, right, top + 2 * r)
        fullPath.arcTo(corner, -90f, 90f)

        fullPath.lineTo(right, bottom - r)
        corner.set(right - 2 * r, bottom - 2 * r, right, bottom)
        fullPath.arcTo(corner, 0f, 90f)

        fullPath.lineTo(left + r, bottom)
        corner.set(left, bottom - 2 * r, left + 2 * r, bottom)
        fullPath.arcTo(corner, 90f, 90f)

        fullPath.lineTo(left, top + r)
        corner.set(left, top, left + 2 * r, top + 2 * r)
        fullPath.arcTo(corner, 180f, 90f)

        fullPath.lineTo(centerX, top)

        measure.setPath(fullPath, false)
        pathLength = measure.length
    }
}
