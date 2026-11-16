package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable

/** Encoder presets. Bitrates target H.264 over a Tailscale link, including DERP relays. */
@Serializable
enum class StreamProfile(val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
    LOW(640, 360, 15, 600_000),
    MEDIUM(1280, 720, 20, 1_500_000),
    HIGH(1920, 1080, 24, 3_000_000),
}

@Serializable
enum class CameraFacing { BACK, FRONT }

/**
 * How the picture is rotated before encoding. [AUTO] follows how the phone is held when
 * the stream starts; the fixed values suit a phone mounted sideways or upside down.
 * [rotationDegrees] uses the encoder's convention: 0 is landscape, 90 is portrait.
 */
@Serializable
enum class VideoOrientation(val rotationDegrees: Int?) {
    AUTO(null),
    LANDSCAPE(0),
    PORTRAIT(90),
    LANDSCAPE_INVERTED(180),
    PORTRAIT_INVERTED(270);

    companion object {
        fun fromDegrees(degrees: Int): VideoOrientation =
            entries.firstOrNull { it.rotationDegrees == Math.floorMod(degrees, 360) } ?: LANDSCAPE
    }
}

/**
 * How the camera renders its picture. [MONOCHROME] drops color (it also compresses better);
 * [NIGHT] adds longer exposures and a brighter, higher-contrast monochrome picture.
 * Phones block infrared, so in total darkness [NIGHT] still needs some light or the torch.
 */
@Serializable
enum class ImageMode { COLOR, MONOCHROME, NIGHT }

@Serializable
data class CameraSettings(
    val profile: StreamProfile = StreamProfile.MEDIUM,
    val facing: CameraFacing = CameraFacing.BACK,
    val audioEnabled: Boolean = false,
    val orientation: VideoOrientation = VideoOrientation.AUTO,
    /** Alert the household when someone picks up or moves this camera. */
    val movementAlerts: Boolean = true,
    val imageMode: ImageMode = ImageMode.COLOR,
)

/** What a streaming camera reports. Absent from [NodeStatus] when the node is not streaming. */
@Serializable
data class CameraStatus(
    val viewers: Int,
    val profile: StreamProfile,
    val facing: CameraFacing,
    val rotationDegrees: Int,
    /** Sensor zoom applied before encoding; [zoomMin]..[zoomMax] is what the current lens supports. */
    val zoom: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val imageMode: ImageMode = ImageMode.COLOR,
    val torch: Boolean = false,
    /** Whether the current lens has a flash the torch can use. */
    val torchAvailable: Boolean = false,
    /** Camera and encoder are off (to cool down or save battery); the node still answers and can resume. */
    val paused: Boolean = false,
) {
    val portrait: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
}

/** Live health of any node running a control server: cameras, alert receivers, recorders. */
@Serializable
data class NodeStatus(
    val nodeId: NodeId,
    val role: NodeRole,
    val batteryPercent: Int?,
    val charging: Boolean,
    val camera: CameraStatus? = null,
)

/** A partial change to a running camera; null fields stay as they are. */
@Serializable
data class CameraCommand(
    val facing: CameraFacing? = null,
    val orientation: VideoOrientation? = null,
    val zoom: Float? = null,
    val imageMode: ImageMode? = null,
    val torch: Boolean? = null,
    val paused: Boolean? = null,
)

object RtspCredentials {
    const val USER = "camnode"
}

/**
 * How a viewer turns a camera's picture on its own screen (0, 90, 180 or 270 degrees).
 * Purely visual: the camera keeps streaming the same video, so nothing reconnects.
 */
fun nextDisplayRotation(degrees: Int): Int = Math.floorMod(degrees + 90, 360)
