package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.NodeId
import kotlinx.coroutines.flow.Flow

interface CameraSettingsRepository {
    val settings: Flow<CameraSettings>
    suspend fun update(transform: (CameraSettings) -> CameraSettings)

    /** Whether the camera grid plays live video; old phones default to off. */
    val liveGrid: Flow<Boolean>
    suspend fun setLiveGrid(enabled: Boolean)

    val cameraLayout: Flow<CameraLayout>
    suspend fun setCameraLayout(layout: CameraLayout)

    /** Degrees this viewer turns each camera's picture; missing cameras are upright. */
    val displayRotations: Flow<Map<NodeId, Int>>
    suspend fun rotateDisplay(cameraId: NodeId)

    /** True from the moment the user starts the camera until the user stops it, across reboots. */
    val cameraShouldRun: Flow<Boolean>
    suspend fun setCameraShouldRun(shouldRun: Boolean)
}
