package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.UpdatesActivity
import com.v2ray.ang.util.UpdateNotesFormatter

/**
 * The one notification about a new version: posted when the user has something to do — the
 * update can't go in by itself, or Android asked for a confirmation. Once per version.
 *
 * Nothing is posted under the calculator disguise, where an update notice would name the app it
 * hides; the badge on the Settings row still shows it to whoever opens the app.
 */
object AppUpdateNotifier {

    private const val CHANNEL_ID = "app_update_channel"
    private const val NOTIFICATION_ID = 3900

    fun notifyAvailable(context: Context, available: AppUpdateManager.Available, ready: Boolean, force: Boolean = false) {
        val versionCode = available.manifest.versionCode
        if (!force && MmkvManager.decodeSettingsLong(AppConfig.PREF_UPDATE_NOTIFIED, 0L) >= versionCode) return
        if (DecoyManager.isActive() || AppIconManager.current().isDisguise) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_NOTIFIED, versionCode)
        post(context, available, ready)
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    @SuppressLint("MissingPermission") // areNotificationsEnabled checked by the caller
    private fun post(context: Context, available: AppUpdateManager.Available, ready: Boolean) {
        ensureChannel(context)
        val manifest = available.manifest
        val notes = AppUpdateManager.localized(manifest.notes)
        val text = UpdateNotesFormatter.plain(notes).ifBlank { context.getString(R.string.update_notify_text) }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, UpdatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val variant = AppIconManager.current()
        val title = context.getString(
            if (ready) R.string.update_notify_ready_title else R.string.update_notify_available_title,
            manifest.versionName,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(variant.statIconRes)
            .setColor(variant.notificationColor)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (manifest.critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                0,
                context.getString(if (ready) R.string.update_action_install else R.string.update_action_open),
                open,
            )
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
