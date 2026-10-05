package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogsBinding
import com.v2ray.ang.databinding.ItemSessionLogBinding
import com.v2ray.ang.handler.LogLevel
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SessionLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs: the log level (off included), the whole app's logcat, and the history of connections —
 * one card per connection, showing the server and the subscription it came from.
 */
class LogsActivity : BaseActivity() {
    private val binding by lazy { ActivityLogsBinding.inflate(layoutInflater) }
    private val dateFormat = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.logs_title))

        binding.rowLogLevel.setOnClickListener { pickLogLevel() }
        binding.tvAppLogSummary.setText(R.string.logs_app_summary)
        binding.rowAppLog.setOnClickListener { startActivity(Intent(this, LogcatActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        renderLevel()
        loadSessions()
    }

    private fun levelLabels(): Array<String> = resources.getStringArray(R.array.log_level_labels)

    private fun renderLevel() {
        val index = LogLevel.values.indexOf(LogLevel.current()).coerceAtLeast(0)
        binding.tvLogLevel.text = levelLabels()[index]
    }

    private fun pickLogLevel() {
        val current = LogLevel.values.indexOf(LogLevel.current()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_level_title)
            .setSingleChoiceItems(levelLabels(), current) { dialog, which ->
                LogLevel.set(LogLevel.values[which])
                renderLevel()
                loadSessions()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { SessionLogStore.list(applicationContext) }
            renderSessions(sessions)
        }
    }

    private fun renderSessions(sessions: List<SessionLogStore.Session>) {
        val container = binding.containerSessions
        container.removeAllViews()
        val now = System.currentTimeMillis()

        binding.cardStats.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        binding.tvStatSessions.text = sessions.size.toString()
        val finished = sessions.filterNot { it.isActive(now) }
        binding.tvStatAverage.text = if (finished.isEmpty()) "—"
        else formatDuration(this, finished.sumOf { it.durationMs(now) } / finished.size)

        binding.tvSessionsEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        binding.tvSessionsEmpty.setText(
            if (LogLevel.isOff()) R.string.logs_sessions_off else R.string.logs_sessions_empty
        )

        sessions.forEach { session ->
            val item = ItemSessionLogBinding.inflate(layoutInflater, container, false)
            item.tvStarted.text = dateFormat.format(Date(session.startedAt))
            if (session.isActive(now)) {
                item.tvDuration.setText(R.string.logs_session_active)
                item.tvDuration.setTextColor(
                    MaterialColors.getColor(item.root, androidx.appcompat.R.attr.colorPrimary)
                )
            } else {
                item.tvDuration.text = formatDuration(this, session.durationMs(now))
            }
            item.tvRemarks.text = session.remarks.ifBlank { getString(R.string.logs_session_unnamed) }
            // Fetch the current name: a subscription renamed since still reads right; one deleted
            // since keeps the name it had then.
            val subName = session.subscriptionId.takeIf { it.isNotBlank() }
                ?.let { MmkvManager.decodeSubscription(it)?.remarks }
                ?.takeIf { it.isNotBlank() } ?: session.subscriptionName
            item.tvSubscription.visibility = if (subName.isBlank()) View.GONE else View.VISIBLE
            item.tvSubscription.text = subName
            item.tvAddress.visibility = if (session.address.isBlank()) View.GONE else View.VISIBLE
            item.tvAddress.text = session.address
            item.tvRecords.text = resources.getQuantityString(R.plurals.logs_records, session.records, session.records)
            item.root.setOnClickListener {
                startActivity(
                    Intent(this, LogcatActivity::class.java)
                        .putExtra(LogcatActivity.EXTRA_SESSION_ID, session.id)
                )
            }
            container.addView(item.root)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logs, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.clear_sessions -> {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.logs_clear_sessions_confirm)
                .setPositiveButton(R.string.logs_clear_sessions) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SessionLogStore.clear(applicationContext) }
                        loadSessions()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        /** "25ч 18м 16с", "2м 5с", "11с" — the largest unit first, zero units left out. */
        fun formatDuration(context: Context, ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return buildList {
                if (h > 0) add(context.getString(R.string.duration_hours, h))
                if (m > 0) add(context.getString(R.string.duration_minutes, m))
                if (s > 0 || (h == 0L && m == 0L)) add(context.getString(R.string.duration_seconds, s))
            }.joinToString(" ")
        }
    }
}
