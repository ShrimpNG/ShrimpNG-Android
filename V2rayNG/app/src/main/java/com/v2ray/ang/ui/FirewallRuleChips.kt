package com.v2ray.ang.ui

import android.view.LayoutInflater
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.R
import com.v2ray.ang.data.firewall.DestRuleStatus
import com.v2ray.ang.data.firewall.FirewallPolicyStore
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.SettingsChangeManager

/** Shared chip editors for global / per-app domain & IP block|trust lists. */
object FirewallRuleChips {

    fun bindDomainSection(
        layout: TextInputLayout,
        field: TextInputEditText,
        group: ChipGroup,
        scope: String,
        status: DestRuleStatus,
    ) {
        fun reload() {
            val values = when (scope) {
                FirewallPolicyStore.SCOPE_GLOBAL -> FirewallManager.globalDomains()
                else -> FirewallManager.appDomains(scope)
            }.filter { it.status == status }.map { it.domain }
            render(group, values) { value ->
                FirewallManager.removeDomainRule(value, scope)
                SettingsChangeManager.makeRestartService()
                reload()
            }
        }
        layout.setEndIconOnClickListener {
            commit(field) { value ->
                FirewallManager.setDomainRule(value, scope, status)
                SettingsChangeManager.makeRestartService()
                reload()
            }
        }
        field.setOnEditorActionListener { _, _, _ ->
            commit(field) { value ->
                FirewallManager.setDomainRule(value, scope, status)
                SettingsChangeManager.makeRestartService()
                reload()
            }
            true
        }
        reload()
    }

    fun bindIpSection(
        layout: TextInputLayout,
        field: TextInputEditText,
        group: ChipGroup,
        scope: String,
        status: DestRuleStatus,
    ) {
        fun reload() {
            val values = when (scope) {
                FirewallPolicyStore.SCOPE_GLOBAL -> FirewallManager.globalIps()
                else -> FirewallManager.appIps(scope)
            }.filter { it.status == status }.map { it.ip }
            render(group, values) { value ->
                FirewallManager.removeIpRule(value, scope)
                SettingsChangeManager.makeRestartService()
                reload()
            }
        }
        layout.setEndIconOnClickListener {
            commit(field) { value ->
                FirewallManager.setIpRule(value, scope, status)
                SettingsChangeManager.makeRestartService()
                reload()
            }
        }
        field.setOnEditorActionListener { _, _, _ ->
            commit(field) { value ->
                FirewallManager.setIpRule(value, scope, status)
                SettingsChangeManager.makeRestartService()
                reload()
            }
            true
        }
        reload()
    }

    private fun commit(field: TextInputEditText, onValue: (String) -> Unit) {
        val value = field.text?.toString()?.trim().orEmpty()
        field.text = null
        if (value.isEmpty()) return
        onValue(value)
    }

    private fun render(group: ChipGroup, values: List<String>, onRemove: (String) -> Unit) {
        group.removeAllViews()
        values.forEach { value ->
            val chip = LayoutInflater.from(group.context)
                .inflate(R.layout.item_chip_domain_rule, group, false) as Chip
            chip.text = value
            chip.setOnCloseIconClickListener { onRemove(value) }
            group.addView(chip)
        }
    }
}
