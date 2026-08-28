package com.v2ray.ang.util

/**
 * Works out which private subnets a WireGuard profile needs routed into the tunnel.
 *
 * "Bypass LAN" keeps RFC1918 ranges out of the VPN so printers, NAS boxes and router admin pages
 * keep working — but a WireGuard peer's own network lives in exactly those ranges, so the setting
 * also makes the WG network unreachable. The official WireGuard client has no such conflict: it
 * routes whatever the peer's AllowedIPs says, and that normally covers the internal subnet.
 *
 * These helpers recover the same information so the two can coexist: everything private that the
 * profile actually needs is routed in, and the rest of the LAN still bypasses the tunnel.
 */
object WireguardRoutes {

    /** Roughly RFC1918 plus the carrier-grade range, matching what "bypass LAN" holds back. */
    private val PRIVATE_PREFIXES = listOf("10.", "192.168.", "172.", "100.64.")

    /**
     * @param allowedIps the peer's AllowedIPs, comma separated. Authoritative when it names
     * specific subnets.
     * @param localAddress the interface Address, e.g. "10.66.66.7/32" or "10.66.66.2/24".
     * @return CIDR strings to add as VPN routes; empty when nothing private is involved.
     */
    fun privateRoutesFor(allowedIps: String?, localAddress: String?): List<String> {
        val fromAllowed = splitCidrs(allowedIps)
            // A catch-all says "send everything", which is precisely what bypass-LAN is refusing
            // to do; it carries no information about which subnet the peer actually serves.
            .filterNot { it.startsWith("0.0.0.0/") || it.startsWith("::/") }
            .filter { isPrivateCidr(it) }
            .mapNotNull { normalizeToNetwork(it) }

        if (fromAllowed.isNotEmpty()) return fromAllowed.distinct()

        // Nothing usable in AllowedIPs — fall back to the interface address's own subnet.
        return listOfNotNull(subnetOfLocalAddress(localAddress)).distinct()
    }

    private fun splitCidrs(raw: String?): List<String> =
        raw?.split(",", "\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun isPrivateCidr(cidr: String): Boolean {
        val address = cidr.substringBefore('/')
        return PRIVATE_PREFIXES.any { address.startsWith(it) }
    }

    /**
     * The interface address usually carries the subnet, e.g. "10.66.66.2/24" → "10.66.66.0/24".
     *
     * A bare address or a /32 says nothing about the subnet, so this assumes the surrounding /24.
     * That is a guess — the right cure is to list the real subnet in the peer's AllowedIPs, which
     * takes priority over this path.
     */
    private fun subnetOfLocalAddress(localAddress: String?): String? {
        val first = splitCidrs(localAddress).firstOrNull { isPrivateCidr(it) } ?: return null
        val address = first.substringBefore('/')
        val prefix = first.substringAfter('/', "").toIntOrNull()
        return when {
            prefix != null && prefix in 1..31 -> normalizeToNetwork("$address/$prefix")
            else -> normalizeToNetwork("$address/24")
        }
    }

    /** "10.66.66.7/24" → "10.66.66.0/24". IPv6 and malformed input are skipped. */
    fun normalizeToNetwork(cidr: String): String? {
        val address = cidr.substringBefore('/')
        val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val octets = address.split('.')
        if (octets.size != 4) return null

        val value = octets.fold(0L) { acc, octet ->
            val part = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            (acc shl 8) or part.toLong()
        }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = value and mask
        val networkOctets = (3 downTo 0).map { (network shr (it * 8)) and 0xFF }
        return "${networkOctets.joinToString(".")}/$prefix"
    }
}
