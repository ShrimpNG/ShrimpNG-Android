package com.v2ray.ang.data.firewall

import android.content.ContentValues
import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver

/**
 * SQLite-backed policies/rules for the Rethink-like firewall.
 * Safe across UI and VPN daemon processes (WAL + MULTI_PROCESS MMKV for the one-shot migrate flag).
 */
class FirewallPolicyStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val helper = FirewallPolicyDbHelper.get(appContext)

    fun getAppPolicy(packageName: String): AppPolicy {
        helper.readableDatabase.query(
            FirewallPolicyDbHelper.TABLE_APP,
            arrayOf(
                FirewallPolicyDbHelper.COL_PACKAGE,
                FirewallPolicyDbHelper.COL_UID,
                FirewallPolicyDbHelper.COL_STATUS,
            ),
            "${FirewallPolicyDbHelper.COL_PACKAGE} = ?",
            arrayOf(packageName),
            null,
            null,
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                return AppPolicy(packageName)
            }
            return AppPolicy(
                packageName = c.getString(0),
                uid = c.getInt(1),
                status = AppFirewallStatus.fromStorage(c.getString(2)),
            )
        }
    }

    fun setAppStatus(packageName: String, status: AppFirewallStatus, uid: Int = -1) {
        val resolvedUid = if (uid >= 0) {
            uid
        } else {
            PackageUidResolver.packageNamesToUids(appContext, listOf(packageName))
                .firstOrNull()?.toIntOrNull() ?: -1
        }
        if (status == AppFirewallStatus.ALLOW) {
            helper.writableDatabase.delete(
                FirewallPolicyDbHelper.TABLE_APP,
                "${FirewallPolicyDbHelper.COL_PACKAGE} = ?",
                arrayOf(packageName),
            )
            return
        }
        val values = ContentValues().apply {
            put(FirewallPolicyDbHelper.COL_PACKAGE, packageName)
            put(FirewallPolicyDbHelper.COL_UID, resolvedUid)
            put(FirewallPolicyDbHelper.COL_STATUS, status.name)
        }
        helper.writableDatabase.insertWithOnConflict(
            FirewallPolicyDbHelper.TABLE_APP,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun allNonDefaultPolicies(): List<AppPolicy> {
        helper.readableDatabase.query(
            FirewallPolicyDbHelper.TABLE_APP,
            arrayOf(
                FirewallPolicyDbHelper.COL_PACKAGE,
                FirewallPolicyDbHelper.COL_UID,
                FirewallPolicyDbHelper.COL_STATUS,
            ),
            null,
            null,
            null,
            null,
            null,
        ).use { c ->
            val out = ArrayList<AppPolicy>(c.count)
            while (c.moveToNext()) {
                out.add(
                    AppPolicy(
                        packageName = c.getString(0),
                        uid = c.getInt(1),
                        status = AppFirewallStatus.fromStorage(c.getString(2)),
                    )
                )
            }
            return out
        }
    }

    fun countNonDefaultPolicies(): Int = allNonDefaultPolicies().size

    fun hasNetworkRestrictedPolicies(): Boolean =
        allNonDefaultPolicies().any {
            it.status == AppFirewallStatus.WIFI_ONLY || it.status == AppFirewallStatus.MOBILE_ONLY
        }

    fun domains(scope: String): List<CustomDomainRule> = queryDomains(scope)

    fun allDomains(): List<CustomDomainRule> = queryDomains(null)

    private fun queryDomains(scope: String?): List<CustomDomainRule> {
        val selection = if (scope != null) "${FirewallPolicyDbHelper.COL_SCOPE} = ?" else null
        val args = if (scope != null) arrayOf(scope) else null
        helper.readableDatabase.query(
            FirewallPolicyDbHelper.TABLE_DOMAIN,
            arrayOf(
                FirewallPolicyDbHelper.COL_DOMAIN,
                FirewallPolicyDbHelper.COL_SCOPE,
                FirewallPolicyDbHelper.COL_RULE,
                FirewallPolicyDbHelper.COL_WILDCARD,
            ),
            selection,
            args,
            null,
            null,
            null,
        ).use { c ->
            val out = ArrayList<CustomDomainRule>(c.count)
            while (c.moveToNext()) {
                out.add(
                    CustomDomainRule(
                        domain = c.getString(0),
                        uidScope = c.getString(1),
                        status = DestRuleStatus.fromStorage(c.getString(2)),
                        wildcard = c.getInt(3) != 0,
                    )
                )
            }
            return out
        }
    }

    fun setDomainRule(rule: CustomDomainRule) {
        val values = ContentValues().apply {
            put(FirewallPolicyDbHelper.COL_DOMAIN, rule.domain.trim())
            put(FirewallPolicyDbHelper.COL_SCOPE, rule.uidScope)
            put(FirewallPolicyDbHelper.COL_RULE, rule.status.name)
            put(FirewallPolicyDbHelper.COL_WILDCARD, if (rule.wildcard) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict(
            FirewallPolicyDbHelper.TABLE_DOMAIN,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeDomainRule(domain: String, scope: String) {
        helper.writableDatabase.delete(
            FirewallPolicyDbHelper.TABLE_DOMAIN,
            "${FirewallPolicyDbHelper.COL_DOMAIN} = ? AND ${FirewallPolicyDbHelper.COL_SCOPE} = ?",
            arrayOf(domain, scope),
        )
    }

    fun ips(scope: String): List<CustomIpRule> = queryIps(scope)

    fun allIps(): List<CustomIpRule> = queryIps(null)

    private fun queryIps(scope: String?): List<CustomIpRule> {
        val selection = if (scope != null) "${FirewallPolicyDbHelper.COL_SCOPE} = ?" else null
        val args = if (scope != null) arrayOf(scope) else null
        helper.readableDatabase.query(
            FirewallPolicyDbHelper.TABLE_IP,
            arrayOf(
                FirewallPolicyDbHelper.COL_IP,
                FirewallPolicyDbHelper.COL_SCOPE,
                FirewallPolicyDbHelper.COL_RULE,
            ),
            selection,
            args,
            null,
            null,
            null,
        ).use { c ->
            val out = ArrayList<CustomIpRule>(c.count)
            while (c.moveToNext()) {
                out.add(
                    CustomIpRule(
                        ip = c.getString(0),
                        uidScope = c.getString(1),
                        status = DestRuleStatus.fromStorage(c.getString(2)),
                    )
                )
            }
            return out
        }
    }

    fun setIpRule(rule: CustomIpRule) {
        val values = ContentValues().apply {
            put(FirewallPolicyDbHelper.COL_IP, rule.ip.trim())
            put(FirewallPolicyDbHelper.COL_SCOPE, rule.uidScope)
            put(FirewallPolicyDbHelper.COL_RULE, rule.status.name)
        }
        helper.writableDatabase.insertWithOnConflict(
            FirewallPolicyDbHelper.TABLE_IP,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeIpRule(ip: String, scope: String) {
        helper.writableDatabase.delete(
            FirewallPolicyDbHelper.TABLE_IP,
            "${FirewallPolicyDbHelper.COL_IP} = ? AND ${FirewallPolicyDbHelper.COL_SCOPE} = ?",
            arrayOf(ip, scope),
        )
    }

    /**
     * One-shot import of the legacy MMKV block lists into SQLite.
     * Safe to call repeatedly — gated by [AppConfig.PREF_FIREWALL_POLICY_MIGRATED].
     */
    fun migrateFromMmkvIfNeeded() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FIREWALL_POLICY_MIGRATED, false)) return
        try {
            val blockedApps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_FIREWALL_BLOCKED_APPS).orEmpty()
            for (pkg in blockedApps) {
                setAppStatus(pkg, AppFirewallStatus.BLOCK)
            }
            readLegacyList(AppConfig.PREF_FIREWALL_BLOCKED_DOMAINS).forEach { domain ->
                setDomainRule(
                    CustomDomainRule(
                        domain = domain,
                        uidScope = SCOPE_GLOBAL,
                        status = DestRuleStatus.BLOCK,
                    )
                )
            }
            readLegacyList(AppConfig.PREF_FIREWALL_BLOCKED_IPS).forEach { ip ->
                setIpRule(
                    CustomIpRule(
                        ip = ip,
                        uidScope = SCOPE_GLOBAL,
                        status = DestRuleStatus.BLOCK,
                    )
                )
            }
            MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_BLOCKED_APPS, mutableSetOf())
            MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_BLOCKED_DOMAINS, "")
            MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_BLOCKED_IPS, "")
            MmkvManager.encodeSettings(AppConfig.PREF_FIREWALL_POLICY_MIGRATED, true)
            LogUtil.i(AppConfig.TAG, "FirewallPolicyStore: migrated legacy MMKV rules")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FirewallPolicyStore: migration failed", e)
        }
    }

    private fun readLegacyList(key: String): List<String> =
        MmkvManager.decodeSettingsString(key)
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    companion object {
        const val SCOPE_GLOBAL = "*"

        @Volatile
        private var instance: FirewallPolicyStore? = null

        fun get(context: Context = AngApplication.application): FirewallPolicyStore {
            return instance ?: synchronized(this) {
                instance ?: FirewallPolicyStore(context.applicationContext).also {
                    instance = it
                    it.migrateFromMmkvIfNeeded()
                }
            }
        }
    }
}
