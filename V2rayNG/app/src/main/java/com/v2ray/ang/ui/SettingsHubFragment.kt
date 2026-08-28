package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSettingsHubBinding
import com.v2ray.ang.extension.toast

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
        binding.rowAppSettings.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, SettingsActivity::class.java))
        }
        binding.rowPerAppProxy.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, PerAppProxyActivity::class.java))
        }
        binding.rowUserAssets.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, UserAssetActivity::class.java))
        }
        binding.rowBackupRestore.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, BackupActivity::class.java))
        }
        binding.rowCheckUpdate.setOnClickListener {
            ownerActivity.toast(R.string.shrimp_coming_soon)
        }
        binding.rowLogcat.setOnClickListener {
            startActivity(Intent(ownerActivity, LogcatActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(ownerActivity, AboutActivity::class.java))
        }
    }
}
