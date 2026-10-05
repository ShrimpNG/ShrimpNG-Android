package com.v2ray.ang.util

import android.graphics.drawable.Drawable
import com.v2ray.ang.dto.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class AppListPresentationTest {
    private fun app(name: String, pkg: String, system: Boolean = false) =
        AppInfo(name, pkg, mock<Drawable>(), system, 0)

    @Test fun selectedSystemAppsRemainReachableWithSystemFilterOff() {
        val apps = listOf(app("Browser", "browser"), app("Selected", "selected", true), app("Hidden", "hidden", true))
        assertEquals(listOf("selected", "browser"),
            AppListPresentation.display(apps, setOf("selected"), false, true, "").map { it.packageName })
    }

    @Test fun searchMatchesNameOrPackageAndDoesNotChangeTheSelection() {
        val selected = setOf("hidden")
        val apps = listOf(app("Browser", "org.browser"), app("Other", "hidden", true))
        assertEquals(listOf("org.browser"),
            AppListPresentation.display(apps, selected, false, true, " ORG.BROWSER ").map { it.packageName })
        assertEquals(listOf("org.browser"),
            AppListPresentation.display(apps, selected, false, true, "BROWser").map { it.packageName })
        assertEquals(setOf("hidden"), selected)
    }

    @Test fun routingUnknownEntryStaysFirstAndSelectedFirstCanBeDisabled() {
        val apps = listOf(app("Z unknown", "unknown"), app("A browser", "browser"), app("B selected", "selected"))
        assertEquals(listOf("unknown", "selected", "browser"),
            AppListPresentation.display(apps, setOf("selected"), false, true, "", "unknown").map { it.packageName })
        assertEquals(listOf("unknown", "browser", "selected"),
            AppListPresentation.display(apps, setOf("selected"), false, false, "", "unknown").map { it.packageName })
    }
}
