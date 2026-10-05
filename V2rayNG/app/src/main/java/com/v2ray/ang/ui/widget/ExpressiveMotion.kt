package com.v2ray.ang.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.motion.MotionUtils
import com.v2ray.ang.R
import com.google.android.material.R as MaterialR

/** Shared Material motion tokens for custom Views alongside the library's own components. */
object ExpressiveMotion {
    fun spatialSpring(context: Context): SpringForce = MotionUtils.resolveThemeSpringForce(
        context, MaterialR.attr.motionSpringFastSpatial,
        R.style.Motion_ShrimpNG_FastSpatial,
    )

    fun effectsSpring(context: Context): SpringForce = MotionUtils.resolveThemeSpringForce(
        context, MaterialR.attr.motionSpringDefaultEffects,
        R.style.Motion_ShrimpNG_DefaultEffects,
    )

    fun duration(context: Context): Long = MotionUtils.resolveThemeDuration(
        context, MaterialR.attr.motionDurationMedium2, 300,
    ).toLong()
}

/** Retarget running springs rather than restarting a timed animation on every gesture event. */
class ExpressiveScale(private val view: View) : View.OnAttachStateChangeListener {
    private val x = SpringAnimation(view, SpringAnimation.SCALE_X).apply {
        spring = ExpressiveMotion.spatialSpring(view.context)
    }
    private val y = SpringAnimation(view, SpringAnimation.SCALE_Y).apply {
        spring = ExpressiveMotion.spatialSpring(view.context)
    }

    init { view.addOnAttachStateChangeListener(this) }

    fun animateTo(scale: Float) {
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || !view.isShown) {
            cancel()
            view.scaleX = scale
            view.scaleY = scale
        } else {
            x.animateToFinalPosition(scale)
            y.animateToFinalPosition(scale)
        }
    }

    fun cancel() {
        x.cancel()
        y.cancel()
    }

    override fun onViewAttachedToWindow(v: View) = Unit

    override fun onViewDetachedFromWindow(v: View) {
        cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }
}
