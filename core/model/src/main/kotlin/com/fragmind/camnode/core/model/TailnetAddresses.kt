package com.fragmind.camnode.core.model

/**
 * Tailscale assigns IPv4 addresses from the CGNAT block 100.64.0.0/10 and IPv6
 * addresses from fd7a:115c:a1e0::/48. IPv4 is preferred: it is shorter in QR codes
 * and works with every RTSP client.
 */
object TailnetAddresses {
    private const val IPV6_PREFIX = "fd7a:115c:a1e0:"

    fun pick(candidates: List<String>): String? =
        candidates.firstOrNull(::isTailnetIpv4) ?: candidates.firstOrNull(::isTailnetIpv6)

    fun isTailnet(address: String): Boolean = isTailnetIpv4(address) || isTailnetIpv6(address)

    fun isTailnetIpv4(address: String): Boolean {
        val octets = address.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun isTailnetIpv6(address: String): Boolean =
        address.substringBefore('%').lowercase().startsWith(IPV6_PREFIX)
}
