package com.fragmind.camnode.feature.node

import android.content.Intent
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.CameraSettingsRepository
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.device.ContinuousOperation
import com.fragmind.camnode.core.data.device.DeviceClass
import com.fragmind.camnode.core.data.device.TailscaleSetup
import com.fragmind.camnode.core.data.device.VendorStartupManager
import com.fragmind.camnode.core.model.CameraCommand
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.ImageMode
import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.NetworkKind
import com.fragmind.camnode.core.model.VideoOrientation
import com.fragmind.camnode.core.model.RtspCredentials
import com.fragmind.camnode.core.network.LocalAddressProvider
import com.fragmind.camnode.core.network.LocalAddresses
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerLink(val network: NetworkKind, val url: String)

data class ContinuousState(val batteryUnrestricted: Boolean = false, val vendor: VendorStartupManager? = null)

data class NodeUiState(
    val deviceName: String = "",
    val stream: StreamState = StreamState.Idle,
    val settings: CameraSettings = CameraSettings(),
    val addresses: LocalAddresses = LocalAddresses(null, null),
    /** Credentialed URLs for VLC or any other RTSP player in the household. */
    val playerLinks: List<PlayerLink> = emptyList(),
    val continuous: ContinuousState = ContinuousState(),
)

@HiltViewModel
class NodeViewModel @Inject constructor(
    private val controller: CameraStreamController,
    private val cameraSettings: CameraSettingsRepository,
    private val localAddresses: LocalAddressProvider,
    private val continuousOperation: ContinuousOperation,
    val tailscale: TailscaleSetup,
    deviceClass: DeviceClass,
    households: HouseholdRepository,
) : ViewModel() {
    private val addresses = MutableStateFlow(localAddresses.current())

    /** The preview costs about a third of the camera's CPU; old phones start with it hidden. */
    val previewByDefault: Boolean = !deviceClass.isLowEnd
    private val continuous = MutableStateFlow(readContinuous())

    val uiState: StateFlow<NodeUiState> = combine(
        controller.state, cameraSettings.settings, addresses, continuous, households.session,
    ) { stream, settings, local, continuous, session ->
        val links = session?.let { active ->
            local.endpoints.map { endpoint ->
                PlayerLink(
                    endpoint.network,
                    "rtsp://${RtspCredentials.USER}:${active.household.secret.value}@${endpoint.urlHost}:${endpoint.rtspPort}/",
                )
            }
        }.orEmpty()
        NodeUiState(session?.self?.name.orEmpty(), stream, settings, local, links, continuous)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeUiState())

    /** Addresses and battery rules change outside the app, so they are re-read on every resume. */
    fun refresh() {
        addresses.value = localAddresses.current()
        continuous.value = readContinuous()
    }

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { cameraSettings.update(transform) }
    }

    fun selectFacing(facing: CameraFacing) {
        controller.apply(CameraCommand(facing = facing))
        updateSettings { it.copy(facing = facing) }
    }

    /** Turns camera and encoder off to cool down; the household can still see and resume this node. */
    fun pause() {
        controller.pause()
    }

    fun resume() {
        controller.resume()
    }

    /** Applies instantly while live: only GL filters and sensor exposure change, the stream keeps going. */
    fun selectImageMode(mode: ImageMode) {
        controller.apply(CameraCommand(imageMode = mode))
        updateSettings { it.copy(imageMode = mode) }
    }

    /** While live this restarts the encoder for a moment; viewers reconnect on their own. */
    fun selectOrientation(orientation: VideoOrientation) {
        controller.apply(CameraCommand(orientation = orientation))
        updateSettings { it.copy(orientation = orientation) }
    }

    fun batteryExemptionIntent(): Intent = continuousOperation.batteryExemptionIntent()

    fun attachPreview(view: SurfaceView) = controller.attachPreview(view)

    fun detachPreview() = controller.detachPreview()

    private fun readContinuous() = ContinuousState(
        batteryUnrestricted = continuousOperation.isBatteryUnrestricted(),
        vendor = continuousOperation.vendorStartupManager(),
    )
}
