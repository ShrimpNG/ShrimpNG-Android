package com.v2ray.ang.handler

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R

/**
 * The app's colour palette and AMOLED black, chosen on the Themes & icons screen.
 *
 * Presets are static theme overlays generated ahead of time (scripts/themegen) rather than
 * computed at runtime through DynamicColors' content-based source: that path rewrites colour
 * resources through ResourcesLoader, and the whole point of a preset is to look right on ROMs
 * whose own Monet support is broken or stuck on one colour.
 */
object ThemeManager {

    enum class Palette(
        val prefValue: String,
        @StyleRes val overlay: Int?,
        /** Preview colour: themed primary or the accent used by a Fidelity container. */
        @ColorRes val swatch: Int?,
        @StringRes val label: Int,
    ) {
        /** Monet from the wallpaper / system settings: what the app always did before. */
        SYSTEM("system", null, null, R.string.theme_palette_system),
        /** Keep the existing preference key while giving the app's teal its own overlay. */
        SHRIMP("shrimp", R.style.ThemeOverlay_ShrimpNG_Preset_Teal, R.color.preset_teal_seed, R.string.theme_palette_shrimp),
        CORAL("coral", R.style.ThemeOverlay_ShrimpNG_Preset_Coral, R.color.preset_coral_seed, R.string.theme_palette_coral),
        INDIGO("indigo", R.style.ThemeOverlay_ShrimpNG_Preset_Indigo, R.color.preset_indigo_seed, R.string.theme_palette_indigo),
        MONOCHROME("monochrome", R.style.ThemeOverlay_ShrimpNG_Preset_Monochrome, R.color.preset_monochrome_primary, R.string.theme_palette_monochrome),
        RED("red", R.style.ThemeOverlay_ShrimpNG_Preset_Red, R.color.preset_red_primary, R.string.theme_palette_red),
        ORANGE("orange", R.style.ThemeOverlay_ShrimpNG_Preset_Orange, R.color.preset_orange_primary, R.string.theme_palette_orange),
        YELLOW("yellow", R.style.ThemeOverlay_ShrimpNG_Preset_Yellow, R.color.preset_yellow_primary, R.string.theme_palette_yellow),
        GREEN("green", R.style.ThemeOverlay_ShrimpNG_Preset_Green, R.color.preset_green_primary, R.string.theme_palette_green),
        FOREST("forest", R.style.ThemeOverlay_ShrimpNG_Preset_Forest, R.color.preset_forest_primary, R.string.theme_palette_forest),
        CYAN("cyan", R.style.ThemeOverlay_ShrimpNG_Preset_Cyan, R.color.preset_cyan_primary, R.string.theme_palette_cyan),
        BLUE("blue", R.style.ThemeOverlay_ShrimpNG_Preset_Blue, R.color.preset_blue_primary, R.string.theme_palette_blue),
        NAVY("navy", R.style.ThemeOverlay_ShrimpNG_Preset_Navy, R.color.preset_navy_primary, R.string.theme_palette_navy),
        PURPLE("purple", R.style.ThemeOverlay_ShrimpNG_Preset_Purple, R.color.preset_purple_primary, R.string.theme_palette_purple),
        LAVENDER("lavender", R.style.ThemeOverlay_ShrimpNG_Preset_Lavender, R.color.preset_lavender_primary, R.string.theme_palette_lavender),
        PINK("pink", R.style.ThemeOverlay_ShrimpNG_Preset_Pink, R.color.preset_pink_primary, R.string.theme_palette_pink),
        BROWN("brown", R.style.ThemeOverlay_ShrimpNG_Preset_Brown, R.color.preset_brown_primary, R.string.theme_palette_brown);

        companion object {
            /** What a build without a choice yet opens in. */
            val default: Palette get() = if (BuildConfig.LEGACY_BUILD) SHRIMP else SYSTEM

            /**
             * The palettes offered. The legacy build has no "system": below Android 12 there is no
             * Monet, and on EMUI and HarmonyOS Material's dynamic colours are the stock fallback,
             * not the user's.
             */
            val available: List<Palette> get() = if (BuildConfig.LEGACY_BUILD) entries - SYSTEM else entries

            /** Earlier names of presets, so a choice saved under one still resolves. */
            private val renamed = mapOf("vpnet" to "indigo")

            fun from(prefValue: String?): Palette {
                val value = renamed[prefValue] ?: prefValue
                return available.firstOrNull { it.prefValue == value } ?: default
            }
        }
    }

    /**
     * Bumped on every change. Activities remember the value they were themed with and recreate
     * themselves on resume when it moved, so the ones further back in the stack catch up too.
     */
    @Volatile
    var generation: Int = 0
        private set

    fun palette(): Palette = Palette.from(MmkvManager.decodeSettingsString(AppConfig.PREF_THEME_PALETTE))

    fun setPalette(palette: Palette) {
        MmkvManager.encodeSettings(AppConfig.PREF_THEME_PALETTE, palette.prefValue)
        generation++
    }

    fun isAmoled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_THEME_AMOLED, false)

    fun setAmoled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_THEME_AMOLED, enabled)
        generation++
    }

    /** Replaces DynamicColors.applyToActivitiesIfAvailable: same hook, but palette-aware. */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = applyTo(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun applyTo(activity: Activity) {
        val palette = palette()
        when {
            palette == Palette.SYSTEM -> DynamicColors.applyToActivityIfAvailable(activity)
            palette.overlay != null -> activity.theme.applyStyle(palette.overlay, true)
        }
        // By pre-create AppCompat has already put the activity's night mode into its
        // configuration (attachBaseContext), so this follows "auto" correctly too.
        if (isAmoled() && isNight(activity)) {
            activity.theme.applyStyle(R.style.ThemeOverlay_ShrimpNG_Amoled, true)
        }
    }

    private fun isNight(activity: Activity): Boolean =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
