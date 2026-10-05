package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * A fast scroller laid over a [RecyclerView]: a pill at the right edge that shows while the list
 * moves and can be grabbed and dragged — through a touch target far wider than the pill, which is
 * what RecyclerView's own fast scroller lacks — plus a bubble beside it with the label (here, the
 * first letter) of the row the list is at, ticking as it changes.
 *
 * Match the list's bounds; touches that don't start on the thumb fall through to the list.
 */
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val thumbHeight = dp(52f)
    private val thumbWidthIdle = dp(6f)
    private val thumbWidthDragging = dp(10f)
    private val thumbMarginEnd = dp(5f)
    /** How far from the right edge a press can start a drag: the whole strip beside the tiles. */
    private val touchWidth = dp(40f)
    private val bubbleSize = dp(64f)
    private val bubbleGap = dp(16f)

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(28f)
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var recycler: RecyclerView? = null
    private var labelAt: (Int) -> String? = { null }

    private var thumbTop = 0f
    private var dragging = false
    private var label: String? = null
    /** 0 hidden … 1 shown, for the thumb and (while dragging) the bubble. */
    private var thumbAlpha = 0f
    private var bubbleScale = 0f
    private var thumbAnimator: ValueAnimator? = null
    private var bubbleAnimator: ValueAnimator? = null
    private val hide = Runnable { animateThumb(0f) }

    fun attach(recyclerView: RecyclerView, labelAt: (Int) -> String?) {
        recycler = recyclerView
        this.labelAt = labelAt
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // While dragging the thumb follows the finger, not the list it is moving.
                if (dy == 0 || dragging) return
                syncThumbToList()
                if (!dragging && scrollable()) {
                    animateThumb(1f)
                    scheduleHide()
                }
            }
        })
    }

    private fun scrollable(): Boolean {
        val rv = recycler ?: return false
        return rv.computeVerticalScrollRange() > rv.computeVerticalScrollExtent()
    }

    private fun syncThumbToList() {
        val rv = recycler ?: return
        val range = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent()
        if (range <= 0) return
        val fraction = rv.computeVerticalScrollOffset().toFloat() / range
        thumbTop = fraction.coerceIn(0f, 1f) * (height - thumbHeight)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scrollable() || event.x < width - touchWidth) return false
                // Anywhere along the strip while the thumb is out; only on it when it's hidden,
                // so a tap near the edge of a row still reaches the row.
                val onThumb = event.y in (thumbTop - dp(16f))..(thumbTop + thumbHeight + dp(16f))
                if (thumbAlpha < 0.5f && !onThumb) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hide)
                animateThumb(1f)
                animateBubble(1f)
                ViewCompat.performHapticFeedback(this, HapticFeedbackConstantsCompat.GESTURE_START)
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateBubble(0f)
                scheduleHide()
                invalidate()
                return true
            }
        }
        return dragging
    }

    /** The finger's height on the strip picks the row: the list's whole length maps onto it. */
    private fun dragTo(y: Float) {
        val rv = recycler ?: return
        val count = rv.adapter?.itemCount ?: return
        if (count == 0) return
        val fraction = ((y - thumbHeight / 2) / (height - thumbHeight)).coerceIn(0f, 1f)
        thumbTop = fraction * (height - thumbHeight)
        val position = (fraction * (count - 1)).toInt()
        (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        val next = labelAt(position)
        if (next != null && next != label) {
            if (label != null) {
                performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK
                )
            }
            label = next
        }
        invalidate()
    }

    private fun scheduleHide() {
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    private fun animateThumb(target: Float) {
        thumbAnimator?.cancel()
        thumbAnimator = ValueAnimator.ofFloat(thumbAlpha, target).apply {
            duration = 200
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateBubble(target: Float) {
        bubbleAnimator?.cancel()
        bubbleAnimator = ValueAnimator.ofFloat(bubbleScale, target).apply {
            duration = 180
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { bubbleScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        syncThumbToList()
    }

    override fun onDraw(canvas: Canvas) {
        if (thumbAlpha <= 0f) return
        // Read at draw time, from the theme: Dynamic Color only exists as theme attributes.
        val idle = MaterialColors.getColor(this, MaterialR.attr.colorOutline)
        val active = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val thumbWidth = if (dragging) thumbWidthDragging else thumbWidthIdle
        val right = width - thumbMarginEnd
        thumbPaint.color = if (dragging) active else idle
        thumbPaint.alpha = (255 * thumbAlpha).toInt()
        rect.set(right - thumbWidth, thumbTop, right, thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, thumbWidth / 2, thumbWidth / 2, thumbPaint)

        val text = label ?: return
        if (bubbleScale <= 0f) return
        // A circle with one square corner pointing at the thumb, as in the Contacts app.
        val cx = right - thumbWidthDragging - bubbleGap - bubbleSize / 2
        val cy = (thumbTop + thumbHeight / 2).coerceIn(bubbleSize / 2, height - bubbleSize / 2)
        canvas.save()
        canvas.scale(bubbleScale, bubbleScale, cx + bubbleSize / 2, cy)
        bubblePaint.color = MaterialColors.getColor(this, MaterialR.attr.colorPrimaryContainer)
        rect.set(cx - bubbleSize / 2, cy - bubbleSize / 2, cx + bubbleSize / 2, cy + bubbleSize / 2)
        canvas.drawRoundRect(rect, bubbleSize / 2, bubbleSize / 2, bubblePaint)
        canvas.drawRect(cx, cy, cx + bubbleSize / 2, cy + bubbleSize / 2, bubblePaint)
        textPaint.color = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimaryContainer)
        canvas.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        canvas.restore()
    }
}
