package com.fragmind.camnode.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.TailnetAddresses
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class LocalAddresses(val lan: String?, val tailnet: String?) {
    /** LAN first: peers on the same Wi-Fi connect directly, Tailscale covers everything else. */
    val endpoints: List<NodeEndpoint> get() = listOfNotNull(lan, tailnet).map(::NodeEndpoint)
}

/**
 * This phone's reachable addresses. The LAN address comes from the Wi-Fi or Ethernet
 * network only: cellular interfaces also carry private 10.x addresses that peers can't reach.
 * The tailnet address lives on the Tailscale VPN interface, so no Tailscale SDK is needed.
 */
class LocalAddressProvider @Inject constructor(@ApplicationContext private val context: Context) {

    fun current(): LocalAddresses = LocalAddresses(lan = lanAddress(), tailnet = tailnetAddress())

    private fun lanAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks is the only synchronous way to list Wi-Fi next to an active VPN.
        val networks = connectivity.allNetworks
        return networks
            .filter { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !TailnetAddresses.isTailnet(it.hostAddress.orEmpty()) }
            ?.hostAddress
    }

    private fun tailnetAddress(): String? {
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        return TailnetAddresses.pick(addresses)?.substringBefore('%')
    }
}
