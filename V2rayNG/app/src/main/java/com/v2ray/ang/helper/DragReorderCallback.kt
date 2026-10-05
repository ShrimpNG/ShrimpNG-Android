package com.v2ray.ang.helper

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Drag-to-reorder only — no swipe. Rows here are tap-to-act and were losing data to accidental
 * horizontal swipes, so the swipe directions are deliberately left empty; edit/remove live in
 * each row's overflow menu instead.
 *
 * Drag is only ever started explicitly (a row's drag handle calling `ItemTouchHelper.startDrag`),
 * never on long-press, so long-press stays free for the row's own gesture.
 */
class DragReorderCallback(
    private val adapter: ItemTouchHelperAdapter,
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled() = false

    override fun isItemViewSwipeEnabled() = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
        adapter.onItemMove(source.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.onItemMoveCompleted()
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
}
