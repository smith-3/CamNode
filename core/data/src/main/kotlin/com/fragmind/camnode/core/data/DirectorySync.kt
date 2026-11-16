package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.control.ControlResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

data class SyncReport(
    val reached: Int,
    val unauthorized: List<NodeEndpoint>,
    val unreachable: List<NodeEndpoint>,
)

/**
 * Gossips the directory with every known camera in parallel. Each exchange is a
 * convergent merge, so retries and overlapping syncs are safe.
 */
class DirectorySync @Inject constructor(
    private val households: HouseholdRepository,
    private val client: ControlClient,
) {
    /** [discovered] are extra addresses, such as cameras found on the same Wi-Fi. */
    suspend fun sync(discovered: Collection<NodeEndpoint> = emptyList()): SyncReport {
        val session = households.currentSession() ?: return SyncReport(0, emptyList(), emptyList())
        val peers = session.directory.cameras
            .filter { it.id != session.self.id }
            .flatMap { it.endpoints }
        val targets = (peers + session.bootstrapSeeds + discovered).distinct()

        val results = coroutineScope {
            targets.map { endpoint ->
                async { endpoint to client.exchangeDirectory(endpoint, session.auth, session.directory) }
            }.awaitAll()
        }

        results.forEach { (_, result) -> if (result is ControlResult.Success) households.mergeDirectory(result.value) }
        return SyncReport(
            reached = results.count { it.second is ControlResult.Success },
            unauthorized = results.filter { it.second is ControlResult.Unauthorized }.map { it.first },
            unreachable = results.filter { it.second is ControlResult.Unreachable }.map { it.first },
        )
    }
}
