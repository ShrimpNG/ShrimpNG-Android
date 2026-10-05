package com.v2ray.ang.enums

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * A launcher identity the app can wear. Everything user-visible *outside* the app — the launcher
 * icon and label, the status bar icon, the Quick Settings tile — follows the selected variant, so
 * a glance at the home screen or notification shade doesn't reveal a VPN client. The UI inside the
 * app is deliberately left alone.
 *
 * Single source of truth: adding a variant means one constant here, one `<activity-alias>` in the
 * manifest whose name ends in [aliasSuffix], plus the icon/label resources it points at.
 */
enum class AppIconVariant(
    val prefValue: String,
    /** Appended to "<applicationId>.ui.Alias" to form the alias component name. */
    val aliasSuffix: String,
    @StringRes val labelRes: Int,
    @DrawableRes val statIconRes: Int,
    /** Drawable used for the in-app preview; the launcher itself reads the alias's android:icon. */
    @DrawableRes val previewIconRes: Int,
    /** Caption in the picker, for variants that share their launcher label with another one. */
    @StringRes val pickerLabelRes: Int = labelRes,
    /** Hides that the app is a VPN client: no server names in the shade, no shortcuts menu. */
    val isDisguise: Boolean = false,
    /**
     * Fill of the circle the shade draws the status icon in (the system tints the glyph to
     * contrast); 0 leaves the system's default grey. The light variant's white circle and dark
     * shrimp match its launcher icon and Quick Settings tile.
     */
    @ColorInt val notificationColor: Int = 0,
) {
    DEFAULT(
        prefValue = "default",
        aliasSuffix = "Default",
        labelRes = R.string.app_name,
        statIconRes = R.drawable.ic_stat_name,
        previewIconRes = R.mipmap.ic_launcher,
    ),
    LIGHT(
        prefValue = "light",
        aliasSuffix = "Light",
        labelRes = R.string.app_name,
        statIconRes = R.drawable.ic_stat_name,
        previewIconRes = R.mipmap.ic_launcher_light,
        pickerLabelRes = R.string.shrimp_app_icon_light,
        notificationColor = Color.WHITE,
    ),
    CALCULATOR(
        prefValue = "calculator",
        aliasSuffix = "Calculator",
        labelRes = R.string.shrimp_app_icon_calculator,
        statIconRes = R.drawable.ic_stat_calc,
        previewIconRes = R.mipmap.ic_launcher_calc,
        isDisguise = true,
    );

    companion object {
        fun from(prefValue: String?): AppIconVariant =
            entries.firstOrNull { it.prefValue == prefValue } ?: DEFAULT
    }
}
