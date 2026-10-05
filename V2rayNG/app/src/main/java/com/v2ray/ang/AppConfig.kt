package com.v2ray.ang


object AppConfig {

    /** The application's package name. */
    const val ANG_PACKAGE = BuildConfig.APPLICATION_ID
    const val TAG = BuildConfig.APPLICATION_ID

    /** Directory names used in the app's file system. */
    const val DIR_ASSETS = "assets"

    const val WEBDAV_BACKUP_DIR = "backups"
    const val WEBDAV_BACKUP_FILE_NAME = "backup_ng.zip"

    /** Legacy configuration keys. */
    const val ANG_CONFIG = "ang_config"

    // Default subscription ID for ungrouped servers
    const val DEFAULT_SUBSCRIPTION_ID = "__default_subscription__"

    /** Preferences mapped to MMKV storage. */
    const val PREF_SNIFFING_ENABLED = "pref_sniffing_enabled"
    const val PREF_ROUTE_ONLY_ENABLED = "pref_route_only_enabled"
    const val PREF_PER_APP_PROXY = "pref_per_app_proxy"
    const val PREF_PER_APP_PROXY_SET = "pref_per_app_proxy_set"
    const val PREF_PER_APP_SHOW_SYSTEM = "pref_per_app_show_system"
    const val PREF_PER_APP_SELECTED_FIRST = "pref_per_app_selected_first"
    const val PREF_BYPASS_APPS = "pref_bypass_apps"
    const val PREF_LOCAL_DNS_ENABLED = "pref_local_dns_enabled"
    const val PREF_FAKE_DNS_ENABLED = "pref_fake_dns_enabled"
    const val PREF_APPEND_HTTP_PROXY = "pref_append_http_proxy"
    const val PREF_LOCAL_DNS_PORT = "pref_local_dns_port"
    const val PREF_VPN_DNS = "pref_vpn_dns"
    const val PREF_VPN_BYPASS_LAN = "pref_vpn_bypass_lan"
    const val PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX = "pref_vpn_interface_address_config_index"
    const val PREF_VPN_MTU = "pref_vpn_mtu"
    const val PREF_ROUTING_DOMAIN_STRATEGY = "pref_routing_domain_strategy"
    const val PREF_ROUTING_RULESET = "pref_routing_ruleset"

    // Правила tab: a friendly Simple mode (3-way proxy/bypass choice, like many mainstream VPN
    // clients) sits alongside the free-form Expert rule editor — same Xray routing engine
    // underneath, just two different ways to build the rule list.
    const val PREF_ROUTING_UI_MODE = "pref_routing_ui_mode"
    const val ROUTING_UI_MODE_SIMPLE = "simple"
    const val ROUTING_UI_MODE_EXPERT = "expert"
    const val PREF_ROUTING_SIMPLE_MODE = "pref_routing_simple_mode"
    const val ROUTING_MODE_PROXY_ALL = "proxyAll"
    const val ROUTING_MODE_PROXY_SELECTED = "proxySelected"
    const val ROUTING_MODE_BYPASS_SELECTED = "bypassSelected"
    const val PREF_ROUTING_SIMPLE_DOMAINS = "pref_routing_simple_domains"

    // Ready-made scenarios: independent, fixed-tag rules a user just flips on — evaluated
    // before the general mode rule above, so they work no matter which of the 3 modes is chosen.
    const val PREF_ROUTING_SCENARIO_RU_DIRECT = "pref_routing_scenario_ru_direct"
    const val PREF_ROUTING_SCENARIO_VPN_SERVICES = "pref_routing_scenario_vpn_services"
    val ROUTING_SCENARIO_RU_DIRECT_TAGS = listOf(
        "geosite:YANDEX",
        "geosite:VK",
        "geosite:CATEGORY-RETAIL-RU",
        "geosite:SBER",
        "geosite:CATEGORY-GOV-RU",
        "geosite:TBANK-RU",
        "geosite:WILDBERRIES",
        "geosite:OZON",
        "geosite:2GIS",
    )
    val ROUTING_SCENARIO_VPN_SERVICES_TAGS = listOf(
        "geosite:YOUTUBE",
        "geosite:TELEGRAM",
        "geosite:DISCORD",
        "geosite:INSTAGRAM",
        "geosite:TWITTER",
        "geosite:FACEBOOK",
    )

    // Trusted Wi-Fi: while connected to one of these SSIDs, everything (including DNS) is routed
    // direct regardless of the chosen Simple/Expert routing mode — evaluated fresh against the
    // currently-connected SSID every time the routing config is built (no separate state to track).
    const val PREF_ROUTING_TRUSTED_WIFI_ENABLED = "pref_routing_trusted_wifi_enabled"
    const val PREF_ROUTING_TRUSTED_WIFI_SSIDS = "pref_routing_trusted_wifi_ssids"

    // Rules tab quick-add chips: (display name, flag emoji, geoip tag) and the same VPN-services
    // list above, shown individually toggleable rather than as one bundled switch.
    val ROUTING_QUICK_COUNTRIES = listOf(
        Triple("Russia", "🇷🇺", "RU"),
        Triple("Ukraine", "🇺🇦", "UA"),
        Triple("Belarus", "🇧🇾", "BY"),
        Triple("Kazakhstan", "🇰🇿", "KZ"),
        Triple("China", "🇨🇳", "CN"),
        Triple("Iran", "🇮🇷", "IR"),
        Triple("USA", "🇺🇸", "US"),
        Triple("Germany", "🇩🇪", "DE"),
        Triple("France", "🇫🇷", "FR"),
        Triple("United Kingdom", "🇬🇧", "GB"),
        Triple("Netherlands", "🇳🇱", "NL"),
        Triple("Poland", "🇵🇱", "PL"),
        Triple("Turkey", "🇹🇷", "TR"),
        Triple("Japan", "🇯🇵", "JP"),
    )
    val ROUTING_QUICK_SERVICES = listOf(
        "YOUTUBE" to "YouTube",
        "TELEGRAM" to "Telegram",
        "DISCORD" to "Discord",
        "INSTAGRAM" to "Instagram",
        "TWITTER" to "Twitter/X",
        "FACEBOOK" to "Facebook",
    )
    const val PREF_MUX_ENABLED = "pref_mux_enabled"
    const val PREF_MUX_CONCURRENCY = "pref_mux_concurrency"
    const val PREF_MUX_XUDP_CONCURRENCY = "pref_mux_xudp_concurrency"
    const val PREF_MUX_XUDP_QUIC = "pref_mux_xudp_quic"
    const val PREF_FRAGMENT_ENABLED = "pref_fragment_enabled"
    const val PREF_FRAGMENT_PACKETS = "pref_fragment_packets"
    const val PREF_FRAGMENT_LENGTH = "pref_fragment_length"
    const val PREF_FRAGMENT_INTERVAL = "pref_fragment_interval"
    const val PREF_FRAGMENT_MAXSPLIT = "pref_fragment_maxsplit"
    const val SUBSCRIPTION_UPDATE_TASK_NAME = "subscription_updater"
    const val SUBSCRIPTION_MIN_INTERVAL_MINUTES = 15L
    const val PREF_SPEED_ENABLED = "pref_speed_enabled"
    const val PREF_CONFIRM_REMOVE = "pref_confirm_remove"
    const val PREF_START_SCAN_IMMEDIATE = "pref_start_scan_immediate"
    const val PREF_DOUBLE_COLUMN_DISPLAY = "pref_double_column_display"
    const val PREF_GROUP_ALL_DISPLAY = "pref_group_all_display"
    const val PREF_LANGUAGE = "pref_language"
    const val PREF_UI_MODE_NIGHT = "pref_ui_mode_night"
    const val PREF_THEME_PALETTE = "pref_theme_palette"
    const val PREF_THEME_AMOLED = "pref_theme_amoled"
    /** "apple" (bundled, default) or "system" — see EmojiStyle. */
    const val PREF_EMOJI_STYLE = "pref_emoji_style"
    const val PREF_IPV6_ENABLED = "pref_ipv6_enabled"
    const val PREF_PREFER_IPV6 = "pref_prefer_ipv6"
    const val PREF_PROXY_SHARING = "pref_proxy_sharing_enabled"
    const val PREF_ENABLE_LOCAL_PROXY = "pref_enable_local_proxy"
    const val PREF_SOCKS_PORT = "pref_socks_port"
    const val PREF_DYNAMIC_SOCKS_PORT = "pref_dynamic_socks_port"
    const val PREF_SOCKS_USERNAME = "pref_socks_username"
    const val PREF_SOCKS_PASSWORD = "pref_socks_password"
    const val PREF_SOCKS_ENABLE_UDP = "pref_socks_enable_udp"
    const val PREF_REMOTE_DNS = "pref_remote_dns"
    const val PREF_DOMESTIC_DNS = "pref_domestic_dns"
    const val PREF_DNS_HOSTS = "pref_dns_hosts"
    const val PREF_DELAY_TEST_URL = "pref_delay_test_url"
    const val PREF_IP_API_URL = "pref_ip_api_url"
    const val PREF_LOGLEVEL = "pref_core_loglevel"
    const val PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD = "pref_outbound_domain_resolve_method"
    const val PREF_MODE = "pref_mode"
    const val PREF_ROOT_MODE_ENABLE = "pref_root_mode_enabled"
    const val PREF_ROOT_LAN_SHARING = "pref_root_lan_sharing"
    const val PREF_IS_BOOTED = "pref_is_booted"
    const val PREF_CONNECTION_STARTED_AT = "pref_connection_started_at"
    const val PREF_CHECK_UPDATE_PRE_RELEASE = "pref_check_update_pre_release"
    const val PREF_GEO_FILES_SOURCES = "pref_geo_files_sources"
    const val PREF_GEO_AUTO_UPDATE = "pref_geo_auto_update"
    const val PREF_GEO_LAST_UPDATE = "pref_geo_last_update"
    const val PREF_USE_HEV_TUNNEL = "pref_use_hev_tunnel_v2"
    const val PREF_HEV_TUNNEL_RW_TIMEOUT = "pref_hev_tunnel_rw_timeout_v2"
    const val PREF_AUTO_REMOVE_INVALID_AFTER_TEST = "pref_auto_remove_invalid_after_test"
    const val PREF_AUTO_SORT_AFTER_TEST = "pref_auto_sort_after_test"
    const val PREF_REAL_PING_CONCURRENCY = "pref_real_ping_concurrency"
    /** How the server list measures latency: "http" (through the server), "tcp" or "tls" (direct). */
    const val PREF_PING_METHOD = "pref_ping_method"
    const val PREF_SEND_HWID_ENABLED = "pref_send_hwid_enabled"
    const val PREF_EXPIRY_REMINDERS = "pref_expiry_reminders"
    const val PREF_CUSTOM_HWID = "pref_custom_hwid"
    const val PREF_DEVELOPER_MODE_ENABLED = "pref_developer_mode_enabled"
    const val DEVELOPER_MODE_TAP_COUNT = 10
    const val DEVELOPER_MODE_TAP_HINT_AT = 7
    const val PREF_SHOW_SERVER_ADDRESS_HOME = "pref_show_server_address_home"
    const val PREF_APP_ICON = "pref_app_icon"
    const val PREF_FAVORITE_SERVERS = "pref_favorite_servers"
    const val PREF_FINGERPRINT_OVERRIDES = "pref_fingerprint_overrides"
    const val PREF_FINGERPRINT_AUTO_FALLBACK = "pref_fingerprint_auto_fallback"
    const val PREF_TUNNEL_WATCHDOG = "pref_tunnel_watchdog"

    /**
     * Firewall. Blocked targets are stored as package names / plain strings rather than UIDs,
     * because a UID changes when an app is reinstalled; resolution happens at config-build time.
     * The two SAVED_* keys hold whatever the user had chosen before the firewall took over the
     * tunnel backend, so switching it off restores their setup instead of the app defaults.
     */
    const val PREF_FIREWALL_ENABLED = "pref_firewall_enabled"
    const val PREF_FIREWALL_SAVED_HEV = "pref_firewall_saved_hev"
    const val PREF_FIREWALL_SAVED_ROUTE_ONLY = "pref_firewall_saved_route_only"
    /** Legacy MMKV lists — migrated once into SQLite [FirewallPolicyStore]. */
    const val PREF_FIREWALL_BLOCKED_APPS = "pref_firewall_blocked_apps"
    const val PREF_FIREWALL_BLOCKED_DOMAINS = "pref_firewall_blocked_domains"
    const val PREF_FIREWALL_BLOCKED_IPS = "pref_firewall_blocked_ips"
    const val PREF_FIREWALL_POLICY_MIGRATED = "pref_firewall_policy_migrated"
    /** Connection journal. Explicit opt-in; records app → IP(/domain). */
    const val PREF_FIREWALL_LOG_ENABLED = "pref_firewall_log_enabled"
    const val PREF_FIREWALL_LOG_OPT_IN_MIGRATED = "pref_firewall_log_opt_in_migrated"
    const val PREF_FIREWALL_LOG_RETENTION_DAYS = "pref_firewall_log_retention_days"
    const val PREF_FIREWALL_LOG_MAX_ROWS = "pref_firewall_log_max_rows"
    const val FIREWALL_LOG_MAINTENANCE_TASK = "firewall_log_maintenance"
    const val GEO_ASSET_UPDATE_TASK = "geo_asset_updater"
    const val FIREWALL_LOG_DEFAULT_RETENTION_DAYS = 3
    const val FIREWALL_LOG_DEFAULT_MAX_ROWS = 50_000
    const val PREF_DECOY_ENABLED = "pref_decoy_enabled"
    const val PREF_DECOY_EXPRESSION = "pref_decoy_expression"

    /** Cache keys. */
    const val CACHE_SUBSCRIPTION_ID = "cache_subscription_id"

    /** Protocol identifiers. */
    const val PROTOCOL_FREEDOM = "freedom"

    /** Broadcast actions. */
    const val BROADCAST_ACTION_SERVICE = "$ANG_PACKAGE.action.service"
    const val BROADCAST_ACTION_ACTIVITY = "$ANG_PACKAGE.action.activity"
    const val BROADCAST_ACTION_WIDGET_CLICK = "$ANG_PACKAGE.action.widget.click"

    /** Tasker extras. */
    const val TASKER_EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val TASKER_EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val TASKER_EXTRA_BUNDLE_SWITCH = "tasker_extra_bundle_switch"
    const val TASKER_EXTRA_BUNDLE_GUID = "tasker_extra_bundle_guid"
    const val TASKER_DEFAULT_GUID = "Default"

    /** Tags for different proxy modes. */
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCKED = "block"
    const val TAG_FRAGMENT = "fragment"
    const val TAG_DNS = "dns-module"
    const val TAG_DOMESTIC_DNS = "domestic-dns"
    const val TAG_BALANCER = "balancer-main"
    const val TAG_BALANCER_PRE = "balancer"

    /** Network-related constants. */
    const val UPLINK = "uplink"
    const val DOWNLINK = "downlink"

    /** URLs for various resources. */
    const val GITHUB_URL = "https://github.com"
    const val GITHUB_RAW_URL = "https://raw.githubusercontent.com"
    const val GITHUB_DOWNLOAD_URL = "$GITHUB_URL/%s/releases/latest/download"
    const val ANDROID_PACKAGE_NAME_LIST_URL = "$GITHUB_RAW_URL/2dust/androidpackagenamelist/master/proxy.txt"
    const val APP_SITE_URL = "https://ng.shrimp.fish"
    const val APP_URL = "$GITHUB_URL/invisible-shrimp/ShrimpNG"
    const val APP_API_URL = "https://api.github.com/repos/invisible-shrimp/ShrimpNG/releases"
    const val APP_ISSUES_URL = "$GITHUB_URL/invisible-shrimp/ShrimpNG/issues"
    const val APP_WIKI_MODE = "$GITHUB_URL/2dust/v2rayNG/wiki/Mode"
    const val APP_PROMOTION_URL = "aHR0cHM6Ly85LjIzNDQ1Ni54eXovYWJjLmh0bWw="

    /** Source of the "Calculator" disguise icon, reused under GPL-3.0. */
    const val SOURCE_CODE_URL = "$GITHUB_URL/ShrimpNG/ShrimpNG-Android"
    const val FEEDBACK_URL = "https://t.me/+8j05BK1_GKk5YjYy"
    const val TG_CHANNEL_URL = "https://t.me/ShrimpNG"
    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DELAY_TEST_URL2 = "https://www.google.com/generate_204"

    //    const val IP_API_URL = "https://speed.cloudflare.com/meta"
    const val IP_API_URL = "https://api.ip.sb/geoip"

    /** DNS server addresses. */
    const val DNS_PROXY = "https://cloudflare-dns.com/dns-query"
    /** Yandex DNS. Upstream's default, 223.5.5.5 (AliDNS), is migrated to it on start. */
    const val DNS_DIRECT = "77.88.8.8"
    const val DNS_DIRECT_LEGACY = "223.5.5.5"
    const val DNS_VPN = "1.1.1.1"
    const val GEOSITE_PRIVATE = "geosite:private"
    const val GEOSITE_CN = "geosite:cn"
    const val GEOIP_PRIVATE = "geoip:private"
    const val GEOIP_CN = "geoip:cn"

    /** Geo data file names. */
    const val GEOSITE_DAT = "geosite.dat"
    const val GEOIP_DAT = "geoip.dat"
    const val GEOIP_ONLY_CN_PRIVATE_DAT = "geoip-only-cn-private.dat"
    const val GEOIP_ONLY_CN_PRIVATE_URL = "$GITHUB_RAW_URL/Loyalsoldier/geoip/release/$GEOIP_ONLY_CN_PRIVATE_DAT"

    /** Ports and addresses for various services. */
    const val PORT_LOCAL_DNS = "10853"
    const val PORT_SOCKS = "10808"
    const val WIREGUARD_LOCAL_ADDRESS_V4 = "172.16.0.2/32"
    const val WIREGUARD_LOCAL_ADDRESS_V6 = "2606:4700:110:8f81:d551:a0:532e:a2b3/128"
    const val WIREGUARD_LOCAL_MTU = "1420"
    const val LOOPBACK = "127.0.0.1"

    /** Message constants for communication. */
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_STATE_RUNNING = 11
    const val MSG_STATE_NOT_RUNNING = 12
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_STATE_START = 3
    const val MSG_STATE_START_SUCCESS = 31
    const val MSG_STATE_START_FAILURE = 32
    const val MSG_STATE_STOP = 4
    const val MSG_STATE_STOP_SUCCESS = 41
    const val MSG_STATE_RESTART = 5
    const val MSG_MEASURE_DELAY = 6
    const val MSG_MEASURE_DELAY_SUCCESS = 61
    const val MSG_MEASURE_CONFIG_START = 7
    const val MSG_MEASURE_CONFIG_CANCEL = 71
    const val MSG_MEASURE_CONFIG_SUCCESS = 72
    const val MSG_MEASURE_CONFIG_NOTIFY = 73
    const val MSG_MEASURE_CONFIG_FINISH = 74
    /** A subscription's profiles were rewritten, possibly by another process; content is its id. */
    const val MSG_SUBSCRIPTION_UPDATED = 81

    /** Notification channel IDs and names. */
    const val RAY_NG_CHANNEL_ID = "SHRIMP_NG_M_CH_ID"
    const val RAY_NG_CHANNEL_NAME = "ShrimpNG Background Service"

    /** Protocols Scheme **/
    const val VMESS = "vmess://"
    const val CUSTOM = ""
    const val SHADOWSOCKS = "ss://"
    const val SOCKS = "socks://"
    const val SOCKS4 = "socks4://"
    const val SOCKS5 = "socks5://"
    const val HTTP = "http://"
    const val VLESS = "vless://"
    const val TROJAN = "trojan://"
    const val WIREGUARD = "wireguard://"
    const val TUIC = "tuic://"
    const val HYSTERIA = "hysteria://"
    const val HYSTERIA2 = "hysteria2://"
    const val HY2 = "hy2://"

    /** Give a good name to this, IDK*/
    const val VPN = "VPN"
    const val VPN_MTU = 1500

    /** Root (system-wide) mode runtime constants. */
    const val ROOT_RUNTIME_DIR = "root"
    const val ROOT_IPTABLES_CHAIN = "V2RAY_NG"
    const val ROOT_FWMARK = 255            // defensive RETURN tag; hev's only upstream socket is loopback (already bypassed)
    const val ROOT_MARK_ROUTE = 1          // packets we want pushed into the tun device
    const val ROOT_ROUTE_TABLE = 2024
    const val ROOT_RULE_PRIORITY = 1000
    const val ROOT_TUN_NAME = "v2raytun0"
    const val ROOT_TUN_ADDR_V4 = "198.18.0.1/15"
    const val ROOT_TUN_ADDR_V6 = "fdfe:dcba:9876::1/64"

    // hev-socks5-tunnel run as a standalone root binary (reuses the same project already
    // bundled for the VPN hev path; distinct filename from the JNI lib to avoid collision).
    const val ROOT_TUN2SOCKS_BIN = "libhevsockstun.so"
    const val ROOT_FWD_CHAIN = "V2RAY_NG_FWD"   // FORWARD chain for LAN/tethering sharing
    const val ROOT_DNS_CHAIN = "V2RAY_NG_DNS"   // nat chain for tethered-client DNS DNAT
    const val ROOT_V6_CHAIN = "V2RAY_NG6"       // ip6tables filter/OUTPUT chain: blackhole native IPv6 when it isn't tunneled
    const val ROOT_V6_FWD_CHAIN = "V2RAY_NG6_FWD" // ip6tables FORWARD chain: route or reject tethered clients' native IPv6
    const val ROOT_V6_PRE_CHAIN = "V2RAY_NG6_PRE" // ip6tables mangle/PREROUTING chain: mark forwarded clients' IPv6 into the tun
    const val ROOT_LAN_DNS = "1.1.1.1"          // fallback resolver for tethered clients when no plain-IPv4 DNS is configured
    const val ROOT_OOM_SCORE = "-1000"          // oom_score_adj that makes the LMK never kill us

    /** hev-sock5-tunnel read-write-timeout value */
    const val HEVTUN_RW_TIMEOUT = "300,60"

    // Google API rule constants
    const val GOOGLEAPIS_CN_DOMAIN = "domain:googleapis.cn"
    const val GOOGLEAPIS_COM_DOMAIN = "googleapis.com"

    // Android Private DNS constants
    const val DNS_ALIDNS_DOMAIN = "dns.alidns.com"
    const val DNS_CISCO_SSE_DOMAIN = "dns.sse.cisco.com"
    const val DNS_CISCO_UMBRELLA_DOMAIN = "dns.umbrella.com"
    const val DNS_CLOUDFLARE_ONE_DOMAIN = "one.one.one.one"
    const val DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN = "1dot1dot1dot1.cloudflare-dns.com"
    const val DNS_CLOUDFLARE_DNS_COM_DOMAIN = "dns.cloudflare.com"
    const val DNS_CLOUDFLARE_DNS_DOMAIN = "cloudflare-dns.com"
    const val DNS_CLOUDFLARE_WARP_DOMAIN = "engage.cloudflareclient.com"
    const val DNS_DNSPOD_DOH_DOMAIN = "doh.pub"
    const val DNS_DNSPOD_DOT_DOMAIN = "dot.pub"
    const val DNS_GOOGLE_DOMAIN = "dns.google"
    const val DNS_QUAD9_DOMAIN = "dns.quad9.net"
    const val DNS_SB_DOMAIN = "dns.sb"
    const val DNS_YANDEX_DOMAIN = "common.dot.dns.yandex.net"

    const val DEFAULT_PORT = 443
    const val DEFAULT_SECURITY = "auto"
    const val DEFAULT_LEVEL = 8
    const val DEFAULT_NETWORK = "tcp"
    const val TLS = "tls"
    const val REALITY = "reality"
    const val HEADER_TYPE_HTTP = "http"

    const val UNIDENTIFIED_PACKAGE = "__unknown_app__"

    val DNS_ALIDNS_ADDRESSES = arrayListOf("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1")
    val DNS_CISCO_SSE_ADDRESSES = arrayListOf("208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53")
    val DNS_CISCO_UMBRELLA_ADDRESSES = arrayListOf("208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53")
    val DNS_CLOUDFLARE_ONE_ADDRESSES = arrayListOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    val DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES = arrayListOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    val DNS_CLOUDFLARE_DNS_COM_ADDRESSES = arrayListOf("162.159.61.8", "172.64.41.8", "2a06:98c1:52::8", "2803:f800:53::8")
    val DNS_CLOUDFLARE_DNS_ADDRESSES = arrayListOf("104.16.248.249", "104.16.249.249", "2606:4700::6810:f8f9", "2606:4700::6810:f9f9")
    val DNS_CLOUDFLARE_WARP_ADDRESSES = arrayListOf("162.159.192.1", "2606:4700:d0::a29f:c001")
    val DNS_DNSPOD_DOH_ADDRESSES = arrayListOf("1.12.12.12", "120.53.53.53")
    val DNS_DNSPOD_DOT_ADDRESSES = arrayListOf("1.12.12.12", "120.53.53.53")
    val DNS_GOOGLE_ADDRESSES = arrayListOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
    val DNS_QUAD9_ADDRESSES = arrayListOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
    val DNS_SB_ADDRESSES = arrayListOf("45.11.45.11", "185.222.222.222", "2a09::", "2a11::")
    val DNS_YANDEX_ADDRESSES = arrayListOf("77.88.8.8", "77.88.8.1", "2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff")

    //minimum list https://serverfault.com/a/304791
    val ROUTED_IP_LIST = arrayListOf(
        "0.0.0.0/5",
        "8.0.0.0/7",
        "11.0.0.0/8",
        "12.0.0.0/6",
        "16.0.0.0/4",
        "32.0.0.0/3",
        "64.0.0.0/2",
        "128.0.0.0/3",
        "160.0.0.0/5",
        "168.0.0.0/6",
        "172.0.0.0/12",
        "172.32.0.0/11",
        "172.64.0.0/10",
        "172.128.0.0/9",
        "173.0.0.0/8",
        "174.0.0.0/7",
        "176.0.0.0/4",
        "192.0.0.0/9",
        "192.128.0.0/11",
        "192.160.0.0/13",
        "192.169.0.0/16",
        "192.170.0.0/15",
        "192.172.0.0/14",
        "192.176.0.0/12",
        "192.192.0.0/10",
        "193.0.0.0/8",
        "194.0.0.0/7",
        "196.0.0.0/6",
        "200.0.0.0/5",
        "208.0.0.0/4",
        "240.0.0.0/4"
    )

    val PRIVATE_IP_LIST = arrayListOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "224.0.0.0/4"
    )

    val GEO_FILES_SOURCES = arrayListOf(
        "Loyalsoldier/v2ray-rules-dat",
        "runetfreedom/russia-v2ray-rules-dat",
        "Chocolate4U/Iran-v2ray-rules"
    )

    val BUILTIN_OUTBOUND_TAGS = setOf(
        TAG_PROXY,
        TAG_DIRECT,
        TAG_BLOCKED,
    )
}
