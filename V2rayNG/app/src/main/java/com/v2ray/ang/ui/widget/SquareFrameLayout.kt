package com.v2ray.ang.ui.widget

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.v2ray.ang.R

/**
 * Always square, sized as [sizeFraction] of the available width — used for the QR camera
 * preview card. Center it in its parent via `layout_gravity`/`gravity` when sizeFraction < 1.
 * Corners are rounded to [cornerRadiusPx] (matching the app's Material You card shape) and all
 * children — including a `PreviewView`'s live camera feed — are clipped to that rounded outline.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var sizeFraction: Float = 1f
    var cornerRadiusPx: Float = 16 * resources.displayMetrics.density
        set(value) {
            field = value
            invalidateOutline()
        }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.SquareFrameLayout)
            sizeFraction = a.getFloat(R.styleable.SquareFrameLayout_sizeFraction, 1f)
            cornerRadiusPx = a.getDimension(R.styleable.SquareFrameLayout_cornerRadius, cornerRadiusPx)
            a.recycle()
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (View.MeasureSpec.getSize(widthMeasureSpec) * sizeFraction).toInt()
        val sideSpec = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
        super.onMeasure(sideSpec, sideSpec)
    }
}
