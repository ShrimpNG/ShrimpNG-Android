package com.v2ray.ang.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityRoutingEditBinding
import com.v2ray.ang.databinding.ItemOutboundTagBinding
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoutingEditActivity : BaseActivity() {
    private val binding by lazy { ActivityRoutingEditBinding.inflate(layoutInflater) }
    private val position by lazy { intent.getIntExtra("position", -1) }
    private val processPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPackages = AppPickerActivity.getSelectedPackages(result.data)
            binding.etProcess.text = Utils.getEditable(selectedPackages.joinToString(","))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.routing_settings_rule_title))

        setupOutboundTagInput()
        setupProcessPicker()

        val rulesetItem = SettingsManager.getRoutingRuleset(position)
        if (rulesetItem != null) {
            bindingServer(rulesetItem)
        } else {
            clearServer()
        }

        SettingsManager.canUseProcessRouting().let { canUse ->
            binding.tilProcess.isEnabled = canUse
        }
    }

    private fun setupProcessPicker() {
        binding.tilProcess.setEndIconOnClickListener {
            processPickerLauncher.launch(
                AppPickerActivity.createIntent(
                    context = this,
                    selectedPackages = getSelectedProcessPackages(),
                    title = getString(R.string.routing_settings_process)
                )
            )
        }
    }

    private fun getSelectedProcessPackages(): List<String> {
        return binding.etProcess.text
            .toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Sets up the AutoCompleteTextView for outbound tag: the built-in tags followed by every
     * profile that can actually serve as a rule target, grouped by subscription.
     *
     * The field's end icon shows the full list without typing.
     */
    private fun setupOutboundTagInput() {
        val profileOptions = SettingsManager.getRoutingOutboundOptions()
        val builtin = BUILTIN_OUTBOUND_TAGS.map { SettingsManager.RoutingOutboundOption(it, null) }
        val options = (builtin + profileOptions).distinctBy { it.tag }

        // The subscription name only earns its place when it distinguishes one server from
        // another; with a single subscription it would repeat down the whole list. Servers
        // outside any subscription count as a group of their own here.
        val showSubscription = profileOptions.map { it.subscription }.distinct().size > 1

        binding.spOutboundTag.setAdapter(OutboundTagAdapter(this, options, showSubscription))
        // threshold=0 means show all suggestions even before typing; still need focus+request
        binding.spOutboundTag.threshold = 0

        // The end icon shows the full suggestion list
        binding.tilOutboundTag.setEndIconOnClickListener {
            binding.spOutboundTag.requestFocus()
            binding.spOutboundTag.showDropDown()
        }
        // Also show on field click when it already has focus
        binding.spOutboundTag.setOnClickListener {
            binding.spOutboundTag.showDropDown()
        }
    }

    private fun bindingServer(rulesetItem: RulesetItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(rulesetItem.remarks)
        binding.chkLocked.isChecked = rulesetItem.locked == true
        binding.etDomain.text = Utils.getEditable(rulesetItem.domain?.joinToString(","))
        binding.etIp.text = Utils.getEditable(rulesetItem.ip?.joinToString(","))
        binding.etProcess.text = Utils.getEditable(rulesetItem.process?.joinToString(","))
        binding.etPort.text = Utils.getEditable(rulesetItem.port)
        binding.etProtocol.text = Utils.getEditable(rulesetItem.protocol?.joinToString(","))
        binding.etNetwork.text = Utils.getEditable(rulesetItem.network)
        // Set text directly; filter won't fire because we're not using setText(filter=true)
        binding.spOutboundTag.setText(rulesetItem.outboundTag, false)
        return true
    }

    private fun clearServer(): Boolean {
        binding.etRemarks.text = null
        binding.spOutboundTag.setText(BUILTIN_OUTBOUND_TAGS.first(), false)
        return true
    }

    private fun saveServer(): Boolean {
        val rulesetItem = SettingsManager.getRoutingRuleset(position) ?: RulesetItem()

        rulesetItem.apply {
            remarks = binding.etRemarks.text.toString()
            locked = binding.chkLocked.isChecked
            domain = binding.etDomain.text.toString().nullIfBlank()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ip = binding.etIp.text.toString().nullIfBlank()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            process = binding.etProcess.text.toString().nullIfBlank()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            protocol = binding.etProtocol.text.toString().nullIfBlank()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            port = binding.etPort.text.toString().nullIfBlank()
            network = binding.etNetwork.text.toString().nullIfBlank()
            outboundTag = binding.spOutboundTag.text.toString().trim().ifEmpty { TAG_PROXY }
        }

        if (rulesetItem.remarks.isNullOrEmpty()) {
            toast(R.string.sub_setting_remarks)
            return false
        }

        SettingsManager.saveRoutingRuleset(position, rulesetItem)
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }


    private fun deleteServer(): Boolean {
        if (position >= 0) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        SettingsManager.removeRoutingRuleset(position)
                        launch(Dispatchers.Main) {
                            finish()
                        }
                    }
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
        val delConfig = menu.findItem(R.id.del_config)

        if (position < 0) {
            delConfig?.isVisible = false
        }

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

/**
 * Renders one suggestion as "name + subscription chip".
 *
 * ArrayAdapter filters on, and commits to the text field, each item's toString(), which
 * [SettingsManager.RoutingOutboundOption] defines as the bare tag — so the chip stays a purely
 * visual annotation and never leaks into the saved rule.
 */
private class OutboundTagAdapter(
    context: Context,
    options: List<SettingsManager.RoutingOutboundOption>,
    private val showSubscription: Boolean,
) : ArrayAdapter<SettingsManager.RoutingOutboundOption>(context, R.layout.item_outbound_tag, options) {

    // The popup list goes through getView; getDropDownView is overridden for the cases that
    // don't, so the two can never drift apart.
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindRow(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindRow(position, convertView, parent)

    private fun bindRow(position: Int, convertView: View?, parent: ViewGroup): View {
        val rowBinding = convertView?.let { ItemOutboundTagBinding.bind(it) }
            ?: ItemOutboundTagBinding.inflate(LayoutInflater.from(context), parent, false)
        val item = getItem(position)
        rowBinding.tvOutboundTag.text = item?.tag
        val subscription = item?.subscription
        rowBinding.tvOutboundSubscription.text = subscription
        rowBinding.tvOutboundSubscription.isVisible = showSubscription && !subscription.isNullOrEmpty()
        return rowBinding.root
    }
}
