package com.v2ray.ang.data.firewall

/**
 * Pure collapse/normalize helpers for the connection journal hot path.
 * Kept free of Android so unit tests can exercise the batching logic.
 */
object ConnLogCollapse {

    data class Raw(
        val ts: Long,
        val uid: Int,
        val network: String,
        val destIp: String,
        val destPort: Int,
        val srcPort: Int,
        val domain: String? = null,
    )

    data class Collapsed(
        val ts: Long,
        val uid: Int,
        val network: String,
        val destIp: String,
        val destPort: Int,
        val srcPort: Int,
        val hits: Int,
        val domain: String? = null,
    )

    fun collapseKey(uid: Int, network: String, destIp: String, destPort: Int): String =
        "$uid|$network|$destIp|$destPort"

    fun normalizeNetwork(network: String): String = network.lowercase()

    /**
     * Groups [raw] by uid/network/dest, summing hits and keeping the newest ts / a representative
     * srcPort. Prefers any non-blank sniffed domain seen in the batch.
     */
    fun collapse(raw: List<Raw>): List<Collapsed> {
        if (raw.isEmpty()) return emptyList()
        val order = LinkedHashMap<String, Collapsed>()
        for (item in raw) {
            val network = normalizeNetwork(item.network)
            val key = collapseKey(item.uid, network, item.destIp, item.destPort)
            val domain = item.domain?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
            val existing = order[key]
            if (existing == null) {
                order[key] = Collapsed(
                    ts = item.ts,
                    uid = item.uid,
                    network = network,
                    destIp = item.destIp,
                    destPort = item.destPort,
                    srcPort = item.srcPort,
                    hits = 1,
                    domain = domain,
                )
            } else {
                order[key] = existing.copy(
                    ts = maxOf(existing.ts, item.ts),
                    srcPort = item.srcPort,
                    hits = existing.hits + 1,
                    domain = existing.domain ?: domain,
                )
            }
        }
        return order.values.toList()
    }
}
