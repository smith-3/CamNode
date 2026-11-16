package com.fragmind.camnode.core.control

import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeKeys
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.StreamTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Calls another node's control API. [target] is the callee's node id when known;
 * privileged calls (camera commands, stream tickets) require it.
 */
class ControlClient @Inject constructor(private val http: OkHttpClient) {

    suspend fun status(endpoint: NodeEndpoint, auth: ControlAuth, target: NodeId? = null): ControlResult<NodeStatus> =
        call(endpoint, "GET", ControlProtocol.STATUS_PATH, auth, target, body = null) { decode(it, NodeStatus.serializer()) }

    /** Sends our directory and returns the peer's merged view. */
    suspend fun exchangeDirectory(endpoint: NodeEndpoint, auth: ControlAuth, directory: Directory, target: NodeId? = null): ControlResult<Directory> =
        call(endpoint, "POST", ControlProtocol.DIRECTORY_PATH, auth, target, encode(directory, Directory.serializer())) { decode(it, Directory.serializer()) }

    /** Changes a running camera and returns its new status. Needs the control permission. */
    suspend fun applyCameraCommand(endpoint: NodeEndpoint, auth: ControlAuth, target: NodeId, command: CameraCommand): ControlResult<NodeStatus> =
        call(endpoint, "POST", ControlProtocol.CAMERA_PATH, auth, target, encode(command, CameraCommand.serializer())) { decode(it, NodeStatus.serializer()) }

    /** Delivers an alert; Success means the receiver stored it. */
    suspend fun sendEvent(endpoint: NodeEndpoint, auth: ControlAuth, event: CameraEvent, target: NodeId? = null): ControlResult<Unit> =
        call(endpoint, "POST", ControlProtocol.EVENTS_PATH, auth, target, encode(event, CameraEvent.serializer())) { }

    /** RTSP credentials for this caller. Needs the view permission. */
    suspend fun streamTicket(endpoint: NodeEndpoint, auth: ControlAuth, target: NodeId): ControlResult<StreamTicket> =
        call(endpoint, "POST", ControlProtocol.STREAM_TICKET_PATH, auth, target, ByteArray(0)) { decode(it, StreamTicket.serializer()) }

    private suspend fun <T> call(
        endpoint: NodeEndpoint,
        method: String,
        path: String,
        auth: ControlAuth,
        target: NodeId?,
        body: ByteArray?,
        parse: (String) -> T,
    ): ControlResult<T> {
        val builder = Request.Builder()
            .url("http://${endpoint.urlHost}:${endpoint.controlPort}$path")
            .header("Authorization", ControlProtocol.bearer(auth.secret))
        val keys = auth.keys
        if (auth.caller != null && keys != null) {
            val targetValue = target?.value ?: ControlProtocol.ANY_TARGET
            val time = System.currentTimeMillis()
            val signature = NodeKeys.sign(keys.privateKey, ControlProtocol.signedPayload(method, path, targetValue, time, body ?: ByteArray(0)))
            builder.header(ControlProtocol.HEADER_NODE, auth.caller.value)
                .header(ControlProtocol.HEADER_KEY, keys.publicKey)
                .header(ControlProtocol.HEADER_TIME, time.toString())
                .header(ControlProtocol.HEADER_TARGET, targetValue)
                .header(ControlProtocol.HEADER_SIGNATURE, signature)
        }
        val request = if (body == null) builder.get().build() else builder.method(method, body.toRequestBody(JSON)).build()
        return execute(request, parse)
    }

    private fun <T> encode(value: T, serializer: KSerializer<T>): ByteArray =
        ControlProtocol.json.encodeToString(serializer, value).encodeToByteArray()

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = ControlProtocol.json.decodeFromString(serializer, body)

    private suspend fun <T> execute(request: Request, parse: (String) -> T): ControlResult<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).await().use { response ->
                when {
                    response.code == 401 -> ControlResult.Unauthorized
                    response.code == 403 -> ControlResult.Forbidden
                    !response.isSuccessful -> ControlResult.Unreachable("HTTP ${response.code}")
                    else -> ControlResult.Success(parse(response.body.string()))
                }
            }
        } catch (e: IOException) {
            ControlResult.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            ControlResult.Unreachable("Invalid response: ${e.message}")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        })
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
