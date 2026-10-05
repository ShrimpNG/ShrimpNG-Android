package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        val existingWorkPolicy =
            if (forceReschedule) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        MmkvManager.decodeSubscriptions().forEach { sub ->
            scheduleOne(
                context = context,
                subId = sub.guid,
                shouldRun = sub.subscription.autoUpdate,
                existingWorkPolicy = existingWorkPolicy
            )
        }
        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
        )
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        scheduleOne(
            context = context,
            subId = subId,
            shouldRun = subItem.autoUpdate,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    /** PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS: WorkManager silently raises anything shorter to this. */
    private const val PERIODIC_FLOOR_MINUTES = 15L

    /**
     * @param afterRun true when called from the worker itself once an update has run, so the next
     *   one-time request waits a full interval whether or not the update succeeded — otherwise a
     *   failed update leaves lastUpdated untouched and the next run would fire immediately.
     */
    private fun scheduleOne(
        context: Context,
        subId: String,
        shouldRun: Boolean,
        existingWorkPolicy: ExistingPeriodicWorkPolicy,
        afterRun: Boolean = false,
    ) {
        val rw = RemoteWorkManager.getInstance(context)
        if (!shouldRun) {
            rw.cancelUniqueWork(taskName(subId))
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for $subId")
            return
        }

        val subItem = MmkvManager.decodeSubscription(subId) ?: return

        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            subItem.updateInterval
        )

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        if (intervalMinutes < PERIODIC_FLOOR_MINUTES) {
            // Below WorkManager's periodic floor the schedule has to be a chain of one-time
            // requests, each enqueued by the worker after it has run (see UpdateTask.doWork).
            val request = OneTimeWorkRequestBuilder<UpdateTask>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_SUB_ID to subId))
                .setInitialDelay(if (afterRun) intervalMillis else initialDelayMillis, TimeUnit.MILLISECONDS)
                .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                .build()
            // From the worker itself the next link is appended behind the current run rather
            // than replacing it; REPLACE would cancel the run that is enqueueing it.
            val policy = if (afterRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
            rw.enqueueUniqueWork(taskName(subId), policy, request)
            LogUtil.i(
                AppConfig.TAG,
                "SubscriptionUpdater: scheduled one-time [$subId] interval=${intervalMinutes}min " +
                        "delay=${(if (afterRun) intervalMillis else initialDelayMillis) / 1000}s"
            )
            return
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        rw.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [$subId] interval=${intervalMinutes}min " +
                    "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private const val KEY_SUB_ID = "subId"

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update starting: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            val subItem = MmkvManager.decodeSubscription(subId)
            if (subItem == null) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: no subscription found for $subId")
                return Result.success()
            }

            if (!subItem.autoUpdate) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: auto-update disabled for $subId, skip")
                return Result.success()
            }

            val sub = SubscriptionCache(subId, subItem)

            // Notify about update start
            NotificationHelper.notify(
                NotificationChannelType.SUBSCRIPTION_UPDATE,
                applicationContext,
                applicationContext.getString(R.string.title_pref_auto_update_subscription),
                "Updating ${sub.subscription.remarks}"
            )

            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update: ---${sub.subscription.remarks}")
            AngConfigManager.updateConfigViaSub(sub, reschedule = false)

            // Clear notification
            NotificationHelper.cancel(NotificationChannelType.SUBSCRIPTION_UPDATE, applicationContext)

            // With the end date just refreshed from the provider.
            SubscriptionExpiryNotifier.check(applicationContext, subId)

            // Rescheduled here, after the update, never from inside it. UPDATE leaves this run
            // alone and applies any interval the provider just changed to the next one; a
            // sub-15-minute schedule is a chain of one-time requests, and this is the next link.
            MmkvManager.decodeSubscription(subId)?.let { updated ->
                scheduleOne(
                    applicationContext,
                    subId,
                    shouldRun = updated.autoUpdate,
                    existingWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                    afterRun = true,
                )
            }

            return Result.success()
        }
    }
}