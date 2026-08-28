package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.ConnLogDestAggregate
import com.v2ray.ang.databinding.ItemFirewallConnectionBinding
import java.text.DateFormat
import java.util.Date

class FirewallConnectionsAdapter(
    private val onClick: (ConnLogDestAggregate) -> Unit,
) : RecyclerView.Adapter<FirewallConnectionsAdapter.VH>() {

    private var items: List<ConnLogDestAggregate> = emptyList()
    private val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ConnLogDestAggregate>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFirewallConnectionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemFirewallConnectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConnLogDestAggregate) {
            val context = binding.root.context
            val title = item.domain?.takeIf { it.isNotBlank() } ?: item.destIp
            binding.tvDest.text = "$title:${item.destPort}"
            val isBlocked = item.blockedCount > 0
            val verdict = if (isBlocked) {
                context.getString(R.string.shrimp_firewall_conn_blocked)
            } else {
                context.getString(R.string.shrimp_firewall_conn_allowed)
            }
            val ipLine = if (!item.domain.isNullOrBlank()) "${item.destIp} · " else ""
            val meta = context.getString(
                R.string.shrimp_firewall_conn_meta,
                ipLine,
                item.attempts,
                verdict,
                timeFmt.format(Date(item.lastSeen)),
            )
            val start = meta.indexOf(verdict)
            binding.tvMeta.text = SpannableString(meta).apply {
                if (start >= 0) {
                    val end = start + verdict.length
                    val color = ContextCompat.getColor(
                        context,
                        if (isBlocked) {
                            R.color.firewall_status_block_content
                        } else {
                            R.color.firewall_status_allow_content
                        },
                    )
                    setSpan(
                        ForegroundColorSpan(color),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
