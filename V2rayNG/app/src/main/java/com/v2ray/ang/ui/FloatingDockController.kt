package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import com.v2ray.ang.R
import com.v2ray.ang.databinding.LayoutFloatingDockBinding
import com.v2ray.ang.ui.widget.ExpressiveMotion
import com.v2ray.ang.ui.widget.ExpressiveScale

/**
 * Drives the floating dock (layout_floating_dock.xml): which destination is selected, how that
 * looks, and the animation between them. Stands in for the BottomNavigationView it replaced, with
 * the same shape of API — [selectedItemId] and a listener keyed by the R.id.nav_* ids.
 */
class FloatingDockController(
    private val dock: LayoutFloatingDockBinding,
    private val onItemSelected: (Int) -> Unit,
) {
    /** Something a long press on a destination opened, that the rest of the press then drives. */
    interface HeldGesture {
        fun onMove(rawX: Float, rawY: Float)
        /** [moved]: whether the finger travelled at all after the long press fired. */
        fun onRelease(rawX: Float, rawY: Float, moved: Boolean)
    }

    /**
     * Called when a destination is long-pressed, with its id and view; returns what takes over the
     * rest of the press, or null to ignore it (Главное opens the subscription switcher).
     */
    var onLongPress: ((itemId: Int, anchor: View) -> HeldGesture?)? = null

    /** [iconRes] outlined, [selectedIconRes] filled: the M3 navigation convention. */
    private class Tab(
        val root: View,
        val icon: ImageView,
        val label: TextView?,
        @DrawableRes val iconRes: Int,
        @DrawableRes val selectedIconRes: Int,
    )

    private val tabs: Map<Int, Tab> = mapOf(
        R.id.nav_home to Tab(
            dock.dockTabHome, dock.dockIconHome, dock.dockLabelHome,
            R.drawable.ic_home_24dp, R.drawable.ic_home_filled_24dp,
        ),
        R.id.nav_subscriptions to Tab(
            dock.dockTabSubscriptions, dock.dockIconSubscriptions, dock.dockLabelSubscriptions,
            R.drawable.ic_subscriptions_24dp, R.drawable.ic_subscriptions_filled_24dp,
        ),
        R.id.nav_rules to Tab(
            dock.dockTabRules, dock.dockIconRules, dock.dockLabelRules,
            R.drawable.ic_routing_24dp, R.drawable.ic_routing_filled_24dp,
        ),
        R.id.nav_settings to Tab(
            dock.dockSettings, dock.dockIconSettings, null,
            R.drawable.ic_settings_24dp, R.drawable.ic_settings_filled_24dp,
        ),
    )

    private val scaleMotion = tabs.values.flatMap { listOfNotNull(it.icon, it.label) }
        .associateWith { ExpressiveScale(it) }

    var selectedItemId: Int = View.NO_ID
        set(value) {
            if (value == field || value !in tabs) return
            val animate = field != View.NO_ID
            field = value
            render(animate)
            onItemSelected(value)
        }

    // ---------------------------------------------------------------------------------------
    // Scrubbing: drag the selected pill along the dock; the destination under the finger becomes
    // the selected one as it is reached, with a tick, and the pages follow. A long press without
    // moving instead goes to onLongPress.
    // ---------------------------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(dock.root.context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false
    private var pressedView: View? = null
    private var touchedView: View? = null
    private var held: HeldGesture? = null
    private var heldX = 0f
    private var heldY = 0f
    private var heldMoved = false

    /**
     * Where each destination's centre was when the scrub began. Hit-testing against these rather
     * than the live layout keeps the choice steady while the pills resize under the finger:
     * the newly selected one widening would otherwise push its neighbour back under it.
     */
    private var centres: List<Pair<Int, Float>> = emptyList()

    /** A long press hands the rest of the gesture to whatever [onLongPress] opened. */
    private val longPress = Runnable {
        val view = touchedView ?: return@Runnable
        val id = tabs.entries.firstOrNull { it.value.root === view }?.key ?: return@Runnable
        val gesture = onLongPress?.invoke(id, view) ?: return@Runnable
        held = gesture
        heldMoved = false
        heldX = downX
        heldY = downY
        view.isPressed = false
        view.cancelLongPress()
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private val scrubListener = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                scrubbing = false
                held = null
                touchedView = view
                // Only the selected pill can be dragged along; the others are plain buttons.
                pressedView = view.takeIf { it.isSelected }
                if (onLongPress != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                false
            }
            MotionEvent.ACTION_MOVE -> {
                held?.let { gesture ->
                    if (!heldMoved && kotlin.math.hypot(event.rawX - heldX, event.rawY - heldY) > touchSlop) heldMoved = true
                    gesture.onMove(event.rawX, event.rawY)
                    return@OnTouchListener true
                }
                if (!scrubbing && kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                    handler.removeCallbacks(longPress)
                }
                if (pressedView == null) return@OnTouchListener false
                if (!scrubbing) {
                    val dx = event.rawX - downX
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(event.rawY - downY)) {
                        startScrub(view)
                    } else {
                        return@OnTouchListener false
                    }
                }
                scrubTo(event.rawX)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                touchedView = null
                held?.let { gesture ->
                    held = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        gesture.onRelease(event.rawX, event.rawY, heldMoved)
                    } else {
                        gesture.onRelease(Float.NaN, Float.NaN, true)
                    }
                    pressedView = null
                    return@OnTouchListener true
                }
                val was = scrubbing
                if (was) endScrub()
                pressedView = null
                // A scrub swallows its release, so it never also counts as a tap.
                was
            }
            else -> scrubbing
        }
    }

    private fun startScrub(view: View) {
        if (scrubbing) return
        handler.removeCallbacks(longPress)
        scrubbing = true
        centres = tabs.map { (id, tab) ->
            val loc = IntArray(2)
            tab.root.getLocationOnScreen(loc)
            id to loc[0] + tab.root.width / 2f
        }
        // The view's own press (and the tap it would turn into) is cancelled; the gesture is ours.
        view.isPressed = false
        view.cancelLongPress()
        view.parent?.requestDisallowInterceptTouchEvent(true)
        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_START)
        lift(selectedItemId, true)
    }

    private fun scrubTo(rawX: Float) {
        val target = centres.minByOrNull { (_, centre) -> kotlin.math.abs(centre - rawX) }?.first ?: return
        if (target == selectedItemId) return
        lift(selectedItemId, false)
        selectedItemId = target
        lift(target, true)
        dock.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }

    private fun endScrub() {
        scrubbing = false
        tabs.keys.forEach { lift(it, false) }
        ViewCompat.performHapticFeedback(dock.root, HapticFeedbackConstantsCompat.GESTURE_END)
    }

    /**
     * The held pill's icon and label grow a little, so it reads as picked up. Not the pill itself:
     * the tab row clips its children to its rectangle, so a grown end pill had its rounded end cut
     * flat against the dock's corner.
     */
    private fun lift(id: Int, up: Boolean) {
        val tab = tabs[id] ?: return
        val scale = if (up) 1.1f else 1f
        listOfNotNull(tab.icon, tab.label).forEach { view ->
            scaleMotion.getValue(view).animateTo(scale)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fitting the width: with a large font or display size (Settings → Accessibility) the labels
    // alone outgrew a narrow screen and the dock ran off its edges. [fitWidth] first tightens the
    // tabs' padding, then shrinks the labels a little — never below MIN_LABEL_SCALE, since
    // whoever turned the font up needs it — and failing that drops them for larger icons.
    // ---------------------------------------------------------------------------------------

    private val labelTextSizes: Map<TextView, Float> =
        tabs.values.mapNotNull { it.label }.associateWith { it.textSize }
    private var iconOnly = false
    private var fittedWidth = -1

    /**
     * Lays the dock out to fit [available] px of width. Each candidate is measured for real, with
     * every destination in turn wearing the icon the selected one shows: the widest of those is
     * what has to fit, or selecting the longest label would push Settings off the screen again.
     */
    fun fitWidth(available: Int) {
        if (available <= 0 || available == fittedWidth) return
        fittedWidth = available
        val full = dock.root.resources.getDimensionPixelSize(R.dimen.dock_tab_padding)
        val tight = full / 2
        val candidates = buildList {
            add(full to 1f)
            var scale = 1f
            while (scale >= MIN_LABEL_SCALE - 0.001f) {
                add(tight to scale)
                scale -= 0.05f
            }
        }
        val fits = candidates.any { (padding, scale) ->
            applyFit(padding, scale, iconsOnly = false)
            widestMeasured() <= available
        }
        if (!fits) applyFit(full, 1f, iconsOnly = true)
        render(animate = false)
    }

    private fun applyFit(padding: Int, scale: Float, iconsOnly: Boolean) {
        val res = dock.root.resources
        iconOnly = iconsOnly
        val iconSize = res.getDimensionPixelSize(if (iconsOnly) R.dimen.dock_icon_size_large else R.dimen.dock_icon_size)
        tabs.values.filter { it.label != null }.forEach { tab ->
            tab.root.setPadding(padding, tab.root.paddingTop, padding, tab.root.paddingBottom)
            tab.icon.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = iconSize
                height = iconSize
                marginEnd = if (iconsOnly) 0 else res.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
            }
            tab.label?.visibility = if (iconsOnly) View.GONE else View.VISIBLE
        }
        labelTextSizes.forEach { (label, size) -> label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale) }
    }

    private fun widestMeasured(): Int {
        val labelled = tabs.values.filter { it.label != null }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return labelled.maxOf { withIcon ->
            labelled.forEach { it.icon.visibility = if (it === withIcon) View.VISIBLE else View.GONE }
            dock.root.measure(unspecified, unspecified)
            dock.root.measuredWidth
        }
    }

    // After the scrub state above, which the listeners it installs read.
    init {
        tabs.forEach { (id, tab) ->
            tab.root.setOnClickListener { selectedItemId = id }
            tab.root.setOnTouchListener(scrubListener)
        }
    }

    private fun render(animate: Boolean) {
        val root = dock.root as ViewGroup
        TransitionManager.endTransitions(root)
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            // Scrubbing changes the selection faster than a transition lasts. Finish the running
            // one first, and fade icons in only: a fading-out icon is drawn as a copy in the
            // overlay, and one interrupted mid-fade was left there over its tab's label.
            TransitionManager.beginDelayedTransition(
                root,
                TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(ChangeBounds())
                    .addTransition(Fade(Fade.IN))
                    .setDuration(ExpressiveMotion.duration(root.context))
                    .setInterpolator(MotionUtils.resolveThemeInterpolator(
                        root.context, com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                        FastOutSlowInInterpolator(),
                    ))
            )
        }
        val context = dock.root.context
        val onSelected = MaterialColors.getColor(dock.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val onUnselected = MaterialColors.getColor(dock.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val ripple = ColorStateList.valueOf(
            MaterialColors.getColor(dock.root, androidx.appcompat.R.attr.colorControlHighlight)
        )

        tabs.forEach { (id, tab) ->
            val selected = id == selectedItemId
            tab.root.isSelected = selected
            val color = if (selected) onSelected else onUnselected
            tab.icon.setImageResource(if (selected) tab.selectedIconRes else tab.iconRes)
            tab.icon.imageTintList = ColorStateList.valueOf(color)
            tab.label?.setTextColor(color)
            // In the pill, an unselected destination is its label alone; the icon arrives with
            // the indicator. Settings is a lone icon either way.
            // Without room for labels, every destination is its icon.
            if (tab.label != null) {
                tab.icon.visibility = if (selected || iconOnly) View.VISIBLE else View.GONE
                tab.label.visibility = if (iconOnly) View.GONE else View.VISIBLE
            }

            val indicator: Drawable? = if (selected) {
                ContextCompat.getDrawable(context, R.drawable.shape_dock_indicator)
            } else if (tab.label == null) {
                // The settings circle keeps its own container when it isn't the selected one.
                ContextCompat.getDrawable(context, R.drawable.shape_dock_container)
            } else {
                null
            }
            val mask = GradientDrawable().apply {
                cornerRadius = context.resources.getDimension(R.dimen.dock_height)
                setColor(android.graphics.Color.WHITE)
            }
            tab.root.background = RippleDrawable(ripple, indicator, mask)
        }
    }

    private companion object {
        /** How far a label may shrink to keep the dock on screen, before it gives way to icons. */
        const val MIN_LABEL_SCALE = 0.8f
    }
}
