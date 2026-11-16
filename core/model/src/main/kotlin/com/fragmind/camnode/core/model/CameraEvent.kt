package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class CameraEventType { MOVED }

/**
 * Something a camera noticed. [id] lets receivers drop duplicates, because the sender
 * retries until each receiver confirms.
 */
@Serializable
data class CameraEvent(
    val id: String,
    val cameraId: NodeId,
    val cameraName: String,
    val type: CameraEventType,
    val atMillis: Long,
) {
    companion object {
        fun new(cameraId: NodeId, cameraName: String, type: CameraEventType, atMillis: Long) =
            CameraEvent(UUID.randomUUID().toString(), cameraId, cameraName, type, atMillis)
    }
}
