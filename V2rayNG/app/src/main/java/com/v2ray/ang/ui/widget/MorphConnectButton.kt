package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.google.android.material.shape.MaterialShapes
import com.v2ray.ang.R

/**
 * A button whose background morphs between a plain circle (disconnected) and a soft scalloped
 * "cookie" shape (connected) using [androidx.graphics.shapes.Morph] — the same shape-morphing
 * primitive Material 3 Expressive uses for its own FAB transitions. Views don't have a built-in
 * morphing FAB, so this draws the shape itself: [Morph.calculateMaxBounds] gives a stable
 * bounding box that doesn't jitter as [morphProgress] animates, which is scaled/centered into
 * the view's actual pixel size once per layout pass.
 */
class MorphConnectButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val morph = Morph(MaterialShapes.CIRCLE, MaterialShapes.COOKIE_9)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = Path()
    private val shapeMatrix = Matrix()
    private val maxBounds = morph.calculateMaxBounds()

    private var morphProgress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var shapeRotationDegrees = 0f
        set(value) {
            field = value
            invalidate()
        }
    private val rotatedShapeMatrix = Matrix()

    private var morphAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private val iconView = ImageView(context).apply {
        val iconSize = resources.getDimensionPixelSize(R.dimen.connection_button_icon_size)
        layoutParams = LayoutParams(iconSize, iconSize, Gravity.CENTER)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        addView(iconView)
    }

    private var currentIconRes = 0

    fun setIconResource(@DrawableRes resId: Int) {
        currentIconRes = resId
        iconView.setImageResource(resId)
    }

    /** Same as [setIconResource] but scale-swaps the glyph instead of jumping instantly. */
    fun setIconResourceAnimated(@DrawableRes resId: Int) {
        if (currentIconRes == resId) return
        val previous = currentIconRes
        currentIconRes = resId
        if (previous == 0) {
            iconView.setImageResource(resId)
            return
        }
        iconView.animate().scaleX(0f).scaleY(0f).setDuration(100L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                iconView.setImageResource(resId)
                iconView.animate().scaleX(1f).scaleY(1f).setDuration(200L)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }.start()
    }

    fun setIconTint(@ColorInt color: Int) {
        iconView.imageTintList = ColorStateList.valueOf(color)
    }

    fun setFillColor(@ColorInt color: Int) {
        shapePaint.color = color
        invalidate()
    }

    /** Animates the background shape between circle (false) and flower (true). */
    fun setMorphed(morphed: Boolean, animate: Boolean = true) {
        val target = if (morphed) 1f else 0f
        if (morphProgress == target && morphAnimator == null) return
        morphAnimator?.cancel()
        if (!animate) {
            morphProgress = target
            return
        }
        morphAnimator = ValueAnimator.ofFloat(morphProgress, target).apply {
            duration = 500L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { morphProgress = it.animatedValue as Float }
            start()
        }
    }

    private var isSpinning = false

    /** Slow, continuous rotation of the shape only (not the icon) — a subtle "still working"
     *  cue while connected. Eases back to 0° instead of snapping when turned off. */
    fun setSpinning(spinning: Boolean) {
        if (isSpinning == spinning) return
        isSpinning = spinning
        spinAnimator?.cancel()
        if (spinning) {
            spinAnimator = ValueAnimator.ofFloat(shapeRotationDegrees, shapeRotationDegrees + 360f).apply {
                duration = 12000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { shapeRotationDegrees = it.animatedValue as Float }
                start()
            }
        } else {
            val current = shapeRotationDegrees % 360f
            spinAnimator = ValueAnimator.ofFloat(current, 0f).apply {
                duration = 300L
                addUpdateListener { shapeRotationDegrees = it.animatedValue as Float }
                start()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val left = maxBounds[0]
        val top = maxBounds[1]
        val right = maxBounds[2]
        val bottom = maxBounds[3]
        val boundsWidth = right - left
        val boundsHeight = bottom - top
        val scale = minOf(w / boundsWidth, h / boundsHeight)
        shapeMatrix.reset()
        shapeMatrix.setScale(scale, scale)
        shapeMatrix.postTranslate(
            w / 2f - (left + right) / 2f * scale,
            h / 2f - (top + bottom) / 2f * scale,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.92f).scaleY(0.92f).setDuration(100L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        shapePath.rewind()
        morph.toPath(morphProgress, shapePath)
        rotatedShapeMatrix.set(shapeMatrix)
        if (shapeRotationDegrees != 0f) {
            rotatedShapeMatrix.postRotate(shapeRotationDegrees, width / 2f, height / 2f)
        }
        shapePath.transform(rotatedShapeMatrix)
        canvas.drawPath(shapePath, shapePaint)
        super.onDraw(canvas)
    }
}
