package com.v2ray.ang.handler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.firewall.ConnLogDao
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Daily retention pass for the connection journal. Scheduled from the UI process via
 * RemoteWorkManager (same pattern as [SubscriptionUpdater]) so it keeps running even when the
 * VPN daemon is down.
 */
object ConnectionLogMaintenance {

    fun schedule(context: Context) {
        if (!FirewallManager.isLogEnabled()) {
            cancel(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<CleanupTask>(1, TimeUnit.DAYS)
            .addTag(AppConfig.FIREWALL_LOG_MAINTENANCE_TASK)
            .build()
        RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AppConfig.FIREWALL_LOG_MAINTENANCE_TASK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        LogUtil.i(AppConfig.TAG, "ConnectionLogMaintenance: scheduled")
    }

    fun cancel(context: Context) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(AppConfig.FIREWALL_LOG_MAINTENANCE_TASK)
    }

    class CleanupTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            if (!FirewallManager.isLogEnabled()) {
                ConnLogDao.get(applicationContext).deleteAll()
                return Result.success()
            }
            val dao = ConnLogDao.get(applicationContext)
            val days = FirewallManager.logRetentionDays()
            val maxRows = FirewallManager.logMaxRows()
            val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
            return try {
                dao.deleteOlderThan(cutoff)
                dao.trimToMaxRows(maxRows)
                dao.vacuum()
                LogUtil.i(AppConfig.TAG, "ConnectionLogMaintenance: cleanup done")
                Result.success()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "ConnectionLogMaintenance: cleanup failed", e)
                Result.retry()
            }
        }
    }
}
