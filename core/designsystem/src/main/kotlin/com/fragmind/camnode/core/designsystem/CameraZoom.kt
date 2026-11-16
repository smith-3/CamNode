package com.fragmind.camnode.core.designsystem

import java.util.Locale
import kotlin.math.floor

/**
 * Sensor zoom steps shown like a phone camera: the widest the lens allows, 1x, then whole
 * steps up to [MAX_ZOOM_STOP]. A lens that can't reach 2x offers its maximum instead.
 * Sensor zoom is shared by every viewer and the recorder, so it moves in deliberate steps
 * while pinching only zooms the local screen.
 */
fun zoomStops(min: Float, max: Float): List<Float> {
    if (max <= min) return listOf(min)
    val whole = (2..floor(minOf(max, MAX_ZOOM_STOP)).toInt()).map(Int::toFloat)
    val stops = buildList {
        add(min)
        if (1f in min..max) add(1f)
        addAll(whole)
        if (whole.isEmpty()) add(max)
    }
    return stops.distinct().sorted()
}

/** The step a zoom belongs to: the largest one not above it, so 2.5x lights up the 2x step. */
fun activeZoomStop(zoom: Float, stops: List<Float>): Float =
    stops.lastOrNull { it <= zoom + ZOOM_TOLERANCE } ?: stops.first()

/** "1x", "2x", or "1.5x" for a zoom between whole steps. */
fun zoomLabel(zoom: Float): String {
    val rounded = Math.round(zoom * 10) / 10f
    return if (rounded == floor(rounded)) "${rounded.toInt()}x" else String.format(Locale.ROOT, "%.1fx", rounded)
}

private const val MAX_ZOOM_STOP = 4f

/** Cameras report zoom as a float; 1.98 is still the 2x step. */
private const val ZOOM_TOLERANCE = 0.05f
