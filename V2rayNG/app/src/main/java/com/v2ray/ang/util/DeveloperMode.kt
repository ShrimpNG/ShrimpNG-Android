package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

object DeveloperMode {

    private const val TAP_RESET_MS = 1500L
    private var lastTapMs = 0L
    private var tapCount = 0

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DEVELOPER_MODE_ENABLED, false)

    fun onVersionTapped(): TapResult {
        if (isEnabled()) {
            return TapResult.AlreadyEnabled
        }

        val now = System.currentTimeMillis()
        if (now - lastTapMs > TAP_RESET_MS) {
            tapCount = 0
        }
        lastTapMs = now
        tapCount++

        return when {
            tapCount >= AppConfig.DEVELOPER_MODE_TAP_COUNT -> {
                MmkvManager.encodeSettings(AppConfig.PREF_DEVELOPER_MODE_ENABLED, true)
                tapCount = 0
                TapResult.Enabled
            }

            tapCount >= AppConfig.DEVELOPER_MODE_TAP_HINT_AT -> {
                TapResult.StepsAway(AppConfig.DEVELOPER_MODE_TAP_COUNT - tapCount)
            }

            else -> TapResult.Silent
        }
    }

    sealed class TapResult {
        data object Silent : TapResult()
        data object AlreadyEnabled : TapResult()
        data object Enabled : TapResult()
        data class StepsAway(val remaining: Int) : TapResult()
    }
}
