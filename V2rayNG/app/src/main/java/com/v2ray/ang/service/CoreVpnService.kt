package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.FirewallManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.FirewallTunnelSelection
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.WifiSsidUtil
import com.v2ray.ang.util.WireguardRoutes
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    // Trusted-Wi-Fi routing is re-evaluated fresh every time the core config is (re)built — this
    // just detects that the connected SSID actually changed while the VPN keeps running, and
    // triggers the ordinary restart so the config gets rebuilt against the new SSID. Initialized
    // once when the interface is established (see configureVpnService()) so the network
    // callback's first report doesn't look like a spurious change from "unknown".
    private var lastKnownSsid: String? = null
    private var lastNetworkKind: com.v2ray.ang.util.NetworkMeteredUtil.Kind? = null

    // The last network the VPN ran over, kept across onLost so that the one arriving after it
    // can be recognised as a replacement rather than the first ever.
    private var lastUnderlyingNetwork: Network? = null

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                checkTrustedWifiTransition()
                checkFirewallNetworkTransition()
                checkUnderlyingNetworkReplaced(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
                checkTrustedWifiTransition()
                checkFirewallNetworkTransition()
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
                checkTrustedWifiTransition()
                checkFirewallNetworkTransition()
            }
        }
    }

    /**
     * A different default network means every connection the core had is gone — if not closed by
     * the system, then dead at the far end — so have the tunnel checked rather than waiting for
     * the core to find out the slow way.
     */
    private fun checkUnderlyingNetworkReplaced(network: Network) {
        val previous = lastUnderlyingNetwork
        lastUnderlyingNetwork = network
        if (previous != null && previous != network && isRunning) {
            CoreServiceManager.checkTunnelHealth("network changed")
        }
    }

    /** Restarts the core (only way to make it pick up a routing change) if the SSID actually changed. */
    private fun checkTrustedWifiTransition() {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTING_TRUSTED_WIFI_ENABLED, false)) return
        val currentSsid = WifiSsidUtil.currentSsid(applicationContext)
        if (currentSsid == lastKnownSsid) return
        LogUtil.w(AppConfig.TAG, "TrustedWifi: SSID changed \"$lastKnownSsid\" -> \"$currentSsid\", isRunning=$isRunning")
        lastKnownSsid = currentSsid
        if (isRunning) {
            MessageUtil.sendMsg2Service(applicationContext, AppConfig.MSG_STATE_RESTART, "")
        }
    }

    /**
     * WIFI_ONLY / MOBILE_ONLY policies are baked into the Xray config at build time, so a
     * Wi‑Fi↔cellular flip needs the same restart path as trusted-Wi-Fi.
     */
    private fun checkFirewallNetworkTransition() {
        if (!com.v2ray.ang.handler.FirewallManager.isEnabled()) return
        if (!com.v2ray.ang.handler.FirewallManager.hasNetworkRestrictedPolicies()) return
        val kind = com.v2ray.ang.util.NetworkMeteredUtil.currentKind(applicationContext)
        if (kind == lastNetworkKind) return
        LogUtil.w(AppConfig.TAG, "Firewall: network kind \"$lastNetworkKind\" -> \"$kind\", isRunning=$isRunning")
        lastNetworkKind = kind
        if (isRunning) {
            MessageUtil.sendMsg2Service(applicationContext, AppConfig.MSG_STATE_RESTART, "")
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        NotificationManager.showNotification(null)
        setupVpnService()
        startService()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService() {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            stopSelf()
            return
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            stopSelf()
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Snapshot the current SSID before registering the network callback below, so its first
        // capabilities report doesn't look like a change from "unknown" and trigger a spurious restart.
        lastKnownSsid = WifiSsidUtil.currentSsid(applicationContext)
        lastUnderlyingNetwork = null

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    /**
     * Routes the active WireGuard profile's own private subnets into the tunnel.
     *
     * "Bypass LAN" deliberately keeps RFC1918 out of the VPN, but a WireGuard peer's network lives
     * there, so without this the tunnel carries internet traffic while its own hosts (10.66.66.x
     * and friends) stay unreachable — packets for them go to the physical LAN instead. The
     * official WireGuard client has no conflict here because it simply routes the peer's
     * AllowedIPs; this recovers the same subnets so both settings can hold at once.
     */
    private fun addWireguardPrivateRoutes(builder: Builder) {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.configType != EConfigType.WIREGUARD) return

        WireguardRoutes.privateRoutesFor(profile.allowedIps, profile.localAddress).forEach { cidr ->
            val parts = cidr.split('/')
            val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
            try {
                builder.addRoute(parts[0], prefix)
                LogUtil.i(AppConfig.TAG, "WireGuard: routing $cidr into the tunnel")
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "WireGuard: skipping unusable route $cidr", e)
            }
        }
    }

    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
            addWireguardPrivateRoutes(builder)
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        // On trusted Wi-Fi, hand out the Wi-Fi's own DHCP DNS (the router) instead of the app's
        // configured DNS — so resolution flows through the router's dnsmasq exactly like with the
        // VPN off, keeping router-side DNS-based policy routing (OpenWrt/podkop) working.
        val dnsServers = if (WifiSsidUtil.isOnTrustedWifi(applicationContext)) {
            WifiSsidUtil.wifiDnsServers(applicationContext).ifEmpty { SettingsManager.getVpnDnsServers() }
        } else {
            SettingsManager.getVpnDnsServers()
        }
        dnsServers.forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        val firewallApps = FirewallManager.packagesRequiringTunnel()
        // A package cannot be filtered after Android routes it around the VPN. Preserve the
        // user's saved selection and apply firewall requirements only to this Builder instance.
        val effectiveApps = FirewallTunnelSelection.apply(apps, bypassApps, firewallApps)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) effectiveApps.add(selfPackageName) else effectiveApps.remove(selfPackageName)

        effectiveApps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }
}

