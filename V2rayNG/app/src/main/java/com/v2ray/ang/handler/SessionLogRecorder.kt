package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Records one [SessionLogStore] session per connection, in the VPN daemon: from core start to
 * core stop it follows the app's own logcat (core and app tags, at whatever level logging is set
 * to) into the session's file, and keeps the session's "last seen" fresh so a session cut off by
 * a killed process still shows a sensible duration.
 */
object SessionLogRecorder {

    private const val FLUSH_EVERY_MS = 3_000L
    private const val LOGCAT_TAGS = "GoLog,${AppConfig.ANG_PACKAGE},AndroidRuntime,System.err"

    private class Active(
        val context: Context,
        val session: SessionLogStore.Session,
        val process: Process,
        val reader: Thread,
        val flusher: ScheduledExecutorService,
        val pending: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    )

    @Volatile
    private var active: Active? = null

    /** [startedAt]: when the connection attempt began, so the core's own start-up lines are in. */
    @Synchronized
    fun start(context: Context, guid: String, profile: ProfileItem, startedAt: Long) {
        stop()
        LogUtil.refreshLogLevel()
        // Off, or behind the calculator disguise: a list of VPN servers is what the disguise hides.
        if (LogLevel.isOff() || DecoyManager.isActive()) return
        val app = context.applicationContext
        try {
            val session = SessionLogStore.begin(app, guid, profile, startedAt)
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(startedAt - 1_000))
            val process = ProcessBuilder("logcat", "-v", "time", "-T", since, "-s", LOGCAT_TAGS)
                .redirectErrorStream(true)
                .start()
            val pending = ConcurrentLinkedQueue<String>()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { pending.add(it) }
                }
            }, "SessionLogReader").apply { isDaemon = true }
            val flusher = Executors.newSingleThreadScheduledExecutor()
            val state = Active(app, session, process, reader, flusher, pending)
            active = state
            reader.start()
            flusher.scheduleWithFixedDelay({ flush(state) }, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SessionLogRecorder: could not start: ${e.message}")
        }
    }

    /**
     * Detaches the running session at once — so a start right after it can't be mistaken for the
     * one being closed — and finishes it on a background thread, off whatever thread stops the core.
     */
    @Synchronized
    fun stop() {
        val state = active ?: return
        active = null
        state.flusher.shutdownNow()
        Thread({
            // Let lines the core printed while stopping arrive before the stream is cut.
            Thread.sleep(300)
            state.process.destroy()
            flush(state)
            state.session.endedAt = System.currentTimeMillis()
            SessionLogStore.save(state.context, state.session)
        }, "SessionLogFinish").start()
    }

    private fun flush(state: Active) {
        val session = state.session
        val batch = ArrayList<String>()
        while (true) {
            val line = state.pending.poll() ?: break
            // logcat prints a separator line per buffer; it isn't a record.
            if (line.startsWith("--------- ")) continue
            if (session.records + batch.size < SessionLogStore.MAX_LINES_PER_SESSION) batch.add(line)
        }
        SessionLogStore.append(state.context, session.id, batch)
        session.records += batch.size
        session.lastSeenAt = System.currentTimeMillis()
        SessionLogStore.save(state.context, session)
    }
}

/** The one log level setting: the core's, the app's own LogUtil, and whether sessions are kept. */
object LogLevel {
    const val OFF = "none"
    val values = listOf(OFF, "error", "warning", "info", "debug")

    fun current(): String = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"

    fun isOff(): Boolean = current() == OFF

    /**
     * hev-socks5-tunnel's level for the same setting, or null for off. Its info level prints a
     * line per connection, so only Debug goes past warn; everything it prints reaches logcat as
     * E/GoLog (gomobile routes the process's stderr there), whatever the level was.
     */
    fun hevTunnelLevel(): String? = when (current()) {
        OFF -> null
        "error" -> "error"
        "debug" -> "debug"
        else -> "warn"
    }

    fun set(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_LOGLEVEL, value)
        LogUtil.refreshLogLevel()
    }
}
