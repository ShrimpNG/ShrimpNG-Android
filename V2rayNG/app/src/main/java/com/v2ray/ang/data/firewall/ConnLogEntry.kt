package com.v2ray.ang.data.firewall

/** One collapsed row in the connection journal. */
data class ConnLogEntry(
    val id: Long = 0L,
    val ts: Long,
    val uid: Int,
    val packageName: String?,
    val network: String,
    val destIp: String,
    val destPort: Int,
    val srcPort: Int,
    val blocked: Boolean,
    val hits: Int = 1,
    val domain: String? = null,
)

/** Per-app rollup for the journal's top-level list. */
data class ConnLogAppAggregate(
    val uid: Int,
    val packageName: String?,
    val conns: Int,
    val attempts: Int,
    val lastSeen: Long,
    val destinations: Int,
    val blockedCount: Int,
)

/** Destination rollup inside one app's detail screen. */
data class ConnLogDestAggregate(
    val destIp: String,
    val domain: String?,
    val destPort: Int,
    val attempts: Int,
    val lastSeen: Long,
    val blockedCount: Int,
)
