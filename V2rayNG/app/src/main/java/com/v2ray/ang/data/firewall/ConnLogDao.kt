package com.v2ray.ang.data.firewall

import android.content.ContentValues
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/** Thin SQLite access for the connection journal. Safe to open from either process. */
class ConnLogDao private constructor(context: Context) {

    private val helper = ConnLogDbHelper.get(context)

    fun insertAll(entries: List<ConnLogEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        val db = helper.writableDatabase
        val ids = ArrayList<Long>(entries.size)
        db.beginTransaction()
        try {
            for (entry in entries) {
                ids.add(db.insert(ConnLogDbHelper.TABLE, null, entry.toContentValues()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    /** Bumps hits/ts on an existing row; returns false if the id is gone. */
    fun addHits(id: Long, extraHits: Int, ts: Long): Boolean {
        val db = helper.writableDatabase
        // Absolute SET would race with concurrent writers; we only ever write from the daemon.
        return db.compileStatement(
            """
            UPDATE ${ConnLogDbHelper.TABLE}
            SET ${ConnLogDbHelper.COL_HITS} = ${ConnLogDbHelper.COL_HITS} + ?,
                ${ConnLogDbHelper.COL_TS} = ?
            WHERE ${ConnLogDbHelper.COL_ID} = ?
            """.trimIndent()
        ).use { statement ->
            statement.bindLong(1, extraHits.toLong())
            statement.bindLong(2, ts)
            statement.bindLong(3, id)
            statement.executeUpdateDelete() > 0
        }
    }

    fun deleteOlderThan(cutoffTs: Long): Int {
        return helper.writableDatabase.delete(
            ConnLogDbHelper.TABLE,
            "${ConnLogDbHelper.COL_TS} < ?",
            arrayOf(cutoffTs.toString())
        )
    }

    fun trimToMaxRows(max: Int): Int {
        if (max <= 0) return 0
        val db = helper.writableDatabase
        db.execSQL(
            """
            DELETE FROM ${ConnLogDbHelper.TABLE}
            WHERE ${ConnLogDbHelper.COL_ID} NOT IN (
                SELECT ${ConnLogDbHelper.COL_ID} FROM ${ConnLogDbHelper.TABLE}
                ORDER BY ${ConnLogDbHelper.COL_ID} DESC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(max)
        )
        return 0
    }

    fun deleteAll() {
        helper.writableDatabase.delete(ConnLogDbHelper.TABLE, null, null)
    }

    fun deleteForApp(packageName: String, uid: Int): Int =
        helper.writableDatabase.delete(
            ConnLogDbHelper.TABLE,
            "${ConnLogDbHelper.COL_PACKAGE} = ? OR (? >= 0 AND ${ConnLogDbHelper.COL_UID} = ?)",
            arrayOf(packageName, uid.toString(), uid.toString()),
        )

    /**
     * Re-evaluates saved rows after a policy changes. The detail UI presents the verdict as the
     * destination's current firewall state, so leaving an old BLOCK marker after clearing its
     * rule would be misleading.
     */
    fun recomputeVerdictsForApp(
        packageName: String,
        uid: Int,
        verdict: (uid: Int, destIp: String, domain: String?) -> Boolean,
    ): Int {
        data class Row(val id: Long, val uid: Int, val ip: String, val domain: String?)

        val db = helper.writableDatabase
        val rows = ArrayList<Row>()
        db.query(
            ConnLogDbHelper.TABLE,
            arrayOf(
                ConnLogDbHelper.COL_ID,
                ConnLogDbHelper.COL_UID,
                ConnLogDbHelper.COL_DEST_IP,
                ConnLogDbHelper.COL_DOMAIN,
            ),
            "${ConnLogDbHelper.COL_PACKAGE} = ? OR (? >= 0 AND ${ConnLogDbHelper.COL_UID} = ?)",
            arrayOf(packageName, uid.toString(), uid.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    id = cursor.getLong(0),
                    uid = cursor.getInt(1),
                    ip = cursor.getString(2),
                    domain = if (cursor.isNull(3)) null else cursor.getString(3),
                )
            }
        }
        if (rows.isEmpty()) return 0

        var updated = 0
        db.beginTransaction()
        try {
            db.compileStatement(
                """
                UPDATE ${ConnLogDbHelper.TABLE}
                SET ${ConnLogDbHelper.COL_BLOCKED} = ?
                WHERE ${ConnLogDbHelper.COL_ID} = ?
                """.trimIndent()
            ).use { statement ->
                rows.forEach { row ->
                    statement.clearBindings()
                    statement.bindLong(1, if (verdict(row.uid, row.ip, row.domain)) 1L else 0L)
                    statement.bindLong(2, row.id)
                    updated += statement.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun updateDomain(id: Long, domain: String) {
        val values = ContentValues().apply {
            put(ConnLogDbHelper.COL_DOMAIN, domain)
        }
        helper.writableDatabase.update(
            ConnLogDbHelper.TABLE,
            values,
            "${ConnLogDbHelper.COL_ID} = ?",
            arrayOf(id.toString()),
        )
    }

    fun count(): Long {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${ConnLogDbHelper.TABLE}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun aggregatesSince(sinceTs: Long): List<ConnLogAppAggregate> {
        val sql = """
            SELECT ${ConnLogDbHelper.COL_UID},
                   ${ConnLogDbHelper.COL_PACKAGE},
                   COUNT(*) AS conns,
                   SUM(${ConnLogDbHelper.COL_HITS}) AS attempts,
                   MAX(${ConnLogDbHelper.COL_TS}) AS last_seen,
                   COUNT(DISTINCT ${ConnLogDbHelper.COL_DEST_IP}) AS destinations,
                   SUM(${ConnLogDbHelper.COL_BLOCKED}) AS blocked_count
            FROM ${ConnLogDbHelper.TABLE}
            WHERE ${ConnLogDbHelper.COL_TS} > ?
            GROUP BY ${ConnLogDbHelper.COL_UID}
            ORDER BY last_seen DESC
        """.trimIndent()
        helper.readableDatabase.rawQuery(sql, arrayOf(sinceTs.toString())).use { cursor ->
            val out = ArrayList<ConnLogAppAggregate>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    ConnLogAppAggregate(
                        uid = cursor.getInt(0),
                        packageName = cursor.getString(1),
                        conns = cursor.getInt(2),
                        attempts = cursor.getInt(3),
                        lastSeen = cursor.getLong(4),
                        destinations = cursor.getInt(5),
                        blockedCount = cursor.getInt(6),
                    )
                )
            }
            return out
        }
    }

    fun entriesForUid(uid: Int, limit: Int, offset: Int): List<ConnLogEntry> {
        val sql = """
            SELECT ${ConnLogDbHelper.COL_ID}, ${ConnLogDbHelper.COL_TS}, ${ConnLogDbHelper.COL_UID},
                   ${ConnLogDbHelper.COL_PACKAGE}, ${ConnLogDbHelper.COL_NETWORK},
                   ${ConnLogDbHelper.COL_DEST_IP}, ${ConnLogDbHelper.COL_DEST_PORT},
                   ${ConnLogDbHelper.COL_SRC_PORT}, ${ConnLogDbHelper.COL_BLOCKED},
                   ${ConnLogDbHelper.COL_HITS}, ${ConnLogDbHelper.COL_DOMAIN}
            FROM ${ConnLogDbHelper.TABLE}
            WHERE ${ConnLogDbHelper.COL_UID} = ?
            ORDER BY ${ConnLogDbHelper.COL_TS} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        helper.readableDatabase.rawQuery(
            sql,
            arrayOf(uid.toString(), limit.toString(), offset.toString())
        ).use { cursor ->
            val out = ArrayList<ConnLogEntry>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(cursor.toEntry())
            }
            return out
        }
    }

    fun entriesForPackage(packageName: String, limit: Int, offset: Int = 0): List<ConnLogEntry> {
        val sql = """
            SELECT ${ConnLogDbHelper.COL_ID}, ${ConnLogDbHelper.COL_TS}, ${ConnLogDbHelper.COL_UID},
                   ${ConnLogDbHelper.COL_PACKAGE}, ${ConnLogDbHelper.COL_NETWORK},
                   ${ConnLogDbHelper.COL_DEST_IP}, ${ConnLogDbHelper.COL_DEST_PORT},
                   ${ConnLogDbHelper.COL_SRC_PORT}, ${ConnLogDbHelper.COL_BLOCKED},
                   ${ConnLogDbHelper.COL_HITS}, ${ConnLogDbHelper.COL_DOMAIN}
            FROM ${ConnLogDbHelper.TABLE}
            WHERE ${ConnLogDbHelper.COL_PACKAGE} = ?
            ORDER BY ${ConnLogDbHelper.COL_TS} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        helper.readableDatabase.rawQuery(
            sql,
            arrayOf(packageName, limit.toString(), offset.toString())
        ).use { cursor ->
            val out = ArrayList<ConnLogEntry>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(cursor.toEntry())
            }
            return out
        }
    }

    /**
     * UID is the authoritative identity captured by ProcessFinder. package_name is enrichment
     * only and may be null when PackageManager cannot reverse-resolve early daemon traffic.
     */
    fun destAggregatesForApp(
        packageName: String,
        uid: Int,
        sinceTs: Long = 0L,
    ): List<ConnLogDestAggregate> {
        val sql = """
            SELECT ${ConnLogDbHelper.COL_DEST_IP},
                   ${ConnLogDbHelper.COL_DOMAIN},
                   ${ConnLogDbHelper.COL_DEST_PORT},
                   SUM(${ConnLogDbHelper.COL_HITS}) AS attempts,
                   MAX(${ConnLogDbHelper.COL_TS}) AS last_seen,
                   SUM(${ConnLogDbHelper.COL_BLOCKED}) AS blocked_count
            FROM ${ConnLogDbHelper.TABLE}
            WHERE (${ConnLogDbHelper.COL_PACKAGE} = ? OR ${ConnLogDbHelper.COL_UID} = ?)
              AND ${ConnLogDbHelper.COL_TS} > ?
            GROUP BY ${ConnLogDbHelper.COL_DEST_IP}, ${ConnLogDbHelper.COL_DEST_PORT},
                     IFNULL(${ConnLogDbHelper.COL_DOMAIN}, '')
            ORDER BY last_seen DESC
            LIMIT 200
        """.trimIndent()
        helper.readableDatabase.rawQuery(
            sql,
            arrayOf(packageName, uid.toString(), sinceTs.toString())
        ).use { cursor ->
            val out = ArrayList<ConnLogDestAggregate>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    ConnLogDestAggregate(
                        destIp = cursor.getString(0),
                        domain = cursor.getString(1),
                        destPort = cursor.getInt(2),
                        attempts = cursor.getInt(3),
                        lastSeen = cursor.getLong(4),
                        blockedCount = cursor.getInt(5),
                    )
                )
            }
            return out
        }
    }

    private fun android.database.Cursor.toEntry(): ConnLogEntry = ConnLogEntry(
        id = getLong(0),
        ts = getLong(1),
        uid = getInt(2),
        packageName = getString(3),
        network = getString(4),
        destIp = getString(5),
        destPort = getInt(6),
        srcPort = getInt(7),
        blocked = getInt(8) != 0,
        hits = getInt(9),
        domain = if (isNull(10)) null else getString(10),
    )

    /** Compacts the file after large deletes. Best-effort; failures are logged and ignored. */
    fun vacuum() {
        try {
            // VACUUM cannot run inside a transaction; close WAL writers first when possible.
            helper.writableDatabase.execSQL("VACUUM")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "ConnLogDao: VACUUM failed", e)
        }
    }

    private fun ConnLogEntry.toContentValues(): ContentValues = ContentValues().apply {
        put(ConnLogDbHelper.COL_TS, ts)
        put(ConnLogDbHelper.COL_UID, uid)
        put(ConnLogDbHelper.COL_PACKAGE, packageName)
        put(ConnLogDbHelper.COL_NETWORK, network)
        put(ConnLogDbHelper.COL_DEST_IP, destIp)
        put(ConnLogDbHelper.COL_DEST_PORT, destPort)
        put(ConnLogDbHelper.COL_SRC_PORT, srcPort)
        put(ConnLogDbHelper.COL_BLOCKED, if (blocked) 1 else 0)
        put(ConnLogDbHelper.COL_HITS, hits)
        put(ConnLogDbHelper.COL_DOMAIN, domain)
    }

    companion object {
        @Volatile
        private var instance: ConnLogDao? = null

        fun get(context: Context): ConnLogDao {
            return instance ?: synchronized(this) {
                instance ?: ConnLogDao(context.applicationContext).also { instance = it }
            }
        }
    }
}
