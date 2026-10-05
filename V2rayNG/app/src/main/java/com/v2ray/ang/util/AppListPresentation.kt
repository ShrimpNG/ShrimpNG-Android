package com.v2ray.ang.util

import com.v2ray.ang.dto.AppInfo
import java.text.Collator

/** Common filtering for both app-selection screens. Keep selected system apps reachable. */
object AppListPresentation {
    fun display(
        apps: List<AppInfo>,
        selected: Set<String>,
        showSystem: Boolean,
        selectedFirst: Boolean,
        query: String,
        pinnedPackage: String? = null,
    ): List<AppInfo> {
        val key = query.trim()
        return apps.filter { showSystem || !it.isSystemApp || it.packageName in selected }
            .filter { key.isEmpty() || it.appName.contains(key, ignoreCase = true) || it.packageName.contains(key, ignoreCase = true) }
            .sortedWith(compareBy<AppInfo> { if (it.packageName == pinnedPackage) 0 else 1 }
                .thenBy { if (selectedFirst && it.packageName in selected) 0 else 1 }
                .thenBy(Collator.getInstance()) { it.appName }
                .thenBy { it.packageName })
    }

    fun indexLetter(name: String): String {
        val trimmed = name.trimStart()
        if (trimmed.isEmpty()) return "#"
        val cp = trimmed.codePointAt(0)
        return if (Character.isLetter(cp)) String(Character.toChars(cp)).uppercase() else "#"
    }
}
