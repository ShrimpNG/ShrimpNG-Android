package com.v2ray.ang.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment

/**
 * Keeps a tab's content clear of the floating dock, which sits on top of it: [scrolling]
 * containers get the dock's height as extra bottom padding (scrolling under the dock, but able to
 * scroll their last item above it), [fabs] get it as extra bottom margin.
 *
 * A ScrollView's padding goes on its content instead. ScrollView only takes over a drag that
 * starts on a child when canScrollVertically() says it can scroll, and that ignores its own bottom
 * padding: content scrollable only thanks to the dock's padding then scrolled from bare
 * background but not from a row.
 *
 * Call from onViewCreated. The paddings and margins in the layout stay the baseline.
 */
fun Fragment.keepClearOfDock(scrolling: List<View>, fabs: List<View> = emptyList()) {
    val activity = activity as? MainActivity ?: return
    val scrolling = scrolling.map { view ->
        if (view is ScrollView || view is NestedScrollView) {
            (view as ViewGroup).clipToPadding = false
            view.getChildAt(0) ?: view
        } else {
            view
        }
    }
    val basePadding = scrolling.map { it.paddingBottom }
    val baseMargin = fabs.map { (it.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin }
    scrolling.forEach { (it as? ViewGroup)?.clipToPadding = false }
    activity.onDockInset(viewLifecycleOwner) { inset ->
        scrolling.forEachIndexed { i, view -> view.updatePadding(bottom = basePadding[i] + inset) }
        fabs.forEachIndexed { i, fab ->
            fab.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = baseMargin[i] + inset }
        }
    }
}
