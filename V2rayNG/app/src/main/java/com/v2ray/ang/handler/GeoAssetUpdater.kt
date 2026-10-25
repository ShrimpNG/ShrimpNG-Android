package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.GeoDatIndex
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The geo/asset file list and its download, shared by the Asset files screen's download button
 * and the daily background refresh.
 *
 * Downloaded files take effect the next time the core starts; a running connection keeps the
 * rule sets it loaded.
 *
 * A file on disk is only ever replaced by one that is known to load: a .dat download has to be a
 * well-formed geo list that still holds every code the current config uses (see [GeoDatIndex],
 * [GeoRuleGuard]), and it goes into place by rename, so the core never sees half a file.
 */
object GeoAssetUpdater {

    private val builtInGeoFiles = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

    private const val REPAIR_TASK = AppConfig.GEO_ASSET_UPDATE_TASK + "_repair"
    private const val KEY_REPAIR = "repair"

    /** A repair download at most this often, however many configs come out short meanwhile. */
    private val REPAIR_MIN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)

    fun isBuiltInGeoFile(name: String): Boolean = name in builtInGeoFiles

    fun geoFilesSource(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES) ?: AppConfig.GEO_FILES_SOURCES.first()

    /** On by default: stale geosite lists are a common reason a domain rule stops matching. */
    fun isAutoUpdateEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GEO_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_GEO_AUTO_UPDATE, enabled)
        schedule(context)
    }

    /** Built-in geo files (pointed at the chosen source) followed by the user's own assets. */
    fun buildAssetList(geoFilesSource: String = geoFilesSource()): List<AssetUrlCache> {
        val savedAssets = MmkvManager.decodeAssetUrls().orEmpty()
        val builtInItems = builtInGeoFiles
            .filter { geoFile -> savedAssets.none { it.assetUrl.remarks == geoFile } }
            .map {
                AssetUrlCache(
                    Utils.getUuid(),
                    AssetUrlItem(
                        it,
                        String.format(AppConfig.GITHUB_DOWNLOAD_URL, geoFilesSource).concatUrl(it),
                        locked = true
                    )
                )
            }
        // Force update URL for geoip-only-cn-private.dat
        return (builtInItems + savedAssets).map { cache ->
            if (cache.assetUrl.remarks == AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT) {
                cache.copy(assetUrl = cache.assetUrl.copy(url = AppConfig.GEOIP_ONLY_CN_PRIVATE_URL))
            } else {
                cache
            }
        }
    }

    data class Result(
        val successCount: Int,
        val failureCount: Int,
        val failedAssets: List<String>
    )

    /**
     * Downloads every asset in [assets], through the app's local HTTP proxy first (so it works
     * where GitHub is blocked while the VPN is up) and directly as a fallback.
     */
    fun downloadAll(context: Context, assets: List<AssetUrlCache> = buildAssetList()): Result {
        val extDir = File(Utils.userAssetPath(context))
        return withAssetLock(extDir) { downloadAllLocked(extDir, assets) }
    }

    private fun downloadAllLocked(extDir: File, assets: List<AssetUrlCache>): Result {
        // Left behind by a download or restore that was killed midway; nothing else holds them now.
        extDir.listFiles()
            ?.filter { f -> listOf("_temp", ".download", ".bundled").any { f.name.endsWith(it) } }
            ?.forEach { it.delete() }
        val httpPort = SettingsManager.getHttpPort()
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        var successCount = 0
        val failures = mutableListOf<String>()

        assets.forEach { cache ->
            val item = cache.assetUrl
            val portsToTry = if (httpPort == 0) listOf(0) else listOf(httpPort, 0)
            if (portsToTry.any { tryDownload(item, extDir, it, proxyUsername, proxyPassword) }) {
                successCount++
            } else {
                failures.add(item.remarks)
            }
        }

        if (successCount > 0) {
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_LAST_UPDATE, System.currentTimeMillis())
        }
        return Result(successCount, failures.size, failures)
    }

    private fun tryDownload(
        item: AssetUrlItem,
        extDir: File,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?
    ): Boolean {
        // Unique per attempt: the Asset files screen and the background refresh can overlap, and
        // two writers on one temp file would interleave into something neither downloaded.
        val targetTemp = File(extDir, "${item.remarks}.${UUID.randomUUID()}.download")
        val target = File(extDir, item.remarks)
        try {
            val downloaded = HttpUtil.downloadToFile(
                UrlContentRequest(
                    url = item.url,
                    timeout = 15000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                ),
                targetTemp
            )
            // An empty body would replace a working rule set with one the core refuses to load.
            if (downloaded && targetTemp.length() > 0 && isAcceptable(item.remarks, targetTemp, target)) {
                if (targetTemp.renameTo(target)) return true
                LogUtil.w(AppConfig.TAG, "GeoAssetUpdater: could not move ${item.remarks} into place")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        }
        targetTemp.delete()
        return false
    }

    /**
     * Whether [downloaded] may replace [current]. A .dat has to be a geo list the core can read —
     * a captive portal's page or a proxy's error saved under the name is not — and must still hold
     * every code the config uses that the current file has. A source that dropped a category
     * would otherwise trade a working rule for one the core rejects.
     */
    private fun isAcceptable(name: String, downloaded: File, current: File): Boolean {
        if (!name.endsWith(".dat", ignoreCase = true)) return true
        val codes = GeoDatIndex.codes(downloaded, cached = false)
        if (codes == null) {
            LogUtil.w(AppConfig.TAG, "GeoAssetUpdater: $name download is not a geo list, keeping the current file")
            return false
        }
        val currentCodes = GeoDatIndex.codes(current).orEmpty()
        val lost = codesInUse(name).filter { it in currentCodes && it !in codes }
        if (lost.isNotEmpty()) {
            LogUtil.w(AppConfig.TAG, "GeoAssetUpdater: $name download lacks $lost, keeping the current file")
            return false
        }
        return true
    }

    /** Remembers which codes of which file the latest config used, for [isAcceptable]. */
    fun recordCodesInUse(refs: List<GeoRuleGuard.GeoRef>) {
        val entries = refs.mapTo(HashSet()) { "${it.file}:${it.code}" }
        if (entries != MmkvManager.decodeSettingsStringSet(AppConfig.PREF_GEO_CODES_IN_USE)) {
            MmkvManager.encodeSettings(AppConfig.PREF_GEO_CODES_IN_USE, entries)
        }
    }

    private fun codesInUse(name: String): List<String> =
        MmkvManager.decodeSettingsStringSet(AppConfig.PREF_GEO_CODES_IN_USE).orEmpty()
            .filter { it.startsWith("$name:") }
            .map { it.removePrefix("$name:") }

    /**
     * Puts the APK's copy of built-in geo file [name] in place of a missing or unreadable one.
     * The copy may be older than the rules — [GeoRuleGuard] covers that — but it loads.
     */
    fun restoreBundled(context: Context, name: String): Boolean {
        val extDir = File(Utils.userAssetPath(context))
        return try {
            withAssetLock(extDir) {
                val target = File(extDir, name)
                // Another process may have put a good file back while this one waited for the lock.
                if (GeoDatIndex.codes(target) != null) return@withAssetLock true
                val temp = File(extDir, "$name.${UUID.randomUUID()}.bundled")
                try {
                    context.assets.open(name).use { input -> temp.outputStream().use { input.copyTo(it) } }
                    GeoDatIndex.codes(temp, cached = false) != null && temp.renameTo(target)
                } finally {
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GeoAssetUpdater: could not restore $name from the APK", e)
            false
        }
    }

    /**
     * One update as soon as there is a network, whatever the auto-update setting: a config just
     * came out short of geo codes, and only fresh files bring those rules back.
     */
    fun requestRepair(context: Context) {
        val now = System.currentTimeMillis()
        val last = MmkvManager.decodeSettingsLong(AppConfig.PREF_GEO_LAST_REPAIR, 0L)
        if (now - last in 0 until REPAIR_MIN_INTERVAL_MS) return
        MmkvManager.encodeSettings(AppConfig.PREF_GEO_LAST_REPAIR, now)
        val request = OneTimeWorkRequestBuilder<UpdateTask>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_REPAIR to true))
            .addTag(AppConfig.GEO_ASSET_UPDATE_TASK)
            .build()
        RemoteWorkManager.getInstance(context)
            .enqueueUniqueWork(REPAIR_TASK, ExistingWorkPolicy.KEEP, request)
        LogUtil.i(AppConfig.TAG, "GeoAssetUpdater: repair update requested")
    }

    /**
     * Runs [block] holding the asset folder to this caller alone. The Asset files screen, the
     * background refresh and a repair run in different processes, so a file lock does the
     * serialising across them and the monitor does it between threads of one process, which a
     * FileLock does not.
     */
    private fun <T> withAssetLock(extDir: File, block: () -> T): T = synchronized(this) {
        extDir.mkdirs()
        RandomAccessFile(File(extDir, ".geo-assets.lock"), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    /**
     * Keeps the daily refresh in line with the setting. KEEP: calling this on every app start
     * must not push the next run back by a day each time.
     */
    fun schedule(context: Context) {
        val workManager = RemoteWorkManager.getInstance(context)
        if (!isAutoUpdateEnabled()) {
            workManager.cancelUniqueWork(AppConfig.GEO_ASSET_UPDATE_TASK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateTask>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(AppConfig.GEO_ASSET_UPDATE_TASK)
            .build()
        workManager.enqueueUniquePeriodicWork(
            AppConfig.GEO_ASSET_UPDATE_TASK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val repair = inputData.getBoolean(KEY_REPAIR, false)
            if (!repair && !isAutoUpdateEnabled()) return Result.success()
            val result = downloadAll(applicationContext)
            LogUtil.i(
                AppConfig.TAG,
                "GeoAssetUpdater: ${result.successCount} updated, failed: ${result.failedAssets}"
            )
            // Nothing at all came through: most likely no usable network yet, try again later.
            return if (result.successCount == 0 && result.failureCount > 0) Result.retry() else Result.success()
        }
    }
}
