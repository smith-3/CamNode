package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable

object CamNodePorts {
    const val CONTROL = 47180
    const val RTSP = 8554
}

enum class NetworkKind { LAN, TAILNET }

/** One address of a node: a Wi-Fi LAN IP, a Tailscale IP, or a MagicDNS name. */
@Serializable
data class NodeEndpoint(
    val host: String,
    val controlPort: Int = CamNodePorts.CONTROL,
    val rtspPort: Int = CamNodePorts.RTSP,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(controlPort in PORT_RANGE && rtspPort in PORT_RANGE) { "ports must be in $PORT_RANGE" }
    }

    /** Hosts with ':' are IPv6 literals and need brackets inside URLs. */
    val urlHost: String get() = if (':' in host) "[$host]" else host

    /** Derived from the address so the wire format stays a plain host and ports. */
    val network: NetworkKind
        get() = if (TailnetAddresses.isTailnet(host) || host.endsWith(MAGIC_DNS_SUFFIX)) NetworkKind.TAILNET else NetworkKind.LAN

    private companion object {
        val PORT_RANGE = 1..65535
        const val MAGIC_DNS_SUFFIX = ".ts.net"
    }
}

/** Same Wi-Fi first: lower latency and no relay. Tailscale reaches the node from anywhere else. */
fun List<NodeEndpoint>.byPreference(): List<NodeEndpoint> = distinct().sortedBy { it.network != NetworkKind.LAN }
