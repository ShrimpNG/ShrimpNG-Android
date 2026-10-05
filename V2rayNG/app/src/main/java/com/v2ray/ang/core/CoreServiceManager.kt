package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.ConnectionLogRecorder
import com.v2ray.ang.handler.SessionLogRecorder
import com.v2ray.ang.handler.ConnectionTimer
import com.v2ray.ang.handler.FingerprintManager
import com.v2ray.ang.handler.FingerprintProbe
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.IDialerService
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder

object CoreServiceManager {

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    /** Settling time before judging whether the tunnel actually carries traffic. */
    private const val FINGERPRINT_PROBE_DELAY_MS = 2000L

    /** Settling time after a wake-up or a network change before the tunnel is judged. */
    private const val WATCHDOG_PROBE_DELAY_MS = 1500L
    private const val WATCHDOG_RETRY_DELAY_MS = 3000L

    /** A core that only just came up is not judged; its start-up is still proving itself. */
    private const val WATCHDOG_GRACE_MS = 30_000L
    private var watchdogJob: Job? = null

    @Volatile
    private var coreStartedAt = 0L
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        if (guid != null) {
            // Validated before it is persisted. This is reachable from Tasker, whose automations
            // hold GUIDs from whenever they were written, and a subscription refresh recreates
            // every profile under a fresh GUID — storing an unknown one would break not just this
            // start but every later one, from every entry point, until a server is picked by hand.
            if (decodeProfileAllowingStaleView(guid) == null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Requested profile '$guid' does not exist")
                context.toast(R.string.shrimp_toast_selected_server_gone)
                return
            }
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Decode one profile by GUID, re-reading storage once before concluding it is missing.
     *
     * For a GUID supplied from outside — a Tasker automation naming a specific server — where
     * re-reading the selection would answer a different question than the one being asked.
     * [resolveSelectedProfile] is the one to use when the question is "what is selected".
     */
    private fun decodeProfileAllowingStaleView(guid: String): ProfileItem? {
        MmkvManager.decodeServerConfig(guid)?.let { return it }
        MmkvManager.syncFromOtherProcesses()
        return MmkvManager.decodeServerConfig(guid)
    }

    /**
     * Resolve the selected server and its profile, re-reading both once before giving up.
     *
     * A profile that cannot be found is more often a stale cross-process view than a deletion:
     * subscriptions are refreshed in the ":bg" process and recreate every profile under a new
     * GUID, while this code frequently runs in ":RunSoLibV2RayDaemon" behind the tile, the widget
     * or a shortcut — a process that can outlive several such refreshes.
     *
     * @return the GUID and its profile, or null once a re-read confirms nothing is selected or
     *   the selected profile really is gone.
     */
    private fun resolveSelectedProfile(): Pair<String, ProfileItem>? {
        MmkvManager.getSelectServer()?.let { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { return guid to it }
        }

        // Re-read the *selection* as well as the profile, not the profile alone. A subscription
        // refresh deletes every profile it owns and writes the replacements under fresh GUIDs, so
        // a process holding a view from before it is left with a GUID that no longer exists while
        // the selection has already moved to one that does. Looking the obsolete GUID up again
        // against fresh storage can only fail a second time — and that failure was being answered
        // by clearing a selection which was, in fact, perfectly good.
        MmkvManager.syncFromOtherProcesses()
        val guid = MmkvManager.getSelectServer() ?: return null
        return MmkvManager.decodeServerConfig(guid)?.let { guid to it }
    }

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     * @throws IllegalStateException if the core is already running, no server is selected,
     *   server config cannot be decoded, or server configuration is invalid.
     * @throws Exception if the foreground service fails to start.
     */
    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val (guid, config) = resolveSelectedProfile()
            ?: run {
                // resolveSelectedProfile has already re-read storage, so this is the settled
                // answer rather than a stale one.
                val selected = MmkvManager.getSelectServer()
                    ?: run {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: No server selected")
                        error(context.getString(R.string.app_tile_first_use))
                    }
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Selected profile '$selected' no longer exists")
                // Confirmed gone, not merely unseen. Drop the dangling selection: leaving it would
                // hand the same failure to every future start, which is what forced users to
                // restart the whole app to get moving again.
                MmkvManager.clearSelectServer()
                error(context.getString(R.string.shrimp_toast_selected_server_gone))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: Invalid server configuration: '${config.remarks}' (${config.configType}) " +
                    "server '${config.server}' as $guid"
            )
            error(context.getString(R.string.toast_config_file_invalid))
        }

        // refresh socks port when enabled dynamic socks port
        SettingsManager.refreshRuntimeSocksPort()

//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })

        if (config.insecure == true) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            context.toastError(R.string.toast_allow_insecure_deprecated)
        }

        // Only the sharing warning is worth raising here. Announcing the *attempt* as well left
        // two toasts per connect — "Start Services" now and "Start Services Success" a moment
        // later — where the second one already says everything the first did, and says it only
        // once the core actually came up.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val attemptStartedAt = System.currentTimeMillis()
        // Re-read here too, and not redundantly: startContextService runs in whichever process
        // asked for the connection — often the UI's — while this is the daemon's own first look at
        // storage, and so exactly where a view from before a subscription refresh surfaces. The
        // messages are resources rather than the bare English they used to be, because they are
        // handed to the user through MSG_STATE_START_FAILURE.
        val (guid, config) = resolveSelectedProfile()
            ?: error(
                if (MmkvManager.getSelectServer() == null) {
                    service.getString(R.string.app_tile_first_use)
                } else {
                    service.getString(R.string.shrimp_toast_selected_server_gone)
                }
            )

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerAddr = if (currentConfig?.browserDialerMode.isNullOrEmpty()) {
            ""
        } else {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(result.content, tunFd)

        if (!coreController.isRunning) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        if (config.browserDialerMode == "OkHttp") {
            browserDialer = DialerNativeService()
            browserDialer!!.start(service, dialerAddr)
        } else if (config.browserDialerMode == "WebView") {
            browserDialer = DialerWebviewService()
            browserDialer!!.start(service, dialerAddr)
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        // Raised here rather than in MainViewModel's receiver, which only exists while the app is
        // on screen: connecting from the tile, the widget or a shortcut used to confirm nothing at
        // all. Posted to the main looper because this runs on whichever thread brought the core up.
        //
        // Held back mid-search: a fingerprint search reconnects once per candidate, and announcing
        // success at each attempt would flash the toast several times before anything works. The
        // search raises it itself once it settles.
        if (!FingerprintProbe.isSearching(config)) {
            Handler(Looper.getMainLooper()).post { service.toast(R.string.toast_services_success) }
        }
        verifyFingerprintIfNeeded(service, guid, config)
        coreStartedAt = System.currentTimeMillis()
        ConnectionTimer.resetStarted()
        NotificationManager.startSpeedNotification()
        ConnectionLogRecorder.start(service)
        SessionLogRecorder.start(service, guid, config, attemptStartedAt)
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        watchdogJob?.cancel()
        watchdogJob = null

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        ConnectionTimer.markStopped()
        NotificationManager.cancelNotification()
        ConnectionLogRecorder.stop()
        SessionLogRecorder.stop()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    /**
     * Check that traffic actually leaves through this server, and if it does not, look for a uTLS
     * fingerprint that works.
     *
     * The check is the same delay probe the UI uses — a real request through the running core —
     * because a tunnel comes up and reports itself connected whether or not the handshake to the
     * server succeeded. That distinction is the whole point: a blocked fingerprint fails here and
     * nowhere else.
     *
     * A failure hands off to [FingerprintProbe], which either names the next candidate or has
     * already restored the original and given up. Nothing here loops on its own: each attempt ends
     * by asking the service to restart, which comes back through this same function once, and the
     * candidate list is finite.
     */
    private fun verifyFingerprintIfNeeded(service: Service, guid: String, profile: ProfileItem) {
        // What the config pins on its own, independent of any override already in place — read
        // once and reused below, since it is what a fresh search has to be seeded with. For a raw
        // provider config the value has to be found inside the JSON itself; the profile's own
        // fingerPrint field is never populated for one (nothing parses it out at import), so
        // reading that instead would look like the config pins nothing at all.
        val builtInFingerprint = if (profile.configType == EConfigType.CUSTOM) {
            MmkvManager.decodeServerRaw(guid)
                ?.let { JsonUtil.parseString(it) }
                ?.let { CoreRawFingerprintPatcher.currentFingerprint(it) }
        } else {
            profile.fingerPrint
        }
        if (!FingerprintProbe.appliesTo(profile, builtInFingerprint)) return

        CoroutineScope(Dispatchers.IO).launch {
            // Long enough for the core to have finished coming up; the probe would otherwise race
            // the very startup it is meant to be judging.
            delay(FINGERPRINT_PROBE_DELAY_MS)
            if (coreController.isRunning == false) return@launch

            val reachable = runCatching {
                coreController.measureDelay(SettingsManager.getDelayTestUrl())
            }.getOrDefault(-1L) >= 0

            if (reachable) {
                val wasSearching = FingerprintProbe.isSearching(profile)
                FingerprintProbe.succeeded(profile)
                if (wasSearching) {
                    Handler(Looper.getMainLooper()).post { service.toast(R.string.toast_services_success) }
                }
                return@launch
            }

            val next = FingerprintProbe.nextCandidate(profile, builtInFingerprint) ?: return@launch
            FingerprintManager.setOverride(profile, next)
            MessageUtil.sendMsg2Service(service, AppConfig.MSG_STATE_RESTART, "")
        }
    }

    /**
     * Make sure the tunnel still carries traffic after an event that tends to have killed it
     * silently — the screen coming back on after a long sleep, or the default network changing
     * underneath the VPN — and bring the core back if it does not.
     *
     * Nothing gets unloaded during sleep: the core is a thread in the same process that owns the
     * tun, and the VPN icon staying up is proof that process is alive. What dies is state on the
     * far side — a carrier NAT drops idle mappings, the server closes its end — while the client
     * keeps connections it believes are fine. Each layer notices eventually (TCP keepalive after
     * some seven minutes, an H2 ping after one), but the user is looking at a connected icon and
     * a dead page long before that, and a transport that pools one connection — gRPC, mux —
     * stalls outright until then.
     *
     * A probe costs one small request through the tunnel. A reload costs the open connections,
     * which by the time it runs were dead anyway.
     */
    fun checkTunnelHealth(reason: String) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_TUNNEL_WATCHDOG, true)) return
        if (!coreController.isRunning) return
        val service = getService() ?: return
        val profile = currentConfig ?: return
        // The search reconnects on its own and judges each attempt itself.
        if (FingerprintProbe.isSearching(profile)) return
        if (System.currentTimeMillis() - coreStartedAt < WATCHDOG_GRACE_MS) return

        synchronized(this) {
            if (watchdogJob?.isActive == true) return
            watchdogJob = CoroutineScope(Dispatchers.IO).launch {
                delay(WATCHDOG_PROBE_DELAY_MS)
                if (!coreController.isRunning) return@launch
                if (!hasUsableNetwork(service)) {
                    LogUtil.i(AppConfig.TAG, "Watchdog: no network after $reason, nothing to judge")
                    return@launch
                }
                if (probeTunnel()) return@launch

                // Once more, on the other URL, before deciding: the first attempt may have hit
                // the tail of the wake-up rather than a dead tunnel.
                delay(WATCHDOG_RETRY_DELAY_MS)
                if (!coreController.isRunning || !hasUsableNetwork(service)) return@launch
                if (probeTunnel(second = true)) return@launch

                LogUtil.w(AppConfig.TAG, "Watchdog: tunnel carries no traffic after $reason, reloading core")
                reloadCore(service)
            }
        }
    }

    /**
     * Whether there is a network the tunnel could possibly work over. This process is excluded
     * from its own VPN, so its active network is the physical one — absent while the radio is
     * still waking, when a failed probe would say nothing about the tunnel.
     */
    private fun hasUsableNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun probeTunnel(second: Boolean = false): Boolean =
        runCatching { coreController.measureDelay(SettingsManager.getDelayTestUrl(second)) }
            .getOrDefault(-1L) >= 0

    /**
     * Restart the core in place, keeping the VPN interface, the notification and everything else
     * the user can see. Only on the hev-socks5-tunnel path, where the core does not own the tun:
     * it serves a socks port the tunnel reconnects to on its own with every new session. With the
     * core's own tun inbound the fd would change hands, so that path takes the full restart.
     */
    private fun reloadCore(service: Service) {
        if (!SettingsManager.isUsingHevTun()) {
            MessageUtil.sendMsg2Service(service, AppConfig.MSG_STATE_RESTART, "")
            return
        }

        val (guid, config) = resolveSelectedProfile()
            ?: run {
                LogUtil.e(AppConfig.TAG, "Watchdog: selected profile is gone, cannot reload")
                return
            }
        // Rebuilt rather than reused: this is also what re-resolves the server's address, which
        // is pinned into the config at build time and may itself be what went stale.
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            LogUtil.e(AppConfig.TAG, "Watchdog: config rebuild failed: ${result.errorMessage}")
            return
        }

        try {
            coreController.stopLoop()
            coreController.startLoop(result.content, 0)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Watchdog: core reload failed", e)
        }

        if (!coreController.isRunning) {
            // A tunnel with no core behind it is the one state worse than the one being fixed:
            // the icon says connected and nothing moves. Hand it to the full restart, which either
            // brings everything back or takes the icon down with an honest error.
            LogUtil.e(AppConfig.TAG, "Watchdog: core did not come back, falling back to a full restart")
            MessageUtil.sendMsg2Service(service, AppConfig.MSG_STATE_RESTART, "")
            return
        }

        currentConfig = config
        coreStartedAt = System.currentTimeMillis()
        LogUtil.i(AppConfig.TAG, "Watchdog: core reloaded for ${config.remarks}")
    }

    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(
            network: String,
            srcIP: String,
            srcPort: Long,
            destIP: String,
            destPort: Long,
            domain: String,
        ): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                )
                // Hot path: one trySend max. UDP is not journalled by the TCP-only sentinel, but
                // blocked-app process rules still invoke this for udp — keep offer cheap.
                ConnectionLogRecorder.offer(
                    network = network,
                    srcPort = srcPort.toInt(),
                    destIp = destIP,
                    destPort = destPort.toInt(),
                    uid = uid,
                    domain = domain,
                )
                LogUtil.d(
                    AppConfig.TAG,
                    "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort" +
                        (if (domain.isNotBlank()) " domain=$domain" else "") +
                        ", uid=$uid",
                )
                uid.toLong()
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                    checkTunnelHealth("screen on")
                }
            }
        }
    }
}