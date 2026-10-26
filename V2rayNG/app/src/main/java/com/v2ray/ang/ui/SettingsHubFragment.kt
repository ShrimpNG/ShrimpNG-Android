package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSettingsHubBinding
import com.v2ray.ang.handler.AppUpdateManager

/**
 * Настройки: everything that used to live in the hamburger drawer, minus Подписки/Правила
 * (which are now their own tabs). Each row just opens the existing, untouched Activity.
 */
class SettingsHubFragment : BaseFragment<FragmentSettingsHubBinding>() {

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentSettingsHubBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        keepClearOfDock(listOf(binding.root))
        binding.rowAppSettings.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, SettingsActivity::class.java))
        }
        binding.rowThemes.setOnClickListener {
            startActivity(Intent(requireContext(), AppIconActivity::class.java))
        }
        binding.rowPerAppProxy.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, PerAppProxyActivity::class.java))
        }
        binding.rowExpert.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(
                Intent(ownerActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_EXPERT)
            )
        }
        binding.rowUserAssets.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, UserAssetActivity::class.java))
        }
        binding.rowBackupRestore.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, BackupActivity::class.java))
        }
        binding.rowCheckUpdate.setOnClickListener {
            startActivity(Intent(ownerActivity, UpdatesActivity::class.java))
        }
        binding.rowLogcat.setOnClickListener {
            startActivity(Intent(ownerActivity, LogsActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(ownerActivity, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderUpdateBadge()
    }

    // Tabs are shown and hidden, not resumed: the badge is refreshed on the way back too.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) renderUpdateBadge()
    }

    private fun renderUpdateBadge() {
        view ?: return
        binding.tvUpdateBadge.visibility =
            if (AppUpdateManager.availableUpdate(requireContext()) != null) View.VISIBLE else View.GONE
    }
}
