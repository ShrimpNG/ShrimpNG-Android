package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.AppFirewallStatus
import com.v2ray.ang.databinding.ItemFirewallAppBinding
import com.v2ray.ang.dto.AppInfo

class FirewallAppsAdapter(
    private val statusOf: (packageName: String) -> AppFirewallStatus,
    private val isOutsideTunnel: (packageName: String) -> Boolean,
    private val onOpen: (AppInfo) -> Unit,
    private val onQuickToggle: (AppInfo, AppFirewallStatus) -> Unit,
    private val onChooseStatus: (AppInfo) -> Unit,
    private val onSelectionChanged: (count: Int) -> Unit,
) : RecyclerView.Adapter<FirewallAppsAdapter.ViewHolder>() {

    private var apps: List<AppInfo> = emptyList()
    private val selected = linkedSetOf<String>()

    val isSelectionMode: Boolean
        get() = selected.isNotEmpty()

    fun selectedPackages(): Set<String> = selected.toSet()

    fun selectAllVisible() {
        selected.addAll(apps.map { it.packageName })
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(0)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFirewallAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(apps[position])

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(apps[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    inner class ViewHolder(private val binding: ItemFirewallAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppInfo) {
            binding.ivAppIcon.setImageDrawable(app.appIcon)
            binding.tvAppName.text = if (app.isSystemApp) "** ${app.appName}" else app.appName
            binding.tvAppPackage.text = app.packageName
            val status = statusOf(app.packageName)
            binding.btnQuickStatus.text = statusLabel(status)
            applyStatusColors(status)
            binding.tvAppWarning.visibility =
                if (isOutsideTunnel(app.packageName)) View.VISIBLE else View.GONE
            bindSelection(app)
            binding.btnQuickStatus.setOnClickListener {
                binding.statusSplitButton.clearChecked()
                onQuickToggle(app, status)
            }
            binding.btnChooseStatus.setOnClickListener {
                // MaterialButtonToggleGroup makes its children checkable even though this group
                // is only used for split-button geometry. Clear that transient checked state so
                // the arrow does not remain highlighted after the status dialog is dismissed.
                binding.statusSplitButton.clearChecked()
                onChooseStatus(app)
            }
            binding.root.setOnClickListener {
                if (isSelectionMode) toggleSelection(app.packageName) else onOpen(app)
            }
            binding.root.setOnLongClickListener {
                toggleSelection(app.packageName)
                true
            }
        }

        fun bindSelection(app: AppInfo) {
            val selecting = isSelectionMode
            // INVISIBLE (not GONE): keep the same reserved widths so rows don't reflow.
            binding.ivAppIcon.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
            binding.checkAppSelected.visibility = if (selecting) View.VISIBLE else View.INVISIBLE
            binding.checkAppSelected.isChecked = app.packageName in selected
            binding.statusSplitButton.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        }

        private fun toggleSelection(packageName: String) {
            val wasSelectionMode = isSelectionMode
            if (!selected.add(packageName)) selected.remove(packageName)
            if (wasSelectionMode != isSelectionMode) {
                // Entering/leaving selection only toggles chrome; keep content/icons intact.
                notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
            } else {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position, PAYLOAD_SELECTION)
                }
            }
            onSelectionChanged(selected.size)
        }

        private fun applyStatusColors(status: AppFirewallStatus) {
            val (containerRes, contentRes) = when (status) {
                AppFirewallStatus.ALLOW ->
                    R.color.firewall_status_allow_container to R.color.firewall_status_allow_content
                AppFirewallStatus.BLOCK ->
                    R.color.firewall_status_block_container to R.color.firewall_status_block_content
                AppFirewallStatus.WIFI_ONLY ->
                    R.color.firewall_status_wifi_container to R.color.firewall_status_wifi_content
                AppFirewallStatus.MOBILE_ONLY ->
                    R.color.firewall_status_mobile_container to R.color.firewall_status_mobile_content
                AppFirewallStatus.ISOLATE ->
                    R.color.firewall_status_isolate_container to R.color.firewall_status_isolate_content
                AppFirewallStatus.BYPASS_DIRECT ->
                    R.color.firewall_status_bypass_container to R.color.firewall_status_bypass_content
            }
            val context = binding.root.context
            val background = ColorStateList.valueOf(ContextCompat.getColor(context, containerRes))
            val content = ContextCompat.getColor(context, contentRes)
            binding.btnQuickStatus.backgroundTintList = background
            binding.btnChooseStatus.backgroundTintList = background
            binding.btnQuickStatus.setTextColor(content)
            binding.btnChooseStatus.iconTint = ColorStateList.valueOf(content)
        }

        private fun statusLabel(status: AppFirewallStatus): String {
            val res = when (status) {
                AppFirewallStatus.ALLOW -> R.string.shrimp_firewall_status_allow
                AppFirewallStatus.BLOCK -> R.string.shrimp_firewall_status_block
                AppFirewallStatus.WIFI_ONLY -> R.string.shrimp_firewall_status_wifi_only
                AppFirewallStatus.MOBILE_ONLY -> R.string.shrimp_firewall_status_mobile_only
                AppFirewallStatus.ISOLATE -> R.string.shrimp_firewall_status_isolate
                AppFirewallStatus.BYPASS_DIRECT -> R.string.shrimp_firewall_status_bypass
            }
            return binding.root.context.getString(res)
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }
}
