package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.data.firewall.FirewallPolicyStore
import com.v2ray.ang.databinding.ActivityFirewallRulesEditBinding

/** Global domain/IP block and trust lists (`uidScope = *`). */
class FirewallTargetsFragment : BaseFragment<ActivityFirewallRulesEditBinding>() {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        ActivityFirewallRulesEditBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scope = FirewallPolicyStore.SCOPE_GLOBAL
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
}
