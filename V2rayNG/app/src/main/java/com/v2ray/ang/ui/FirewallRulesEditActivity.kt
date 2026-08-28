package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.data.firewall.FirewallPolicyStore
import com.v2ray.ang.databinding.ActivityFirewallRulesEditBinding

/** Per-app or global domain/IP block|trust editor. */
class FirewallRulesEditActivity : BaseActivity() {

    private val binding by lazy { ActivityFirewallRulesEditBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = intent.getStringExtra(EXTRA_SCOPE) ?: FirewallPolicyStore.SCOPE_GLOBAL
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.shrimp_firewall_tab_targets)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = title)

        FirewallRuleChips.bindDomainSection(
            binding.tilDomainBlock, binding.etDomainBlock, binding.chipsDomainBlock,
            scope, DestRuleStatus.BLOCK,
        )
        FirewallRuleChips.bindDomainSection(
            binding.tilDomainTrust, binding.etDomainTrust, binding.chipsDomainTrust,
            scope, DestRuleStatus.TRUST,
        )
        FirewallRuleChips.bindIpSection(
            binding.tilIpBlock, binding.etIpBlock, binding.chipsIpBlock,
            scope, DestRuleStatus.BLOCK,
        )
        FirewallRuleChips.bindIpSection(
            binding.tilIpTrust, binding.etIpTrust, binding.chipsIpTrust,
            scope, DestRuleStatus.TRUST,
        )
    }

    companion object {
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_TITLE = "title"
    }
}
