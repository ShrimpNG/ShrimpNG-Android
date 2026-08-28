package com.v2ray.ang.data.firewall

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device journal of firewall-observed connections.
 *
 * Lives in the default app files dir and is opened from both the UI process and the VPN daemon
 * (`:RunSoLibV2RayDaemon`). WAL keeps readers from blocking the writer; the UI still polls rather
 * than relying on cross-process change notifications.
 */
class ConnLogDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TS INTEGER NOT NULL,
                $COL_UID INTEGER NOT NULL,
                $COL_PACKAGE TEXT,
                $COL_NETWORK TEXT NOT NULL,
                $COL_DEST_IP TEXT NOT NULL,
                $COL_DEST_PORT INTEGER NOT NULL,
                $COL_SRC_PORT INTEGER NOT NULL,
                $COL_BLOCKED INTEGER NOT NULL,
                $COL_HITS INTEGER NOT NULL,
                $COL_DOMAIN TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_conn_log_ts ON $TABLE($COL_TS)")
        db.execSQL("CREATE INDEX idx_conn_log_uid_ts ON $TABLE($COL_UID, $COL_TS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_DOMAIN TEXT")
            } catch (_: Exception) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE")
                onCreate(db)
            }
        }
    }

    companion object {
        const val DB_NAME = "firewall_conn_log.db"
        const val DB_VERSION = 2

        const val TABLE = "conn_log"
        const val COL_ID = "id"
        const val COL_TS = "ts"
        const val COL_UID = "uid"
        const val COL_PACKAGE = "package_name"
        const val COL_NETWORK = "network"
        const val COL_DEST_IP = "dest_ip"
        const val COL_DEST_PORT = "dest_port"
        const val COL_SRC_PORT = "src_port"
        const val COL_BLOCKED = "blocked"
        const val COL_HITS = "hits"
        const val COL_DOMAIN = "domain"

        @Volatile
        private var instance: ConnLogDbHelper? = null

        fun get(context: Context): ConnLogDbHelper {
            return instance ?: synchronized(this) {
                instance ?: ConnLogDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
