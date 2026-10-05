package com.v2ray.ang.handler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.SubscriptionExpiry
import com.v2ray.ang.util.SubscriptionExpiry.Stage
import com.v2ray.ang.util.Utils

/**
 * Reminds that a subscription is about to end or has ended: three days before, the last day, and
 * once it has expired (a short one such as a trial only at the end, see
 * [SubscriptionExpiry.shouldRemind]) — each at most once per end date, so a renewal (a new date) starts over and
 * clears a reminder already shown. Checked by the background subscription refresh.
 *
 * Nothing is posted under the calculator disguise: a notice about a VPN subscription is exactly
 * what it hides.
 */
object SubscriptionExpiryNotifier {

    private const val CHANNEL_ID = "subscription_expiry_channel"
    private const val NOTIFIED_PREFIX = "pref_expiry_notified_"

    fun check(context: Context, subId: String) {
        try {
            val sub = MmkvManager.decodeSubscription(subId) ?: return
            val expireAt = sub.expireAtSeconds
            val stage = SubscriptionExpiry.stage(expireAt)
            val key = NOTIFIED_PREFIX + subId
            val (notifiedFor, notifiedStage) = MmkvManager.decodeSettingsString(key)
                ?.split(":")?.takeIf { it.size == 2 }
                ?.let { it[0].toLongOrNull() to it[1].toIntOrNull() } ?: (null to null)
            val already = if (notifiedFor == expireAt) notifiedStage ?: 0 else 0

            if (stage == Stage.NONE) {
                // Renewed, or the date went away: a reminder left in the shade is now wrong.
                if (notifiedStage != null) {
                    NotificationManagerCompat.from(context).cancel(notificationId(subId))
                    MmkvManager.encodeSettings(key, null as String?)
                }
                return
            }
            if (stage.ordinal <= already) return
            // Remembered even when nothing gets shown, so turning reminders back on later does not
            // fire a backlog of stale ones.
            MmkvManager.encodeSettings(key, "$expireAt:${stage.ordinal}")

            if (!SubscriptionExpiry.shouldRemind(stage, sub.addedTime, expireAt!!)) return
            if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_EXPIRY_REMINDERS, true)) return
            if (DecoyManager.isActive() || AppIconManager.current().isDisguise) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

            // Renewing happens where support is: providers usually point that link at their bot.
            val renewUrl = sub.supportUrl?.takeIf { it.isNotBlank() } ?: sub.webPageUrl
            post(context, subId, sub.remarks, expireAt, stage, renewUrl)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SubscriptionExpiryNotifier: ${e.message}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // areNotificationsEnabled checked above
    private fun post(context: Context, subId: String, remarks: String, expireAt: Long, stage: Stage, helpUrl: String?) {
        ensureChannel(context)
        val name = remarks.ifBlank { context.getString(R.string.shrimp_home_title) }
        val date = Utils.formatTimestamp(expireAt * 1000, pattern = "dd.MM.yyyy HH:mm")
        val title: String
        val text: String
        when (stage) {
            Stage.EXPIRED -> {
                title = context.getString(R.string.expiry_notify_expired_title, name)
                text = context.getString(R.string.expiry_notify_expired_text, date)
            }
            Stage.DAY -> {
                title = context.getString(R.string.expiry_notify_soon_title, name)
                text = context.getString(R.string.expiry_notify_day_text, date)
            }
            else -> {
                val days = SubscriptionExpiry.daysLeft(expireAt)
                title = context.getString(R.string.expiry_notify_soon_title, name)
                text = context.getString(
                    R.string.expiry_notify_soon_text,
                    context.resources.getQuantityString(R.plurals.expiry_days, days, days),
                    date,
                )
            }
        }
        val open = PendingIntent.getActivity(
            context, notificationId(subId),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val variant = AppIconManager.current()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(variant.statIconRes)
            .setColor(variant.notificationColor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        helpUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val help = PendingIntent.getActivity(
                context, notificationId(subId) + 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.expiry_notify_action_renew), help)
        }
        NotificationManagerCompat.from(context).notify(notificationId(subId), builder.build())
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.expiry_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    /** One per subscription, clear of the fixed ids the other channels use (1, 12, 13). */
    private fun notificationId(subId: String) = 4000 + (subId.hashCode() and 0xFFF) * 2
}
