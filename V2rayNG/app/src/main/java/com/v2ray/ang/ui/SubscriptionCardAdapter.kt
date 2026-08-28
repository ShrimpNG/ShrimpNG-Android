package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.LayoutSubscriptionCardBinding

/**
 * A single-item adapter for the Happ-style subscription card, meant to be combined with
 * [HomeServerAdapter] via a `ConcatAdapter` so the card scrolls away together with the server
 * list below it, instead of staying pinned as a fixed header above a separately-scrolling
 * RecyclerView.
 */
class SubscriptionCardAdapter : RecyclerView.Adapter<SubscriptionCardAdapter.ViewHolder>() {

    private var binder: ((LayoutSubscriptionCardBinding) -> Unit)? = null

    /** Rebinds the card with the given [binder], which should set every field it cares about
     *  (this adapter has no memory of previous state between calls). */
    fun bind(binder: (LayoutSubscriptionCardBinding) -> Unit) {
        this.binder = binder
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = LayoutSubscriptionCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        binder?.invoke(holder.binding)
    }

    class ViewHolder(val binding: LayoutSubscriptionCardBinding) : RecyclerView.ViewHolder(binding.root)
}
