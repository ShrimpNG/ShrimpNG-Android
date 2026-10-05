package com.v2ray.ang.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import okhttp3.Request
import java.util.UUID

object DeviceHwid {

    private const val KEY_FALLBACK_HWID = "pref_device_hwid_fallback"

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SEND_HWID_ENABLED, true)

    fun hardwareId(context: Context): String {
        val custom = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_HWID)?.trim()
        if (!custom.isNullOrEmpty()) {
            return custom.take(36)
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }?.trim().orEmpty()

        if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            return androidId.take(36)
        }

        var stored = MmkvManager.decodeSettingsString(KEY_FALLBACK_HWID)?.trim()
        if (stored.isNullOrEmpty()) {
            stored = UUID.randomUUID().toString()
            MmkvManager.encodeSettings(KEY_FALLBACK_HWID, stored)
        }
        return stored.take(36)
    }

    fun applyToRequest(context: Context, requestBuilder: Request.Builder) {
        if (!isEnabled()) return

        val hwid = hardwareId(context)
        if (hwid.isEmpty()) return

        requestBuilder.header("X-HWID", hwid)
        requestBuilder.header("X-Device-OS", "Android")
        requestBuilder.header(
            "X-Ver-OS",
            Build.VERSION.RELEASE.orEmpty().ifEmpty { "unknown" }
        )
        requestBuilder.header("X-Device-Model", deviceModel())
    }

    private fun deviceModel(): String = try {
        Build.MODEL?.trim()?.ifEmpty { "Unknown" } ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }
}
