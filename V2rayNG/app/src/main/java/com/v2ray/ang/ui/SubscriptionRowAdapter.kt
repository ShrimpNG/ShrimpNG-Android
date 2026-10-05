package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemSubscriptionRowBinding
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter

/**
 * Row for the Подписки tab: manage subscriptions (refresh, edit/share/remove via the overflow,
 * drag-to-reorder, hide-from-Home). No server rows — servers only ever show on Главное. Picking
 * which subscription is *active* also happens on Главное now (quick-switch chips), not here.
 */
class SubscriptionRowAdapter(
    private val listener: Listener,
) : RecyclerView.Adapter<SubscriptionRowAdapter.ViewHolder>(), ItemTouchHelperAdapter {

    interface Listener {
        fun onRefresh(subId: String)
        fun onMore(subId: String)
        fun onToggleHidden(subId: String)
        fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
        fun onReordered()
    }

    private var data: MutableList<SubscriptionCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newData: List<SubscriptionCache>) {
        data = newData.toMutableList()
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): SubscriptionCache? = data.getOrNull(position)

    fun currentGuids(): List<String> = data.map { it.guid }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSubscriptionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in data.indices || toPosition !in data.indices) return false
        val item = data.removeAt(fromPosition)
        data.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        listener.onReordered()
    }

    override fun onItemDismiss(position: Int) = Unit

    inner class ViewHolder(private val binding: ItemSubscriptionRowBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(cache: SubscriptionCache) {
            val subId = cache.guid
            val subItem = cache.subscription
            val context = binding.root.context

            binding.tvName.text = subItem.remarks.ifBlank { context.getString(R.string.shrimp_home_title) }

            val serverCount = MmkvManager.decodeServerList(subId).size
            val host = subItem.url.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { java.net.URI(url).host }.getOrNull() ?: url
            }
            binding.tvMeta.text = when {
                subItem.hiddenFromHome -> context.getString(R.string.shrimp_hidden_from_home_hint)
                host != null -> context.getString(R.string.shrimp_subscription_meta, host, serverCount)
                else -> context.getString(R.string.shrimp_subscription_meta_no_host, serverCount)
            }

            binding.layoutRow.alpha = if (subItem.hiddenFromHome) 0.5f else 1f
            binding.btnToggleHidden.setIconResource(
                if (subItem.hiddenFromHome) R.drawable.ic_visibility_off_24dp else R.drawable.ic_visibility_24dp
            )
            binding.btnToggleHidden.setOnClickListener { listener.onToggleHidden(subId) }

            binding.btnRefresh.visibility = if (subItem.url.isNotBlank()) View.VISIBLE else View.GONE
            binding.btnRefresh.setOnClickListener { listener.onRefresh(subId) }
            binding.btnMore.setOnClickListener { listener.onMore(subId) }

            binding.btnDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(this)
                }
                false
            }
        }
    }
}
