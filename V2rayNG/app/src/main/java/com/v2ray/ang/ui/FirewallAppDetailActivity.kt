package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.AppFirewallStatus
import com.v2ray.ang.data.firewall.ConnLogDao
import com.v2ray.ang.data.firewall.ConnLogDestAggregate
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.databinding.ActivityFirewallAppDetailBinding
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.ui.widget.IconShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rethink-style per-app hub: status, destination rules, connection aggregates. */
class FirewallAppDetailActivity : BaseActivity() {

    private val binding by lazy { ActivityFirewallAppDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private var appUid: Int = -1
    private lateinit var connectionsAdapter: FirewallConnectionsAdapter
    private var connectionQuery = ""
    private var connectionFilter = ConnectionFilter.ALL
    private var connections: List<ConnLogDestAggregate> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshConnections()
            handler.postDelayed(this, 1500L)
        }
    }

    private val statuses = AppFirewallStatus.entries

    private enum class ConnectionFilter {
        ALL,
        ALLOWED,
        BLOCKED,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (packageName.isEmpty()) {
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_LABEL)
            ?: packageName
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = label)

        binding.tvAppName.text = label
        binding.tvAppPackage.text = packageName
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appUid = appInfo.uid
            binding.ivAppIcon.setImageDrawable(IconShape.shaped(this, packageManager.getApplicationIcon(appInfo), 48))
        } catch (_: PackageManager.NameNotFoundException) {
            // leave default
        }

        val labels = statuses.map { statusLabel(it) }
        binding.actStatus.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        val current = FirewallManager.appStatus(packageName)
        binding.actStatus.setText(statusLabel(current), false)
        binding.actStatus.setOnItemClickListener { _, _, position, _ ->
            FirewallManager.setAppStatus(packageName, statuses[position])
            applyFirewallChange()
            refreshRulesSummary()
        }

        binding.btnEditRules.setOnClickListener {
            startActivity(
                Intent(this, FirewallRulesEditActivity::class.java)
                    .putExtra(FirewallRulesEditActivity.EXTRA_SCOPE, packageName)
                    .putExtra(FirewallRulesEditActivity.EXTRA_TITLE, label)
            )
        }

        connectionsAdapter = FirewallConnectionsAdapter { dest -> showDestActions(dest) }
        binding.recyclerConnections.layoutManager = LinearLayoutManager(this)
        binding.recyclerConnections.adapter = connectionsAdapter
        binding.etConnectionsSearch.doAfterTextChanged {
            connectionQuery = it?.toString().orEmpty().trim()
            applyConnectionFilter()
        }
        binding.chipsConnectionVerdict.setOnCheckedStateChangeListener { _, checkedIds ->
            connectionFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_connections_allowed -> ConnectionFilter.ALLOWED
                R.id.chip_connections_blocked -> ConnectionFilter.BLOCKED
                else -> ConnectionFilter.ALL
            }
            applyConnectionFilter()
        }
        binding.btnClearConnections.setOnClickListener { confirmClearConnections() }
    }

    override fun onResume() {
        super.onResume()
        refreshRulesSummary()
        refreshConnections()
        handler.postDelayed(pollRunnable, 1500L)
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    private fun refreshRulesSummary() {
        val domains = FirewallManager.appDomains(packageName)
        val ips = FirewallManager.appIps(packageName)
        binding.tvRulesSummary.text = getString(
            R.string.shrimp_firewall_app_rules_summary,
            domains.size,
            ips.size,
        )
    }

    private fun refreshConnections() {
        connections = ConnLogDao.get(this).destAggregatesForApp(packageName, appUid)
        applyConnectionFilter()
    }

    private fun applyConnectionFilter() {
        val filtered = connections.filter { connection ->
            val verdictMatches = when (connectionFilter) {
                ConnectionFilter.ALL -> true
                ConnectionFilter.ALLOWED -> connection.blockedCount == 0
                ConnectionFilter.BLOCKED -> connection.blockedCount > 0
            }
            val queryMatches = connectionQuery.isEmpty() ||
                connection.destIp.contains(connectionQuery, ignoreCase = true) ||
                connection.domain?.contains(connectionQuery, ignoreCase = true) == true ||
                connection.destPort.toString().contains(connectionQuery)
            verdictMatches && queryMatches
        }
        connectionsAdapter.submit(filtered)
        val empty = filtered.isEmpty()
        binding.tvConnectionsEmpty.setText(
            if (connections.isEmpty()) {
                R.string.shrimp_firewall_connections_empty
            } else {
                R.string.shrimp_firewall_connections_no_matches
            }
        )
        binding.tvConnectionsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerConnections.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun confirmClearConnections() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shrimp_firewall_connections_clear_title)
            .setMessage(R.string.shrimp_firewall_connections_clear_message)
            .setPositiveButton(R.string.routing_settings_delete) { _, _ ->
                ConnLogDao.get(this).deleteForApp(packageName, appUid)
                refreshConnections()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDestActions(dest: ConnLogDestAggregate) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options += getString(R.string.shrimp_firewall_action_copy_ip)
        actions += { copyToClipboard(getString(R.string.shrimp_firewall_clip_ip), dest.destIp) }
        options += getString(R.string.shrimp_firewall_action_block_ip)
        actions += {
            FirewallManager.setIpRule(dest.destIp, packageName, DestRuleStatus.BLOCK)
            applyFirewallChange()
            refreshRulesSummary()
        }
        options += getString(R.string.shrimp_firewall_action_trust_ip)
        actions += {
            FirewallManager.setIpRule(dest.destIp, packageName, DestRuleStatus.TRUST)
            applyFirewallChange()
            refreshRulesSummary()
        }
        options += getString(R.string.shrimp_firewall_action_clear_ip)
        actions += {
            FirewallManager.removeIpRule(dest.destIp, packageName)
            applyFirewallChange()
            refreshRulesSummary()
        }

        val domain = dest.domain?.takeIf { it.isNotBlank() }
        if (domain != null) {
            options += getString(R.string.shrimp_firewall_action_copy_domain)
            actions += { copyToClipboard(getString(R.string.shrimp_firewall_clip_domain), domain) }
            options += getString(R.string.shrimp_firewall_action_block_domain)
            actions += {
                FirewallManager.setDomainRule(domain, packageName, DestRuleStatus.BLOCK)
                applyFirewallChange()
                refreshRulesSummary()
            }
            options += getString(R.string.shrimp_firewall_action_trust_domain)
            actions += {
                FirewallManager.setDomainRule(domain, packageName, DestRuleStatus.TRUST)
                applyFirewallChange()
                refreshRulesSummary()
            }
            options += getString(R.string.shrimp_firewall_action_clear_domain)
            actions += {
                FirewallManager.removeDomainRule(domain, packageName)
                applyFirewallChange()
                refreshRulesSummary()
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(domain ?: dest.destIp)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyFirewallChange() {
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_STATE_RESTART, "")
        Toast.makeText(this, R.string.shrimp_firewall_rule_applied, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val snapshot = FirewallManager.verdictSnapshot(this@FirewallAppDetailActivity)
                ConnLogDao.get(this@FirewallAppDetailActivity).recomputeVerdictsForApp(
                    packageName,
                    appUid,
                ) { rowUid, destIp, domain ->
                    FirewallManager.isConnectionBlocked(
                        snapshot,
                        rowUid,
                        packageName,
                        destIp,
                        domain,
                    )
                }
            }
            refreshConnections()
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(R.string.shrimp_firewall_copied, value), Toast.LENGTH_SHORT).show()
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

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
    }
}
