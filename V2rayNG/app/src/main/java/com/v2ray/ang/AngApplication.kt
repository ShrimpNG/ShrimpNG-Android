package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.DecoyAutoLock
import com.v2ray.ang.handler.EmojiStyle
import com.v2ray.ang.handler.GeoAssetUpdater
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.ThemeManager

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // Palette-aware stand-in for DynamicColors.applyToActivitiesIfAvailable (see ThemeManager).
        ThemeManager.install(this)

        MMKV.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)
        SettingsManager.setNightMode()

        // Re-raises the disguise once the app has been in the background long enough.
        DecoyAutoLock.install(this)

        // Once, from the UI process: the daemon and :bg processes run this onCreate too.
        if (currentProcessName() == packageName) {
            // UI process only: the VPN daemon draws no text.
            EmojiStyle.install(this)
            GeoAssetUpdater.schedule(this)
        }
    }

    /**
     * This process's name. Application.getProcessName() exists from Android 9; the legacy build
     * reaches Android 8, where /proc/self/cmdline holds the same, NUL-terminated.
     */
    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }
            .getOrDefault(packageName)
}
