package com.fragmind.camnode.recorder

import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.TailnetAddresses
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This computer's reachable addresses: the Tailscale IP plus LAN IPv4 addresses of real
 * interfaces. Container and VPN bridges are skipped because phones can't reach them.
 */
object HostAddresses {
    private val VIRTUAL_PREFIXES = listOf("docker", "br-", "veth", "virbr", "cni", "flannel", "vmnet", "vboxnet", "lo")

    fun endpoints(): List<NodeEndpoint> {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val all = interfaces.flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
        val tailnet = TailnetAddresses.pick(all.mapNotNull { it.second.hostAddress?.substringBefore('%') })
        val lan = all
            .filter { (name, address) -> address is Inet4Address && address.isSiteLocalAddress && VIRTUAL_PREFIXES.none(name::startsWith) }
            .mapNotNull { it.second.hostAddress }
        return (lan + listOfNotNull(tailnet)).distinct().map(::NodeEndpoint)
    }
}
