package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.google.android.material.R as MaterialR

/**
 * The quick subscription switcher that a long press on Главное in the dock opens, after
 * Telegram's folder menu: still holding, slide up onto a subscription — a highlight follows the
 * finger with a tick per row — and let go to open it. Let go without having moved and it stays
 * open for ordinary taps; let go anywhere else after moving and it closes.
 *
 * Driven by [FloatingDockController], which keeps receiving the gesture that opened it and passes
 * the finger on through [onMove] / [onRelease].
 */
class DockSubscriptionMenu(
    private val anchor: View,
    private val activeSubscriptionId: String,
    private val onPick: (String) -> Unit,
) : FloatingDockController.HeldGesture {

    private val context = anchor.context
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private var popup: PopupWindow? = null
    private lateinit var card: MaterialCardView
    private lateinit var list: LinearLayout
    private lateinit var highlight: View
    private val rows = mutableListOf<Pair<View, () -> Unit>>()
    private var hovered = -1

    /** @return false when there is nothing to switch between, and nothing was shown. */
    fun show(): Boolean {
        val subs = MmkvManager.decodeVisibleSubscriptions().filter { !it.subscription.hiddenFromHome }
        if (subs.isEmpty()) return false

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        subs.forEach { (guid, sub) ->
            val name = sub.remarks.ifBlank { context.getString(R.string.shrimp_home_title) }
            addRow(name, null, guid == activeSubscriptionId) { onPick(guid) }
        }

        highlight = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(MaterialColors.getColor(anchor, MaterialR.attr.colorSecondaryContainer))
            }
            alpha = 0f
        }
        val inner = FrameLayout(context).apply {
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            addView(highlight, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card = MaterialCardView(context).apply {
            radius = dp(20f).toFloat()
            cardElevation = dp(6f).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(anchor, MaterialR.attr.colorSurfaceContainerHigh))
            addView(inner)
        }
        // Room around the card for its shadow, which the popup window would otherwise clip.
        val root = FrameLayout(context).apply {
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        // As wide as the longest name needs, within bounds — not the screen's width, which is
        // what the rows' match_parent asks for when measured against it.
        list.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = list.measuredWidth.coerceIn(dp(180f), minOf(dp(280f), screenWidth - dp(48f)))
        list.layoutParams = list.layoutParams.apply { this.width = width }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val anchorLoc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val x = (anchorLoc[0] - dp(12f)).coerceIn(0, (screenWidth - root.measuredWidth).coerceAtLeast(0))
        // The card's bottom 8dp above the pressed pill (the window's 12dp shadow margin included).
        val y = anchorLoc[1] - dp(8f) - root.measuredHeight + dp(12f)

        // Not focusable while the finger that opened it is still down, so taking focus can't
        // interrupt that press; it becomes focusable (back, tap outside) once left open.
        popup = PopupWindow(root, root.measuredWidth, root.measuredHeight, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = 0
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }

        // Grows out of the dock: from its bottom-left corner, with a little overshoot.
        card.pivotX = dp(24f).toFloat()
        card.pivotY = root.measuredHeight.toFloat()
        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.translationY = dp(12f).toFloat()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(OvershootInterpolator(1.1f)).start()
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun addRow(text: String, iconRes: Int?, active: Boolean, action: () -> Unit) {
        val onSurface = MaterialColors.getColor(anchor, MaterialR.attr.colorOnSurface)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        if (iconRes != null) {
            row.addView(ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(anchor, MaterialR.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(14f) })
        }
        row.addView(TextView(context).apply {
            this.text = text
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
            setTextColor(onSurface)
            if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (active) {
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check_24dp)
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary))
            }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginStart = dp(12f) })
        }
        val index = rows.size
        row.setOnClickListener {
            hover(index)
            choose(index)
        }
        rows += row to action
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onMove(rawX: Float, rawY: Float) {
        hover(rowAt(rawX, rawY))
    }

    override fun onRelease(rawX: Float, rawY: Float, moved: Boolean) {
        val index = rowAt(rawX, rawY)
        when {
            index >= 0 -> choose(index)
            // Lifted where it was pressed: the menu stays for taps, like a tapped-open menu.
            !moved -> {
                hover(-1)
                popup?.let {
                    it.isFocusable = true
                    it.update()
                }
            }
            else -> dismiss()
        }
    }

    private fun rowAt(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        rows.forEachIndexed { i, (row, _) ->
            row.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX < loc[0] + row.width && rawY >= loc[1] && rawY < loc[1] + row.height) return i
        }
        return -1
    }

    /** Slides the highlight to row [index], or fades it out for -1. */
    private fun hover(index: Int) {
        if (index == hovered) return
        val first = hovered < 0
        hovered = index
        if (index < 0) {
            highlight.animate().alpha(0f).setDuration(120).start()
            return
        }
        val row = rows[index].first
        // The highlight and the list share the frame's padding, so the row's own top is the offset.
        val top = row.top.toFloat()
        highlight.updateLayoutParams<ViewGroup.LayoutParams> { height = row.height }
        if (first) {
            highlight.translationY = top
            highlight.scaleX = 0.94f
            highlight.scaleY = 0.94f
        }
        highlight.animate().alpha(1f).translationY(top).scaleX(1f).scaleY(1f)
            .setDuration(180).setInterpolator(FastOutSlowInInterpolator()).start()
        anchor.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }

    private fun choose(index: Int) {
        ViewCompat.performHapticFeedback(anchor, HapticFeedbackConstantsCompat.CONFIRM)
        val action = rows.getOrNull(index)?.second
        dismiss()
        action?.invoke()
    }

    fun dismiss() {
        val window = popup ?: return
        popup = null
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140)
            .withEndAction { runCatching { window.dismiss() } }.start()
    }
}
