package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.util.SimpleExpression

/**
 * State for the decoy screen: a working calculator shown in place of the client, which hands over
 * to the real UI once the user enters a secret expression. Only meaningful while the Calculator
 * disguise is active — the decoy would be nonsense under the app's own identity.
 */
object DecoyManager {

    const val DEFAULT_EXPRESSION = "1984*1984"

    /** On a disguised build the decoy is on out of the box; elsewhere it is opt-in. */
    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DECOY_ENABLED, BuildConfig.DISGUISE_BY_DEFAULT)

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DECOY_ENABLED, enabled)
    }

    /** True when the decoy should actually stand in front of the client right now. */
    fun isActive(): Boolean =
        isEnabled() && AppIconManager.current() == AppIconVariant.CALCULATOR

    fun unlockExpression(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_DECOY_EXPRESSION)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_EXPRESSION

    /**
     * Stores [expression] if the calculator keypad can actually produce it.
     * @return true when it was accepted.
     */
    fun setUnlockExpression(expression: String): Boolean {
        if (!SimpleExpression.isKeypadTypable(expression)) return false
        MmkvManager.encodeSettings(AppConfig.PREF_DECOY_EXPRESSION, SimpleExpression.normalize(expression))
        return true
    }

    /**
     * Puts the unlock expression back to [DEFAULT_EXPRESSION]. This is the only way back in once
     * the expression is forgotten, so it is reachable from the decoy itself rather than from any
     * screen that already requires getting past the decoy.
     */
    fun resetUnlockExpression() {
        MmkvManager.encodeSettings(AppConfig.PREF_DECOY_EXPRESSION, DEFAULT_EXPRESSION)
    }

    /** Compares ignoring keypad glyph differences (× vs *, spacing, …). */
    fun matchesUnlock(input: String): Boolean {
        val typed = SimpleExpression.normalize(input)
        return typed.isNotEmpty() && typed == SimpleExpression.normalize(unlockExpression())
    }
}
