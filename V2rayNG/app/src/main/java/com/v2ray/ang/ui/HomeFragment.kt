package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.view.doOnLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.ConnectionTimer
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.SubscriptionExpiry
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Главное: connect hub + a card for whichever subscription is "active"
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

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            ownerActivity.restartV2Ray()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        keepClearOfDock(listOf(binding.recyclerServers, binding.layoutEmpty))
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
        mainViewModel.testingGuids.observe(viewLifecycleOwner) { serverAdapter.setTestingGuids(it) }
        mainViewModel.updateListAction.observe(viewLifecycleOwner) { position ->
            // A single measurement result redraws its own row. Rebuilding the whole list, as this
            // used to, rebinds every row still waiting on its own result — which is dozens of
            // needless rebinds over a run, and each one interrupts a running animation.
            if (position != null && position >= 0) {
                serverAdapter.notifyItemChanged(position)
            } else {
                refreshServerList()
            }
        }
        // ensureActiveSubscription first: when this is the very first subscription there is no
        // active one yet, and refreshing alone would just redraw the empty state.
        mainViewModel.subscriptionsChangedAction.observe(viewLifecycleOwner) {
            ensureActiveSubscription()
            refreshServerList()
        }

        binding.connectionHub.btnConnectionMenu.setOnClickListener { showOverflowMenu(it) }
    }

    override fun onResume() {
        super.onResume()
        ensureActiveSubscription()
        refreshServerList()
    }

    fun clearTransientUi() {
        if (::serverAdapter.isInitialized) serverAdapter.clearTransientUi()
    }

    override fun onDestroyView() {
        clearTransientUi()
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
        // The traffic bar's wave follows the connection.
        subscriptionCardAdapter.notifyItemChanged(0)
        hub.btnConnection.contentDescription = getString(if (isRunning) R.string.action_stop_service else R.string.tasker_start_service)
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
        // Shown from the first subscription on, not the second: the row also carries the "Add"
        // chip, the quickest way to a second one.
        if (subs.isEmpty()) {
            binding.scrollSubscriptionChips.visibility = View.GONE
            return
        }

        binding.scrollSubscriptionChips.visibility = View.VISIBLE
        val chipGroup = binding.chipGroupSubscriptions
        chipGroup.removeAllViews()
        var activeChip: View? = null
        subs.forEach { (guid, subItem) ->
            val chip = LayoutInflater.from(ownerActivity)
                .inflate(R.layout.item_chip_subscription, chipGroup, false) as com.google.android.material.chip.Chip
            chip.text = subItem.remarks.ifBlank { getString(R.string.shrimp_home_title) }
            chip.isChecked = guid == activeGuid
            if (guid == activeGuid) activeChip = chip
            chip.setOnClickListener {
                if (guid != mainViewModel.subscriptionId) {
                    mainViewModel.subscriptionIdChanged(guid)
                }
            }
            chipGroup.addView(chip)
        }
        LayoutInflater.from(ownerActivity).inflate(R.layout.item_chip_add_subscription, chipGroup, false).let { add ->
            add.setOnClickListener { AddSubscriptionSheet().show(childFragmentManager, "AddSubscriptionSheet") }
            chipGroup.addView(add)
        }
        activeChip?.let { focusChip(it, animate = activeGuid != lastFocusedChipGuid) }
        lastFocusedChipGuid = activeGuid
    }

    /** The subscription the chip row was last scrolled to, so a plain refresh doesn't animate. */
    private var lastFocusedChipGuid: String? = null

    /**
     * Brings the active subscription's chip to the left edge of the row — however it was chosen:
     * a chip, the dock's quick switcher, or the Подписки tab. Near the end there is not that much
     * row left, so the last ones settle against the right edge instead (the scroll clamps).
     */
    private fun focusChip(chip: View, animate: Boolean) {
        val scroller = binding.scrollSubscriptionChips
        // After the freshly added chips are laid out; before that every left is 0.
        chip.doOnLayout {
            if (animate) scroller.smoothScrollTo(chip.left, 0) else scroller.scrollTo(chip.left, 0)
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
        // A local group has no link to hand on, so nothing to share.
        card.btnSubShare.visibility = if (hasUrl) View.VISIBLE else View.GONE
        card.btnSubRefresh.setOnClickListener { refreshActiveSubscription() }
        card.btnSubPing.setOnClickListener { mainViewModel.testAllRealPing() }
        // Refresh has its own button beside this one; what was left of the old ⋮ menu was copying
        // the link, which the share sheet does along with the QR code.
        card.btnSubShare.setOnClickListener {
            val subId = mainViewModel.subscriptionId
            if (subId.isNotBlank()) ShareSubscriptionSheet.newInstance(subId).show(childFragmentManager, "ShareSubscription")
        }
        card.btnSubInfo.setOnClickListener { openActiveSubscriptionLink() }
        card.btnSubSupport.setOnClickListener { openActiveSubscriptionSupport() }

        // Hidden rather than left empty: a locally-added group has never been updated from a URL,
        // so this line has nothing to say — and an empty TextView still draws its compound
        // drawable, leaving the refresh icon floating on its own.
        val meta = buildMetaLine(subItem)
        card.tvSubMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        card.tvSubMeta.text = meta

        val description = subItem.description?.takeIf { it.isNotBlank() }
        card.tvSubDescription.visibility = if (description != null) View.VISIBLE else View.GONE
        card.tvSubDescription.text = description.orEmpty()

        val supportUrl = subItem.supportUrl?.takeIf { it.isNotBlank() }
        card.btnSubSupport.visibility = if (supportUrl != null) View.VISIBLE else View.GONE
        card.btnSubSupport.setIconResource(
            if (supportUrl != null && isTelegramLink(supportUrl)) R.drawable.ic_telegram_24dp else R.drawable.ic_support_24dp
        )

        card.layoutTraffic.visibility = if (hasUrl || supportUrl != null) View.VISIBLE else View.GONE

        val total = subItem.trafficTotalBytes
        val used = subItem.trafficUsedBytes
        when {
            total != null && total > 0 -> {
                val usedSafe = (used ?: 0L).coerceIn(0L, total)
                card.layoutTrafficBar.visibility = View.VISIBLE
                card.layoutTraffic.visibility = View.VISIBLE
                card.tvTrafficText.text = getString(R.string.shrimp_traffic_used_total, formatBytes(usedSafe), formatBytes(total))
                card.tvTrafficUnlimited.visibility = View.GONE
                showTrafficProgress(card.progressTraffic, usedSafe.toFloat() / total.toFloat(), isConnectedThrough(mainViewModel.subscriptionId))
            }

            used != null -> {
                // No cap reported by the provider, but usage is known: the amount and an
                // "unlimited" pill — a bar would have nothing to measure against.
                card.layoutTrafficBar.visibility = View.VISIBLE
                card.layoutTraffic.visibility = View.VISIBLE
                card.tvTrafficText.text = formatBytes(used)
                card.tvTrafficUnlimited.visibility = View.VISIBLE
                card.progressTraffic.visibility = View.GONE
            }

            else -> card.layoutTrafficBar.visibility = View.GONE
        }

        bindExpiry(card, subItem)
    }

    /**
     * The end date, in the error colour once the subscription has run out and orange in its last
     * [SubscriptionExpiry.SOON_DAYS] days. Once expired, a Renew button takes the traffic tile's
     * place when there is somewhere to renew; before that the tile stays and the reminder carries it.
     */
    private fun bindExpiry(card: com.v2ray.ang.databinding.LayoutSubscriptionCardBinding, subItem: SubscriptionItem) {
        val view = card.tvSubExpiry
        val expireAt = subItem.expireAtSeconds
        val stage = SubscriptionExpiry.stage(expireAt)

        val renewUrl = renewUrl(subItem)
        val renew = stage == SubscriptionExpiry.Stage.EXPIRED && renewUrl != null
        card.btnSubRenew.visibility = if (renew) View.VISIBLE else View.GONE
        card.btnSubRenew.setOnClickListener { renewUrl?.let { Utils.openUri(ownerActivity, it) } }
        if (renew) {
            card.layoutTrafficBar.visibility = View.GONE
            card.layoutTraffic.visibility = View.VISIBLE
        }
        // A trial is "ending soon" from the moment it is added; its date stays orange, but the
        // support button isn't turned into a renew button for it.
        val short = expireAt != null && expireAt > 0 && SubscriptionExpiry.isShort(subItem.addedTime, expireAt)
        bindSupportForExpiry(card.btnSubSupport, if (short) SubscriptionExpiry.Stage.NONE else stage, renewUrl)

        if (expireAt == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val date = if (expireAt > 0) Utils.formatTimestamp(expireAt * 1000, pattern = "dd.MM.yyyy") else ""
        // Orange for "soon" rather than a theme role: a warning should look like one whatever
        // palette is chosen, and no Material role is orange.
        val color = when (stage) {
            SubscriptionExpiry.Stage.EXPIRED -> MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError)
            SubscriptionExpiry.Stage.SOON, SubscriptionExpiry.Stage.DAY ->
                androidx.core.content.ContextCompat.getColor(view.context, R.color.colorExpirySoon)
            SubscriptionExpiry.Stage.NONE -> MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        view.setTextColor(color)
        view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        view.text = when {
            expireAt <= 0 -> getString(R.string.shrimp_never_expires)
            stage == SubscriptionExpiry.Stage.EXPIRED -> getString(R.string.shrimp_expired_on, date)
            stage == SubscriptionExpiry.Stage.DAY -> getString(R.string.shrimp_expires_within_day, date)
            stage == SubscriptionExpiry.Stage.SOON -> {
                val days = SubscriptionExpiry.daysLeft(expireAt)
                getString(R.string.shrimp_expires_in, resources.getQuantityString(R.plurals.expiry_days, days, days), date)
            }
            else -> getString(R.string.shrimp_expires_on, date)
        }
    }

    /**
     * In the last [SubscriptionExpiry.SOON_DAYS] days the support button turns into a highlighted
     * renew button (same link, which is where providers put their bot); otherwise it is put back
     * to the plain support button, since the card's views are reused.
     */
    private fun bindSupportForExpiry(
        button: com.google.android.material.button.MaterialButton,
        stage: SubscriptionExpiry.Stage,
        renewUrl: String?,
    ) {
        val soon = (stage == SubscriptionExpiry.Stage.SOON || stage == SubscriptionExpiry.Stage.DAY) && renewUrl != null
        val (container, content) = if (soon) {
            com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
        } else {
            com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(button, container))
        button.iconTint = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(button, content))
        if (!soon) {
            button.contentDescription = getString(R.string.shrimp_open_support_link)
            return
        }
        button.visibility = View.VISIBLE
        button.setIconResource(R.drawable.ic_renew_24dp)
        button.contentDescription = getString(R.string.shrimp_renew_subscription)
        button.setOnClickListener { renewUrl?.let { Utils.openUri(ownerActivity, it) } }
    }

    /** Where renewing happens: the support link (providers usually point it at their bot), else their page. */
    private fun renewUrl(subItem: SubscriptionItem): String? =
        subItem.supportUrl?.takeIf { it.isNotBlank() } ?: subItem.webPageUrl?.takeIf { it.isNotBlank() }

    /** Whether the running connection uses a server from [subId]. */
    private fun isConnectedThrough(subId: String): Boolean {
        if (mainViewModel.isRunning.value != true) return false
        val guid = MmkvManager.getSelectServer() ?: return false
        return MmkvManager.decodeServerConfig(guid)?.subscriptionId == subId
    }

    /**
     * The share of the cap used; the bar turns the error colour once 90% of it is gone. Flat while
     * idle, a small travelling wave while this subscription carries the connection. The library's
     * default instead switched the wave on with a jump once the progress passed 10%.
     */
    private fun showTrafficProgress(bar: LinearProgressIndicator, fraction: Float, live: Boolean) {
        val density = resources.displayMetrics.density
        bar.waveAmplitude = if (live) (1.5f * density).roundToInt() else 0
        bar.waveSpeed = if (live) (24 * density).roundToInt() else 0
        val nearlyOut = fraction >= 0.9f
        bar.setIndicatorColor(
            MaterialColors.getColor(bar, if (nearlyOut) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary)
        )
        bar.visibility = View.VISIBLE
        bar.setProgressCompat((bar.max * fraction.coerceIn(0f, 1f)).roundToInt(), true)
    }

    private fun buildMetaLine(subItem: SubscriptionItem): String {
        if (subItem.lastUpdated <= 0) return ""
        // Localized and without the year, unlike the shared formatter's "yyyy-MM-dd HH:mm": this
        // line shares one narrow row with the refresh button, and the year was costing five
        // characters that pushed the update interval past the ellipsis in longer languages.
        val updated = DateUtils.formatDateTime(
            requireContext(),
            subItem.lastUpdated,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
        )
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
        // The provider's page when it names one, else the subscription link as before.
        (subItem.webPageUrl?.takeIf { it.isNotBlank() } ?: subItem.url.takeIf { it.isNotBlank() })
            ?.let { Utils.openUri(ownerActivity, it) }
    }

    /** Support in Telegram gets Telegram's icon; anything else, the generic headset. */
    private fun isTelegramLink(url: String): Boolean {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme.equals("tg", ignoreCase = true)) return true
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return host == "t.me" || host == "telegram.me" || host == "telegram.dog"
    }

    private fun openActiveSubscriptionSupport() {
        val subItem = MmkvManager.decodeSubscription(mainViewModel.subscriptionId) ?: return
        subItem.supportUrl?.takeIf { it.isNotBlank() }?.let { Utils.openUri(ownerActivity, it) }
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
            MaterialAlertDialogBuilder(ownerActivity).setMessage(R.string.del_config_comfirm)
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

        override fun onPingServer(guid: String) = mainViewModel.testSingleServerRealPing(guid)
        override fun onToggleFavorite(profile: ProfileItem) {
            // Rebuild rather than redraw the one row: starring moves the server to the top.
            mainViewModel.updateCache()
            refreshServerList()
        }
        override fun onCopyJson(guid: String) = shareFullContent(guid)
        override fun onCopyLink(guid: String) = share2Clipboard(guid)
    }

    /**
     * The toolbar is title-only now — import/manage actions live in this popup, anchored to the
     * "⋯" button on the connection hub card instead.
     */
    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(ownerActivity, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        // Reconnecting means something only while connected.
        popup.menu.findItem(R.id.service_restart)?.isVisible = mainViewModel.isRunning.value == true
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.service_restart -> { ownerActivity.restartV2Ray(); true }
                R.id.sort_by_test_results -> { ownerActivity.sortByTestResults(); true }
                R.id.locate_selected_config -> { scrollToSelectedServer(); true }
                else -> false
            }
        }
        popup.show()
    }
}
