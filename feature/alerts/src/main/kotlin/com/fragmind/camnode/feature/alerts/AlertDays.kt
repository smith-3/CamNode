package com.fragmind.camnode.feature.alerts

import com.fragmind.camnode.core.model.CameraEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The heading an alert is listed under. */
internal sealed interface AlertDay {
    data object Today : AlertDay
    data object Yesterday : AlertDay
    data class On(val date: LocalDate) : AlertDay
}

/** Alerts grouped under their day, newest day first; each group keeps its alerts newest first. */
internal fun groupByDay(alerts: List<CameraEvent>, nowMillis: Long, zone: ZoneId): List<Pair<AlertDay, List<CameraEvent>>> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return alerts
        .sortedByDescending { it.atMillis }
        .groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
        .map { (date, events) ->
            val day = when (date) {
                today -> AlertDay.Today
                today.minusDays(1) -> AlertDay.Yesterday
                else -> AlertDay.On(date)
            }
            day to events
        }
}
