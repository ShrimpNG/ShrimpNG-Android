package com.v2ray.ang.handler

import android.content.Context
import android.util.LruCache
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.firewall.ConnLogCollapse
import com.v2ray.ang.data.firewall.ConnLogDao
import com.v2ray.ang.data.firewall.ConnLogEntry
import com.v2ray.ang.util.DomainIpCache
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives connection observations from [com.v2ray.ang.core.CoreServiceManager]'s ProcessFinder
 * and persists them off the hot path.
 *
 * The finder itself must stay allocation-light: one [offer] / [Channel.trySend] per callback, no
 * PackageManager, no SQLite. Batching, collapse, UID→package resolve and the blocked verdict all
 * happen on [Dispatchers.IO] here.
 */
object ConnectionLogRecorder {

    private const val CHANNEL_CAPACITY = 4096
    private const val BATCH_MAX = 200
    private const val BATCH_WINDOW_MS = 500L
    private const val RECENT_TTL_MS = 10_000L
    private const val RECENT_CACHE_SIZE = 2048
    private const val CLEANUP_EVERY_FLUSHES = 20

    private data class RecentRow(val id: Long, val ts: Long)

    private val channel = Channel<ConnLogCollapse.Raw>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val startStopMutex = Mutex()
    private var consumerJob: Job? = null
    private var appContext: Context? = null
    private val flushCounter = AtomicInteger(0)
    private val firstObservationLogged = AtomicBoolean(false)

    // Avoid an MMKV read on every FindProcess callback; flipped only from start/stop.
    @Volatile
    private var recording = false

    // Key → last written row. Only touched from the consumer coroutine.
    private val recent = LruCache<String, RecentRow>(RECENT_CACHE_SIZE)

    fun start(context: Context) {
        if (!FirewallManager.isLogEnabled()) {
            recording = false
            return
        }
        // Flip before the IO hop so early FindProcess callbacks aren't dropped.
        recording = true
        val app = context.applicationContext
        // Fire-and-forget: startCoreLoop is on the main thread and must not block on the mutex.
        CoroutineScope(Dispatchers.IO).launch {
            startStopMutex.withLock {
                if (consumerJob?.isActive == true) {
                    appContext = app
                    return@withLock
                }
                appContext = app
                PackageUidResolver.warmReverseCache(app)
                flushCounter.set(0)
                firstObservationLogged.set(false)
                recent.evictAll()
                consumerJob = CoroutineScope(Dispatchers.IO).launch { consume() }
                LogUtil.i(AppConfig.TAG, "ConnectionLogRecorder: started")
            }
        }
    }

    fun stop() {
        recording = false
        CoroutineScope(Dispatchers.IO).launch {
            startStopMutex.withLock {
                consumerJob?.cancel()
                consumerJob = null
                recent.evictAll()
                LogUtil.i(AppConfig.TAG, "ConnectionLogRecorder: stopped")
            }
        }
    }

    /**
     * Hot-path entry. Safe to call from the Go→JNI binder thread. Never throws.
     */
    fun offer(
        network: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        uid: Int,
        domain: String? = null,
    ) {
        if (!recording) return
        val sniffed = DomainIpCache.normalizeDomain(domain)
        if (sniffed != null) {
            DomainIpCache.put(destIp, sniffed)
        }
        if (firstObservationLogged.compareAndSet(false, true)) {
            LogUtil.w(
                AppConfig.TAG,
                "ConnectionLogRecorder: first $network observation uid=$uid dest=$destIp:$destPort" +
                    (if (sniffed != null) " domain=$sniffed" else ""),
            )
        }
        try {
            channel.trySend(
                ConnLogCollapse.Raw(
                    ts = System.currentTimeMillis(),
                    uid = uid,
                    network = network,
                    destIp = destIp,
                    destPort = destPort,
                    srcPort = srcPort,
                    domain = sniffed,
                )
            )
        } catch (_: ClosedSendChannelException) {
            // Channel is never closed in practice; ignore if a future change closes it.
        }
    }

    private suspend fun consume() {
        val buffer = ArrayList<ConnLogCollapse.Raw>(BATCH_MAX)
        while (currentCoroutineContext().isActive) {
            // Wait for the first item, then drain up to BATCH_MAX / BATCH_WINDOW_MS.
            val first = channel.receiveCatching().getOrNull() ?: return
            buffer.clear()
            buffer.add(first)
            val deadline = System.nanoTime() + BATCH_WINDOW_MS * 1_000_000L
            while (buffer.size < BATCH_MAX) {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                if (remainingMs == 0L) break
                val next = withTimeoutOrNull(remainingMs) {
                    channel.receiveCatching().getOrNull()
                } ?: break
                buffer.add(next)
            }
            if (!currentCoroutineContext().isActive) return
            try {
                flush(buffer)
            } catch (e: Exception) {
                // One malformed row / transient SQLite lock must not permanently kill logging.
                LogUtil.e(AppConfig.TAG, "ConnectionLogRecorder: flush failed", e)
            }
        }
    }

    private fun flush(raw: List<ConnLogCollapse.Raw>) {
        val context = appContext ?: return
        if (raw.isEmpty() || !FirewallManager.isLogEnabled()) return

        val collapsed = ConnLogCollapse.collapse(raw)
        val dao = ConnLogDao.get(context)
        val verdictSnapshot = FirewallManager.verdictSnapshot(context)
        val now = System.currentTimeMillis()

        val toInsert = ArrayList<ConnLogEntry>(collapsed.size)
        val insertKeys = ArrayList<String>(collapsed.size)

        for (item in collapsed) {
            val packageName = PackageUidResolver.uidToPackageName(context, item.uid)
            // Prefer the sniffed hostname from this batch, then any earlier IP→domain observation.
            val domain = item.domain ?: DomainIpCache.get(item.destIp)
            if (domain != null) DomainIpCache.put(item.destIp, domain)
            val blocked = FirewallManager.isConnectionBlocked(
                verdictSnapshot,
                item.uid,
                packageName,
                item.destIp,
                domain,
            )
            // A rule can change while this destination is still in the 10-second collapse cache.
            // Keep allowed and blocked observations in separate rows so the UI updates promptly.
            val key = "${ConnLogCollapse.collapseKey(item.uid, item.network, item.destIp, item.destPort)}|$blocked"
            val cached = recent.get(key)
            if (cached != null && now - cached.ts <= RECENT_TTL_MS) {
                if (dao.addHits(cached.id, item.hits, item.ts)) {
                    recent.put(key, RecentRow(cached.id, item.ts))
                    // Sniffed domain can arrive on a later hit of the same collapse key.
                    if (!domain.isNullOrBlank()) {
                        try {
                            dao.updateDomain(cached.id, domain)
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }
                // The UI may have cleared this app's rows while the daemon still had their ids
                // cached. Fall through and insert a fresh row instead of dropping new traffic.
                recent.remove(key)
            }
            toInsert.add(
                ConnLogEntry(
                    ts = item.ts,
                    uid = item.uid,
                    packageName = packageName,
                    network = item.network,
                    destIp = item.destIp,
                    destPort = item.destPort,
                    srcPort = item.srcPort,
                    blocked = blocked,
                    hits = item.hits,
                    domain = domain,
                )
            )
            insertKeys.add(key)
        }

        if (toInsert.isNotEmpty()) {
            val ids = dao.insertAll(toInsert)
            for (i in ids.indices) {
                val id = ids[i]
                if (id >= 0L) {
                    recent.put(insertKeys[i], RecentRow(id, toInsert[i].ts))
                    // Fallback only when sniffing did not supply a hostname.
                    if (toInsert[i].domain.isNullOrBlank()) {
                        scheduleDomainLookup(dao, id, toInsert[i].destIp)
                    }
                }
            }
        }

        if (flushCounter.incrementAndGet() % CLEANUP_EVERY_FLUSHES == 0) {
            runRetentionCleanup(dao)
        }
    }

    private fun scheduleDomainLookup(dao: ConnLogDao, rowId: Long, destIp: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val host = reverseLookup(destIp) ?: return@launch
            DomainIpCache.put(destIp, host)
            try {
                dao.updateDomain(rowId, host)
            } catch (_: Exception) {
                // Row may have been trimmed; ignore.
            }
        }
    }

    private fun reverseLookup(ip: String): String? {
        return try {
            val addr = java.net.InetAddress.getByName(ip)
            DomainIpCache.normalizeDomain(addr.canonicalHostName)
        } catch (_: Exception) {
            null
        }
    }

    private fun runRetentionCleanup(dao: ConnLogDao) {
        val days = FirewallManager.logRetentionDays()
        val maxRows = FirewallManager.logMaxRows()
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        try {
            dao.deleteOlderThan(cutoff)
            dao.trimToMaxRows(maxRows)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "ConnectionLogRecorder: retention cleanup failed", e)
        }
    }
}
