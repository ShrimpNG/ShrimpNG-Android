package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.util.AppListPresentation
import com.v2ray.ang.handler.MmkvManager

class AppPickerActivity : BaseActivity() {
    companion object {
        private const val EXTRA_SELECTED_PACKAGES = "selected_packages"
        private const val EXTRA_PICKER_TITLE = "picker_title"

        fun createIntent(
            context: Context,
            selectedPackages: Collection<String> = emptyList(),
            title: String? = null
        ): Intent = Intent(context, AppPickerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_SELECTED_PACKAGES, ArrayList(selectedPackages))
            title?.let { putExtra(EXTRA_PICKER_TITLE, it) }
        }

        fun getSelectedPackages(intent: Intent?): List<String> {
            return intent?.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES).orEmpty()
        }
    }

    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }
    private val initialSelectedPackages by lazy {
        intent.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES).orEmpty()
    }
    private val selectedPackages = LinkedHashSet<String>()
    private var appsAll: List<AppInfo> = emptyList()
    private var query = ""
    private val adapter by lazy {
        AppSelectorAdapter(binding.headerView, selectedPackages::contains) { packageName ->
            if (!selectedPackages.remove(packageName)) selectedPackages.add(packageName)
            updateAppsLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = resolveScreenTitle())

        selectedPackages.addAll(savedInstanceState?.getStringArrayList(EXTRA_SELECTED_PACKAGES) ?: initialSelectedPackages)
        query = savedInstanceState?.getString("query").orEmpty()
        binding.containerPerAppProxy.visibility = View.GONE
        binding.containerBypassApps.visibility = View.GONE
        binding.chipSelectedFirst.isChecked = savedInstanceState?.getBoolean("selected_first")
            ?: MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_SELECTED_FIRST, true)
        binding.chipShowSystem.isChecked = savedInstanceState?.getBoolean("show_system")
            ?: MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_SHOW_SYSTEM, false)
        binding.chipSelectedFirst.setOnCheckedChangeListener { _, _ -> applyView() }
        binding.chipShowSystem.setOnCheckedChangeListener { _, _ -> applyView() }
        updateAppsLabel()
        setupRecyclerView()
        loadApps()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_picker, menu)

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

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.select_all -> {
            selectAllVisible()
            true
        }

        R.id.invert_selection -> {
            invertVisibleSelection()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun finish() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putStringArrayListExtra(EXTRA_SELECTED_PACKAGES, getSelectedPackages())
            }
        )
        super.finish()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.adapter = adapter
        binding.fastScroller.attach(binding.recyclerView) { position ->
            adapter.apps.getOrNull((position - 1).coerceAtLeast(0))?.appName?.let(AppListPresentation::indexLetter)
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun createSpecialItemUnidentified(): AppInfo {
        val icon = requireNotNull(
            getDrawable(R.drawable.ic_help_24dp)
                ?: getDrawable(android.R.drawable.sym_def_app_icon)
        ) { "No fallback drawable available" }
        return AppInfo(
            appName = getString(R.string.app_picker_unknown_app),
            packageName = AppConfig.UNIDENTIFIED_PACKAGE,
            appIcon = icon,
            isSystemApp = false,
            isSelected = 0
        )
    }

    private fun loadApps() {
        showLoading()

        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val appsList = AppManagerUtil.loadNetworkAppList(this@AppPickerActivity)
                    listOf(createSpecialItemUnidentified()) + appsList
                }

                appsAll = apps
                applyView()
            } catch (e: Exception) {
                LogUtil.e("AppPickerActivity", "Failed to load app list", e)
            } finally {
                hideLoading()
            }
        }
    }

    private fun applyView() {
        adapter.submitList(AppListPresentation.display(appsAll, selectedPackages,
            binding.chipShowSystem.isChecked, binding.chipSelectedFirst.isChecked, query,
            AppConfig.UNIDENTIFIED_PACKAGE))
        updateAppsLabel()
    }

    private fun updateAppsLabel() {
        binding.tvAppsLabel.text = getString(R.string.per_app_apps_label_selection,
            adapter.apps.size, selectedPackages.size)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXTRA_SELECTED_PACKAGES, getSelectedPackages())
        outState.putString("query", query)
        outState.putBoolean("selected_first", binding.chipSelectedFirst.isChecked)
        outState.putBoolean("show_system", binding.chipShowSystem.isChecked)
        super.onSaveInstanceState(outState)
    }

    private fun selectAllVisible() {
        adapter.apps.forEach { app -> selectedPackages.add(app.packageName) }
        adapter.refreshSelection()
        updateAppsLabel()
    }

    private fun invertVisibleSelection() {
        adapter.apps.forEach { app ->
            if (selectedPackages.contains(app.packageName)) {
                selectedPackages.remove(app.packageName)
            } else {
                selectedPackages.add(app.packageName)
            }
        }
        adapter.refreshSelection()
        updateAppsLabel()
    }

    private fun getSelectedPackages(): ArrayList<String> {
        return ArrayList(selectedPackages.sorted())
    }

    private fun resolveScreenTitle(): String {
        return intent.getStringExtra(EXTRA_PICKER_TITLE) ?: getString(R.string.per_app_proxy_settings)
    }
}

