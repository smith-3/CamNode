package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.model.CameraEvent
import kotlinx.coroutines.flow.Flow

interface AlertsRepository {
    /** Whether this phone is a master that receives alerts from the cameras. */
    val receiverEnabled: Flow<Boolean>
    val alerts: Flow<List<CameraEvent>>

    /** Also flags this phone in the directory so cameras know where to send alerts. */
    suspend fun setReceiverEnabled(enabled: Boolean)

    /** Stores [event]; returns false when it was already received (sender retries). */
    suspend fun record(event: CameraEvent): Boolean

    suspend fun clear()
}
