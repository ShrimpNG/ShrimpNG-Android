package com.v2ray.ang.handler

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.LogUtil

/**
 * Swaps which launcher identity the app presents, by enabling exactly one of the
 * `<activity-alias>` entries declared in the manifest and disabling the rest.
 */
object AppIconManager {

    /**
     * Falls back to the disguise on a [BuildConfig.DISGUISE_BY_DEFAULT] build, so that variant is
     * already disguised on first launch. Once the user picks an icon their choice is stored and
     * wins from then on — including picking the standard one back.
     */
    fun current(): AppIconVariant {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_APP_ICON)
        if (stored.isNullOrBlank()) {
            return if (BuildConfig.DISGUISE_BY_DEFAULT) AppIconVariant.CALCULATOR else AppIconVariant.DEFAULT
        }
        return AppIconVariant.from(stored)
    }

    /**
     * Enables [variant]'s alias and disables every other one.
     *
     * The enable happens first on purpose: with all aliases disabled, even briefly, the package
     * has no launcher component at all and some launchers drop the app from the drawer for good.
     * [PackageManager.DONT_KILL_APP] asks the system not to restart us, but plenty of OEM ROMs
     * ignore it — callers should warn the user that the app may close before calling this.
     */
    fun apply(context: Context, variant: AppIconVariant) {
        val pm = context.packageManager
        try {
            pm.setComponentEnabledSetting(
                aliasComponent(context, variant),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppIconVariant.entries.filter { it != variant }.forEach { other ->
                pm.setComponentEnabledSetting(
                    aliasComponent(context, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            MmkvManager.encodeSettings(AppConfig.PREF_APP_ICON, variant.prefValue)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to apply app icon variant ${variant.prefValue}", e)
        }
    }

    /**
     * The alias *class* names come from the module namespace (`com.v2ray.ang`), which is not the
     * applicationId (`fish.shrimp.ng`, plus a suffix on the F-Droid flavour) that
     * [Context.getPackageName] returns — building the class name out of the package name would
     * point at components that don't exist. Derive the namespace from a class that lives beside
     * the aliases instead, so a package rename can't quietly break the lookup.
     */
    private fun aliasComponent(context: Context, variant: AppIconVariant): ComponentName {
        val uiPackage = MainActivity::class.java.name.substringBeforeLast('.')
        return ComponentName(context.packageName, "$uiPackage.Alias${variant.aliasSuffix}")
    }
}
