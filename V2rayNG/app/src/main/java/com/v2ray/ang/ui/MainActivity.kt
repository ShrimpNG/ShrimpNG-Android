package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFadeThrough
import com.v2ray.ang.ui.widget.ExpressiveMotion
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the four bottom-nav tabs (Home/Subscriptions/Rules/Settings) and the config-management
 * actions shared across them (import/export/delete/sort act on [MainViewModel]'s current
 * server list, which is scoped to whichever subscription is active).
 */
class MainActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    val mainViewModel: MainViewModel by viewModels()

    private val tabFragments = mutableMapOf<Int, Fragment>()
    private var activeTabId: Int = R.id.nav_home
    private lateinit var dock: FloatingDockController
    private var dockInset = 0
    private val dockInsetListeners = mutableListOf<(Int) -> Unit>()

    val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Below Android 15 the system still paints the theme's status bar colour over the top;
        // Главное has no toolbar to match it, so let the screen's background show instead.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.shrimp_home_title))
        setupEdgeToEdgeInsets()

        activeTabId = savedInstanceState?.getInt(KEY_ACTIVE_TAB) ?: R.id.nav_home
        NAV_TAB_IDS.forEach { id ->
            supportFragmentManager.findFragmentByTag(id.toString())?.let { tabFragments[id] = it }
        }
        dock = FloatingDockController(binding.floatingDock) { itemId -> showTab(itemId) }
        dock.selectedItemId = activeTabId
        // Long-press Главное: the quick subscription switcher.
        dock.onLongPress = { itemId, anchor ->
            if (itemId != R.id.nav_home) {
                null
            } else {
                DockSubscriptionMenu(
                    anchor = anchor,
                    activeSubscriptionId = mainViewModel.subscriptionId,
                    onPick = { guid ->
                        switchToTab(R.id.nav_home)
                        if (guid != mainViewModel.subscriptionId) mainViewModel.subscriptionIdChanged(guid)
                    },
                ).takeIf { it.show() }
            }
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTabId)
    }

    /**
     * Edge-to-edge is enforced on modern Android regardless of `fitsSystemWindows`. The app bar
     * absorbs the status bar inset into its own padding, so its background extends behind it; the
     * floating dock instead lifts itself clear of the gesture area, and the tabs pad their content
     * by however much of the screen the dock now covers.
     */
    private fun setupEdgeToEdgeInsets() {
        val initialAppBarPadding = binding.appBar.paddingTop
        val dockView = binding.floatingDock.root
        val baseDockMargin = (dockView.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.updatePadding(top = initialAppBarPadding + systemBars.top)
            dockView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseDockMargin + systemBars.bottom
            }
            insets
        }
        // The dock is as wide as its labels; a large font or display size could make that wider
        // than the screen. Fit it, inside the content's own 16dp side gutters.
        val dockGutter = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
        (dockView.parent as View).addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
            val available = parent.width - 2 * dockGutter
            parent.post { dock.fitWidth(available) }
        }
        dockView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val margins = view.layoutParams as ViewGroup.MarginLayoutParams
            // A little air above the dock, so the last row doesn't end flush against it.
            val covered = view.height + margins.bottomMargin + resources.getDimensionPixelSize(R.dimen.dock_bottom_margin)
            if (covered != dockInset) {
                dockInset = covered
                dockInsetListeners.forEach { it(covered) }
            }
        }
    }

    /**
     * How far from the bottom the floating dock reaches. [listener] gets the current value now,
     * if the dock has been laid out, and again whenever it changes — the tabs use it as extra
     * bottom padding for their lists and margin for their FABs.
     */
    fun onDockInset(owner: LifecycleOwner, listener: (Int) -> Unit) {
        dockInsetListeners += listener
        if (dockInset > 0) listener(dockInset)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dockInsetListeners -= listener
            }
        })
    }

    /** Lets a fragment (e.g. an empty-state CTA) jump to another tab programmatically. */
    fun switchToTab(itemId: Int) {
        dock.selectedItemId = itemId
    }

    private fun showTab(itemId: Int) {
        val target = tabFragments.getOrPut(itemId) { createTabFragment(itemId) }
        val isTabSwitch = itemId != activeTabId && tabFragments.size > 1
        val transaction = supportFragmentManager.beginTransaction()
        // Top-level destinations use fade-through: the old page clears before the new one
        // arrives. End any previous scrub transition so rapid changes cannot leave ghost pages.
        TransitionManager.endTransitions(binding.navHostContainer)
        if (isTabSwitch) (tabFragments[R.id.nav_home] as? HomeFragment)?.clearTransientUi()
        if (isTabSwitch && ValueAnimator.areAnimatorsEnabled()) {
            TransitionManager.beginDelayedTransition(
                binding.navHostContainer,
                MaterialFadeThrough().apply {
                    duration = ExpressiveMotion.duration(this@MainActivity)
                    // Target pages explicitly. Recursive capture also matches recycled rows
                    // across hidden lists, briefly animating unrelated controls in their overlay.
                    addTarget(R.id.page_home)
                    addTarget(R.id.page_subscriptions)
                    addTarget(R.id.page_rules)
                    addTarget(R.id.page_settings)
                },
            )
        }
        if (!target.isAdded) {
            transaction.add(R.id.nav_host_container, target, itemId.toString())
        }
        tabFragments.values.filter { it !== target }.forEach { transaction.hide(it) }
        transaction.show(target)
        transaction.commitNowAllowingStateLoss()
        activeTabId = itemId
    }

    private fun createTabFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_subscriptions -> SubscriptionsFragment()
        R.id.nav_rules -> RulesFragment()
        R.id.nav_settings -> SettingsHubFragment()
        else -> HomeFragment()
    }

    /** QR scanning lives on [HelperBaseActivity] (protected); fragments reach it through here. */
    fun launchQrScanner(onResult: (String?) -> Unit) = launchQRCodeScanner(onResult)

    /**
     * The connect button's tap handler. Lives here (not in HomeFragment) because settings
     * changes made from any tab can trigger a restart regardless of which tab is on screen.
     */
    fun connectOrDisconnect() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }


    fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> {
                            (tabFragments[R.id.nav_subscriptions] as? SubscriptionsFragment)?.refreshData()
                            // Главное тоже слушает это: без уведомления только что добавленная
                            // подписка не появлялась там до перезахода в приложение.
                            mainViewModel.notifySubscriptionsChanged()
                        }
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }







    fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }



    fun locateSelectedServer() {
        (tabFragments[R.id.nav_home] as? HomeFragment)?.scrollToSelectedServer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val KEY_ACTIVE_TAB = "active_tab"
        private val NAV_TAB_IDS = listOf(R.id.nav_home, R.id.nav_subscriptions, R.id.nav_rules, R.id.nav_settings)
    }
}
