package com.fragmind.camnode.core.control

import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeKeys
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.StreamTicket
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.math.abs

/** A caller whose signature checked out, or an unsigned one (both fields null). */
data class Caller(val nodeId: NodeId?, val publicKey: String?)

/** What a node answers to its peers. Optional capabilities answer 404 when absent. */
interface ControlHandler {
    val secret: HouseholdSecret
    val selfId: NodeId

    /** The caller's role in this household, decided by the signed role grants. */
    fun roleOf(caller: Caller): AccessRole
    fun status(): NodeStatus
    suspend fun exchangeDirectory(remote: Directory): Directory

    /** Null when this node is not a camera. */
    suspend fun applyCameraCommand(command: CameraCommand): NodeStatus?

    /** False when this node does not receive alerts. */
    suspend fun receiveEvent(event: CameraEvent): Boolean

    /** Null when this node is not streaming. */
    suspend fun issueStreamTicket(caller: Caller): StreamTicket?
}

/**
 * HTTP control API of a node. It listens on every interface. The household secret admits
 * a device; a signed request identifies it, and its role decides what it may do.
 */
class ControlServer(private val port: Int, private val handler: ControlHandler, private val clock: () -> Long = System::currentTimeMillis) {
    private var server: EmbeddedServer<*, *>? = null
    private val replays = ReplayGuard(ControlProtocol.MAX_SKEW_MILLIS * 2)

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get(ControlProtocol.STATUS_PATH) {
                    authorize(call, privileged = false, permission = null) ?: return@get
                    call.respondJson(handler.status(), NodeStatus.serializer())
                }
                post(ControlProtocol.DIRECTORY_PATH) {
                    val body = authorize(call, privileged = false, permission = null) ?: return@post
                    val remote = call.decode(body, Directory.serializer()) ?: return@post
                    call.respondJson(handler.exchangeDirectory(remote), Directory.serializer())
                }
                post(ControlProtocol.EVENTS_PATH) {
                    val body = authorize(call, privileged = false, permission = null) ?: return@post
                    val event = call.decode(body, CameraEvent.serializer()) ?: return@post
                    call.respond(if (handler.receiveEvent(event)) HttpStatusCode.Accepted else HttpStatusCode.NotFound)
                }
                post(ControlProtocol.CAMERA_PATH) {
                    val body = authorize(call, privileged = true, permission = Permission.CONTROL_CAMERAS) ?: return@post
                    val command = call.decode(body, CameraCommand.serializer()) ?: return@post
                    val status = handler.applyCameraCommand(command)
                    if (status == null) call.respond(HttpStatusCode.NotFound) else call.respondJson(status, NodeStatus.serializer())
                }
                post(ControlProtocol.STREAM_TICKET_PATH) {
                    authorize(call, privileged = true, permission = Permission.VIEW_STREAMS) ?: return@post
                    val ticket = handler.issueStreamTicket(callerOf(call))
                    if (ticket == null) call.respond(HttpStatusCode.NotFound) else call.respondJson(ticket, StreamTicket.serializer())
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        server = null
    }

    /**
     * Checks secret, signature, freshness, target and role. Returns the raw body when the
     * call may proceed, or null after responding with the refusal.
     * [privileged] routes require a signed request addressed to this node specifically.
     */
    private suspend fun authorize(call: ApplicationCall, privileged: Boolean, permission: Permission?): ByteArray? {
        if (!ControlProtocol.isAuthorized(call.request.headers[HttpHeaders.Authorization], handler.secret)) {
            call.respond(HttpStatusCode.Unauthorized)
            return null
        }
        if ((call.request.contentLength() ?: 0) > ControlProtocol.MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        val body = call.receive<ByteArray>()
        val verification = verifySignature(call, body)
        if (verification == Verification.Invalid) {
            call.respond(HttpStatusCode.Unauthorized)
            return null
        }
        val caller = (verification as? Verification.Signed)?.caller ?: Caller(null, null)
        call.attributes.put(CallerKey, caller)
        val role = handler.roleOf(caller)
        // A signed privileged call must name this node; an unsigned one falls to its role
        // (CAMERA in a secured household), which lacks every privileged permission.
        val wrongTarget = privileged && verification is Verification.Signed && verification.target != handler.selfId.value
        val allowed = role != AccessRole.REVOKED && (permission == null || role.can(permission)) && !wrongTarget
        if (!allowed) {
            call.respond(HttpStatusCode.Forbidden)
            return null
        }
        return body
    }

    private sealed interface Verification {
        data object Unsigned : Verification
        data object Invalid : Verification
        data class Signed(val caller: Caller, val target: String) : Verification
    }

    private fun verifySignature(call: ApplicationCall, body: ByteArray): Verification {
        val headers = call.request.headers
        val node = headers[ControlProtocol.HEADER_NODE] ?: return Verification.Unsigned
        val key = headers[ControlProtocol.HEADER_KEY] ?: return Verification.Invalid
        val time = headers[ControlProtocol.HEADER_TIME]?.toLongOrNull() ?: return Verification.Invalid
        val target = headers[ControlProtocol.HEADER_TARGET] ?: return Verification.Invalid
        val signature = headers[ControlProtocol.HEADER_SIGNATURE] ?: return Verification.Invalid
        val now = clock()
        val fresh = abs(now - time) <= ControlProtocol.MAX_SKEW_MILLIS
        val addressedHere = target == handler.selfId.value || target == ControlProtocol.ANY_TARGET
        val payload = ControlProtocol.signedPayload(call.request.httpMethod.value, call.request.path(), target, time, body)
        val valid = fresh && addressedHere && NodeKeys.verify(key, payload, signature) && replays.firstSeen(signature, now)
        val nodeId = runCatching { NodeId(node) }.getOrNull()
        return if (valid && nodeId != null) Verification.Signed(Caller(nodeId, key), target) else Verification.Invalid
    }

    private fun callerOf(call: ApplicationCall): Caller = call.attributes.getOrNull(CallerKey) ?: Caller(null, null)

    private suspend fun <T> ApplicationCall.decode(body: ByteArray, serializer: KSerializer<T>): T? = try {
        ControlProtocol.json.decodeFromString(serializer, body.decodeToString())
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest)
        null
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest)
        null
    }

    private suspend fun <T> ApplicationCall.respondJson(value: T, serializer: KSerializer<T>) =
        respondText(ControlProtocol.json.encodeToString(serializer, value), ContentType.Application.Json)

    private companion object {
        const val GRACE_MILLIS = 200L
        const val TIMEOUT_MILLIS = 1_000L
        val CallerKey = AttributeKey<Caller>("camnode-caller")
    }
}

/** Remembers recent signatures so a captured request can't be replayed within its freshness window. */
internal class ReplayGuard(private val windowMillis: Long) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun firstSeen(signature: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value > windowMillis }
        return seen.put(signature, now) == null
    }
}
