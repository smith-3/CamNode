package com.fragmind.camnode.core.data.node

import android.util.Log
import com.fragmind.camnode.core.control.Caller
import com.fragmind.camnode.core.control.ControlHandler
import com.fragmind.camnode.core.data.HouseholdSession
import com.fragmind.camnode.core.data.di.ApplicationScope
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.StreamTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.fragmind.camnode.core.control.ControlServer
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.device.DeviceBattery
import com.fragmind.camnode.core.model.CamNodePorts
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraStatus
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.NodeStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A running camera, as seen by the control API. */
interface CameraCapability {
    fun status(): CameraStatus?
    suspend fun apply(command: CameraCommand): CameraStatus?

    /** RTSP credentials for [viewer]; null when the camera is not streaming. */
    fun issueTicket(viewer: NodeId): StreamTicket?
}

fun interface AlertSink {
    suspend fun receive(event: CameraEvent)
}

/**
 * The one control server of this phone. Capabilities (camera, alert receiver) attach
 * and detach independently; the server runs while at least one is attached, so a phone
 * can be a camera and a master at the same time without fighting over the port.
 */
@Singleton
class LocalNodeServer @Inject constructor(
    private val households: HouseholdRepository,
    private val battery: DeviceBattery,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Latest session, so each request's role is decided without waiting on storage. */
    @Volatile private var session: HouseholdSession? = null

    init {
        scope.launch { households.session.collect { session = it } }
    }

    private val lock = Mutex()
    private var camera: CameraCapability? = null
    private var alertSink: AlertSink? = null
    private var running: Running? = null

    private class Running(val household: Household, val server: ControlServer)

    suspend fun attachCamera(capability: CameraCapability) = reconcile { camera = capability }

    suspend fun detachCamera() = reconcile { camera = null }

    suspend fun attachAlertSink(sink: AlertSink) = reconcile { alertSink = sink }

    suspend fun detachAlertSink() = reconcile { alertSink = null }

    private suspend fun reconcile(change: () -> Unit) = lock.withLock {
        change()
        val wanted = camera != null || alertSink != null
        val session = households.currentSession()
        val current = running
        val stale = current != null && (!wanted || session == null || current.household != session.household)
        if (stale) {
            current?.server?.stop()
            running = null
        }
        if (wanted && running == null && session != null) running = start(session.self.id, session.household)
    }

    private fun start(selfId: NodeId, household: Household): Running? {
        val handler = object : ControlHandler {
            override val secret = household.secret
            override val selfId = selfId

            /** Unsigned callers only get full access in a legacy household, which has no roles yet. */
            override fun roleOf(caller: Caller): AccessRole {
                val current = session?.takeIf { it.household == household } ?: return AccessRole.CAMERA
                val nodeId = caller.nodeId ?: return if (household.secured) AccessRole.CAMERA else AccessRole.ADMIN
                return current.directory.roles.roleOf(nodeId, caller.publicKey, household.ownerKey)
            }

            override suspend fun issueStreamTicket(caller: Caller): StreamTicket? =
                caller.nodeId?.let { camera?.issueTicket(it) }

            override fun status(): NodeStatus {
                val level = battery.read()
                val cameraStatus = camera?.status()
                return NodeStatus(
                    nodeId = selfId,
                    role = if (camera != null) NodeRole.CAMERA else NodeRole.VIEWER,
                    batteryPercent = level.percent,
                    charging = level.charging,
                    camera = cameraStatus,
                )
            }

            override suspend fun exchangeDirectory(remote: Directory): Directory = households.mergeDirectory(remote)

            override suspend fun applyCameraCommand(command: CameraCommand): NodeStatus? =
                camera?.apply(command)?.let { status().copy(camera = it) }

            override suspend fun receiveEvent(event: CameraEvent): Boolean {
                val sink = alertSink ?: return false
                sink.receive(event)
                return true
            }
        }
        return try {
            Running(household, ControlServer(CamNodePorts.CONTROL, handler).also(ControlServer::start))
        } catch (e: IOException) {
            Log.w(TAG, "Control API unavailable", e)
            null
        }
    }

    private companion object {
        const val TAG = "LocalNodeServer"
    }
}
