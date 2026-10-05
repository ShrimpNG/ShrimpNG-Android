package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemThemePaletteBinding
import com.v2ray.ang.handler.ThemeManager

/**
 * The row of colour swatches on the Themes & icons screen, after the Pixel launcher's picker:
 * the selected one gets a ring, the others sit in a filled container circle.
 */
class ThemePaletteAdapter(
    private val onPick: (ThemeManager.Palette) -> Unit,
) : RecyclerView.Adapter<ThemePaletteAdapter.ViewHolder>() {

    private val palettes = ThemeManager.Palette.available
    private var selected = ThemeManager.palette()

    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(palette: ThemeManager.Palette) {
        selected = palette
        notifyDataSetChanged()
    }

    override fun getItemCount() = palettes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemThemePaletteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(palettes[position])

    inner class ViewHolder(private val binding: ItemThemePaletteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(palette: ThemeManager.Palette) {
            val context = binding.root.context
            val root = binding.swatchRoot
            binding.ivSwatch.setImageDrawable(swatchFor(context, palette))
            root.contentDescription = context.getString(palette.label)

            val isSelected = palette == selected
            root.isSelected = isSelected
            root.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isSelected) {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(
                        context.resources.getDimensionPixelSize(R.dimen.theme_swatch_ring),
                        MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
                    )
                } else {
                    setColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceContainerHighest))
                }
            }
            root.setOnClickListener { if (palette != selected) onPick(palette) }
        }
    }

    private fun swatchFor(context: Context, palette: ThemeManager.Palette): Drawable {
        palette.swatch?.let { colorRes ->
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(colorRes))
            }
        }
        // Monet's colours exist from Android 12; the legacy build, which reaches lower, hides the
        // system palette, but a swatch must not throw for lack of them.
        if (android.os.Build.VERSION.SDK_INT < 31) {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.md_theme_primary))
            }
        }
        // Read Android's resources, not the currently selected preset's overlay. Different
        // tones keep the three sectors legible even when Monet chooses very similar hues.
        return PieDrawable(
            top = context.getColor(android.R.color.system_accent1_200),
            bottomStart = context.getColor(android.R.color.system_accent2_600),
            bottomEnd = context.getColor(android.R.color.system_accent3_400),
        )
    }

    /** A circle split into a top half and two bottom quarters. */
    private class PieDrawable(
        @ColorInt private val top: Int,
        @ColorInt private val bottomStart: Int,
        @ColorInt private val bottomEnd: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval = RectF()
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            oval.set(bounds)
            // Angles run clockwise from 3 o'clock.
            arc(canvas, 180f, 180f, top)
            arc(canvas, 90f, 90f, bottomStart)
            arc(canvas, 0f, 90f, bottomEnd)
        }

        private fun arc(canvas: Canvas, start: Float, sweep: Float, @ColorInt color: Int) {
            paint.color = color
            paint.alpha = drawableAlpha
            canvas.drawArc(oval, start, sweep, true, paint)
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
