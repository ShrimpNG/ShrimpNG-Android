package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityUpdatesBinding
import com.v2ray.ang.databinding.ItemUpdateReleaseBinding
import com.v2ray.ang.databinding.LayoutUpdateSwitchRowBinding
import com.v2ray.ang.dto.UpdateManifest
import com.v2ray.ang.handler.AppUpdateManager
import com.v2ray.ang.handler.AppUpdateManager.DownloadState
import com.v2ray.ang.handler.AppUpdateManager.Reason
import com.v2ray.ang.handler.AppUpdateNotifier
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Updates: this version and what is newer, the options of the background update, and the
 * changelog. All the work is AppUpdateManager's; this screen only shows where it stands.
 */
class UpdatesActivity : BaseActivity() {
    private val binding by lazy { ActivityUpdatesBinding.inflate(layoutInflater) }

    private var checking = false
    private var checkFailed = false
    private var checkHttpCode = 0
    private var installing = false
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_title))

        bindSwitch(binding.rowAutoCheck, R.string.update_auto_check, R.string.update_auto_check_summary, AppConfig.PREF_UPDATE_AUTO_CHECK) { AppUpdateManager.isAutoCheck() }
        bindSwitch(binding.rowAutoDownload, R.string.update_auto_download, R.string.update_auto_download_summary, AppConfig.PREF_UPDATE_AUTO_DOWNLOAD) { AppUpdateManager.isAutoDownload() }
        bindSwitch(binding.rowWifiOnly, R.string.update_wifi_only, R.string.update_wifi_only_summary, AppConfig.PREF_UPDATE_WIFI_ONLY) { AppUpdateManager.isWifiOnly() }
        bindSwitch(binding.rowAutoInstall, R.string.update_auto_install, R.string.update_auto_install_summary, AppConfig.PREF_UPDATE_AUTO_INSTALL) { AppUpdateManager.isAutoInstall() }
        bindSwitch(binding.rowBeta, R.string.update_beta, R.string.update_beta_summary, AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE) { AppUpdateManager.isBetaChannel() }
        if (Build.VERSION.SDK_INT < 31) {
            binding.rowAutoInstall.root.isEnabled = false
            binding.rowAutoInstall.switchToggle.isEnabled = false
            binding.rowAutoInstall.tvSummary.setText(R.string.update_auto_install_unavailable)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdateManager.download.collect { render() }
            }
        }

        if (!AppUpdateManager.isSupported()) return
        // Opened from the notification, or not looked for a while: ask now rather than show stale news.
        if (System.currentTimeMillis() - AppUpdateManager.lastCheck() > DateUtils.HOUR_IN_MILLIS) check()
    }

    override fun onResume() {
        super.onResume()
        AppUpdateNotifier.cancel(this)
        // Back from "Install unknown apps", or from Android's own install prompt.
        installing = false
        render()
    }

    private fun bindSwitch(
        row: LayoutUpdateSwitchRowBinding,
        title: Int,
        summary: Int,
        key: String,
        value: () -> Boolean,
    ) {
        row.tvTitle.setText(title)
        row.tvSummary.setText(summary)
        row.switchToggle.isChecked = value()
        row.root.setOnClickListener {
            AppUpdateManager.setSetting(this, key, !value())
            row.switchToggle.isChecked = value()
            if (key == AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE) check() else renderOptions()
        }
    }

    private fun renderOptions() {
        // Downloading on Wi-Fi only means nothing when nothing is downloaded ahead.
        val ahead = AppUpdateManager.isAutoCheck() && AppUpdateManager.isAutoDownload()
        binding.rowAutoDownload.root.isEnabled = AppUpdateManager.isAutoCheck()
        binding.rowAutoDownload.switchToggle.isEnabled = AppUpdateManager.isAutoCheck()
        binding.rowWifiOnly.root.isEnabled = ahead
        binding.rowWifiOnly.switchToggle.isEnabled = ahead
    }

    private fun check() {
        if (checking) return
        checking = true
        checkFailed = false
        render()
        lifecycleScope.launch {
            try {
                AppUpdateManager.check(this@UpdatesActivity)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "AppUpdate: check failed: ${e.message}")
                checkFailed = true
                checkHttpCode = (e as? AppUpdateManager.HttpStatusException)?.code ?: 0
            } finally {
                checking = false
                render()
            }
        }
    }

    private fun downloadAndInstall(available: AppUpdateManager.Available) {
        if (downloadJob?.isActive == true) return
        if (!AppUpdateManager.canRequestInstalls(this)) {
            askInstallPermission()
            return
        }
        downloadJob = lifecycleScope.launch {
            try {
                val apk = AppUpdateManager.download(this@UpdatesActivity, available)
                installing = true
                render()
                // Writing the APK into the installer session copies ~50 MB: off the main thread.
                withContext(Dispatchers.IO) {
                    AppUpdateManager.install(this@UpdatesActivity, apk, available.manifest.versionCode, interactive = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shown through the download state, which the manager has set.
                LogUtil.w(AppConfig.TAG, "AppUpdate: ${e.message}")
                installing = false
                render()
            }
        }
    }

    private fun askInstallPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_text)
            .setPositiveButton(R.string.update_install_permission_open) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render() {
        renderOptions()
        renderStatus()
        renderHistory()
    }

    private fun renderStatus() {
        val versionLine = getString(R.string.update_current_version, BuildConfig.VERSION_NAME, CoreNativeManager.getLibVersion())
        val available = AppUpdateManager.availableUpdate(this)
        val state = AppUpdateManager.download.value

        binding.progressDownload.visibility = View.GONE
        binding.tvStatusNotes.visibility = View.GONE
        binding.btnSecondary.visibility = View.GONE
        binding.btnPrimary.isEnabled = true
        binding.ivStatus.setImageResource(R.drawable.ic_check_update_24dp)

        if (!AppUpdateManager.isSupported()) {
            setStatus(getString(R.string.update_unsupported), versionLine)
            binding.btnPrimary.visibility = View.GONE
            return
        }

        when {
            state is DownloadState.Running -> {
                val percent = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                setStatus(
                    getString(R.string.update_downloading, percent),
                    getString(
                        R.string.update_download_progress,
                        Formatter.formatShortFileSize(this, state.done),
                        Formatter.formatShortFileSize(this, state.total),
                    ),
                )
                binding.progressDownload.isIndeterminate = false
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.setProgressCompat(percent, true)
                showNotes(available)
                binding.btnPrimary.setText(android.R.string.cancel)
                binding.btnPrimary.setOnClickListener { downloadJob?.cancel() }
            }
            installing -> {
                setStatus(getString(R.string.update_installing), versionLine)
                // Indeterminate is switched while hidden: the indicator refuses it on screen.
                binding.progressDownload.isIndeterminate = true
                binding.progressDownload.visibility = View.VISIBLE
                binding.btnPrimary.isEnabled = false
                binding.btnPrimary.setText(R.string.update_action_install)
            }
            available != null && state is DownloadState.Failed -> {
                setStatus(getString(R.string.update_failed), getString(R.string.update_failed_detail, reasonText(state)))
                showNotes(available)
                showSiteButton()
                binding.btnPrimary.setText(R.string.update_action_retry)
                binding.btnPrimary.setOnClickListener { downloadAndInstall(available) }
            }
            available != null -> {
                val ready = AppUpdateManager.downloadedApk(this, available) != null
                val manifest = available.manifest
                if (ready) {
                    setStatus(getString(R.string.update_ready), getString(R.string.update_ready_detail, manifest.versionName))
                } else {
                    val meta = getString(
                        R.string.update_available_meta,
                        Formatter.formatShortFileSize(this, available.build.size),
                        formatDate(manifest.date),
                    )
                    setStatus(
                        getString(R.string.update_available, manifest.versionName),
                        if (manifest.critical) getString(R.string.update_critical) + " · " + meta else meta,
                    )
                }
                binding.ivStatus.setImageResource(R.drawable.ic_cloud_download_24dp)
                showNotes(available)
                binding.btnPrimary.setText(if (ready) R.string.update_action_install else R.string.update_action_download)
                binding.btnPrimary.setOnClickListener { downloadAndInstall(available) }
            }
            checking -> {
                setStatus(getString(R.string.update_checking), versionLine)
                // Indeterminate is switched while hidden: the indicator refuses it on screen.
                binding.progressDownload.isIndeterminate = true
                binding.progressDownload.visibility = View.VISIBLE
                binding.btnPrimary.isEnabled = false
                binding.btnPrimary.setText(R.string.update_action_check)
            }
            checkFailed -> {
                setStatus(
                    getString(R.string.update_check_failed),
                    if (checkHttpCode > 0) getString(R.string.update_check_failed_http, checkHttpCode)
                    else getString(R.string.update_check_failed_detail),
                )
                showSiteButton()
                binding.btnPrimary.setText(R.string.update_action_retry)
                binding.btnPrimary.setOnClickListener { check() }
            }
            else -> {
                val last = AppUpdateManager.lastCheck()
                val checked = if (last > 0) {
                    getString(R.string.update_last_checked, DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                } else {
                    getString(R.string.update_never_checked)
                }
                setStatus(getString(R.string.update_latest), "$versionLine\n$checked")
                binding.btnPrimary.setText(R.string.update_action_check)
                binding.btnPrimary.setOnClickListener { check() }
            }
        }
        binding.btnPrimary.visibility = View.VISIBLE
    }

    private fun setStatus(title: String, subtitle: String) {
        binding.tvStatusTitle.text = title
        binding.tvStatusSubtitle.text = subtitle
    }

    private fun showNotes(available: AppUpdateManager.Available?) {
        val notes = available?.let { AppUpdateManager.localized(it.manifest.notes) }.orEmpty()
        if (notes.isEmpty()) return
        binding.tvStatusNotes.text = bullets(notes)
        binding.tvStatusNotes.visibility = View.VISIBLE
    }

    private fun showSiteButton() {
        binding.btnSecondary.setText(R.string.update_action_site)
        binding.btnSecondary.visibility = View.VISIBLE
        binding.btnSecondary.setOnClickListener { Utils.openUri(this, AppConfig.APP_SITE_URL + "/download/") }
    }

    private fun reasonText(state: DownloadState.Failed): String = when (state.reason) {
        Reason.NETWORK -> getString(R.string.update_reason_network)
        Reason.CORRUPT -> getString(R.string.update_reason_corrupt)
        Reason.SIGNATURE -> getString(R.string.update_reason_key)
        Reason.INSTALL -> state.message
    }

    /** Releases newer than none in particular: the latest and its history, newest first. */
    private fun renderHistory() {
        val manifest = AppUpdateManager.storedManifest()
        val releases = manifest?.let {
            listOf(UpdateManifest.Release(it.versionName, it.versionCode, it.date, it.notes)) + it.history
        }.orEmpty().distinctBy { it.versionCode }.filter { AppUpdateManager.localized(it.notes).isNotEmpty() }

        binding.listHistory.removeAllViews()
        if (releases.isEmpty()) {
            val empty = ItemUpdateReleaseBinding.inflate(layoutInflater, binding.listHistory, true)
            // The header carries the gap above the notes: gone with it, the text sits centred.
            empty.rowHeader.visibility = View.GONE
            empty.tvNotes.setText(R.string.update_history_empty)
            return
        }
        val installed = AppUpdateManager.installedVersionCode(this)
        releases.forEach { release ->
            val item = ItemUpdateReleaseBinding.inflate(layoutInflater, binding.listHistory, true)
            item.tvVersion.text = if (release.versionCode == installed) {
                "${release.versionName} · ${getString(R.string.update_installed_mark)}"
            } else {
                release.versionName
            }
            item.tvDate.text = formatDate(release.date)
            item.tvNotes.text = bullets(AppUpdateManager.localized(release.notes))
        }
    }

    private fun bullets(lines: List<String>) = lines.joinToString("\n") { "•  $it" }

    private fun formatDate(date: String): String = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date)
        if (parsed == null) date else DateFormat.getDateInstance(DateFormat.MEDIUM, resources.configuration.locales[0]).format(parsed)
    } catch (e: Exception) {
        date
    }
}
