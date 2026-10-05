package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

object ConnectionTimer {

    fun markStarted() {
        if (getStartedAtMillis() == null) {
            MmkvManager.encodeSettings(AppConfig.PREF_CONNECTION_STARTED_AT, System.currentTimeMillis())
        }
    }

    fun resetStarted() {
        MmkvManager.encodeSettings(AppConfig.PREF_CONNECTION_STARTED_AT, System.currentTimeMillis())
    }

    fun markStopped() {
        MmkvManager.encodeSettings(AppConfig.PREF_CONNECTION_STARTED_AT, 0L)
    }

    fun getStartedAtMillis(): Long? {
        val value = MmkvManager.decodeSettingsLong(AppConfig.PREF_CONNECTION_STARTED_AT, 0L)
        return if (value > 0L) value else null
    }

    fun elapsedMillis(): Long {
        val startedAt = getStartedAtMillis() ?: return 0L
        return (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    }

    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    fun formattedElapsed(): String = formatElapsed(elapsedMillis())
}
