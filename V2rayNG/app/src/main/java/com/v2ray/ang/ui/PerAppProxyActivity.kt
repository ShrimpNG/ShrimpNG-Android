package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.PerAppProxyViewModel
import com.v2ray.ang.ui.widget.SegmentedTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.util.AppListPresentation

class PerAppProxyActivity : BaseActivity() {
    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private var adapter: AppSelectorAdapter? = null
    private var appsAll: List<AppInfo>? = null
    private val viewModel: PerAppProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.per_app_proxy_settings))

        query = savedInstanceState?.getString("query").orEmpty()
        initList()

        // Two tiles of one group; the whole tile toggles its switch, as on the settings screens.
        binding.containerPerAppProxy.background = SegmentedTiles.background(binding.containerPerAppProxy, first = true, last = false)
        binding.containerBypassApps.background = SegmentedTiles.background(binding.containerBypassApps, first = false, last = true)

        binding.switchPerAppProxy.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, isChecked)
            SettingsChangeManager.makeRestartService()
        }
        binding.containerPerAppProxy.setOnClickListener { binding.switchPerAppProxy.toggle() }

        binding.switchBypassApps.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
        binding.switchBypassApps.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, isChecked)
            SettingsChangeManager.makeRestartService()
            renderBypassSummary(isChecked)
        }
        renderBypassSummary(binding.switchBypassApps.isChecked)
        binding.containerBypassApps.setOnClickListener { binding.switchBypassApps.toggle() }
    }

    /** Says what ticking an app does in the current mode, in place of the old info toast. */
    private fun renderBypassSummary(bypass: Boolean) {
        binding.tvBypassSummary.setText(
            if (bypass) R.string.per_app_mode_bypass_summary else R.string.per_app_mode_proxy_summary
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    private var query = ""

    private fun initList() {
        adapter = AppSelectorAdapter(binding.headerView, viewModel::contains) { packageName ->
            viewModel.toggle(packageName)
            updateAppsLabel()
        }
        binding.recyclerView.adapter = adapter
        // Position 0 is the header; it reads as the first app's letter.
        binding.fastScroller.attach(binding.recyclerView) { position ->
            adapter?.apps?.getOrNull((position - 1).coerceAtLeast(0))?.appName?.let(AppListPresentation::indexLetter)
        }

        binding.chipSelectedFirst.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_SELECTED_FIRST, true)
        binding.chipShowSystem.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_SHOW_SYSTEM, false)
        binding.chipSelectedFirst.setOnCheckedChangeListener { _, checked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_SELECTED_FIRST, checked)
            applyView()
        }
        binding.chipShowSystem.setOnCheckedChangeListener { _, checked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_SHOW_SYSTEM, checked)
            applyView()
        }

        showLoading()
        lifecycleScope.launch {
            try {
                appsAll = withContext(Dispatchers.IO) { AppManagerUtil.loadNetworkAppList(this@PerAppProxyActivity) }
                applyView()
            } catch (e: Exception) {
                LogUtil.e(ANG_PACKAGE, "Error loading apps", e)
            } finally {
                hideLoading()
            }
        }
    }

    /** Re-sort on filter changes, not on each tick: keep the touched row under the finger. */
    private fun applyView() {
        val all = appsAll ?: return
        adapter?.submitList(AppListPresentation.display(all, viewModel.getAll(),
            binding.chipShowSystem.isChecked, binding.chipSelectedFirst.isChecked, query))
        updateAppsLabel()
    }

    private fun updateAppsLabel() {
        binding.tvAppsLabel.text = getString(R.string.per_app_apps_label_selection,
            adapter?.apps?.size ?: 0, viewModel.getAll().size)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setQuery(query, false)
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    query = newText.orEmpty()
                    applyView()
                    return false
                }
            })
        }

        return super.onCreateOptionsMenu(menu)
    }


    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.select_all -> {
            selectAllApp()
            allowPerAppProxy()
            true
        }

        R.id.invert_selection -> {
            invertSelection()
            allowPerAppProxy()
            true
        }

        R.id.select_proxy_app -> {
            selectProxyAppAuto()
            allowPerAppProxy()
            true
        }

        R.id.import_proxy_app -> {
            importProxyApp()
            allowPerAppProxy()
            true
        }

        R.id.export_proxy_app -> {
            exportProxyApp()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun selectAllApp() {
        adapter?.let { adapter ->
            val pkgNames = adapter.apps.map { it.packageName }
            val allSelected = pkgNames.all { viewModel.contains(it) }

            if (allSelected) {
                viewModel.removeAll(pkgNames)
            } else {
                viewModel.addAll(pkgNames)
            }
            refreshData()
        }
    }

    private fun invertSelection() {
        adapter?.let { adapter ->
            adapter.apps.forEach { app ->
                viewModel.toggle(app.packageName)
            }
            refreshData()
        }
    }

    private fun selectProxyAppAuto() {
        toast(R.string.msg_downloading_content)
        showLoading()

        val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
        lifecycleScope.launch(Dispatchers.IO) {
            var content = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000
                )
            )
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                ) ?: ""
            }
            launch(Dispatchers.Main) {
                //LogUtil.i(AppConfig.TAG, content)
                selectProxyApp(content, true)
                toastSuccess(R.string.toast_success)
                hideLoading()
            }
        }
    }

    private fun importProxyApp() {
        val content = Utils.getClipboard(applicationContext)
        if (TextUtils.isEmpty(content)) return
        selectProxyApp(content, false)
        toastSuccess(R.string.toast_success)
    }

    private fun exportProxyApp() {
        var lst = binding.switchBypassApps.isChecked.toString()

        viewModel.getAll().forEach { pkg ->
            lst = lst + System.lineSeparator() + pkg
        }
        Utils.setClipboard(applicationContext, lst)
        toastSuccess(R.string.toast_success)
    }

    private fun allowPerAppProxy() {
        binding.switchPerAppProxy.isChecked = true
        SettingsChangeManager.makeRestartService()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun selectProxyApp(content: String, force: Boolean): Boolean {
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                Utils.readTextFromAssets(v2RayApplication, "proxy_package_name")
            } else {
                content
            }
            if (TextUtils.isEmpty(proxyApps)) return false

            viewModel.clear()

            val everyApp = appsAll.orEmpty()
            if (binding.switchBypassApps.isChecked) {
                everyApp.let { apps ->
                    apps.forEach { app ->
                        val packageName = app.packageName
                        if (!inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            } else {
                everyApp.let { apps ->
                    apps.forEach { app ->
                        val packageName = app.packageName
                        if (inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun inProxyApps(proxyApps: String, packageName: String, force: Boolean): Boolean {
        println(packageName)
        if (force) {
            if (packageName == "com.google.android.webview") return false
            if (packageName.startsWith("com.google")) return true
        }

        return proxyApps.indexOf(packageName) >= 0
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        adapter?.refreshSelection()
        updateAppsLabel()
    }
}
