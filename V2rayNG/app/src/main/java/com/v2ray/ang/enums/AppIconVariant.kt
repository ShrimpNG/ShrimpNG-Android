package com.v2ray.ang.enums

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
) {
    DEFAULT(
        prefValue = "default",
        aliasSuffix = "Default",
        labelRes = R.string.app_name,
        statIconRes = R.drawable.ic_stat_name,
        previewIconRes = R.mipmap.ic_launcher,
    ),
    CALCULATOR(
        prefValue = "calculator",
        aliasSuffix = "Calculator",
        labelRes = R.string.shrimp_app_icon_calculator,
        statIconRes = R.drawable.ic_stat_calc,
        previewIconRes = R.mipmap.ic_launcher_calc,
    );

    val isDisguise: Boolean get() = this != DEFAULT

    companion object {
        fun from(prefValue: String?): AppIconVariant =
            entries.firstOrNull { it.prefValue == prefValue } ?: DEFAULT
    }
}
