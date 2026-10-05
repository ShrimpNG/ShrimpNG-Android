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
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.motion.MotionUtils
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

    private val morphValue = FloatValueHolder(0f)
    private val morphAnimator = SpringAnimation(morphValue).apply {
        spring = ExpressiveMotion.effectsSpring(context)
        minimumVisibleChange = 0.001f
        addUpdateListener { _, value, _ -> morphProgress = value.coerceIn(0f, 1f) }
    }
    private var spinAnimator: ValueAnimator? = null
    private val iconView = ImageView(context).apply {
        val iconSize = resources.getDimensionPixelSize(R.dimen.connection_button_icon_size)
        layoutParams = LayoutParams(iconSize, iconSize, Gravity.CENTER)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val pressMotion = ExpressiveScale(this)
    private val iconMotion = ExpressiveScale(iconView)

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
        iconView.animate().cancel()
        iconMotion.cancel()
        if (previous == 0 || !ValueAnimator.areAnimatorsEnabled() || !isShown) {
            iconMotion.animateTo(1f)
            iconView.setImageResource(resId)
            return
        }
        iconView.animate().scaleX(0f).scaleY(0f).setDuration(
            MotionUtils.resolveThemeDuration(context, com.google.android.material.R.attr.motionDurationShort2, 100).toLong()
        )
            .setInterpolator(MotionUtils.resolveThemeInterpolator(
                context, com.google.android.material.R.attr.motionEasingEmphasizedAccelerateInterpolator,
                AccelerateInterpolator(),
            ))
            .withEndAction {
                iconView.setImageResource(resId)
                iconMotion.animateTo(1f)
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
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isShown || !isAttachedToWindow) {
            morphAnimator.cancel()
            morphValue.value = target
            morphProgress = target
        } else {
            morphAnimator.animateToFinalPosition(target)
        }
    }

    private var isSpinning = false

    /** Slow, continuous rotation of the shape only (not the icon) — a subtle "still working"
     *  cue while connected. Eases back to 0° instead of snapping when turned off. */
    fun setSpinning(spinning: Boolean) {
        isSpinning = spinning
        updateSpin()
    }

    private fun updateSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
        if (!ValueAnimator.areAnimatorsEnabled() || !isShown || !isAttachedToWindow) {
            shapeRotationDegrees = 0f
            return
        }
        if (isSpinning) {
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
                duration = ExpressiveMotion.duration(context)
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
            MotionEvent.ACTION_DOWN -> if (isEnabled) pressMotion.animateTo(0.94f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                pressMotion.animateTo(1f)
        }
        return super.onTouchEvent(event)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // A hidden Home tab should not keep scheduling frames for decorative rotation.
        updateSpin()
    }

    override fun onDetachedFromWindow() {
        morphAnimator.cancel()
        spinAnimator?.cancel()
        spinAnimator = null
        iconView.animate().cancel()
        if (currentIconRes != 0) iconView.setImageResource(currentIconRes)
        super.onDetachedFromWindow()
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
