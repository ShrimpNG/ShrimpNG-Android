package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppUpdateManager
import com.v2ray.ang.handler.AppUpdateNotifier
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil

/**
 * The first thing the new version runs after an update. Brings back the connection an in-app
 * update cut (AppUpdateManager.install records whether there was one), and clears what the
 * update left behind. A connection that was down stays down.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppUpdateManager.onPackageReplaced(context)
        AppUpdateNotifier.cancel(context)
        AppUpdateManager.schedule(context)

        val reconnect = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_RECONNECT, false)
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_RECONNECT, false)
        if (!reconnect) return
        if (MmkvManager.getSelectServer().isNullOrEmpty()) return
        if (SettingsManager.isVpnMode() && VpnService.prepare(context) != null) {
            LogUtil.w(AppConfig.TAG, "AppUpdate: VPN consent is gone, not reconnecting")
            return
        }
        LogUtil.i(AppConfig.TAG, "AppUpdate: reconnecting after the update")
        CoreServiceManager.startVService(context)
    }
}
