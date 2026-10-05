package com.v2ray.ang.ui.widget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.R as MaterialR

/**
 * The Material 3 Expressive grouped-list tile: large outer corners on the first and last tile of
 * a group, small ones between, filled with [fillAttr] and rippling within its shape. Used by the
 * settings screens and anything else laid out as a stack of tiles.
 */
object SegmentedTiles {

    fun background(
        view: View,
        first: Boolean,
        last: Boolean,
        @AttrRes fillAttr: Int = MaterialR.attr.colorSurfaceContainerLow,
    ): RippleDrawable {
        val density = view.resources.displayMetrics.density
        val large = 16 * density
        val small = 4 * density
        val topCorner = if (first) large else small
        val bottomCorner = if (last) large else small
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(topCorner)
            .setTopRightCornerSize(topCorner)
            .setBottomLeftCornerSize(bottomCorner)
            .setBottomRightCornerSize(bottomCorner)
            .build()
        // Read at bind time, from the view: Dynamic Color only exists as theme attributes.
        val fill = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, fillAttr))
        }
        val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
        val ripple = ColorStateList.valueOf(
            MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)
        )
        return RippleDrawable(ripple, fill, mask)
    }
}
