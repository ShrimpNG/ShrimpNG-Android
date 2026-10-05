package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.widget.GroupedPreferenceFragment
import com.v2ray.ang.util.DeveloperMode
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expert = intent.getStringExtra(EXTRA_SCREEN) == SCREEN_EXPERT
        setContentViewWithToolbar(
            R.layout.activity_settings,
            showHomeAsUp = true,
            title = getString(if (expert) R.string.title_expert_settings else R.string.title_settings)
        )
        // Opened straight on the expert screen from the settings hub: it replaces the basic one
        // rather than stacking on it, so back leaves to the hub.
        if (savedInstanceState == null && expert) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_settings, SettingsExpertFragment())
                .commitNow()
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment!!)
        fragment.arguments = pref.extras
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_settings, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    /**
     * Basic, user-facing settings. The developer/advanced screen is reached via a single
     * entry that only shows once [DeveloperMode] has been unlocked (10 taps on the version
     * number in About).
     */
    class SettingsFragment : GroupedPreferenceFragment() {

        private val developerSettingsEntry by lazy { findPreference<Preference>("pref_open_developer_settings") }
        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(R.xml.pref_settings)

            initPreferenceSummaries(preferenceScreen)
        }

        override fun onResume() {
            super.onResume()
            setScreenTitle(R.string.title_settings)
            developerSettingsEntry?.isVisible = DeveloperMode.isEnabled()
        }
    }

    /**
     * For experts: DNS, the local proxy, MTU, Mux, fragmentation and root mode — the developer
     * screen's network tuning, reachable from the settings hub without unlocking anything.
     */
    class SettingsExpertFragment : SettingsAdvancedFragment() {
        override val preferencesRes = R.xml.pref_settings_expert
        override val titleRes = R.string.title_expert_settings
    }

    /**
     * Developer settings: core and tunnel internals and test knobs. Only reachable once developer
     * mode is unlocked. Shares its wiring with [SettingsExpertFragment]: each screen finds only its
     * own preferences, and a dependency on one that lives on the other screen reads the stored value.
     */
    open class SettingsAdvancedFragment : GroupedPreferenceFragment() {

        protected open val preferencesRes = R.xml.pref_settings_developer
        protected open val titleRes = R.string.title_developer_settings

        private val customHwid by lazy { findPreference<EditTextPreference>(AppConfig.PREF_CUSTOM_HWID) }

        private val localDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val appendHttpProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_APPEND_HTTP_PROXY) }

        private val vpnDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS) }
        private val vpnBypassLan by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN) }
        private val vpnInterfaceAddress by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX) }
        private val vpnMtu by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU) }

        private val mux by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_MUX_ENABLED) }
        private val muxConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY) }
        private val muxXudpConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY) }
        private val muxXudpQuic by lazy { findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC) }

        private val fragment by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }
        private val fragmentMaxSplit by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_MAXSPLIT) }

        private val enableRootMode by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROOT_MODE_ENABLE) }
        private val lanSharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROOT_LAN_SHARING) }

        private val hevTunRwTimeout by lazy { findPreference<EditTextPreference>(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) }
        private val useHevTun by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_USE_HEV_TUNNEL) }

        private val enableLocalProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ENABLE_LOCAL_PROXY) }
        private val socksPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PORT) }
        private val dynamicSocksPort by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_DYNAMIC_SOCKS_PORT) }
        private val socksUsername by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_USERNAME) }
        private val socksPassword by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PASSWORD) }
        private val socksEnableUdp by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_SOCKS_ENABLE_UDP) }
        private val proxySharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PROXY_SHARING) }

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            // Use MMKV as the storage backend for all Preferences
            // This prevents inconsistencies between SharedPreferences and MMKV
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(preferencesRes)

            setScreenTitle(titleRes)

            initPreferenceSummaries(preferenceScreen)

            customHwid?.isEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SEND_HWID_ENABLED, true)

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            useHevTun?.setOnPreferenceChangeListener { _, newValue ->
                updateHevTunSettings(newValue as Boolean)
                true
            }

            enableLocalProxy?.setOnPreferenceChangeListener { _, newValue ->
                updateEnableLocalProxy(newValue as Boolean)
                true
            }

            dynamicSocksPort?.setOnPreferenceChangeListener { _, newValue ->
                updateDynamicSocksPort(newValue as Boolean)
                true
            }

            enableRootMode?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !RootManager.cachedRoot()) {
                    lifecycleScope.launch {
                        if (checkAndRequestRoot()) {
                            enableRootMode?.isChecked = true
                        }
                    }
                    false
                } else {
                    true
                }
            }

            lanSharing?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !RootManager.cachedRoot()) {
                    lifecycleScope.launch {
                        if (checkAndRequestRoot()) {
                            lanSharing?.isChecked = true
                        }
                    }
                    false
                } else {
                    true
                }
            }

        }

        private suspend fun checkAndRequestRoot(): Boolean {
            val hasRoot = RootManager.refresh()
            if (!isAdded) return false
            if (!hasRoot) {
                context?.toastError(R.string.toast_root_required)
            }
            return hasRoot
        }

        override fun onStart() {
            super.onStart()
            updateHevTunSettings(MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true))

            // Initialize local proxy state
            updateEnableLocalProxy(MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true))

            // Initialize mux-dependent UI states
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))

            // Initialize fragment-dependent UI states
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))

            updateDynamicSocksPort(MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false))

            val vpn = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN) == VPN
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            vpnMtu?.isEnabled = vpn
            useHevTun?.isEnabled = vpn
            if (vpn) {
                updateLocalDns(MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false))
            }
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }

        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
            fragmentMaxSplit?.isEnabled = enabled
        }

        private fun localProxyOn(): Boolean =
            enableLocalProxy?.isChecked ?: MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)

        private fun updateDynamicSocksPort(enabled: Boolean) {
            socksPort?.isEnabled = localProxyOn() && !enabled
        }

        private fun updateEnableLocalProxy(enabled: Boolean) {
            val dynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
            socksPort?.isEnabled = enabled && !dynamic
            dynamicSocksPort?.isEnabled = enabled
            socksUsername?.isEnabled = enabled
            socksPassword?.isEnabled = enabled
            socksEnableUdp?.isEnabled = enabled
            proxySharing?.isEnabled = enabled

            if (!enabled) {
                if (appendHttpProxy?.isChecked == true) {
                    appendHttpProxy?.isChecked = false
                    MmkvManager.encodeSettings(AppConfig.PREF_APPEND_HTTP_PROXY, false)
                }
                appendHttpProxy?.isEnabled = false
            } else {
                val vpn = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) == VPN
                appendHttpProxy?.isEnabled = vpn
            }
        }

        private fun updateHevTunSettings(enabled: Boolean) {
            hevTunRwTimeout?.isEnabled = enabled

            // The hev tunnel feeds traffic through the local proxy, so it keeps it on — whichever
            // screen the proxy's own switch is on.
            if (enabled) {
                if (!localProxyOn()) {
                    enableLocalProxy?.isChecked = true
                    MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
                }
                enableLocalProxy?.isEnabled = false
            } else {
                enableLocalProxy?.isEnabled = true
            }
            updateEnableLocalProxy(localProxyOn())
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_EXPERT = "expert"
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}

/**
 * Through the action bar, not Activity.title: the toolbar keeps a title set on it directly and
 * ignores later window-title changes, so the developer screen used to stay titled "Settings".
 */
private fun PreferenceFragmentCompat.setScreenTitle(@androidx.annotation.StringRes title: Int) {
    (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = getString(title)
}

/**
 * Shared preference-summary wiring: shows the current value/selection as each preference's
 * summary line, for both the basic and the advanced settings screens.
 */
private fun PreferenceFragmentCompat.initPreferenceSummaries(root: androidx.preference.PreferenceScreen?) {
    fun updateSummary(pref: Preference) {
        when (pref) {
            is EditTextPreference -> {
                if (pref.key == AppConfig.PREF_SOCKS_PASSWORD) {
                    pref.summary = if (pref.text.isNullOrEmpty()) "" else "******"
                } else if (pref.summary.isNullOrEmpty() || pref.text != null) {
                    pref.summary = pref.text.orEmpty()
                }
                pref.setOnPreferenceChangeListener { p, newValue ->
                    if (p.key == AppConfig.PREF_SOCKS_PASSWORD) {
                        p.summary = if ((newValue as? String).isNullOrEmpty()) "" else "******"
                    } else {
                        p.summary = (newValue as? String).orEmpty()
                    }
                    true
                }
            }

            is ListPreference -> {
                pref.summary = pref.entry ?: ""
                pref.setOnPreferenceChangeListener { p, newValue ->
                    val lp = p as ListPreference
                    val idx = lp.findIndexOfValue(newValue as? String)
                    lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                    true
                }
            }
        }
    }

    fun traverse(group: androidx.preference.PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            when (val p = group.getPreference(i)) {
                is androidx.preference.PreferenceGroup -> traverse(p)
                else -> updateSummary(p)
            }
        }
    }

    root?.let { traverse(it) }
}
