package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.ConnectionTimer
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Главное: connect hub + a Happ-style card for whichever subscription is "active"
 * ([MainViewModel.subscriptionId]) + that subscription's servers only. Choosing which
 * subscription is active happens on the Подписки tab ([SubscriptionsFragment]).
 */
class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var serverAdapter: HomeServerAdapter
    private val subscriptionCardAdapter = SubscriptionCardAdapter()

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            binding.connectionHub.tvConnectionTimer.text = getString(R.string.shrimp_connection_timer_inline, ConnectionTimer.formattedElapsed())
            timerHandler.postDelayed(this, 1000L)
        }
    }

    private val shareMethod: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method)
    }
    private val shareMethodMore: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method_more)
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            ownerActivity.restartV2Ray()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        serverAdapter = HomeServerAdapter(ActivityAdapterListener())
        binding.recyclerServers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerServers.adapter = androidx.recyclerview.widget.ConcatAdapter(subscriptionCardAdapter, serverAdapter)

        binding.connectionHub.btnConnection.apply {
            setFillColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer))
            setIconTint(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer))
            setOnClickListener {
                applyRunningState(isLoading = true, isRunning = false)
                ownerActivity.connectOrDisconnect()
            }
        }

        mainViewModel.isRunning.observe(viewLifecycleOwner) { isRunning -> applyRunningState(false, isRunning == true) }
        mainViewModel.updateListAction.observe(viewLifecycleOwner) { refreshServerList() }
        // ensureActiveSubscription first: when this is the very first subscription there is no
        // active one yet, and refreshing alone would just redraw the empty state.
        mainViewModel.subscriptionsChangedAction.observe(viewLifecycleOwner) {
            ensureActiveSubscription()
            refreshServerList()
        }
        mainViewModel.updateTestResultAction.observe(viewLifecycleOwner) { ownerActivity.toast(it) }

        binding.connectionHub.btnConnectionMenu.setOnClickListener { showOverflowMenu(it) }
    }

    override fun onResume() {
        super.onResume()
        ensureActiveSubscription()
        refreshServerList()
    }

    override fun onDestroyView() {
        timerHandler.removeCallbacks(timerTick)
        super.onDestroyView()
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val hub = binding.connectionHub
        if (isLoading) {
            // Keep whatever icon/shape is already showing — the transition to the real
            // play/stop state happens once isRunning resolves, no intermediate glyph.
            hub.tvConnectionStatus.text = getString(R.string.shrimp_connecting)
            return
        }

        timerHandler.removeCallbacks(timerTick)
        hub.btnConnection.setMorphed(isRunning)
        hub.btnConnection.setSpinning(isRunning)
        if (isRunning) {
            hub.btnConnection.setIconResourceAnimated(R.drawable.ic_stop_24dp)
            hub.tvConnectionStatus.text = getString(R.string.connection_connected)
            hub.tvConnectionDetail.text = getString(R.string.shrimp_tap_to_disconnect)
            hub.tvConnectionTimer.visibility = View.VISIBLE
            timerHandler.post(timerTick)
        } else {
            hub.btnConnection.setIconResourceAnimated(R.drawable.ic_play_24dp)
            hub.tvConnectionStatus.text = getString(R.string.connection_not_connected)
            hub.tvConnectionDetail.text = getString(R.string.shrimp_tap_to_connect)
            hub.tvConnectionTimer.visibility = View.GONE
        }
    }

    private fun ensureActiveSubscription() {
        val current = MmkvManager.decodeSubscription(mainViewModel.subscriptionId)
        if (current != null && !current.hiddenFromHome) return
        val first = MmkvManager.decodeVisibleSubscriptions().firstOrNull { !it.subscription.hiddenFromHome }?.guid.orEmpty()
        mainViewModel.subscriptionIdChanged(first)
    }

    fun refreshServerList() {
        val subItem = MmkvManager.decodeSubscription(mainViewModel.subscriptionId)
        if (subItem == null) {
            binding.layoutActiveSubscription.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
            bindEmptyState()
            return
        }

        binding.layoutActiveSubscription.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        bindSubscriptionChips(mainViewModel.subscriptionId)
        bindSubscriptionCard(subItem)
        serverAdapter.submitList(mainViewModel.serversCache)
    }

    private fun bindSubscriptionChips(activeGuid: String) {
        val subs = MmkvManager.decodeVisibleSubscriptions().filter { !it.subscription.hiddenFromHome }
        if (subs.size <= 1) {
            binding.scrollSubscriptionChips.visibility = View.GONE
            return
        }

        binding.scrollSubscriptionChips.visibility = View.VISIBLE
        val chipGroup = binding.chipGroupSubscriptions
        chipGroup.removeAllViews()
        subs.forEach { (guid, subItem) ->
            val chip = LayoutInflater.from(ownerActivity)
                .inflate(R.layout.item_chip_subscription, chipGroup, false) as com.google.android.material.chip.Chip
            chip.text = subItem.remarks.ifBlank { getString(R.string.shrimp_home_title) }
            chip.isChecked = guid == activeGuid
            chip.setOnClickListener {
                if (guid != mainViewModel.subscriptionId) {
                    mainViewModel.subscriptionIdChanged(guid)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun bindEmptyState() {
        if (MmkvManager.decodeVisibleSubscriptions().isNotEmpty()) {
            // Subscriptions exist, just none marked active (e.g. the active one got deleted).
            binding.tvEmptyTitle.text = getString(R.string.shrimp_no_active_subscription)
            binding.tvEmptyHint.text = getString(R.string.shrimp_no_active_subscription_hint)
            binding.btnEmptyAction.text = getString(R.string.shrimp_go_to_subscriptions)
            binding.btnEmptyAction.setOnClickListener { ownerActivity.switchToTab(R.id.nav_subscriptions) }
        } else {
            binding.tvEmptyTitle.text = getString(R.string.shrimp_empty_subscriptions)
            binding.tvEmptyHint.text = getString(R.string.shrimp_empty_subscriptions_hint)
            binding.btnEmptyAction.text = getString(R.string.shrimp_add_key_title)
            binding.btnEmptyAction.setOnClickListener {
                AddKeyBottomSheet().show(childFragmentManager, "AddKeyBottomSheet")
            }
        }
    }

    private fun bindSubscriptionCard(subItem: SubscriptionItem) {
        subscriptionCardAdapter.bind { card -> bindSubscriptionCardView(card, subItem) }
    }

    private fun bindSubscriptionCardView(card: com.v2ray.ang.databinding.LayoutSubscriptionCardBinding, subItem: SubscriptionItem) {
        card.tvSubTitle.text = subItem.remarks.ifBlank { getString(R.string.shrimp_home_title) }

        val hasUrl = !subItem.url.isBlank()
        card.btnSubRefresh.visibility = if (hasUrl) View.VISIBLE else View.GONE
        card.btnSubInfo.visibility = if (hasUrl) View.VISIBLE else View.GONE
        card.btnSubRefresh.setOnClickListener { refreshActiveSubscription() }
        card.btnSubPing.setOnClickListener { mainViewModel.testAllRealPing() }
        card.btnSubMore.setOnClickListener { showSubscriptionMoreMenu() }
        card.btnSubInfo.setOnClickListener { openActiveSubscriptionLink() }
        card.btnSubSupport.setOnClickListener { openActiveSubscriptionSupport() }

        card.tvSubMeta.text = buildMetaLine(subItem)

        val description = subItem.description?.takeIf { it.isNotBlank() }
        card.tvSubDescription.visibility = if (description != null) View.VISIBLE else View.GONE
        card.tvSubDescription.text = description.orEmpty()

        val supportUrl = subItem.supportUrl?.takeIf { it.isNotBlank() }
        card.btnSubSupport.visibility = if (supportUrl != null) View.VISIBLE else View.GONE

        card.layoutTraffic.visibility = if (hasUrl || supportUrl != null) View.VISIBLE else View.GONE

        val total = subItem.trafficTotalBytes
        val used = subItem.trafficUsedBytes
        when {
            total != null && total > 0 -> {
                val usedSafe = (used ?: 0L).coerceIn(0L, total)
                card.layoutTrafficBar.visibility = View.VISIBLE
                card.layoutTraffic.visibility = View.VISIBLE
                card.tvTrafficText.text = getString(R.string.shrimp_traffic_used_total, formatBytes(usedSafe), formatBytes(total))
                card.layoutTrafficBar.post { setTrafficFillFraction(card, usedSafe.toFloat() / total.toFloat()) }
            }

            used != null -> {
                // No cap reported by the provider, but usage is known — show it against an
                // infinity mark rather than hiding the bar entirely.
                card.layoutTrafficBar.visibility = View.VISIBLE
                card.layoutTraffic.visibility = View.VISIBLE
                card.tvTrafficText.text = getString(R.string.shrimp_traffic_used_unlimited, formatBytes(used))
                // Unlimited traffic — the bar is just a frame around the ∞ mark, no fill.
                card.layoutTrafficBar.post { setTrafficFillFraction(card, 0f) }
            }

            else -> card.layoutTrafficBar.visibility = View.GONE
        }

        val expireAt = subItem.expireAtSeconds
        when {
            expireAt == null -> card.tvSubExpiry.visibility = View.GONE
            expireAt <= 0 -> {
                card.tvSubExpiry.visibility = View.VISIBLE
                card.tvSubExpiry.text = getString(R.string.shrimp_never_expires)
            }
            else -> {
                card.tvSubExpiry.visibility = View.VISIBLE
                card.tvSubExpiry.text = getString(R.string.shrimp_expires_on, Utils.formatTimestamp(expireAt * 1000, pattern = "dd.MM.yyyy"))
            }
        }
    }

    private fun setTrafficFillFraction(card: com.v2ray.ang.databinding.LayoutSubscriptionCardBinding, fraction: Float) {
        val fill = card.viewTrafficFill
        val targetWidth = (card.layoutTrafficBar.width * fraction.coerceIn(0f, 1f)).roundToInt()
        val startWidth = fill.layoutParams.width.coerceAtLeast(0)
        ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 400L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                val params = fill.layoutParams
                params.width = animator.animatedValue as Int
                fill.layoutParams = params
            }
        }.start()
    }

    private fun buildMetaLine(subItem: SubscriptionItem): String {
        if (subItem.lastUpdated <= 0) return ""
        val updated = Utils.formatTimestamp(subItem.lastUpdated)
        if (!subItem.autoUpdate) return updated
        val hours = (subItem.updateInterval / 60.0)
        val intervalText = if (hours >= 1) {
            val amount = if (hours == hours.roundToInt().toDouble()) {
                hours.roundToInt().toString()
            } else {
                String.format(Locale.getDefault(), "%.1f", hours)
            }
            getString(R.string.shrimp_interval_hours, amount)
        } else {
            getString(R.string.shrimp_interval_minutes, subItem.updateInterval)
        }
        return getString(R.string.shrimp_subscription_meta_autoupdate, updated, intervalText)
    }

    private fun formatBytes(bytes: Long): String {
        val units = resources.getStringArray(R.array.shrimp_byte_units)
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${bytes} ${units[0]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }

    private fun refreshActiveSubscription() {
        val subId = mainViewModel.subscriptionId
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
            }
            handleUpdateResult(result)
            mainViewModel.reloadServerList()
        }
    }

    private fun openActiveSubscriptionLink() {
        val subItem = MmkvManager.decodeSubscription(mainViewModel.subscriptionId) ?: return
        subItem.url.takeIf { it.isNotBlank() }?.let { Utils.openUri(ownerActivity, it) }
    }

    private fun openActiveSubscriptionSupport() {
        val subItem = MmkvManager.decodeSubscription(mainViewModel.subscriptionId) ?: return
        subItem.supportUrl?.takeIf { it.isNotBlank() }?.let { Utils.openUri(ownerActivity, it) }
    }

    private fun showSubscriptionMoreMenu() {
        val subItem = MmkvManager.decodeSubscription(mainViewModel.subscriptionId) ?: return
        val options = arrayOf(getString(R.string.shrimp_refresh_subscription), getString(R.string.logcat_copy))
        AlertDialog.Builder(ownerActivity).setItems(options) { _, i ->
            when (i) {
                0 -> refreshActiveSubscription()
                1 -> {
                    Utils.setClipboard(ownerActivity, subItem.url)
                    ownerActivity.toastSuccess(R.string.toast_success)
                }
            }
        }.show()
    }

    private fun handleUpdateResult(result: SubscriptionUpdateResult) {
        when {
            result.successCount > 0 -> {
                ownerActivity.toastSuccess(getString(R.string.title_update_config_count, result.configCount))
            }
            result.failureCount > 0 -> ownerActivity.toastError(R.string.toast_failure)
            result.skipCount > 0 -> ownerActivity.toast(R.string.title_sub_setting)
        }
    }

    fun scrollToSelectedServer() {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isEmpty()) {
            ownerActivity.toast(R.string.title_file_chooser)
            return
        }
        val position = mainViewModel.serversCache.indexOfFirst { it.guid == selectedGuid }
        if (position >= 0) {
            binding.recyclerServers.smoothScrollToPosition(position)
        } else {
            ownerActivity.toast(R.string.toast_server_not_found_in_group)
        }
    }

    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            if (mainViewModel.isRunning.value == true) {
                ownerActivity.restartV2Ray()
            }
            serverAdapter.notifyDataSetChanged()
        }
    }

    private fun removeServer(guid: String) {
        val removeAction = {
            if (mainViewModel.getPosition(guid) >= 0) {
                stopIfRemovingActiveServer(guid)
                mainViewModel.removeServer(guid)
                refreshServerList()
            }
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> removeAction() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            removeAction()
        }
    }

    /** Deleting the currently-selected server can't restart into it — stop the VPN first. */
    private fun stopIfRemovingActiveServer(guid: String) {
        if (guid == MmkvManager.getSelectServer() && mainViewModel.isRunning.value == true) {
            ownerActivity.connectOrDisconnect()
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            else -> ServerActivity::class.java
        }

        val intent = Intent(ownerActivity, activityClass)
            .putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value == true)
            .putExtra("createConfigType", profile.configType.value)
            .putExtra("subscriptionId", mainViewModel.subscriptionId)

        launcher.launch(intent)
    }

    private fun shareServer(guid: String, profile: ProfileItem, position: Int, shareOptions: List<String>, skip: Int) {
        AlertDialog.Builder(ownerActivity).setItems(shareOptions.toTypedArray()) { _, i ->
            try {
                when (i + skip) {
                    0 -> showQRCode(guid)
                    1 -> share2Clipboard(guid)
                    2 -> shareFullContent(guid)
                    3 -> editServer(guid, profile)
                    4 -> removeServer(guid)
                    else -> ownerActivity.toast("else")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error when sharing server", e)
            }
        }.show()
    }

    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
    }

    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) {
            ownerActivity.toastSuccess(R.string.toast_success)
        } else {
            ownerActivity.toastError(R.string.toast_failure)
        }
    }

    private fun shareFullContent(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) {
                    ownerActivity.toastSuccess(R.string.toast_success)
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) = Unit
        override fun onShare(url: String) = Unit
        override fun onRefreshData() = refreshServerList()
        override fun onRemove(guid: String, position: Int) = removeServer(guid)
        override fun onEdit(guid: String, position: Int, profile: ProfileItem) = editServer(guid, profile)
        override fun onSelectServer(guid: String) = setSelectServer(guid)
        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) {
            val isCustom = profile.configType.isComplexType()
            val (shareOptions, skip) = if (more) {
                val options = if (isCustom) shareMethodMore.asList().takeLast(3) else shareMethodMore.asList()
                options to if (isCustom) 2 else 0
            } else {
                val options = if (isCustom) shareMethod.asList().takeLast(1) else shareMethod.asList()
                options to if (isCustom) 2 else 0
            }
            shareServer(guid, profile, position, shareOptions, skip)
        }
    }

    /**
     * The toolbar is title-only now — import/manage actions live in this popup, anchored to the
     * "⋯" button on the connection hub card instead.
     */
    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(ownerActivity, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_key -> { AddKeyBottomSheet().show(childFragmentManager, "AddKeyBottomSheet"); true }
                R.id.import_local -> ownerActivity.importConfigLocal()
                R.id.import_manually_policy_group -> { ownerActivity.importManually(EConfigType.POLICYGROUP.value); true }
                R.id.import_manually_proxy_chain -> { ownerActivity.importManually(EConfigType.PROXYCHAIN.value); true }
                R.id.import_manually_vmess -> { ownerActivity.importManually(EConfigType.VMESS.value); true }
                R.id.import_manually_vless -> { ownerActivity.importManually(EConfigType.VLESS.value); true }
                R.id.import_manually_ss -> { ownerActivity.importManually(EConfigType.SHADOWSOCKS.value); true }
                R.id.import_manually_socks -> { ownerActivity.importManually(EConfigType.SOCKS.value); true }
                R.id.import_manually_http -> { ownerActivity.importManually(EConfigType.HTTP.value); true }
                R.id.import_manually_trojan -> { ownerActivity.importManually(EConfigType.TROJAN.value); true }
                R.id.import_manually_wireguard -> { ownerActivity.importManually(EConfigType.WIREGUARD.value); true }
                R.id.import_manually_hysteria2 -> { ownerActivity.importManually(EConfigType.HYSTERIA2.value); true }
                R.id.export_all -> { ownerActivity.exportAll(); true }
                R.id.real_ping_all -> {
                    ownerActivity.toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                    mainViewModel.testAllRealPing()
                    true
                }
                R.id.service_restart -> { ownerActivity.restartV2Ray(); true }
                R.id.del_all_config -> { ownerActivity.delAllConfig(); true }
                R.id.del_duplicate_config -> { ownerActivity.delDuplicateConfig(); true }
                R.id.del_invalid_config -> { ownerActivity.delInvalidConfig(); true }
                R.id.sort_by_test_results -> { ownerActivity.sortByTestResults(); true }
                R.id.sub_update -> ownerActivity.importConfigViaSub()
                R.id.locate_selected_config -> { scrollToSelectedServer(); true }
                else -> false
            }
        }
        popup.show()
    }
}
