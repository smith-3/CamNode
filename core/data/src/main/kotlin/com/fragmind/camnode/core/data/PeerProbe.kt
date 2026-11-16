package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.control.ControlResult
import com.fragmind.camnode.core.control.ControlAuth
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.byPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

sealed interface PeerReachability {
    /** [route] is the address that answered, preferring the same Wi-Fi over Tailscale. */
    data class Online(val status: NodeStatus, val route: NodeEndpoint) : PeerReachability
    data object Unauthorized : PeerReachability
    data object Offline : PeerReachability
}

class PeerProbe @Inject constructor(private val client: ControlClient) {
    /** Probes every address at once and keeps the most preferred one that answered. */
    /** [target] is the node expected at these addresses, so its answer can't be confused with another's. */
    suspend fun probe(candidates: List<NodeEndpoint>, auth: ControlAuth, target: NodeId? = null): PeerReachability = coroutineScope {
        val results = candidates.byPreference()
            .map { endpoint -> async { endpoint to client.status(endpoint, auth, target) } }
            .awaitAll()
        results.firstNotNullOfOrNull { (endpoint, result) ->
            (result as? ControlResult.Success)?.let { PeerReachability.Online(it.value, endpoint) }
        } ?: if (results.any { it.second == ControlResult.Unauthorized }) PeerReachability.Unauthorized else PeerReachability.Offline
    }
}
