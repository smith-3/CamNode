package com.fragmind.camnode.recorder

import com.fragmind.camnode.core.control.Caller
import com.fragmind.camnode.core.control.ControlClient
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.StreamTicket
import com.fragmind.camnode.core.control.ControlHandler
import com.fragmind.camnode.core.control.ControlResult
import com.fragmind.camnode.core.control.ControlServer
import com.fragmind.camnode.core.model.CamNodePorts
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.NodeStatus
import com.fragmind.camnode.core.model.RtspCredentials
import com.fragmind.camnode.core.model.byPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The recorder as a household node: answers the control API, receives alerts, gossips
 * the directory and keeps one recording per reachable camera.
 */
class RecorderNode(private val store: RecorderStore) {
    private val client = ControlClient(
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
    )
    private val recorders = mutableMapOf<NodeId, CameraRecorder>()
    /** Latest stored state, read by role checks without touching the disk on every request. */
    @Volatile private var latest: RecorderState = runBlocking { store.read() }
    private val eventsJson = Json

    fun run() = runBlocking {
        val initial = store.read()
        val root = Path.of(initial.storageDir)
        Files.createDirectories(root)
        val server = ControlServer(CamNodePorts.CONTROL, handler(initial, root))
        server.start()
        Runtime.getRuntime().addShutdownHook(Thread {
            recorders.values.forEach(CameraRecorder::stop)
            server.stop()
        })
        log("Grabador \"${initial.self.name}\" en el hogar \"${initial.household.name}\". Guardando en $root")
        var lastPrune = Instant.EPOCH
        while (true) {
            val state = store.update { it.announced(HostAddresses.endpoints(), System.currentTimeMillis()) }
            sync(state)
            reconcileRecordings(store.read(), root)
            if (Instant.now().isAfter(lastPrune.plusSeconds(3600))) {
                val removed = pruneRecordings(root, state.retentionDays)
                if (removed > 0) log("Retención: borrados $removed archivos de más de ${state.retentionDays} días")
                lastPrune = Instant.now()
            }
            delay(LOOP_MILLIS)
        }
    }

    private fun handler(initial: RecorderState, root: Path) = object : ControlHandler {
        override val secret = initial.household.secret
        override val selfId = initial.self.id
        override fun roleOf(caller: Caller): AccessRole {
            val nodeId = caller.nodeId ?: return if (initial.household.secured) AccessRole.CAMERA else AccessRole.ADMIN
            return latest.directory.roles.roleOf(nodeId, caller.publicKey, initial.household.ownerKey)
        }
        override fun status() = NodeStatus(nodeId = selfId, role = NodeRole.RECORDER, batteryPercent = null, charging = true)
        override suspend fun exchangeDirectory(remote: Directory): Directory =
            store.update { it.copy(directory = it.directory.merge(remote, it.household.ownerKey), bootstrapSeeds = emptyList()) }
                .also { latest = it }.directory
        override suspend fun applyCameraCommand(command: CameraCommand): NodeStatus? = null
        override suspend fun issueStreamTicket(caller: Caller): StreamTicket? = null
        override suspend fun receiveEvent(event: CameraEvent): Boolean {
            val line = eventsJson.encodeToString(CameraEvent.serializer(), event) + "\n"
            Files.writeString(root.resolve("events.jsonl"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            log("ALERTA: movieron la cámara ${event.cameraName}")
            notifyDesktop("CamNode: movieron la cámara ${event.cameraName}")
            return true
        }
    }

    private suspend fun sync(state: RecorderState) = coroutineScope {
        val targets = (state.directory.cameras.flatMap(Member::endpoints) + state.bootstrapSeeds).distinct()
        targets.map { endpoint -> async { client.exchangeDirectory(endpoint, state.auth, state.directory) } }
            .awaitAll()
            .filterIsInstance<ControlResult.Success<Directory>>()
            .forEach { result ->
                latest = store.update { it.copy(directory = it.directory.merge(result.value, it.household.ownerKey), bootstrapSeeds = emptyList()) }
            }
    }

    private suspend fun reconcileRecordings(state: RecorderState, root: Path) = coroutineScope {
        val cameras = state.directory.cameras
        val routes = cameras.map { camera -> async { camera to streamingRoute(camera, state) } }.awaitAll()
        if (state.household.secured && !state.role.can(Permission.VIEW_STREAMS) && cameras.isNotEmpty()) {
            log("Sin permiso para grabar: en la app, Hogar → ${state.self.name} → rol Grabador")
        }
        for ((camera, url) in routes) {
            val recorder = recorders.getOrPut(camera.id) { CameraRecorder(root.resolve(folderName(camera)), SEGMENT_SECONDS) }
            if (url == null) {
                if (recorder.recording) log("${camera.name}: sin señal, grabación en pausa")
                recorder.stop()
            } else {
                val wasRecording = recorder.recording
                recorder.ensure(url)
                if (!wasRecording) log("${camera.name}: grabando")
            }
        }
        (recorders.keys - cameras.map(Member::id).toSet()).forEach { removed -> recorders.remove(removed)?.stop() }
    }

    /**
     * The RTSP URL of the first address (Wi-Fi before Tailscale) where the camera answers and
     * streams. Secured households need this recorder's own ticket; legacy ones the shared secret.
     */
    private suspend fun streamingRoute(camera: Member, state: RecorderState): String? {
        val route = camera.endpoints.byPreference().firstOrNull { endpoint ->
            val status = (client.status(endpoint, state.auth, camera.id) as? ControlResult.Success)?.value
            status?.camera?.paused == false
        } ?: return null
        val (user, password) = if (state.household.secured) {
            val ticket = (client.streamTicket(route, state.auth, camera.id) as? ControlResult.Success)?.value ?: return null
            ticket.user to ticket.password
        } else {
            RtspCredentials.USER to state.household.secret.value
        }
        return "rtsp://$user:$password@${route.urlHost}:${route.rtspPort}/"
    }

    private fun folderName(camera: Member): String =
        camera.name.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "camara" } + "_" + camera.id.value.take(6)

    private fun notifyDesktop(message: String) {
        runCatching { ProcessBuilder("notify-send", "-u", "critical", message).start() }
    }

    private companion object {
        const val LOOP_MILLIS = 30_000L
        const val SEGMENT_SECONDS = 300
    }
}

internal fun log(message: String) = println("[${Instant.now()}] $message")
