package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.FragmentRulesBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.GeoTagReader
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Правила: routing rulesets, ported from the former RoutingSettingActivity. */
class RulesFragment : BaseFragment<FragmentRulesBinding>() {

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val viewModel: RoutingSettingsViewModel by viewModels()
    private lateinit var adapter: RoutingSettingRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    private val routingDomainStrategy: Array<out String> by lazy {
        resources.getStringArray(R.array.routing_domain_strategy)
    }
    private val presetRulesets: Array<out String> by lazy {
        resources.getStringArray(R.array.preset_rulesets)
    }

    private val simpleDomains = mutableListOf<String>()
    private val requestWifiSsidPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            maybeRequestBackgroundLocation()
        } else {
            binding.switchTrustedWifi.isChecked = false
            ownerActivity.toast(R.string.toast_permission_denied)
        }
    }

    // Android only shows "Allow all the time" as an option once ACCESS_BACKGROUND_LOCATION is
    // requested as its own separate step, after ACCESS_FINE_LOCATION is already granted — it
    // can't be requested together with the foreground permission in one call.
    private val requestBackgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            ownerActivity.toast(R.string.routing_trusted_wifi_background_location_hint)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRulesBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        keepClearOfDock(listOf(binding.scrollRules), listOf(binding.fabAddRule))
        adapter = RoutingSettingRecyclerAdapter(viewModel, ActivityAdapterListener())
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.tvDomainStrategySummary.text = getDomainStrategy()
        binding.layoutDomainStrategy.setOnClickListener { setDomainStrategy() }

        setupTrustedWifi()
        setupFirewall()
        setupUiModeToggle()
        setupSimpleMode()
        setupSimpleModeApps()

        binding.fabAddRule.setOnClickListener {
            startActivity(Intent(ownerActivity, RoutingEditActivity::class.java))
        }
        binding.btnRulesMenu.setOnClickListener { showRulesPopupMenu(it) }
    }

    private fun showRulesPopupMenu(anchor: View) {
        val popup = PopupMenu(ownerActivity, anchor)
        popup.menuInflater.inflate(R.menu.menu_routing_setting, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.import_predefined_rulesets -> { importPredefined(); true }
                R.id.import_rulesets_from_clipboard -> { importFromClipboard(); true }
                R.id.import_rulesets_from_qrcode -> importQRcode()
                R.id.export_rulesets_to_clipboard -> { export2Clipboard(); true }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Trusted Wi-Fi overrides whichever routing mode is chosen below — while connected to one of
     * the listed SSIDs, [com.v2ray.ang.core.CoreConfigManager] routes everything (including DNS)
     * direct. Reading the SSID needs location permission; if the user denies ACCESS_FINE_LOCATION
     * outright, the switch reverts itself (see [requestWifiSsidPermission]). If they grant only
     * "while in use" (not "Allow all the time"), the feature only works while the app itself is in
     * the foreground — the SSID isn't readable from CoreVpnService's background restart path
     * otherwise, so [maybeRequestBackgroundLocation] follows up asking for that too.
     */
    private fun setupTrustedWifi() {
        binding.switchTrustedWifi.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_TRUSTED_WIFI_ENABLED, false)
        binding.etTrustedWifiSsids.setText(MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_TRUSTED_WIFI_SSIDS).orEmpty())
        applyTrustedWifiInputVisibility(binding.switchTrustedWifi.isChecked)
        // Re-prompt users who enabled this before background location was needed/requested.
        if (binding.switchTrustedWifi.isChecked && hasWifiSsidPermission()) {
            maybeRequestBackgroundLocation()
        }

        binding.switchTrustedWifi.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasWifiSsidPermission()) {
                    requestWifiSsidPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    maybeRequestBackgroundLocation()
                }
            }
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_TRUSTED_WIFI_ENABLED, isChecked)
            applyTrustedWifiInputVisibility(isChecked)
        }
        binding.etTrustedWifiSsids.doAfterTextChanged {
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_TRUSTED_WIFI_SSIDS, it?.toString().orEmpty())
        }
    }

    private fun hasWifiSsidPermission(): Boolean =
        ContextCompat.checkSelfPermission(ownerActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun maybeRequestBackgroundLocation() {
        // Before Android 10 there is no separate background grant: fine location covers it.
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val hasBackground = ContextCompat.checkSelfPermission(
            ownerActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasBackground) {
            requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun applyTrustedWifiInputVisibility(enabled: Boolean) {
        binding.tilTrustedWifiSsids.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Simple mode (a friendly proxy-all/proxy-selected/bypass-selected choice) and Expert mode
     * (the free-form rule list below) share the same Xray routing engine — this toggle just
     * swaps which UI builds the routing rules. Defaults to Expert so existing users/rules are
     * unaffected until they opt into Simple mode themselves.
     */
    private fun setupUiModeToggle() {
        val isSimple = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_UI_MODE) != AppConfig.ROUTING_UI_MODE_EXPERT
        binding.toggleUiMode.check(if (isSimple) R.id.btn_mode_simple else R.id.btn_mode_expert)
        applyUiModeVisibility(isSimple)

        binding.toggleUiMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val simple = checkedId == R.id.btn_mode_simple
            MmkvManager.encodeSettings(
                AppConfig.PREF_ROUTING_UI_MODE,
                if (simple) AppConfig.ROUTING_UI_MODE_SIMPLE else AppConfig.ROUTING_UI_MODE_EXPERT
            )
            applyUiModeVisibility(simple)
        }
    }

    private fun applyUiModeVisibility(simple: Boolean) {
        binding.layoutSimpleMode.visibility = if (simple) View.VISIBLE else View.GONE
        binding.layoutExpertMode.visibility = if (simple) View.GONE else View.VISIBLE
        // Add/import/export rule actions only apply to Expert mode's free-form rule list.
        binding.fabAddRule.visibility = if (simple) View.GONE else View.VISIBLE
    }

    private fun setupSimpleMode() {
        simpleDomains.clear()
        simpleDomains.addAll(
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_SIMPLE_DOMAINS)
                ?.split(",", "\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        )
        renderDomainChips()
        setupDomainAutocomplete()
        // Ready-scenario switches must be wired (value loaded + listener attached) before the
        // mode summary's initial bind, since that bind may need to force one of them off+persist.
        setupReadyScenarios()
        setupModeSummary()
        setupSimpleTabs()
        setupQuickChips()
        setupGeoFilesTab()

        binding.btnAddDomain.setOnClickListener { addDomainFromInput() }
        binding.etSimpleDomainInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isDone) addDomainFromInput()
            isDone
        }
    }

    /** Suggests geosite:/geoip: tags read from the bundled geo asset files as the user types. */
    private fun setupDomainAutocomplete() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (geositeTags, geoipTags) = withContext(Dispatchers.IO) {
                GeoTagReader.readGeositeTags(ownerActivity) to GeoTagReader.readGeoipTags(ownerActivity)
            }
            binding.etSimpleDomainInput.setAdapter(GeoTagSuggestionAdapter(ownerActivity, geositeTags, geoipTags))
        }
    }

    private fun addDomainFromInput() {
        val value = binding.etSimpleDomainInput.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) return
        if (simpleDomains.add(value)) onDomainsChanged()
        binding.etSimpleDomainInput.text = null
    }

    private fun renderDomainChips() {
        binding.chipGroupSimpleDomains.removeAllViews()
        simpleDomains.forEach { domain ->
            val chip = LayoutInflater.from(ownerActivity)
                .inflate(R.layout.item_chip_domain_rule, binding.chipGroupSimpleDomains, false) as Chip
            chip.text = domain
            chip.setOnCloseIconClickListener {
                simpleDomains.remove(domain)
                onDomainsChanged()
            }
            binding.chipGroupSimpleDomains.addView(chip)
        }
    }

    /** Persists the shared domain/tag list and keeps every UI that reflects it in sync. */
    private fun onDomainsChanged() {
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_SIMPLE_DOMAINS, simpleDomains.joinToString(","))
        renderDomainChips()
        syncQuickChipsChecked()
    }

    /**
     * Mode picker — an expandable summary card (title/subtitle/info banner) that opens a
     * 3-choice dialog, instead of a permanent radio group, matching the reference client's
     * "Routing" header. Persists immediately on choice.
     */
    private fun setupModeSummary() {
        applyModeSummary(currentSimpleMode())
        binding.layoutSimpleModeSummary.setOnClickListener { showModePickerDialog() }
    }

    private fun currentSimpleMode(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_SIMPLE_MODE) ?: AppConfig.ROUTING_MODE_PROXY_ALL

    private fun showModePickerDialog() {
        val modes = listOf(
            AppConfig.ROUTING_MODE_PROXY_ALL,
            AppConfig.ROUTING_MODE_PROXY_SELECTED,
            AppConfig.ROUTING_MODE_BYPASS_SELECTED,
        )
        val labels = modes.map { modeTitle(it) }.toTypedArray()
        val currentIndex = modes.indexOf(currentSimpleMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ownerActivity)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_SIMPLE_MODE, modes[which])
                applyModeSummary(modes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun modeTitle(mode: String): String = when (mode) {
        AppConfig.ROUTING_MODE_PROXY_SELECTED -> getString(R.string.routing_simple_mode_proxy_selected)
        AppConfig.ROUTING_MODE_BYPASS_SELECTED -> getString(R.string.routing_simple_mode_bypass_selected)
        else -> getString(R.string.routing_simple_mode_proxy_all)
    }

    private fun modeInfo(mode: String): String = when (mode) {
        AppConfig.ROUTING_MODE_PROXY_SELECTED -> getString(R.string.routing_simple_mode_info_proxy_selected)
        AppConfig.ROUTING_MODE_BYPASS_SELECTED -> getString(R.string.routing_simple_mode_info_bypass_selected)
        else -> getString(R.string.routing_simple_mode_info_proxy_all)
    }

    private fun applyModeSummary(mode: String) {
        binding.tvSimpleModeTitle.text = modeTitle(mode)
        binding.tvSimpleModeSubtitle.text = modeInfo(mode)
        binding.tvSimpleModeInfo.text = modeInfo(mode)
        applyVpnServicesScenarioAvailability(mode)
    }

    /**
     * "Foreign services via VPN" only makes sense paired with "Only selected sites through the
     * proxy" — in the other two modes the general rule already routes everything (or everything
     * but a bypass list) through the proxy, so the scenario would be redundant at best. Disabling
     * it (and forcing it off) outside that mode avoids a confusing "it's on but seems to do
     * nothing new" state.
     */
    private fun applyVpnServicesScenarioAvailability(mode: String) {
        val available = mode == AppConfig.ROUTING_MODE_PROXY_SELECTED
        binding.switchScenarioVpnServices.isEnabled = available
        if (!available && binding.switchScenarioVpnServices.isChecked) {
            binding.switchScenarioVpnServices.isChecked = false
        }
    }

    /** Rules / Manual / GeoFiles segmented toggle — just swaps which container is visible. */
    private fun setupSimpleTabs() {
        binding.toggleSimpleTab.check(R.id.btn_tab_rules)
        applySimpleTabVisibility(R.id.btn_tab_rules)
        binding.toggleSimpleTab.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) applySimpleTabVisibility(checkedId)
        }
    }

    private fun applySimpleTabVisibility(checkedId: Int) {
        binding.layoutTabRules.visibility = if (checkedId == R.id.btn_tab_rules) View.VISIBLE else View.GONE
        binding.layoutTabManual.visibility = if (checkedId == R.id.btn_tab_manual) View.VISIBLE else View.GONE
        binding.layoutTabGeofiles.visibility = if (checkedId == R.id.btn_tab_geofiles) View.VISIBLE else View.GONE
    }

    private fun setupReadyScenarios() {
        binding.switchScenarioRuDirect.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_SCENARIO_RU_DIRECT, true)
        binding.switchScenarioVpnServices.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_SCENARIO_VPN_SERVICES, false)

        binding.switchScenarioRuDirect.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_SCENARIO_RU_DIRECT, isChecked)
        }
        binding.switchScenarioVpnServices.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_SCENARIO_VPN_SERVICES, isChecked)
        }
    }

    /** tag ("geoip:RU"/"geosite:YOUTUBE") -> pill button, for O(1) sync. */
    private val quickFilterPills = mutableMapOf<String, MaterialButton>()
    private var isSyncingQuickChips = false
    private var countriesExpanded = false
    private var servicesExpanded = false

    private fun setupQuickChips() {
        binding.listCountries.removeAllViews()
        binding.listServices.removeAllViews()
        quickFilterPills.clear()
        AppConfig.ROUTING_QUICK_COUNTRIES.forEach { (name, flag, code) ->
            addQuickFilterPill(binding.listCountries, "geoip:$code", "$flag $name")
        }
        AppConfig.ROUTING_QUICK_SERVICES.forEach { (code, name) ->
            addQuickFilterPill(binding.listServices, "geosite:$code", name)
        }
        binding.btnCountriesLabel.setOnClickListener { toggleQuickFilterList(countries = true) }
        binding.btnCountriesExpand.setOnClickListener { toggleQuickFilterList(countries = true) }
        binding.btnServicesLabel.setOnClickListener { toggleQuickFilterList(countries = false) }
        binding.btnServicesExpand.setOnClickListener { toggleQuickFilterList(countries = false) }
        binding.splitCountries.clearChecked()
        binding.splitServices.clearChecked()
        syncQuickChipsChecked()
        renderQuickFilterSummaries()
        applyQuickFilterExpandedState(animate = false)
    }

    private fun addQuickFilterPill(list: ViewGroup, tag: String, label: String) {
        val pill = LayoutInflater.from(ownerActivity)
            .inflate(R.layout.item_quick_filter_pill, list, false) as MaterialButton
        pill.text = label
        pill.isChecked = simpleDomains.contains(tag)
        pill.addOnCheckedChangeListener { _, isChecked ->
            if (isSyncingQuickChips) return@addOnCheckedChangeListener
            if (isChecked) {
                if (!simpleDomains.contains(tag)) simpleDomains.add(tag)
            } else {
                simpleDomains.remove(tag)
            }
            onDomainsChanged()
        }
        quickFilterPills[tag] = pill
        list.addView(pill)
    }

    private fun toggleQuickFilterList(countries: Boolean) {
        if (countries) {
            countriesExpanded = !countriesExpanded
            if (countriesExpanded) servicesExpanded = false
        } else {
            servicesExpanded = !servicesExpanded
            if (servicesExpanded) countriesExpanded = false
        }
        applyQuickFilterExpandedState(animate = true)
        binding.splitCountries.clearChecked()
        binding.splitServices.clearChecked()
    }

    private fun applyQuickFilterExpandedState(animate: Boolean) {
        setQuickFilterExpanded(
            list = binding.listCountries,
            chevron = binding.btnCountriesExpand,
            expanded = countriesExpanded,
            animate = animate,
        )
        setQuickFilterExpanded(
            list = binding.listServices,
            chevron = binding.btnServicesExpand,
            expanded = servicesExpanded,
            animate = animate,
        )
    }

    private fun setQuickFilterExpanded(
        list: ViewGroup,
        chevron: MaterialButton,
        expanded: Boolean,
        animate: Boolean,
    ) {
        chevron.animate().cancel()
        chevron.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(if (animate) 200L else 0L)
            .start()

        if (!animate) {
            list.visibility = if (expanded) View.VISIBLE else View.GONE
            for (i in 0 until list.childCount) {
                list.getChildAt(i).apply {
                    alpha = 1f
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            return
        }

        if (expanded) {
            list.visibility = View.VISIBLE
            list.alpha = 0f
            list.animate().cancel()
            list.animate().alpha(1f).setDuration(160L).setInterpolator(DecelerateInterpolator()).start()
            val rise = -20f * resources.displayMetrics.density
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                child.animate().cancel()
                child.alpha = 0f
                child.translationY = rise
                child.scaleX = 0.88f
                child.scaleY = 0.88f
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(i * 35L)
                    .setDuration(240L)
                    .setInterpolator(OvershootInterpolator(1.1f))
                    .start()
            }
        } else if (list.visibility == View.VISIBLE) {
            list.animate().cancel()
            list.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    list.visibility = View.GONE
                    list.alpha = 1f
                }
                .start()
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                child.animate().cancel()
                child.animate()
                    .alpha(0f)
                    .translationY(-12f * resources.displayMetrics.density)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(120L)
                    .start()
            }
        }
    }

    private fun syncQuickChipsChecked() {
        isSyncingQuickChips = true
        quickFilterPills.forEach { (tag, pill) -> pill.isChecked = simpleDomains.contains(tag) }
        isSyncingQuickChips = false
        renderQuickFilterSummaries()
    }

    private fun renderQuickFilterSummaries() {
        val countryCount = AppConfig.ROUTING_QUICK_COUNTRIES.count { (_, _, code) ->
            simpleDomains.contains("geoip:$code")
        }
        binding.btnCountriesLabel.text = if (countryCount == 0) {
            getString(R.string.routing_simple_countries_summary_none)
        } else {
            resources.getQuantityString(R.plurals.routing_simple_countries_summary, countryCount, countryCount)
        }
        val serviceCount = AppConfig.ROUTING_QUICK_SERVICES.count { (code, _) ->
            simpleDomains.contains("geosite:$code")
        }
        binding.btnServicesLabel.text = if (serviceCount == 0) {
            getString(R.string.routing_simple_services_summary_none)
        } else {
            resources.getQuantityString(R.plurals.routing_simple_services_summary, serviceCount, serviceCount)
        }
    }

    /** Shows bundled geoip.dat/geosite.dat status; full management stays in UserAssetActivity. */
    private fun setupGeoFilesTab() {
        binding.tvGeofileGeoipStatus.text = geoFileStatusText(AppConfig.GEOIP_DAT)
        binding.tvGeofileGeositeStatus.text = geoFileStatusText(AppConfig.GEOSITE_DAT)
        binding.btnManageGeofiles.setOnClickListener {
            startActivity(Intent(ownerActivity, UserAssetActivity::class.java))
        }
    }

    private fun geoFileStatusText(fileName: String): String {
        val file = java.io.File(Utils.userAssetPath(ownerActivity), fileName)
        return if (file.exists()) {
            getString(R.string.routing_geofile_status_updated, fileName, Utils.formatTimestamp(file.lastModified(), pattern = "dd.MM.yyyy"))
        } else {
            getString(R.string.routing_geofile_status_missing, fileName)
        }
    }

    /** Manual routing edits the same VPN app filter as Settings, through the same screen. */
    private fun setupSimpleModeApps() {
        // Clear listeners before restoring preferences, so returning from the shared screen
        // cannot write stale switch values back into settings.
        binding.switchSimpleAppsEnable.setOnCheckedChangeListener(null)
        binding.switchSimpleAppsBypassMode.setOnCheckedChangeListener(null)
        binding.switchSimpleAppsEnable.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        binding.switchSimpleAppsBypassMode.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
        applyAppsOptionsVisibility()
        updateAppsSummary()

        binding.switchSimpleAppsEnable.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, isChecked)
            SettingsChangeManager.makeRestartService()
            applyAppsOptionsVisibility()
            updateAppsSummary()
        }
        binding.switchSimpleAppsBypassMode.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, isChecked)
            SettingsChangeManager.makeRestartService()
            updateAppsSummary()
        }
        binding.btnSimpleAppsChoose.setOnClickListener {
            ownerActivity.requestActivityLauncher.launch(Intent(ownerActivity, PerAppProxyActivity::class.java))
        }
    }

    private fun applyAppsOptionsVisibility() {
        binding.layoutSimpleAppsOptions.visibility =
            if (binding.switchSimpleAppsEnable.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateAppsSummary() {
        if (!binding.switchSimpleAppsEnable.isChecked) {
            binding.tvSimpleAppsSummary.text = getString(R.string.routing_simple_mode_apps_summary_off)
            return
        }
        val count = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.size ?: 0
        binding.tvSimpleAppsSummary.text = when {
            count == 0 -> getString(R.string.routing_simple_mode_apps_summary_none)
            binding.switchSimpleAppsBypassMode.isChecked -> getString(R.string.routing_simple_mode_apps_summary_bypass, count)
            else -> getString(R.string.routing_simple_mode_apps_summary_only, count)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        setupSimpleModeApps()
        renderFirewallCard()
    }

    /**
     * Turning the firewall on takes over the tunnel backend, so it asks first. Everything else
     * lives on the dedicated screen — the card only owns the master switch and a summary.
     */
    private fun setupFirewall() {
        binding.switchFirewall.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (checked) confirmEnableFirewall() else {
                FirewallManager.disable()
                SettingsChangeManager.makeRestartService()
                renderFirewallCard()
            }
        }
        binding.btnFirewallOpen.setOnClickListener {
            // Through MainActivity's launcher, not startActivity: that callback is what consumes
            // the pending-restart flag, so rule changes made on the firewall screen reach the core
            // as soon as the user comes back instead of waiting for some unrelated restart.
            ownerActivity.requestActivityLauncher.launch(
                Intent(requireContext(), FirewallActivity::class.java)
            )
        }
        renderFirewallCard()
    }

    private fun confirmEnableFirewall() {
        MaterialAlertDialogBuilder(ownerActivity)
            .setTitle(R.string.shrimp_firewall_enable_title)
            .setMessage(R.string.shrimp_firewall_enable_message)
            .setPositiveButton(R.string.shrimp_firewall_enable_confirm) { _, _ ->
                FirewallManager.enable()
                SettingsChangeManager.makeRestartService()
                renderFirewallCard()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> renderFirewallCard() }
            .setOnCancelListener { renderFirewallCard() }
            .show()
    }

    private fun renderFirewallCard() {
        val enabled = FirewallManager.isEnabled()
        binding.switchFirewall.isChecked = enabled
        binding.btnFirewallOpen.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.tvFirewallSummary.text = if (enabled) {
            getString(
                R.string.shrimp_firewall_summary_on,
                FirewallManager.blockedAppCount(),
                FirewallManager.globalDomains().size,
                FirewallManager.globalIps().size,
            )
        } else {
            getString(R.string.shrimp_firewall_summary_off)
        }
    }

    private fun getDomainStrategy(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: routingDomainStrategy.first()
    }

    private fun setDomainStrategy() {
        MaterialAlertDialogBuilder(ownerActivity).setItems(routingDomainStrategy.asList().toTypedArray()) { _, i ->
            try {
                val value = routingDomainStrategy[i]
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
                binding.tvDomainStrategySummary.text = value
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set domain strategy", e)
            }
        }.show()
    }

    private fun importPredefined() {
        MaterialAlertDialogBuilder(ownerActivity).setItems(presetRulesets.asList().toTypedArray()) { _, i ->
            MaterialAlertDialogBuilder(ownerActivity).setMessage(R.string.routing_settings_import_rulesets_tip)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.resetRoutingRulesetsFromPresets(ownerActivity, i)
                            launch(Dispatchers.Main) {
                                refreshData()
                                ownerActivity.toastSuccess(R.string.toast_success)
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to import predefined ruleset", e)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.show()
    }

    private fun importFromClipboard() {
        MaterialAlertDialogBuilder(ownerActivity).setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val clipboard = try {
                    Utils.getClipboard(ownerActivity)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to get clipboard content", e)
                    ownerActivity.toastError(R.string.toast_failure)
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = SettingsManager.resetRoutingRulesets(clipboard)
                    withContext(Dispatchers.Main) {
                        if (result) {
                            refreshData()
                            ownerActivity.toastSuccess(R.string.toast_success)
                        } else {
                            ownerActivity.toastError(R.string.toast_failure)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importQRcode(): Boolean {
        ownerActivity.launchQrScanner { scanResult ->
            if (scanResult != null) {
                importRulesetsFromQRcode(scanResult)
            }
        }
        return true
    }

    private fun export2Clipboard() {
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            ownerActivity.toastError(R.string.toast_failure)
        } else {
            Utils.setClipboard(ownerActivity, JsonUtil.toJson(rulesetList))
            ownerActivity.toastSuccess(R.string.toast_success)
        }
    }

    private fun importRulesetsFromQRcode(qrcode: String?) {
        MaterialAlertDialogBuilder(ownerActivity).setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val result = SettingsManager.resetRoutingRulesets(qrcode)
                    withContext(Dispatchers.Main) {
                        if (result) {
                            refreshData()
                            ownerActivity.toastSuccess(R.string.toast_success)
                        } else {
                            ownerActivity.toastError(R.string.toast_failure)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, RoutingEditActivity::class.java)
                    .putExtra("position", position)
            )
        }

        override fun onRemove(guid: String, position: Int) = Unit
        override fun onShare(url: String) = Unit
        override fun onRefreshData() = refreshData()
    }
}
