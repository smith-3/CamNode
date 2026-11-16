package com.fragmind.camnode.core.control

import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.CameraStatus
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeKeyPair
import com.fragmind.camnode.core.model.NodeKeys
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.RoleBook
import com.fragmind.camnode.core.model.RoleGrant
import com.fragmind.camnode.core.model.StreamProfile
import com.fragmind.camnode.core.model.StreamTicket
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class ControlApiTest {
    private val secret = HouseholdSecret.generate()
    private val cameraId = NodeId("camera")
    private val port = ServerSocket(0).use { it.localPort }
    private val endpoint = NodeEndpoint("127.0.0.1", controlPort = port)

    private val owner = NodeKeys.generate()
    private val viewerKeys = NodeKeys.generate()
    private val cameraKeys = NodeKeys.generate()
    private val revokedKeys = NodeKeys.generate()
    private val adminId = NodeId("owner-phone")
    private val viewerId = NodeId("viewer")
    private val otherCameraId = NodeId("other-camera")
    private val revokedId = NodeId("revoked")

    private val roles = RoleBook().merge(
        RoleBook(
            listOf(
                RoleGrant(viewerId, viewerKeys.publicKey, AccessRole.VIEWER, 1, owner.publicKey).signedBy(owner),
                RoleGrant(revokedId, revokedKeys.publicKey, AccessRole.REVOKED, 1, owner.publicKey).signedBy(owner),
            ),
        ),
        owner.publicKey,
    )
    private var serverDirectory = Directory(listOf(member("camera", NodeRole.CAMERA, at = 1)))

    private val handler = object : ControlHandler {
        override val secret = this@ControlApiTest.secret
        override val selfId = cameraId
        var facing = CameraFacing.BACK
        val events = mutableListOf<CameraEvent>()
        override fun roleOf(caller: Caller) = caller.nodeId?.let { roles.roleOf(it, caller.publicKey, owner.publicKey) } ?: AccessRole.CAMERA
        override fun status() = NodeStatus(
            nodeId = cameraId,
            role = NodeRole.CAMERA,
            batteryPercent = 80,
            charging = true,
            camera = CameraStatus(viewers = 2, profile = StreamProfile.LOW, facing = facing, rotationDegrees = 0),
        )
        override suspend fun exchangeDirectory(remote: Directory) = serverDirectory.merge(remote, owner.publicKey).also { serverDirectory = it }
        override suspend fun applyCameraCommand(command: CameraCommand): NodeStatus {
            command.facing?.let { facing = it }
            return status()
        }
        override suspend fun receiveEvent(event: CameraEvent): Boolean = events.add(event)
        override suspend fun issueStreamTicket(caller: Caller) = StreamTicket(caller.nodeId!!.value, "ticket", 99)
    }
    private val server = ControlServer(port, handler)
    private val client = ControlClient(OkHttpClient())

    private fun auth(id: NodeId, keys: NodeKeyPair) = ControlAuth(secret, id, keys)
    private val adminAuth get() = auth(adminId, owner)

    @Before fun start() = server.start()
    @After fun stop() = server.stop()

    @Test
    fun anyMemberReadsStatusButAWrongSecretIsRejected() = runBlocking {
        assertEquals(ControlResult.Success(handler.status()), client.status(endpoint, auth(otherCameraId, cameraKeys)))
        assertEquals(ControlResult.Unauthorized, client.status(endpoint, ControlAuth(HouseholdSecret.generate())))
    }

    @Test
    fun directoryExchangeConvergesBothSides() = runBlocking {
        val viewerDirectory = Directory(listOf(member("viewer", NodeRole.VIEWER, at = 2)))
        val merged = (client.exchangeDirectory(endpoint, auth(otherCameraId, cameraKeys), viewerDirectory) as ControlResult.Success).value
        assertEquals(setOf("camera", "viewer"), merged.members.map { it.id.value }.toSet())
        assertEquals(merged, serverDirectory)
    }

    @Test
    fun onlyAdministratorsControlCameras() = runBlocking {
        val command = CameraCommand(facing = CameraFacing.FRONT)
        assertEquals(ControlResult.Forbidden, client.applyCameraCommand(endpoint, auth(otherCameraId, cameraKeys), cameraId, command))
        assertEquals(ControlResult.Forbidden, client.applyCameraCommand(endpoint, auth(viewerId, viewerKeys), cameraId, command))
        assertEquals(ControlResult.Forbidden, client.applyCameraCommand(endpoint, ControlAuth(secret), cameraId, command))
        val result = client.applyCameraCommand(endpoint, adminAuth, cameraId, command)
        assertEquals(CameraFacing.FRONT, (result as ControlResult.Success).value.camera?.facing)
    }

    @Test
    fun aSignedCommandForAnotherNodeIsRefused() = runBlocking {
        assertEquals(ControlResult.Unauthorized, client.applyCameraCommand(endpoint, adminAuth, otherCameraId, CameraCommand(facing = CameraFacing.FRONT)))
    }

    @Test
    fun viewersGetStreamTicketsCamerasDoNot() = runBlocking {
        assertEquals(ControlResult.Success(StreamTicket("viewer", "ticket", 99)), client.streamTicket(endpoint, auth(viewerId, viewerKeys), cameraId))
        assertEquals(ControlResult.Forbidden, client.streamTicket(endpoint, auth(otherCameraId, cameraKeys), cameraId))
    }

    @Test
    fun revokedDevicesCannotEvenSendAlerts() = runBlocking {
        val event = CameraEvent.new(cameraId, "Cocina", CameraEventType.MOVED, atMillis = 5)
        assertEquals(ControlResult.Forbidden, client.sendEvent(endpoint, auth(revokedId, revokedKeys), event))
        assertEquals(ControlResult.Success(Unit), client.sendEvent(endpoint, auth(otherCameraId, cameraKeys), event))
        assertEquals(listOf(event), handler.events)
    }

    @Test
    fun replayedAndTamperedRequestsAreRejected() = runBlocking {
        var captured: Request? = null
        val recording = ControlClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain -> captured = chain.request(); chain.proceed(chain.request()) }).build())
        assertTrue(recording.applyCameraCommand(endpoint, adminAuth, cameraId, CameraCommand(facing = CameraFacing.FRONT)) is ControlResult.Success)

        val raw = OkHttpClient()
        val replay = raw.newCall(captured!!).execute().use { it.code }
        assertEquals(401, replay)

        val tampered = captured!!.newBuilder()
            .post(okhttp3.RequestBody.create(null, """{"facing":"BACK","paused":true}"""))
            .header(ControlProtocol.HEADER_TIME, captured!!.header(ControlProtocol.HEADER_TIME)!!)
            .build()
        assertEquals(401, raw.newCall(tampered).execute().use { it.code })
    }

    @Test
    fun unreachablePeerIsReportedNotThrown() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertTrue(client.status(NodeEndpoint("127.0.0.1", controlPort = closedPort), ControlAuth(secret)) is ControlResult.Unreachable)
    }

    private fun member(id: String, role: NodeRole, at: Long) =
        Member(NodeId(id), id, role, listOf(NodeEndpoint("100.64.0.1")), updatedAtMillis = at)
}
