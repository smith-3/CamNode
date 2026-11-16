package com.fragmind.camnode.feature.alerts

import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AlertDaysTest {
    private val zone = ZoneId.of("America/La_Paz")
    private val now = at(2026, 11, 16, 15, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun alert(name: String, atMillis: Long) = CameraEvent.new(NodeId(name), name, CameraEventType.MOVED, atMillis)

    @Test
    fun groupsUnderTodayYesterdayAndOlderDatesNewestFirst() {
        val morning = alert("Cocina", at(2026, 11, 16, 9, 41))
        val afternoon = alert("Mama", at(2026, 11, 16, 15, 2))
        val lastNight = alert("Mama", at(2026, 11, 15, 22, 10))
        val older = alert("Sala", at(2026, 11, 12, 8, 0))

        val groups = groupByDay(listOf(older, morning, lastNight, afternoon), now, zone)

        assertEquals(
            listOf(AlertDay.Today, AlertDay.Yesterday, AlertDay.On(LocalDate.of(2026, 11, 12))),
            groups.map { it.first },
        )
        assertEquals(listOf(afternoon, morning), groups[0].second)
    }

    @Test
    fun theDayFollowsTheLocalTimeZoneNotUtc() {
        // 23:30 in La Paz is already the next day in UTC; it still belongs to yesterday here.
        val lateNight = alert("Mama", at(2026, 11, 15, 23, 30))
        assertEquals(AlertDay.Yesterday, groupByDay(listOf(lateNight), now, zone).single().first)
    }

    @Test
    fun noAlertsMeansNoGroups() {
        assertEquals(emptyList<Pair<AlertDay, List<CameraEvent>>>(), groupByDay(emptyList(), now, zone))
    }
}
