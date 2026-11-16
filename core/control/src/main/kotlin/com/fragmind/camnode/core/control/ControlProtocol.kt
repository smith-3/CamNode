package com.fragmind.camnode.core.control

import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeKeyPair
import com.fragmind.camnode.core.model.NodeKeys
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Wire contract between CamNode phones. Paths are versioned so phones running
 * different app versions can coexist in one household.
 */
internal object ControlProtocol {
    const val STATUS_PATH = "/v1/status"
    const val DIRECTORY_PATH = "/v1/directory"
    const val CAMERA_PATH = "/v1/camera"
    const val EVENTS_PATH = "/v1/events"
    const val STREAM_TICKET_PATH = "/v1/stream-ticket"
    const val MAX_BODY_BYTES = 256 * 1024L

    const val HEADER_NODE = "X-CamNode-Node"
    const val HEADER_KEY = "X-CamNode-Key"
    const val HEADER_TIME = "X-CamNode-Time"
    const val HEADER_TARGET = "X-CamNode-Target"
    const val HEADER_SIGNATURE = "X-CamNode-Signature"
    /** Target of a request whose callee is not known yet, such as an invite seed. Only allowed on unprivileged routes. */
    const val ANY_TARGET = "*"
    /** How far a signed request's clock may drift from the callee's before it is refused. */
    const val MAX_SKEW_MILLIS = 5 * 60_000L

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun bearer(secret: HouseholdSecret): String = "Bearer ${secret.value}"

    fun isAuthorized(header: String?, secret: HouseholdSecret): Boolean {
        val presented = header?.toByteArray() ?: return false
        return MessageDigest.isEqual(presented, bearer(secret).toByteArray())
    }

    /** What a caller signs: the target is included so a captured request can't be replayed against another node. */
    fun signedPayload(method: String, path: String, target: String, timeMillis: Long, body: ByteArray): ByteArray =
        "camnode-req-v1|$method|$path|$target|$timeMillis|${NodeKeys.sha256(body)}".toByteArray()
}

/**
 * Credentials for a call. The household secret admits the device; its key pair proves
 * which device is calling, which is what roles are checked against.
 */
data class ControlAuth(
    val secret: HouseholdSecret,
    val caller: NodeId? = null,
    val keys: NodeKeyPair? = null,
)

sealed interface ControlResult<out T> {
    data class Success<T>(val value: T) : ControlResult<T>
    /** Wrong household secret or a bad signature. */
    data object Unauthorized : ControlResult<Nothing>
    /** A valid household member whose role does not allow this action. */
    data object Forbidden : ControlResult<Nothing>
    data class Unreachable(val reason: String) : ControlResult<Nothing>
}
