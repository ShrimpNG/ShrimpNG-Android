package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.DeveloperMode
import com.v2ray.ang.util.Utils

class AboutActivity : BaseActivity() {
    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))

        binding.layoutSoureCcode.setOnClickListener {
            toast(R.string.shrimp_coming_soon)
        }

        binding.layoutFeedback.setOnClickListener {
            toast(R.string.shrimp_coming_soon)
        }

        binding.layoutOssLicenses.setOnClickListener {
            val webView = android.webkit.WebView(this)
            webView.loadUrl("file:///android_asset/open_source_licenses.html")
            android.app.AlertDialog.Builder(this)
                .setTitle("Open source licenses")
                .setView(webView)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        binding.layoutIconCredits.setOnClickListener {
            Utils.openUri(this, AppConfig.FOSSIFY_CALCULATOR_URL)
        }

        binding.layoutTgChannel.setOnClickListener {
            Utils.openUri(this, AppConfig.TG_CHANNEL_URL)
        }

        binding.layoutPrivacyPolicy.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
        }

        "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }
        BuildConfig.APPLICATION_ID.also {
            binding.tvAppId.text = it
        }

        binding.layoutVersion.setOnClickListener {
            when (val result = DeveloperMode.onVersionTapped()) {
                is DeveloperMode.TapResult.Enabled -> toast(R.string.toast_developer_mode_enabled)
                is DeveloperMode.TapResult.StepsAway -> toast(getString(R.string.toast_developer_mode_steps, result.remaining))
                DeveloperMode.TapResult.AlreadyEnabled, DeveloperMode.TapResult.Silent -> Unit
            }
        }
    }
}