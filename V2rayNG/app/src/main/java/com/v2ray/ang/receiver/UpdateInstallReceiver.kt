package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AppUpdateManager
import com.v2ray.ang.handler.AppUpdateNotifier
import com.v2ray.ang.util.LogUtil

/**
 * The package installer's answer to an update session (see AppUpdateManager.install). Success
 * never arrives here in practice: the process is replaced before it could.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent)
                if (interactive && confirm != null) {
                    // The user is on the Updates screen and asked for this: show Android's prompt.
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // A background install that Android won't let through unasked after all.
                    // Abandoned: the user installs from the notification, which starts afresh.
                    AppUpdateManager.onInstallFailed(null)
                    AppUpdateManager.availableUpdate(context)?.let {
                        AppUpdateNotifier.notifyAvailable(context, it, ready = true, force = true)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> LogUtil.i(AppConfig.TAG, "AppUpdate: installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                LogUtil.w(AppConfig.TAG, "AppUpdate: install failed, status=$status: $message")
                // Aborted is the user dismissing Android's prompt: not an error worth a word.
                AppUpdateManager.onInstallFailed(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) null else message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)

    companion object {
        const val EXTRA_INTERACTIVE = "fish.shrimp.ng.update.INTERACTIVE"
        const val EXTRA_VERSION_CODE = "fish.shrimp.ng.update.VERSION_CODE"
    }
}
