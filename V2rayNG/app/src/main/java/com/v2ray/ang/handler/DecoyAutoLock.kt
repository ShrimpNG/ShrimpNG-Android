package com.v2ray.ang.handler

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.v2ray.ang.ui.DecoyCalculatorActivity
import java.lang.ref.WeakReference

/**
 * Puts the disguise back up on its own. Once the app has been in the background for
 * [LOCK_DELAY_MS], every client screen is dropped along with its task, so returning to the app
 * — whether from the launcher or from Recents — starts at the calculator again rather than
 * wherever the user left off.
 *
 * Removing the task also clears the Recents thumbnail, which would otherwise show the client's UI
 * to anyone flicking through open apps.
 *
 * Only the UI is affected: the VPN service is a separate component and keeps running throughout.
 */
object DecoyAutoLock {

    private const val LOCK_DELAY_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { lockNow() }

    /** Client activities only — the decoy itself is never torn down by this. */
    private val clientActivities = mutableListOf<WeakReference<Activity>>()
    private var startedCount = 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is DecoyCalculatorActivity) {
                    clientActivities.add(WeakReference(activity))
                }
            }

            override fun onActivityStarted(activity: Activity) {
                startedCount++
                handler.removeCallbacks(lockRunnable)
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) scheduleLock()
            }

            override fun onActivityDestroyed(activity: Activity) {
                clientActivities.removeAll { it.get() == null || it.get() === activity }
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun scheduleLock() {
        // Nothing to hide when the disguise is off, or when only the calculator was ever open.
        if (!DecoyManager.isActive()) return
        if (clientActivities.none { it.get() != null }) return
        handler.postDelayed(lockRunnable, LOCK_DELAY_MS)
    }

    private fun lockNow() {
        if (startedCount > 0) return
        val live = clientActivities.mapNotNull { it.get() }.filterNot { it.isFinishing }
        clientActivities.clear()
        // The journal is a "who browsed where" database — wipe it with the UI so auto-lock doesn't
        // leave a forensic trail behind a calculator icon. The toggle itself stays as the user set it.
        if (FirewallManager.isLogEnabled()) {
            FirewallManager.clearConnectionLog()
        }
        // Same for the history of connections: which servers, when, from which subscription.
        live.firstOrNull()?.let { SessionLogStore.wipe(it.applicationContext) }
        // finishAndRemoveTask on any one of them takes the whole task, Recents entry included;
        // the rest are finished individually in case they sit in a task of their own.
        live.firstOrNull()?.finishAndRemoveTask()
        live.drop(1).forEach { it.finish() }
    }
}
