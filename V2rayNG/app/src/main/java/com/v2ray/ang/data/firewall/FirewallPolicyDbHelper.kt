package com.v2ray.ang.data.firewall

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Per-app policies and domain/IP rules. Separate from the connection journal DB. */
class FirewallPolicyDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_APP (
                $COL_PACKAGE TEXT PRIMARY KEY NOT NULL,
                $COL_UID INTEGER NOT NULL DEFAULT -1,
                $COL_STATUS TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_DOMAIN (
                $COL_DOMAIN TEXT NOT NULL,
                $COL_SCOPE TEXT NOT NULL,
                $COL_RULE TEXT NOT NULL,
                $COL_WILDCARD INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY ($COL_DOMAIN, $COL_SCOPE)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_IP (
                $COL_IP TEXT NOT NULL,
                $COL_SCOPE TEXT NOT NULL,
                $COL_RULE TEXT NOT NULL,
                PRIMARY KEY ($COL_IP, $COL_SCOPE)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_APP")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DOMAIN")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_IP")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "firewall_policy.db"
        const val DB_VERSION = 1

        const val TABLE_APP = "app_policy"
        const val TABLE_DOMAIN = "custom_domain"
        const val TABLE_IP = "custom_ip"

        const val COL_PACKAGE = "package_name"
        const val COL_UID = "uid"
        const val COL_STATUS = "status"
        const val COL_DOMAIN = "domain"
        const val COL_SCOPE = "uid_scope"
        const val COL_RULE = "rule_status"
        const val COL_WILDCARD = "wildcard"
        const val COL_IP = "ip_address"

        @Volatile
        private var instance: FirewallPolicyDbHelper? = null

        fun get(context: Context): FirewallPolicyDbHelper {
            return instance ?: synchronized(this) {
                instance ?: FirewallPolicyDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
