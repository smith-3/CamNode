package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.NodeId
import kotlinx.coroutines.flow.Flow

interface AlertsRepository {
    /** Whether this phone receives alerts: its role may view and the user did not mute them here. */
    val receiverEnabled: Flow<Boolean>

    /** The user silenced alerts on this phone only. */
    val alertsMuted: Flow<Boolean>
    val alerts: Flow<List<CameraEvent>>

    val cameraMutes: Flow<Map<NodeId, Long>>

    suspend fun setCameraMuted(cameraId: NodeId, untilMillis: Long?)

    /** Records and deduplicates every event, returning whether to notify at receipt time. */
    suspend fun recordForNotification(event: CameraEvent): Boolean

    /** Also updates this phone's directory record so cameras know whether to send it alerts. */
    suspend fun setAlertsMuted(muted: Boolean)

    /** Stores [event]; returns false when it was already received (sender retries). */
    suspend fun record(event: CameraEvent): Boolean

    suspend fun dismiss(id: String)

    suspend fun restore(event: CameraEvent)

    suspend fun clear()
}
