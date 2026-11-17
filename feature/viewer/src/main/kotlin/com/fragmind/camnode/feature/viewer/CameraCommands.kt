package com.fragmind.camnode.feature.viewer

import com.fragmind.camnode.core.model.NodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class CameraCommandState { PENDING, CONFIRMED, UNCONFIRMED, DENIED, RECONNECTING }

internal data class CameraPollRevision(val generation: Long, val request: Long)

/** Owned by the main dispatcher. Each camera has its own request and revision. */
internal class CameraCommands(private val scope: CoroutineScope) {
    private val mutableStates = MutableStateFlow<Map<NodeId, CameraCommandState>>(emptyMap())
    val states: StateFlow<Map<NodeId, CameraCommandState>> = mutableStates
    private val jobs = mutableMapOf<NodeId, Job>()
    private val revisions = mutableMapOf<NodeId, Long>()
    private var generation = 0L

    fun revision(id: NodeId): CameraPollRevision = CameraPollRevision(generation, revisions[id] ?: 0L)

    fun acceptsPoll(id: NodeId, revision: CameraPollRevision): Boolean =
        revision(id) == revision && states.value[id] != CameraCommandState.PENDING

    fun start(
        id: NodeId,
        onTimeout: () -> CameraCommandState = { CameraCommandState.UNCONFIRMED },
        operation: suspend () -> CameraCommandState,
    ): Boolean {
        if (states.value[id] == CameraCommandState.PENDING) return false
        val requestGeneration = generation
        revisions[id] = (revisions[id] ?: 0L) + 1
        mutableStates.value += id to CameraCommandState.PENDING
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    withTimeout(10_000) { operation() }
                } catch (_: TimeoutCancellationException) {
                    onTimeout()
                }
                if (generation == requestGeneration) mutableStates.value += id to result
            } catch (cancelled: CancellationException) {
                if (generation == requestGeneration) mutableStates.value -= id
                throw cancelled
            } finally {
                if (generation == requestGeneration) {
                    revisions[id] = (revisions[id] ?: 0L) + 1
                    jobs.remove(id)
                }
            }
        }
        jobs[id] = job
        job.start()
        return true
    }

    fun confirmReconnection(id: NodeId) {
        if (states.value[id] == CameraCommandState.RECONNECTING) {
            mutableStates.value += id to CameraCommandState.CONFIRMED
        }
    }

    fun clear() {
        generation++
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        revisions.clear()
        mutableStates.value = emptyMap()
    }
}
