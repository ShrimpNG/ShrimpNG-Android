package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SessionLogStore
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatActivity : BaseActivity(), SwipeRefreshLayout.OnRefreshListener {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }
    private val viewModel: LogcatViewModel by viewModels()
    private lateinit var adapter: LogcatRecyclerAdapter

    /** Set when showing one connection from LogsActivity's history instead of the live logcat. */
    private val sessionId: String? by lazy { intent.getStringExtra(EXTRA_SESSION_ID) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = sessionId?.let { SessionLogStore.get(this, it) }
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = session?.remarks?.ifBlank { null } ?: getString(if (sessionId != null) R.string.logs_session_unnamed else R.string.logs_app_title),
        )

        adapter = LogcatRecyclerAdapter(viewModel, ::onLogLongClick)

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener(this)

        // Loaded on open; pulling down reloads.
        binding.refreshLayout.isRefreshing = true
        onRefresh()
    }

    private fun onLogLongClick(log: String): Boolean {
        Utils.setClipboard(this, log)
        return true
    }

    private fun shareLogcat() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logText = viewModel.getAll().joinToString("\n")

            val result = try {
                val shareDir = File(cacheDir, "shared_logs").apply {
                    mkdirs()
                }

                shareDir.listFiles()?.forEach { it.delete() }

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val logFile = File(shareDir, "ShrimpNG_log_$timestamp.txt")
                logFile.writeText(logText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@LogcatActivity,
                    "${packageName}.cache",
                    logFile
                )

                uri to logFile.name
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(e.localizedMessage ?: e.toString())
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, result.first)
                    putExtra(Intent.EXTRA_SUBJECT, result.second)
                    putExtra(Intent.EXTRA_TITLE, result.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, result.second, result.first)
                }

                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.logcat_share)
                    )
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logcat, menu)
        // Clearing means the system log buffer; a recorded session is cleared from the history list.
        if (sessionId != null) menu.findItem(R.id.clear_all)?.isVisible = false

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.filter(newText)
                    refreshData()
                    return false
                }
            })
            searchView.setOnCloseListener {
                viewModel.filter("")
                refreshData()
                false
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.copy_all -> {
            val all = viewModel.getAll().joinToString("\n")
            Utils.setClipboard(this, all)
            toastSuccess(R.string.toast_success)
            true
        }

        R.id.share_all -> {
            shareLogcat()
            true
        }

        R.id.clear_all -> {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.clearLogcat()
                withContext(Dispatchers.Main) {
                    refreshData()
                }
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            sessionId?.let { viewModel.loadSession(applicationContext, it) } ?: viewModel.loadLogcat()
            withContext(Dispatchers.Main) {
                binding.refreshLayout.isRefreshing = false
                refreshData()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        adapter.notifyDataSetChanged()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
