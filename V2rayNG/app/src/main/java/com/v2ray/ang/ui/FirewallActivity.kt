package com.v2ray.ang.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityFirewallBinding
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager

/** Firewall: per-app policies and global domain/IP rules. */
class FirewallActivity : BaseActivity() {

    private val binding by lazy { ActivityFirewallBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.shrimp_firewall_title),
        )

        binding.chipConnectionLog.setOnCheckedChangeListener { chip, checked ->
            if (!chip.isPressed) return@setOnCheckedChangeListener
            FirewallManager.setLogEnabled(checked)
            // The TCP sentinel is part of Xray's routing config.
            SettingsChangeManager.makeRestartService()
            renderLogSwitch()
        }

        binding.pagerFirewall.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) FirewallAppsFragment() else FirewallTargetsFragment()
        }

        binding.toggleFirewallPage.check(R.id.btn_page_apps)
        binding.toggleFirewallPage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.pagerFirewall.setCurrentItem(
                if (checkedId == R.id.btn_page_apps) 0 else 1,
                true,
            )
        }
        binding.pagerFirewall.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.toggleFirewallPage.check(
                        if (position == 0) R.id.btn_page_apps else R.id.btn_page_targets
                    )
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        val misconfigured = FirewallManager.isEnabled() && !SettingsManager.isFirewallEffective()
        binding.tvInactiveWarning.visibility = if (misconfigured) View.VISIBLE else View.GONE
        renderLogSwitch()
    }

    private fun renderLogSwitch() {
        val enabled = FirewallManager.isLogEnabled()
        binding.chipConnectionLog.isChecked = enabled
        binding.chipConnectionLog.isEnabled = FirewallManager.isEnabled()
        binding.tvConnectionLogSummary.setText(
            if (enabled) R.string.shrimp_firewall_log_summary_on
            else R.string.shrimp_firewall_log_summary_off
        )
    }
}
