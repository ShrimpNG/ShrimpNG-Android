package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The geo/asset file list and its download, shared by the Asset files screen's download button
 * and the daily background refresh.
 *
 * Downloaded files take effect the next time the core starts; a running connection keeps the
 * rule sets it loaded.
 */
object GeoAssetUpdater {

    private val builtInGeoFiles = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

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
        val targetTemp = File(extDir, item.remarks + "_temp")
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
            if (downloaded && targetTemp.length() > 0) {
                targetTemp.renameTo(target)
                return true
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        }
        targetTemp.delete()
        return false
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
            if (!isAutoUpdateEnabled()) return Result.success()
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
