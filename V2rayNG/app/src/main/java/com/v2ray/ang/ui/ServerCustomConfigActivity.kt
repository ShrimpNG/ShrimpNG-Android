package com.v2ray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.blacksquircle.ui.editorkit.utils.EditorTheme
import com.blacksquircle.ui.language.json.JsonLanguage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityServerCustomConfigBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.FingerprintManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

class ServerCustomConfigActivity : BaseActivity() {
    private val binding by lazy { ActivityServerCustomConfigBinding.inflate(layoutInflater) }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = EConfigType.CUSTOM.toString())

        if (!Utils.getDarkModeStatus(this)) {
            binding.editor.colorScheme = EditorTheme.INTELLIJ_LIGHT
        }
        binding.editor.language = JsonLanguage()
        val config = MmkvManager.decodeServerConfig(editGuid)
        if (config != null) {
            bindingServer(config)
        } else {
            clearServer()
        }
    }

    /**
     * Binding selected server config
     */
    private fun bindingServer(config: ProfileItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(config.remarks)
        val raw = MmkvManager.decodeServerRaw(editGuid)
        val configContent = raw.orEmpty()

        binding.editor.setTextContent(Utils.getEditable(configContent))
        setupFingerprintSpinner(config)
        return true
    }

    /**
     * The fingerprint list, with "from config" first — the default, meaning nothing is overridden.
     *
     * Offered here and not only for typed profiles because a raw config is exactly where the value
     * is otherwise unreachable: it sits inside the JSON the provider sends, and editing it by hand
     * would be undone by the next subscription update.
     */
    private fun setupFingerprintSpinner(config: ProfileItem) {
        val options = listOf(getString(R.string.shrimp_fingerprint_from_config)) + FingerprintManager.CANDIDATES
        binding.spFingerprint.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        val current = FingerprintManager.overrideFor(config)
        binding.spFingerprint.setSelection(
            FingerprintManager.CANDIDATES.indexOf(current).let { if (it < 0) 0 else it + 1 }
        )
    }

    /** @return the chosen fingerprint, or null for "from config". */
    private fun selectedFingerprint(): String? =
        FingerprintManager.CANDIDATES.getOrNull(binding.spFingerprint.selectedItemPosition - 1)

    /**
     * clear or init server config
     */
    private fun clearServer(): Boolean {
        binding.etRemarks.text = null
        setupFingerprintSpinner(ProfileItem.create(EConfigType.CUSTOM))
        return true
    }

    /**
     * save server config
     */
    private fun saveServer(): Boolean {
        if (TextUtils.isEmpty(binding.etRemarks.text.toString())) {
            toast(R.string.server_lab_remarks)
            return false
        }

        val profileItem = try {
            CustomFmt.parse(binding.editor.text.toString())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse custom configuration", e)
            toast("${getString(R.string.toast_malformed_josn)} ${e.cause?.message}")
            return false
        }

        val config = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.CUSTOM)
        binding.etRemarks.text.let {
            config.remarks = if (it.isNullOrEmpty()) profileItem?.remarks.orEmpty() else it.toString()
        }
        config.server = profileItem?.server
        config.serverPort = profileItem?.serverPort
        config.balancerMemberCount = profileItem?.balancerMemberCount
        config.balancerStrategyType = profileItem?.balancerStrategyType
        config.description = AngConfigManager.generateDescription(config)

        MmkvManager.encodeServerConfig(editGuid, config)
        MmkvManager.encodeServerRaw(editGuid, binding.editor.text.toString())
        // Stored beside the profile rather than inside it: the profile is rebuilt from scratch on
        // every subscription refresh, and the choice has to outlive that.
        FingerprintManager.setOverride(config, selectedFingerprint())
        if (isRunning) {
            SettingsChangeManager.makeRestartService()
        }
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    /**
     * save server config
     */
    private fun deleteServer(): Boolean {
        if (editGuid.isNotEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeServer(editGuid)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // do nothing
                }
                .show()
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)

        val delButton = menu.findItem(R.id.del_config)
        delButton?.isVisible = editGuid.isNotEmpty() && !isRunning

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteServer()
            true
        }

        R.id.save_config -> {
            saveServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
