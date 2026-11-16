package com.fragmind.camnode.feature.node

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.MainThread
import com.fragmind.camnode.core.model.CamNodePorts
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.CameraStatus
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.StreamTicket
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.rtspserver.RtspServerStream
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.ServerClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** Why the camera could not stream; the screen words it, [StreamState.Failed.detail] adds specifics. */
enum class StreamProblem { SERVER, UNSUPPORTED_SIZE, ENCODER }

sealed interface StreamState {
    data object Idle : StreamState
    /** [rotationDegrees] is the encoder rotation actually applied (AUTO resolved). */
    data class Live(
        val settings: CameraSettings,
        val viewers: Int,
        val rotationDegrees: Int,
        val zoom: Float = 1f,
        val torch: Boolean = false,
        /** Nobody is watching: few frames are captured and encoded until a viewer connects. */
        val idle: Boolean = true,
    ) : StreamState
    data class Failed(val problem: StreamProblem, val detail: String = "") : StreamState
    /** Camera, encoder and RTSP server are released; the service stays up so the node can resume remotely. */
    data class Paused(val settings: CameraSettings) : StreamState
}

/**
 * Owns the RTSP server and camera for the whole process, so the service can stream
 * with the screen off while the UI attaches and detaches its preview at will.
 * RootEncoder is not thread-safe: every call happens on the main thread.
 */
@Singleton
class CameraStreamController @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private var stream: RtspServerStream? = null
    private var access: StreamAccess? = null
    private val tickets = TicketBook(clock = System::currentTimeMillis)
    /** Weak: the controller outlives screens and must not keep an Activity alive. */
    private var previewView = WeakReference<SurfaceView>(null)
    private val imageTuner = ImageTuner(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The capture request is rebuilt on every camera open, so the image mode and torch are re-applied. */
    private val cameraCallbacks = object : CameraCallbacks {
        override fun onCameraOpened() {
            mainHandler.post {
                imageTuner.onCameraReopened()
                reapplyImage()
            }
        }
        override fun onCameraChanged(facing: CameraHelper.Facing) = Unit
        override fun onCameraError(error: String) = Unit
        override fun onCameraDisconnected() = Unit
    }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() = Unit
        override fun onConnectionFailed(reason: String) {
            fail(StreamProblem.SERVER, reason)
        }
        override fun onDisconnect() = Unit
        override fun onAuthError() = Unit
        override fun onAuthSuccess() = Unit
    }

    /** RTSP callbacks arrive on network threads; camera and GL state are only touched on the main thread. */
    private val clientListener = object : ClientListener {
        override fun onClientConnected(client: ServerClient) {
            mainHandler.post {
                refreshViewers()
                keyframeWhenPlaying(client, attemptsLeft = PLAY_WAIT_MILLIS / PLAY_POLL_MILLIS)
            }
        }
        override fun onClientDisconnected(client: ServerClient) {
            mainHandler.post { refreshViewers() }
        }
        override fun onClientNewBitrate(bitrate: Long, client: ServerClient) = Unit
    }

    fun cameraStatus(): CameraStatus? = (state.value as? StreamState.Paused)?.let {
        CameraStatus(viewers = 0, profile = it.settings.profile, facing = it.settings.facing, rotationDegrees = 0, imageMode = it.settings.imageMode, paused = true)
    } ?: (state.value as? StreamState.Live)?.let {
        val range = zoomRange()
        val source = stream?.videoSource as? Camera2Source
        CameraStatus(
            viewers = it.viewers,
            profile = it.settings.profile,
            facing = it.settings.facing,
            rotationDegrees = it.rotationDegrees,
            zoom = it.zoom,
            zoomMin = range.start,
            zoomMax = range.endInclusive,
            imageMode = it.settings.imageMode,
            torch = it.torch,
            torchAvailable = source?.let(imageTuner::torchAvailable) == true,
        )
    }

    /** RTSP credentials for a viewer whose role was already checked by the control API. */
    fun issueTicket(viewer: NodeId): StreamTicket? = if (stream != null) tickets.issue(viewer) else null

    @MainThread
    fun start(settings: CameraSettings, access: StreamAccess): Boolean {
        if (stream != null) return true
        this.access = access
        val video = Camera2Source(context).apply {
            if (settings.facing == CameraFacing.FRONT) switchCamera()
            setCameraCallback(cameraCallbacks)
        }
        val audio = if (settings.audioEnabled) MicrophoneSource() else NoAudioSource()
        val candidate = RtspServerStream(context, CamNodePorts.RTSP, connectChecker, video, audio)
        candidate.getStreamClient().apply {
            setAuthenticator { user, password -> access.accepts(tickets, user, password) }
            setOnlyVideo(!settings.audioEnabled)
            setClientListener(clientListener)
        }
        val rotation = settings.orientation.rotationDegrees ?: autoRotation()
        val prepared = try {
            val profile = settings.profile
            candidate.prepareVideo(profile.width, profile.height, profile.bitrateBps, profile.fps, KEYFRAME_INTERVAL_SECONDS, rotation) &&
                candidate.prepareAudio(AUDIO_SAMPLE_RATE, false, AUDIO_BITRATE)
        } catch (e: IllegalArgumentException) {
            candidate.release()
            return fail(StreamProblem.UNSUPPORTED_SIZE, "${settings.profile.width}x${settings.profile.height}")
        }
        if (!prepared) {
            candidate.release()
            return fail(StreamProblem.ENCODER)
        }
        candidate.startStream()
        stream = candidate
        _state.value = StreamState.Live(settings, viewers = 0, rotationDegrees = rotation)
        previewView.get()?.let(::attachPreview)
        return true
    }

    @MainThread
    fun stop() {
        release()
        tickets.clear()
        _state.update { if (it is StreamState.Failed) it else StreamState.Idle }
    }

    /** Applies a remote or local change; orientation needs a quick encoder restart. */
    @MainThread
    fun apply(command: CameraCommand): CameraStatus? {
        when (command.paused) {
            true -> return pause()
            false -> resume()
            null -> Unit
        }
        val live = state.value as? StreamState.Live ?: return cameraStatus()
        var settings = live.settings
        command.facing?.let { facing ->
            switchFacing(facing)
            settings = settings.copy(facing = facing)
        }
        command.zoom?.let(::setZoom)
        command.imageMode?.let { mode ->
            settings = settings.copy(imageMode = mode)
            _state.update { if (it is StreamState.Live) it.copy(settings = it.settings.copy(imageMode = mode)) else it }
            reapplyImage()
        }
        command.torch?.let(::setTorch)
        command.orientation?.takeIf { it != settings.orientation }?.let { orientation ->
            val currentAccess = access ?: return null
            release()
            start(settings.copy(orientation = orientation), currentAccess)
        }
        return cameraStatus()
    }

    /** Releases camera, encoder and RTSP server: what heats the phone. Idempotent. */
    @MainThread
    fun pause(): CameraStatus? {
        val live = state.value as? StreamState.Live ?: return cameraStatus()
        release()
        _state.value = StreamState.Paused(live.settings)
        return cameraStatus()
    }

    /** Reopens the camera with the settings it had; allowed because the camera service never stopped. */
    @MainThread
    fun resume(): CameraStatus? {
        val paused = state.value as? StreamState.Paused ?: return cameraStatus()
        val currentAccess = access ?: return cameraStatus()
        start(paused.settings, currentAccess)
        return cameraStatus()
    }

    @MainThread
    fun attachPreview(view: SurfaceView) {
        previewView = WeakReference(view)
        val current = stream ?: return
        if (!current.isOnPreview) current.startPreview(view, true)
    }

    @MainThread
    fun detachPreview() {
        previewView = WeakReference(null)
        val current = stream ?: return
        if (current.isOnPreview) current.stopPreview(true)
    }

    /**
     * The socket connects before the viewer finishes the RTSP handshake, so a keyframe sent
     * at connect time is lost and the viewer waits for the next natural one (up to 2 s).
     * Sending it the moment the client may receive (after PLAY) shows the first image sooner.
     */
    private fun keyframeWhenPlaying(client: ServerClient, attemptsLeft: Long) {
        val current = stream ?: return
        when {
            client.canSend -> {
                current.requestKeyframe()
                // Leaving idle, decoders kept waiting for the next natural keyframe (~3 s on a Samsung A02);
                // a second one once the full frame rate is back brought the first image to ~1.4 s.
                mainHandler.postDelayed({ stream?.requestKeyframe() }, SECOND_KEYFRAME_DELAY_MILLIS)
            }
            attemptsLeft > 0 && client.isAlive() -> mainHandler.postDelayed({ keyframeWhenPlaying(client, attemptsLeft - 1) }, PLAY_POLL_MILLIS)
        }
    }

    /** Re-applies the current image mode and torch to whatever lens is open. */
    private fun reapplyImage() {
        val live = state.value as? StreamState.Live ?: return
        val current = stream ?: return
        val source = current.videoSource as? Camera2Source ?: return
        val gl = current.getGlInterface()
        gl.forceFpsLimit(if (live.idle) IDLE_FPS else live.settings.profile.fps)
        imageTuner.apply(source, gl, ImagePlan.forMode(live.settings.imageMode), live.settings.profile.fps, live.idle)
        if (live.torch) setTorch(true)
    }

    /** The torch only exists on lenses with a flash; asking for it elsewhere turns it off. */
    private fun setTorch(on: Boolean) {
        val source = stream?.videoSource as? Camera2Source ?: return
        val enabled = on && imageTuner.torchAvailable(source)
        runCatching { if (enabled) source.enableLantern() else source.disableLantern() }
        _state.update { if (it is StreamState.Live) it.copy(torch = enabled) else it }
    }

    /** Each lens reopens at 1x, so a lens switch resets the zoom. */
    private fun switchFacing(facing: CameraFacing) {
        val source = stream?.videoSource as? Camera2Source ?: return
        val wanted = if (facing == CameraFacing.FRONT) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        if (source.getCameraFacing() != wanted) source.switchCamera()
        _state.update { if (it is StreamState.Live) it.copy(settings = it.settings.copy(facing = facing), zoom = 1f) else it }
    }

    /** Sensor zoom: crops before encoding, so viewers and recordings get real detail, not upscaled pixels. */
    private fun setZoom(level: Float) {
        val source = stream?.videoSource as? Camera2Source ?: return
        val clamped = level.coerceIn(zoomRange())
        source.setZoom(clamped)
        _state.update { if (it is StreamState.Live) it.copy(zoom = clamped) else it }
    }

    private fun zoomRange(): ClosedFloatingPointRange<Float> {
        val range = runCatching { (stream?.videoSource as? Camera2Source)?.getZoomRange() }.getOrNull()
        val min = range?.lower ?: 1f
        val max = range?.upper ?: 1f
        return min..maxOf(min, max)
    }

    private fun release() {
        val current = stream ?: return
        stream = null
        if (current.isOnPreview) current.stopPreview(true)
        if (current.isStreaming) current.stopStream()
        current.release()
    }

    /** Entering or leaving idle re-tunes the sensor and the GL frame limiter. */
    private fun refreshViewers() {
        val viewers = stream?.getStreamClient()?.getNumClients() ?: 0
        val before = state.value as? StreamState.Live
        _state.update { if (it is StreamState.Live) it.copy(viewers = viewers, idle = viewers == 0) else it }
        if (before != null && before.idle != (viewers == 0)) reapplyImage()
    }

    private fun fail(problem: StreamProblem, detail: String = ""): Boolean {
        _state.value = StreamState.Failed(problem, detail)
        return false
    }

    /**
     * RootEncoder's convention: 90 is portrait, 0 landscape. A phone's natural
     * orientation (ROTATION_0) is portrait, hence the offset.
     */
    private fun autoRotation(): Int {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    private companion object {
        const val KEYFRAME_INTERVAL_SECONDS = 2
        /** Enough to keep the pipeline warm and the first frame fast when someone connects. */
        const val IDLE_FPS = 2
        const val PLAY_POLL_MILLIS = 50L
        const val PLAY_WAIT_MILLIS = 3_000L
        const val SECOND_KEYFRAME_DELAY_MILLIS = 300L
        const val AUDIO_SAMPLE_RATE = 32_000
        const val AUDIO_BITRATE = 64_000
    }
}
