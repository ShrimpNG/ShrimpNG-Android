package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Compact Material 3 Expressive-style loading indicator.
 *
 * Android Views does not currently expose the expressive morphing indicator used by Material 3,
 * so this view draws the same visual language natively: a rotating shape continuously morphs
 * through a set of soft geometric silhouettes inside a subtle primary container.
 */
class ExpressiveLoadingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val pointCount = 48
    private val shapes = listOf(
        radialShape { angle -> 0.91f + 0.09f * cos(6f * angle) },
        radialShape { angle -> 0.88f + 0.12f * cos(3f * angle) },
        radialShape { angle -> 0.90f + 0.10f * cos(4f * angle + PI.toFloat() / 4f) },
        radialShape { angle -> 0.89f + 0.11f * cos(5f * angle) },
        radialShape { angle -> 0.91f + 0.06f * cos(7f * angle) + 0.03f * sin(3f * angle) },
        radialShape { angle ->
            val x = cos(angle)
            val y = sin(angle)
            (1f / (x.pow(2) / 1.0f.pow(2) + y.pow(2) / 0.72f.pow(2)).pow(0.5f)) * 0.88f
        },
    )

    private var animationProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, shapes.size.toFloat()).apply {
        duration = 4_800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        backgroundPaint.color = MaterialColors.getColor(
            this,
            MaterialR.attr.colorPrimaryContainer,
        )
        backgroundPaint.alpha = 92
        shapePaint.color = MaterialColors.getColor(this, AppCompatR.attr.colorPrimary)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val outerRadius = minOf(width, height) / 2f
        canvas.drawCircle(centerX, centerY, outerRadius, backgroundPaint)

        val phase = animationProgress % shapes.size
        val segment = phase.toInt()
        val next = (segment + 1) % shapes.size
        val local = phase - segment
        val morph = smoothStep(local)
        val shapeRadius = outerRadius - 7f * density

        buildMorphPath(shapes[segment], shapes[next], morph, centerX, centerY, shapeRadius)
        canvas.save()
        canvas.rotate(phase * 120f, centerX, centerY)
        canvas.drawPath(path, shapePaint)
        canvas.restore()
    }

    private fun buildMorphPath(
        from: FloatArray,
        to: FloatArray,
        fraction: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        val x = FloatArray(pointCount)
        val y = FloatArray(pointCount)
        for (index in 0 until pointCount) {
            val angle = (2.0 * PI * index / pointCount - PI / 2.0).toFloat()
            val distance = lerp(from[index], to[index], fraction) * radius
            x[index] = centerX + cos(angle) * distance
            y[index] = centerY + sin(angle) * distance
        }

        path.reset()
        path.moveTo(x[0], y[0])
        for (index in 0 until pointCount) {
            val previous = (index - 1 + pointCount) % pointCount
            val current = index
            val next = (index + 1) % pointCount
            val afterNext = (index + 2) % pointCount
            path.cubicTo(
                x[current] + (x[next] - x[previous]) / 6f,
                y[current] + (y[next] - y[previous]) / 6f,
                x[next] - (x[afterNext] - x[current]) / 6f,
                y[next] - (y[afterNext] - y[current]) / 6f,
                x[next],
                y[next],
            )
        }
        path.close()
    }

    private fun radialShape(radiusAt: (Float) -> Float): FloatArray =
        FloatArray(pointCount) { index ->
            radiusAt((2.0 * PI * index / pointCount - PI / 2.0).toFloat())
        }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
