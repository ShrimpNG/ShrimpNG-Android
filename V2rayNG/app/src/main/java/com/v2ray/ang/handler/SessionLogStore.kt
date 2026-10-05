package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import java.io.File
import java.util.UUID

/**
 * History of connections — one session per core start — kept as files so the VPN daemon can
 * write it and the UI process read it: `<id>.json` (what was connected, when) and `<id>.log`
 * (the log lines seen meanwhile, oldest first).
 *
 * Written only while logging is on (see [LogLevel]); wiped with the UI by the disguise auto-lock.
 */
object SessionLogStore {

    const val MAX_SESSIONS = 50
    const val MAX_LINES_PER_SESSION = 5000

    /** A session whose daemon stopped refreshing it for this long is over, however it ended. */
    private const val STALE_AFTER_MS = 20_000L

    data class Session(
        val id: String,
        val startedAt: Long,
        var endedAt: Long? = null,
        var lastSeenAt: Long,
        val serverGuid: String,
        val remarks: String,
        val address: String,
        val subscriptionId: String,
        val subscriptionName: String,
        var records: Int = 0,
    ) {
        fun isActive(now: Long = System.currentTimeMillis()): Boolean =
            endedAt == null && now - lastSeenAt < STALE_AFTER_MS

        /** Up to now while active; a session cut off without a clean stop ends where it was last seen. */
        fun durationMs(now: Long = System.currentTimeMillis()): Long =
            ((endedAt ?: if (isActive(now)) now else lastSeenAt) - startedAt).coerceAtLeast(0)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "session_logs").apply { mkdirs() }

    private fun metaFile(context: Context, id: String) = File(dir(context), "$id.json")
    private fun logFile(context: Context, id: String) = File(dir(context), "$id.log")

    fun begin(context: Context, guid: String, profile: ProfileItem, startedAt: Long): Session {
        val subscription = profile.subscriptionId.takeIf { it.isNotBlank() }
            ?.let { MmkvManager.decodeSubscription(it) }
        val session = Session(
            id = UUID.randomUUID().toString(),
            startedAt = startedAt,
            lastSeenAt = System.currentTimeMillis(),
            serverGuid = guid,
            remarks = profile.remarks,
            address = listOfNotNull(profile.server?.takeIf { it.isNotBlank() }, profile.serverPort?.takeIf { it.isNotBlank() })
                .joinToString(":"),
            subscriptionId = profile.subscriptionId,
            subscriptionName = subscription?.remarks.orEmpty(),
        )
        save(context, session)
        trim(context)
        return session
    }

    fun save(context: Context, session: Session) {
        try {
            val target = metaFile(context, session.id)
            val temp = File(target.path + ".tmp")
            temp.writeText(JsonUtil.toJson(session))
            temp.renameTo(target)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SessionLogStore: save failed: ${e.message}")
        }
    }

    fun append(context: Context, id: String, lines: List<String>) {
        if (lines.isEmpty()) return
        try {
            logFile(context, id).appendText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SessionLogStore: append failed: ${e.message}")
        }
    }

    /** Newest first. */
    fun list(context: Context): List<Session> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { JsonUtil.fromJson(it.readText(), Session::class.java) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    fun get(context: Context, id: String): Session? =
        runCatching { JsonUtil.fromJson(metaFile(context, id).readText(), Session::class.java) }.getOrNull()

    /** Oldest first, as written. */
    fun lines(context: Context, id: String): List<String> =
        runCatching { logFile(context, id).readLines() }.getOrDefault(emptyList())

    /** Everything except a session still being written, which its daemon would only recreate. */
    fun clear(context: Context) {
        list(context).filterNot { it.isActive() }.forEach { delete(context, it.id) }
    }

    /** All of it, the running session included: for the disguise lock, where nothing may remain. */
    fun wipe(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun delete(context: Context, id: String) {
        metaFile(context, id).delete()
        logFile(context, id).delete()
    }

    private fun trim(context: Context) {
        list(context).drop(MAX_SESSIONS).forEach { delete(context, it.id) }
    }
}
