package com.fragmind.camnode.core.data.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.AppLockState
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.NodeKeyPair
import com.fragmind.camnode.core.model.NodeEndpoint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Everything this phone persists. Small enough to live in a single DataStore file. */
@kotlinx.serialization.Serializable
internal data class StoredState(
    val self: LocalNode? = null,
    /** This phone's own key pair; generated once and never shared. */
    val nodeKeys: NodeKeyPair? = null,
    val household: Household? = null,
    val directory: Directory = Directory(),
    /** Null until the user changes something, so the first default can depend on the device. */
    val cameraSettings: CameraSettings? = null,
    /** Live video in the camera grid; null means "decide by device tier". */
    val liveGrid: Boolean? = null,
    val cameraLayout: CameraLayout = CameraLayout.CARDS,
    /** Screen rotation this viewer applies to each camera, keyed by node id. */
    val displayRotations: Map<String, Int> = emptyMap(),
    /** Invite seeds kept until the first successful directory exchange. */
    val bootstrapSeeds: List<NodeEndpoint> = emptyList(),
    /**
     * Alerts follow the role (whoever may view receives them); this only silences this phone.
     * Replaces the old manual "master" switch, whose stored value is now ignored.
     */
    val alertsMuted: Boolean = false,
    /** Local notification mute expiry per camera; Long.MAX_VALUE means until restored. */
    val cameraMutes: Map<String, Long> = emptyMap(),
    /** Most recent alerts first, capped so the file stays small. */
    val alerts: List<CameraEvent> = emptyList(),
    val dismissedAlertIds: List<String> = emptyList(),
    /** The user left the camera on; a reboot or update should offer to resume it. */
    val cameraShouldRun: Boolean = false,
    val appLock: AppLockState = AppLockState(),
)

internal object StoredStateSerializer : Serializer<StoredState> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = StoredState()

    override suspend fun readFrom(input: InputStream): StoredState = try {
        json.decodeFromString(StoredState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable CamNode state", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Invalid CamNode state", e)
    }

    override suspend fun writeTo(t: StoredState, output: OutputStream) {
        output.write(json.encodeToString(StoredState.serializer(), t).encodeToByteArray())
    }
}
