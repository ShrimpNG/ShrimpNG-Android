package com.v2ray.ang.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AppIconManager
import com.v2ray.ang.util.DeveloperMode
import com.v2ray.ang.util.DeviceHwid
import com.v2ray.ang.util.Utils
import com.v2ray.ang.ui.widget.IconShape

class AboutActivity : BaseActivity() {
    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))

        binding.layoutSourceCode.setOnClickListener {
            Utils.openUri(this, AppConfig.SOURCE_CODE_URL)
        }

        binding.layoutOssLicenses.setOnClickListener {
            val webView = android.webkit.WebView(this)
            webView.loadUrl("file:///android_asset/open_source_licenses.html")
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_oss_license)
                .setView(webView)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .show()
        }

        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(this, AppConfig.FEEDBACK_URL)
        }

        binding.layoutSite.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_SITE_URL)
        }

        // The icon in use, the way the launcher draws it; behind the calculator disguise, the
        // shrimp — this screen is inside the app already.
        val icon = AppIconManager.current().takeUnless { it.isDisguise } ?: AppIconVariant.DEFAULT
        binding.ivAppIcon.setImageDrawable(IconShape.shaped(this, icon.previewIconRes, 96))

        bindHwid()

        binding.layoutPrivacy.setOnClickListener {
            PrivacySheet().show(supportFragmentManager, "PrivacySheet")
        }

        "v${BuildConfig.VERSION_NAME} · ${CoreNativeManager.getLibVersion()}".also {
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

    override fun onResume() {
        super.onResume()
        bindHwid()
    }

    /** The same ID [DeviceHwid] sends, custom override included — and whether it is sent at all. */
    private fun bindHwid() {
        val hwid = DeviceHwid.hardwareId(this)
        binding.tvHwid.text = hwid
        binding.tvHwidStatus.setText(
            if (DeviceHwid.isEnabled()) R.string.about_hwid_sent else R.string.about_hwid_not_sent
        )
        binding.layoutHwid.setOnClickListener { toast(R.string.about_hwid_hold_to_copy) }
        binding.layoutHwid.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Utils.setClipboard(this, hwid)
            toastSuccess(R.string.about_hwid_copied)
            true
        }
    }
}
