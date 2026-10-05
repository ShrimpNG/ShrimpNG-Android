package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.widget.IconShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    /**
     * Load the list of network applications.
     *
     * @param context The context to use.
     * @return A list of AppInfo objects representing the network applications.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val apps = ArrayList<AppInfo>()

            for (pkg in packages) {
                val applicationInfo = pkg.applicationInfo ?: continue

                val appName = applicationInfo.loadLabel(packageManager).toString()
                // Redrawn here, off the main thread, for the lists that show it (IconShape).
                val appIcon = applicationInfo.loadIcon(packageManager)?.let { IconShape.shaped(context, it, 48) } ?: continue
                val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0

                val appInfo = AppInfo(appName, pkg.packageName, appIcon, isSystemApp, 0)
                apps.add(appInfo)
            }

            return@withContext apps
        }

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
