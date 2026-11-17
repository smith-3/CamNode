package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.control.ControlResult
import com.fragmind.camnode.core.control.ControlAuth
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.byPreference
import kotlinx.coroutines.async
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
        val attempts = candidates.byPreference().map { endpoint ->
            endpoint to async { client.status(endpoint, auth, target) }
        }
        var unauthorized = false
        try {
            for ((endpoint, attempt) in attempts) {
                when (val result = attempt.await()) {
                    is ControlResult.Success -> return@coroutineScope PeerReachability.Online(result.value, endpoint)
                    ControlResult.Unauthorized -> unauthorized = true
                    else -> Unit
                }
            }
            if (unauthorized) PeerReachability.Unauthorized else PeerReachability.Offline
        } finally {
            attempts.forEach { (_, attempt) -> attempt.cancel() }
        }
    }
}
