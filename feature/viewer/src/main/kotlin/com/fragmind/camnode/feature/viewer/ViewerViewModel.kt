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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val health = MutableStateFlow<Map<NodeId, CameraHealth>>(emptyMap())
    internal val playback = PlaybackSessions(viewModelScope, { url -> RtspConnection(context, url, viewModelScope) })

    init {
        viewModelScope.launch {
            households.session.map { it?.household }.distinctUntilChanged().collect {
                health.value = emptyMap()
                playback.clear()
            }
        }
    }

    /** This phone's role without the discovery work [uiState] starts; null until the store loads. */
    val access: StateFlow<AccessRole?> = households.session.map { it?.role }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setForeground(active: Boolean) = playback.setForeground(active)

    override fun onCleared() {
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
                    households.currentSession()?.let { session ->
                        val checked = checkHealth(session, found)
                        if (households.currentSession()?.household == session.household) health.value = checked
                    }
                    delay(REFRESH_MILLIS)
                }
            }
        }
        combine(
            households.session, health, cameraSettings.liveGrid, cameraSettings.cameraLayout, cameraSettings.displayRotations,
        ) { session, health, liveGrid, layout, rotations ->
            ViewerUiState(
                loading = false,
                householdName = session?.household?.name.orEmpty(),
                cameras = session?.let { tiles(it, health) }.orEmpty().map { it.copy(displayRotation = rotations[it.id] ?: 0) },
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

    private fun command(cameraId: NodeId, build: (CameraStatus) -> CameraCommand) {
        val online = health.value[cameraId] as? CameraHealth.Online ?: return
        val camera = online.camera ?: return
        viewModelScope.launch {
            val session = households.currentSession() ?: return@launch
            val result = client.applyCameraCommand(online.route, session.auth, cameraId, build(camera))
            if (result is ControlResult.Success) {
                health.update { it + (cameraId to online.copy(status = result.value)) }
            }
        }
    }

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

    private suspend fun checkHealth(session: HouseholdSession, discovered: Map<NodeId, NodeEndpoint>): Map<NodeId, CameraHealth> =
        coroutineScope {
            remoteCameras(session).map { camera ->
                async {
                    val candidates = listOfNotNull(discovered[camera.id]) + camera.endpoints
                    camera.id to when (val reach = probe.probe(candidates, session.auth, camera.id)) {
                        is PeerReachability.Online -> CameraHealth.Online(reach.status, reach.route, ticketFor(session, camera, reach))
                        PeerReachability.Unauthorized -> CameraHealth.Unauthorized
                        PeerReachability.Offline -> CameraHealth.Offline
                    }
                }
            }.awaitAll().toMap()
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
