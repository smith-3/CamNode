package com.fragmind.camnode.feature.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.DirectorySync
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.HouseholdSession
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.ImageMode
import com.fragmind.camnode.core.model.CameraStatus
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.RtspCredentials
import com.fragmind.camnode.core.model.StreamTicket
import com.fragmind.camnode.core.data.CameraSettingsRepository
import com.fragmind.camnode.core.data.PeerProbe
import com.fragmind.camnode.core.data.PeerReachability
import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.control.ControlResult
import com.fragmind.camnode.core.network.LanDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CameraHealth {
    data object Checking : CameraHealth
    /** [route] is the address that answered, preferring the same Wi-Fi over Tailscale. */
    /** [ticket] is this phone's own RTSP credential for the camera; null when it may not watch. */
    data class Online(val status: NodeStatus, val route: NodeEndpoint, val ticket: StreamTicket? = null) : CameraHealth {
        val camera: CameraStatus? get() = status.camera
    }
    data object Unauthorized : CameraHealth
    data object Offline : CameraHealth
}

data class CameraTile(
    val id: NodeId,
    val name: String,
    val health: CameraHealth,
    /** Present only while the camera answers, so offline tiles never hold a video decoder. */
    val rtspUrl: String?,
    /** Screen rotation this viewer applies; the stream itself is untouched. */
    val displayRotation: Int = 0,
    internal val commandState: CameraCommandState? = null,
)

data class ViewerUiState(
    val loading: Boolean = true,
    val householdName: String = "",
    val cameras: List<CameraTile> = emptyList(),
    /** Off: tiles show status only and video plays when a camera is opened. */
    val liveGrid: Boolean = true,
    val cameraLayout: CameraLayout = CameraLayout.CARDS,
    /** This phone's role: whether it may watch, and whether it may control cameras. */
    val access: AccessRole = AccessRole.CAMERA,
) {
    val online: Int get() = cameras.count { it.health is CameraHealth.Online }
    val canView: Boolean get() = access.can(Permission.VIEW_STREAMS)
    val canControl: Boolean get() = access.can(Permission.CONTROL_CAMERAS)
    val canManage: Boolean get() = access.can(Permission.MANAGE_HOUSEHOLD)
}

@HiltViewModel
class ViewerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val households: HouseholdRepository,
    private val directorySync: DirectorySync,
    private val client: ControlClient,
    private val probe: PeerProbe,
    private val cameraSettings: CameraSettingsRepository,
    private val lanDiscovery: LanDiscovery,
) : ViewModel() {
    private val commands = CameraCommands(viewModelScope)
    private var currentSession: HouseholdSession? = null
    private val health = MutableStateFlow<Map<NodeId, CameraHealth>>(emptyMap())
    internal val playback = PlaybackSessions(viewModelScope, { url -> RtspConnection(context, url, viewModelScope) })

    init {
        viewModelScope.launch {
            households.session.collect { session ->
                if (currentSession?.household != session?.household || currentSession?.self?.id != session?.self?.id || currentSession?.role != session?.role) {
                    commands.clear()
                    health.value = emptyMap()
                    playback.clear()
                }
                currentSession = session
            }
        }
    }

    /** This phone's role without the discovery work [uiState] starts; null until the store loads. */
    val access: StateFlow<AccessRole?> = households.session.map { it?.role }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setForeground(active: Boolean) = playback.setForeground(active)

    override fun onCleared() {
        commands.clear()
        playback.clear()
    }

    /** Discovery, gossip and health checks run only while a screen collects this state. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ViewerUiState> = channelFlow {
        val discovered = MutableStateFlow<Map<NodeId, NodeEndpoint>>(emptyMap())
        launch {
            households.session
                .map { it?.household }
                .distinctUntilChanged()
                .flatMapLatest { household -> household?.let(lanDiscovery::discover) ?: flowOf(emptyMap()) }
                .collect { discovered.value = it }
        }
        launch {
            discovered.collectLatest { found ->
                while (true) {
                    directorySync.sync(found.values)
                    delay(REFRESH_MILLIS)
                }
            }
        }
        launch {
            households.session.map { it?.household }.distinctUntilChanged().collectLatest {
                discovered.collectLatest { found ->
                    while (true) {
                        households.currentSession()?.let { checkHealth(it, found) }
                        delay(REFRESH_MILLIS)
                    }
                }
            }
        }
        combine(
            combine(households.session, commands.states) { session, states -> session to states },
            health, cameraSettings.liveGrid, cameraSettings.cameraLayout, cameraSettings.displayRotations,
        ) { sessionAndCommands, health, liveGrid, layout, rotations ->
            val (session, commandStates) = sessionAndCommands
            ViewerUiState(
                loading = false,
                householdName = session?.household?.name.orEmpty(),
                cameras = session?.let { tiles(it, health) }.orEmpty().map { it.copy(displayRotation = rotations[it.id] ?: 0, commandState = commandStates[it.id]) },
                liveGrid = liveGrid,
                cameraLayout = layout,
                access = session?.role ?: AccessRole.CAMERA,
            )
        }.collect(::send)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    fun switchLens(cameraId: NodeId) = command(cameraId) { camera ->
        CameraCommand(facing = if (camera.facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK)
    }

    /** Turns the picture on this screen only: instant, no reconnect, nothing changes on the camera. */
    fun rotate(cameraId: NodeId) {
        viewModelScope.launch { cameraSettings.rotateDisplay(cameraId) }
    }

    /** Steps the camera's sensor zoom by [delta] within what the lens supports. */
    /** Color, black and white, or night: applied on the camera, so every viewer and recording sees it. */
    fun setImageMode(cameraId: NodeId, mode: ImageMode) = command(cameraId) { CameraCommand(imageMode = mode) }

    /** Turns a camera off to cool down, or back on; the node stays reachable while paused. */
    fun setPaused(cameraId: NodeId, paused: Boolean) = command(cameraId) { CameraCommand(paused = paused) }

    fun toggleTorch(cameraId: NodeId) = command(cameraId) { camera -> CameraCommand(torch = !camera.torch) }

    fun setZoom(cameraId: NodeId, zoom: Float) = command(cameraId) { camera ->
        CameraCommand(zoom = zoom.coerceIn(camera.zoomMin, camera.zoomMax))
    }

    fun setLiveGrid(enabled: Boolean) {
        viewModelScope.launch { cameraSettings.setLiveGrid(enabled) }
    }

    fun setCameraLayout(layout: CameraLayout) {
        viewModelScope.launch { cameraSettings.setCameraLayout(layout) }
    }

    fun setMovementAlerts(cameraId: NodeId, enabled: Boolean, mutedUntilMillis: Long) = command(cameraId) {
        CameraCommand(movementAlerts = enabled, movementAlertsMutedUntilMillis = mutedUntilMillis)
    }

    /** A timed-out mutation is never resent. Read the camera to reconcile its actual state. */
    fun refreshCamera(cameraId: NodeId) {
        val session = currentSession ?: return
        val camera = remoteCameras(session).firstOrNull { it.id == cameraId } ?: return
        val online = health.value[cameraId] as? CameraHealth.Online
        var acknowledged = false
        commands.start(cameraId, onTimeout = { if (acknowledged) CameraCommandState.RECONNECTING else CameraCommandState.UNCONFIRMED }) {
            when (val reach = probe.probe(listOfNotNull(online?.route) + camera.endpoints, session.auth, cameraId)) {
                is PeerReachability.Online -> {
                    if (sameSession(session) && reach.status.nodeId == cameraId) {
                        acknowledged = true
                        updateConfirmedCamera(cameraId, session, CameraHealth.Online(reach.status, reach.route, online?.ticket))
                    } else CameraCommandState.UNCONFIRMED
                }
                PeerReachability.Unauthorized -> CameraCommandState.DENIED
                PeerReachability.Offline -> CameraCommandState.UNCONFIRMED
            }
        }
    }

    private fun command(cameraId: NodeId, build: (CameraStatus) -> CameraCommand) {
        val session = currentSession ?: return
        if (!session.can(Permission.CONTROL_CAMERAS)) return
        val online = health.value[cameraId] as? CameraHealth.Online ?: return
        val camera = online.camera ?: return
        var acknowledged = false
        commands.start(cameraId, onTimeout = { if (acknowledged) CameraCommandState.RECONNECTING else CameraCommandState.UNCONFIRMED }) {
            when (val result = client.applyCameraCommand(online.route, session.auth, cameraId, build(camera))) {
                is ControlResult.Success -> {
                    if (sameSession(session) && result.value.nodeId == cameraId) {
                        acknowledged = true
                        updateConfirmedCamera(cameraId, session, online.copy(status = result.value))
                    } else CameraCommandState.UNCONFIRMED
                }
                ControlResult.Forbidden, ControlResult.Unauthorized -> CameraCommandState.DENIED
                is ControlResult.Unreachable -> CameraCommandState.UNCONFIRMED
            }
        }
    }

    private suspend fun updateConfirmedCamera(
        cameraId: NodeId,
        session: HouseholdSession,
        online: CameraHealth.Online,
    ): CameraCommandState {
        health.update { it + (cameraId to online) }
        if (online.camera?.paused != false || !session.household.secured || online.ticket != null) return CameraCommandState.CONFIRMED
        val ticket = (client.streamTicket(online.route, session.auth, cameraId) as? ControlResult.Success)?.value
        if (!sameSession(session)) return CameraCommandState.UNCONFIRMED
        if (ticket == null) return CameraCommandState.RECONNECTING
        health.update { it + (cameraId to online.copy(ticket = ticket)) }
        return CameraCommandState.CONFIRMED
    }

    private fun sameSession(session: HouseholdSession): Boolean =
        currentSession?.household == session.household && currentSession?.self?.id == session.self.id && currentSession?.role == session.role

    private fun tiles(session: HouseholdSession, health: Map<NodeId, CameraHealth>): List<CameraTile> =
        remoteCameras(session).map { camera ->
            val cameraHealth = health[camera.id] ?: CameraHealth.Checking
            // A paused camera answers but streams nothing: no player, no decoder.
            val route = (cameraHealth as? CameraHealth.Online)?.takeIf { it.camera?.paused == false }?.route
            CameraTile(
                id = camera.id,
                name = camera.name,
                health = cameraHealth,
                rtspUrl = route?.let { streamUrl(session, cameraHealth as CameraHealth.Online, it) },
            )
        }

    private suspend fun checkHealth(session: HouseholdSession, discovered: Map<NodeId, NodeEndpoint>) = coroutineScope {
        remoteCameras(session).forEach { camera ->
            launch {
                val revision = commands.revision(camera.id)
                val candidates = listOfNotNull(discovered[camera.id]) + camera.endpoints
                val checked = when (val reach = probe.probe(candidates, session.auth, camera.id)) {
                    is PeerReachability.Online -> CameraHealth.Online(reach.status, reach.route, ticketFor(session, camera, reach))
                    PeerReachability.Unauthorized -> CameraHealth.Unauthorized
                    PeerReachability.Offline -> CameraHealth.Offline
                }
                if (sameSession(session) && commands.acceptsPoll(camera.id, revision)) {
                    health.update { it + (camera.id to checked) }
                    if (checked is CameraHealth.Online && (checked.camera?.paused == true || !session.household.secured || checked.ticket != null)) {
                        commands.confirmReconnection(camera.id)
                    }
                }
            }
        }
    }

    /**
     * Asks the camera for this phone's RTSP ticket. The camera returns the same ticket while
     * it is valid, so the URL stays stable; after a camera restart it is a new one.
     */
    private suspend fun ticketFor(session: HouseholdSession, camera: Member, reach: PeerReachability.Online): StreamTicket? {
        val streaming = reach.status.camera?.paused == false
        if (!streaming || !session.household.secured || !session.can(Permission.VIEW_STREAMS)) return null
        return (client.streamTicket(reach.route, session.auth, camera.id) as? ControlResult.Success)?.value
    }

    /** Secured households use the per-viewer ticket; legacy ones still use the shared secret. */
    private fun streamUrl(session: HouseholdSession, online: CameraHealth.Online, route: NodeEndpoint): String? {
        val (user, password) = if (session.household.secured) {
            val ticket = online.ticket ?: return null
            ticket.user to ticket.password
        } else {
            RtspCredentials.USER to session.household.secret.value
        }
        return "rtsp://$user:$password@${route.urlHost}:${route.rtspPort}/"
    }

    private fun remoteCameras(session: HouseholdSession): List<Member> =
        session.directory.cameras.filter { it.id != session.self.id }.sortedBy { it.name.lowercase() }

    private companion object {
        const val REFRESH_MILLIS = 15_000L
    }
}
