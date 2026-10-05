package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.AppFirewallStatus
import com.v2ray.ang.databinding.FragmentFirewallAppsBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.AppManagerUtil
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.launch

/** Per-app firewall list. Tap opens the Rethink-style detail screen. */
class FirewallAppsFragment : BaseFragment<FragmentFirewallAppsBinding>() {

    private lateinit var adapter: FirewallAppsAdapter
    private var allApps: List<AppInfo> = emptyList()
    private var query: String = ""
    private var outsideTunnel: Set<String> = emptySet()
    private var typeFilter = AppTypeFilter.USER
    private var statusFilter: AppFirewallStatus? = null

    private enum class AppTypeFilter {
        ALL,
        USER,
        SYSTEM,
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentFirewallAppsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FirewallAppsAdapter(
            statusOf = { FirewallManager.appStatus(it) },
            isOutsideTunnel = { it in outsideTunnel },
            onOpen = { app ->
                startActivity(
                    Intent(requireContext(), FirewallAppDetailActivity::class.java)
                        .putExtra(FirewallAppDetailActivity.EXTRA_PACKAGE, app.packageName)
                        .putExtra(FirewallAppDetailActivity.EXTRA_LABEL, app.appName)
                )
            },
            onQuickToggle = { app, current ->
                setAppStatus(
                    app.packageName,
                    if (current == AppFirewallStatus.ALLOW) {
                        AppFirewallStatus.BLOCK
                    } else {
                        AppFirewallStatus.ALLOW
                    },
                )
            },
            onChooseStatus = ::showAppStatusDialog,
            onSelectionChanged = ::renderBulkActions,
        )
        binding.recyclerFirewallApps.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFirewallApps.adapter = adapter
        binding.etSearchApps.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            applyFilter()
        }
        binding.chipsAppType.setOnCheckedStateChangeListener { _, checkedIds ->
            typeFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_apps_user -> AppTypeFilter.USER
                R.id.chip_apps_system -> AppTypeFilter.SYSTEM
                else -> AppTypeFilter.ALL
            }
            adapter.clearSelection()
            applyFilter()
        }
        binding.chipsAppStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_status_allow -> AppFirewallStatus.ALLOW
                R.id.chip_status_block -> AppFirewallStatus.BLOCK
                R.id.chip_status_wifi -> AppFirewallStatus.WIFI_ONLY
                R.id.chip_status_mobile -> AppFirewallStatus.MOBILE_ONLY
                R.id.chip_status_isolate -> AppFirewallStatus.ISOLATE
                R.id.chip_status_bypass -> AppFirewallStatus.BYPASS_DIRECT
                else -> null
            }
            adapter.clearSelection()
            applyFilter()
        }
        binding.btnSelectAll.setOnClickListener { adapter.selectAllVisible() }
        binding.btnBulkCancel.setOnClickListener { adapter.clearSelection() }
        binding.btnBulkApply.setOnClickListener { showBulkStatusDialog() }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        outsideTunnel = computeOutsideTunnel()
        applyFilter()
    }

    private fun loadApps() {
        binding.appLoadingContainer.visibility = View.VISIBLE
        binding.recyclerFirewallApps.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val collator = Collator.getInstance(Locale.getDefault())
                allApps = AppManagerUtil.loadNetworkAppList(requireContext())
                    .sortedWith(compareBy(collator) { it.appName })
                outsideTunnel = computeOutsideTunnel()
                applyFilter()
            } finally {
                binding.appLoadingContainer.visibility = View.GONE
                binding.recyclerFirewallApps.visibility = View.VISIBLE
            }
        }
    }

    private fun computeOutsideTunnel(): Set<String> {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)) return emptySet()
        val selected = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
        if (selected.isEmpty()) return emptySet()
        val excluded = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)) {
            selected
        } else {
            allApps.map { it.packageName }.filterNot { it in selected }.toSet()
        }
        return excluded - FirewallManager.packagesRequiringTunnel()
    }

    private fun applyFilter() {
        val filtered = allApps.filter {
            val statusMatches = statusFilter == null ||
                FirewallManager.appStatus(it.packageName) == statusFilter
            val typeMatches = when (typeFilter) {
                AppTypeFilter.ALL -> true
                AppTypeFilter.USER -> !it.isSystemApp
                AppTypeFilter.SYSTEM -> it.isSystemApp
            }
            statusMatches && typeMatches && (
                query.isEmpty() ||
                    it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
                )
        }
        // Non-default policies first.
        adapter.submitList(
            filtered.sortedByDescending {
                FirewallManager.appStatus(it.packageName) != AppFirewallStatus.ALLOW
            }
        )
    }

    private fun renderBulkActions(count: Int) {
        val show = count > 0
        binding.bulkActions.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvSelectedCount.text = resources.getQuantityString(
            R.plurals.shrimp_firewall_selected_count,
            count,
            count,
        )
        binding.btnBulkApply.isEnabled = show
        // Keep last rows scrollable above the overlay bar without resizing the list itself.
        val basePad = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
        val bottomPad = if (show) {
            binding.bulkActions.measure(
                View.MeasureSpec.makeMeasureSpec(binding.root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            basePad + binding.bulkActions.measuredHeight
        } else {
            basePad
        }
        binding.recyclerFirewallApps.setPadding(
            binding.recyclerFirewallApps.paddingLeft,
            binding.recyclerFirewallApps.paddingTop,
            binding.recyclerFirewallApps.paddingRight,
            bottomPad,
        )
    }

    private fun showBulkStatusDialog() {
        val selected = adapter.selectedPackages()
        if (selected.isEmpty()) return
        val statuses = AppFirewallStatus.entries
        val labels = statuses.map { statusLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shrimp_firewall_bulk_status_title)
            .setItems(labels) { _, which ->
                val status = statuses[which]
                selected.forEach { FirewallManager.setAppStatus(it, status) }
                SettingsChangeManager.makeRestartService()
                adapter.clearSelection()
                applyFilter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppStatusDialog(app: AppInfo) {
        val statuses = AppFirewallStatus.entries
        val labels = statuses.map { statusLabel(it) }.toTypedArray()
        val current = FirewallManager.appStatus(app.packageName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(app.appName)
            .setSingleChoiceItems(labels, statuses.indexOf(current)) { dialog, which ->
                setAppStatus(app.packageName, statuses[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setAppStatus(packageName: String, status: AppFirewallStatus) {
        FirewallManager.setAppStatus(packageName, status)
        SettingsChangeManager.makeRestartService()
        outsideTunnel = computeOutsideTunnel()
        applyFilter()
    }

    private fun statusLabel(status: AppFirewallStatus): String = getString(
        when (status) {
            AppFirewallStatus.ALLOW -> R.string.shrimp_firewall_status_allow
            AppFirewallStatus.BLOCK -> R.string.shrimp_firewall_status_block
            AppFirewallStatus.WIFI_ONLY -> R.string.shrimp_firewall_status_wifi_only
            AppFirewallStatus.MOBILE_ONLY -> R.string.shrimp_firewall_status_mobile_only
            AppFirewallStatus.ISOLATE -> R.string.shrimp_firewall_status_isolate
            AppFirewallStatus.BYPASS_DIRECT -> R.string.shrimp_firewall_status_bypass
        }
    )
}
