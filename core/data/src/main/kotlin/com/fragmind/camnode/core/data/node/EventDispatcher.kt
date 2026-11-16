package com.fragmind.camnode.core.data.node

import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.control.ControlResult
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.di.ApplicationScope
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.WallClock
import com.fragmind.camnode.core.model.byPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers camera alerts to every household member that receives them. Each pending
 * delivery is retried until the receiver confirms or the alert is too old to matter;
 * receivers drop duplicates by event id, so retries are safe.
 */
@Singleton
class EventDispatcher @Inject constructor(
    private val households: HouseholdRepository,
    private val client: ControlClient,
    private val clock: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    /** Event plus the receivers that still have to confirm it. Null means "not resolved yet". */
    private val pending = mutableMapOf<String, Pair<CameraEvent, MutableSet<NodeId>?>>()
    private var worker: Job? = null

    fun dispatch(event: CameraEvent) {
        scope.launch {
            lock.withLock {
                pending[event.id] = event to null
                if (worker?.isActive != true) worker = scope.launch { deliverUntilDone() }
            }
        }
    }

    private suspend fun deliverUntilDone() {
        while (scope.isActive) {
            val done = deliverOnce()
            if (done) return
            delay(RETRY_MILLIS)
        }
    }

    /** Returns true when nothing is left to deliver. */
    private suspend fun deliverOnce(): Boolean {
        val session = households.currentSession() ?: return lock.withLock { pending.clear(); true }
        // Alerts reveal camera activity: only devices allowed to watch receive them.
        val receivers = session.directory.alertReceivers
            .filter { it.id != session.self.id && session.roleOf(it).can(Permission.VIEW_STREAMS) }
        val snapshot = lock.withLock { pending.toMap() }
        for ((id, entry) in snapshot) {
            val (event, waiting) = entry
            val targets = waiting ?: receivers.map { it.id }.toMutableSet()
            receivers.filter { it.id in targets }.forEach { receiver ->
                val delivered = receiver.endpoints.byPreference().any { endpoint ->
                    client.sendEvent(endpoint, session.auth, event, receiver.id) is ControlResult.Success
                }
                if (delivered) targets.remove(receiver.id)
            }
            lock.withLock {
                val expired = clock.nowMillis() - event.atMillis > MAX_AGE_MILLIS
                if (targets.isEmpty() || expired) pending.remove(id) else pending[id] = event to targets
            }
        }
        return lock.withLock { pending.isEmpty() }
    }

    private companion object {
        const val RETRY_MILLIS = 15_000L
        const val MAX_AGE_MILLIS = 10 * 60_000L
    }
}
