package com.v2ray.ang.util

import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import java.util.concurrent.ConcurrentHashMap

object PackageUidResolver {

    // In-process cache to avoid resolving the same package UID repeatedly.
    private val packageUidCache = ConcurrentHashMap<String, String>()

    // Reverse of [packageUidCache]. Filled by direct getPackagesForUid lookups and by
    // [warmReverseCache]; the forward cache alone is almost always empty for journal UIDs.
    private val uidPackageCache = ConcurrentHashMap<Int, String?>()

    val packageUidMap: Map<String, String>
        get() = packageUidCache

    fun packageNamesToUids(context: Context, packageNames: List<String>): List<String> {
        return packageNames.mapNotNull { pkg ->
            packageUidCache[pkg] ?: resolveUid(context, pkg)?.also { uid ->
                packageUidCache[pkg] = uid
                uid.toIntOrNull()?.let { uidPackageCache.putIfAbsent(it, pkg) }
            }
        }
    }

    fun uidsToPackageNames(uids: List<String>): List<String> {
        if (uids.isEmpty()) return emptyList()
        return packageUidCache.filterValues { it in uids }.keys.toList()
    }

    /** Legacy reverse lookup against the forward cache only — prefer the Context overload. */
    fun uidToPackageName(uid: String): String? {
        return packageUidCache.entries.firstOrNull { it.value == uid }?.key
    }

    /**
     * Resolves a kernel UID to a package name for the connection journal.
     * Special-cases root (0), system (1000) and the "not found" sentinel (-1).
     */
    fun uidToPackageName(context: Context, uid: Int): String? {
        when (uid) {
            -1 -> return null
            0 -> return "root"
            1000 -> return "android"
        }
        uidPackageCache[uid]?.let { return it }
        packageUidCache.entries.firstOrNull { it.value == uid.toString() }?.key?.let { pkg ->
            uidPackageCache[uid] = pkg
            return pkg
        }
        return try {
            val pkgs = context.packageManager.getPackagesForUid(uid)
            val name = pkgs?.firstOrNull()
            uidPackageCache[uid] = name
            name
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "PackageUidResolver: getPackagesForUid($uid) failed", e)
            uidPackageCache[uid] = null
            null
        }
    }

    /** Prefills both caches from installed apps so the first journal flush is cheaper. */
    fun warmReverseCache(context: Context) {
        try {
            @Suppress("DEPRECATION")
            val apps = context.packageManager.getInstalledApplications(0)
            for (app in apps) {
                packageUidCache.putIfAbsent(app.packageName, app.uid.toString())
                uidPackageCache.putIfAbsent(app.uid, app.packageName)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "PackageUidResolver: warmReverseCache failed", e)
        }
    }

    private fun resolveUid(context: Context, packageName: String): String? {
        // Special token for connections whose UID cannot be resolved (mapped to -1)
        if (packageName == AppConfig.UNIDENTIFIED_PACKAGE) {
            val uid = "-1"
            LogUtil.d(AppConfig.TAG, "Special package: $packageName -> UID: $uid")
            return uid
        }

        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0).toString()
            LogUtil.d(AppConfig.TAG, "Package: $packageName -> UID: $uid")
            uid
        } catch (_: PackageManager.NameNotFoundException) {
            LogUtil.w(AppConfig.TAG, "Package not found: $packageName")
            null
        }
    }
}
