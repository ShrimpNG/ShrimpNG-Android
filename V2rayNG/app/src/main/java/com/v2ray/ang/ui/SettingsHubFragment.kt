package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSettingsHubBinding
import com.v2ray.ang.util.Utils

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
        // Until in-app updates exist, releases are announced in the Telegram channel.
        binding.rowCheckUpdate.setOnClickListener {
            Utils.openUri(ownerActivity, AppConfig.TG_CHANNEL_URL)
        }
        binding.rowLogcat.setOnClickListener {
            startActivity(Intent(ownerActivity, LogsActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(ownerActivity, AboutActivity::class.java))
        }
    }
}
