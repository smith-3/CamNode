package com.fragmind.camnode.feature.node

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import com.fragmind.camnode.core.model.ImageMode
import com.pedro.encoder.input.gl.render.filters.ContrastFilterRender
import com.pedro.encoder.input.gl.render.filters.GammaFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.view.GlStreamInterface

/** What an [ImageMode] asks of the GL pipeline and the sensor. Pure, so it is testable without a camera. */
internal data class ImagePlan(
    val greyscale: Boolean,
    /** Below 1 brightens the shadows. */
    val gamma: Float = 1f,
    val contrast: Float = 1f,
    /** Maximum exposure compensation and the lowest frame-rate floor, so each frame gathers more light. */
    val lowLight: Boolean = false,
) {
    /** The part drawn by GL filters; the rest is sensor settings. */
    val glFilters: GlFilters get() = GlFilters(greyscale, gamma, contrast)

    companion object {
        fun forMode(mode: ImageMode): ImagePlan = when (mode) {
            ImageMode.COLOR -> ImagePlan(greyscale = false)
            ImageMode.MONOCHROME -> ImagePlan(greyscale = true)
            ImageMode.NIGHT -> ImagePlan(greyscale = true, gamma = 0.7f, contrast = 1.25f, lowLight = true)
        }
    }
}

internal data class GlFilters(val greyscale: Boolean, val gamma: Float, val contrast: Float)

/**
 * The sensor range whose floor is lowest without exceeding the stream's frame rate, so
 * auto-exposure may lengthen each frame while the encoder never gets more frames than set.
 */
internal fun lowLightFpsRange(available: List<IntRange>, streamFps: Int): IntRange? =
    available.filter { it.last <= streamFps }.minWithOrNull(compareBy<IntRange>({ it.first }, { -it.last }))

/**
 * The sensor range with the lowest ceiling: while nobody watches, the sensor produces as
 * few frames as it can (15 fps on many phones) and the GL limiter drops the rest.
 */
internal fun idleFpsRange(available: List<IntRange>): IntRange? =
    available.minWithOrNull(compareBy<IntRange>({ it.last }, { it.first }))

/**
 * Applies an [ImagePlan] to a running camera. The capture builder is rebuilt whenever the
 * camera reopens (lens switch, restart), so callers re-apply after each open.
 */
internal class ImageTuner(private val context: Context) {
    private var defaultFpsRange: Range<Int>? = null

    /** The filters set on [filteredGl]; a restarted stream brings a new GL pipeline without them. */
    private var filteredGl: GlStreamInterface? = null
    private var appliedFilters: GlFilters? = null

    /** Call when the camera reopened: the new capture builder has fresh defaults to restore later. */
    fun onCameraReopened() {
        defaultFpsRange = null
    }

    fun apply(source: Camera2Source, gl: GlStreamInterface, plan: ImagePlan, streamFps: Int, idle: Boolean) {
        applyFilters(gl, plan.glFilters)

        val characteristics = characteristics(source)
        source.setCustomRequest { builder ->
            if (defaultFpsRange == null) defaultFpsRange = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
            val ranges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower..it.upper }
            val compensation = if (plan.lowLight) characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0 else 0
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            val range = when {
                idle -> idleFpsRange(ranges)
                plan.lowLight -> lowLightFpsRange(ranges, streamFps)
                else -> null
            }
            val target = range?.let { Range(it.first, it.last) } ?: defaultFpsRange
            target?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
    }

    /**
     * GL applies a clear and each add on its own thread, so a few frames in between go out
     * without filters: in black and white a viewer connecting flashed color on every screen.
     * Leaving idle only changes the frame rate, so unchanged filters are left alone.
     */
    private fun applyFilters(gl: GlStreamInterface, filters: GlFilters) {
        if (gl === filteredGl && filters == appliedFilters) return
        gl.clearFilters()
        if (filters.greyscale) gl.addFilter(GreyScaleFilterRender())
        if (filters.gamma != 1f) gl.addFilter(GammaFilterRender().apply { gamma = filters.gamma })
        if (filters.contrast != 1f) gl.addFilter(ContrastFilterRender().apply { contrast = filters.contrast })
        filteredGl = gl
        appliedFilters = filters
    }

    fun torchAvailable(source: Camera2Source): Boolean =
        characteristics(source)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun characteristics(source: Camera2Source): CameraCharacteristics? = runCatching {
        context.getSystemService(CameraManager::class.java).getCameraCharacteristics(source.getCurrentCameraId())
    }.getOrNull()
}
