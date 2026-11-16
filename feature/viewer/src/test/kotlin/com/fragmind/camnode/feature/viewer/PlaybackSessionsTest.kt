package com.fragmind.camnode.feature.viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionsTest {
    private val connections = mutableListOf<Connection>()

    private fun TestScope.sessions() = PlaybackSessions(backgroundScope, { url ->
        Connection(url).also(connections::add)
    })

    @Test
    fun gridAndDetailShareOneTransportAndOnlyVisibleDetailPlaysSound() = runTest {
        val sessions = sessions()
        val grid = sessions.acquire("camera", muted = true)
        val detail = sessions.acquire("camera", muted = false)

        assertSame(grid.connection, detail.connection)
        assertEquals(1, connections.size)
        assertEquals(1, detail.connection.starts)
        assertTrue(detail.connection.soundEnabled)

        grid.close()
        assertTrue(detail.connection.running)
        val returningGrid = sessions.acquire("camera", muted = true)
        detail.close()
        assertFalse(returningGrid.connection.soundEnabled)
        assertEquals(0, returningGrid.connection.releases)
        sessions.clear()
    }

    @Test
    fun shortTabVisitsReuseTheConnectionAndLongVisitsReleaseIt() = runTest {
        val sessions = sessions()
        val grid = sessions.acquire("camera", muted = false)
        grid.close()
        assertFalse(grid.connection.soundEnabled)
        runCurrent()
        advanceTimeBy(4_999)

        val returning = sessions.acquire("camera", muted = true)
        assertSame(grid.connection, returning.connection)
        advanceTimeBy(10_000)
        assertTrue(returning.connection.running)
        assertEquals(1, returning.connection.starts)

        returning.close()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, returning.connection.releases)
        val reopened = sessions.acquire("camera", muted = true)
        assertNotSame(returning.connection, reopened.connection)
        sessions.clear()
    }

    @Test
    fun backgroundStopsTransportsImmediatelyAndResumesOnlyWhenForegroundReturns() = runTest {
        val sessions = sessions()
        val detail = sessions.acquire("camera", muted = false)
        sessions.setForeground(false)
        sessions.setForeground(false)

        assertFalse(detail.connection.running)
        assertFalse(detail.connection.soundEnabled)
        assertEquals(1, detail.connection.stops)
        val other = sessions.acquire("other-camera", muted = true)
        assertEquals(0, other.connection.starts)

        sessions.setForeground(true)
        assertTrue(detail.connection.running)
        assertTrue(detail.connection.soundEnabled)
        assertEquals(2, detail.connection.starts)
        assertTrue(other.connection.running)
        assertFalse(other.connection.soundEnabled)
        sessions.clear()
    }

    @Test
    fun clearingAHouseholdReleasesEverythingAndOldSurfacesCannotAffectNewConnections() = runTest {
        val sessions = sessions()
        val old = sessions.acquire("camera", muted = false)
        old.close()
        runCurrent()
        sessions.clear()
        val current = sessions.acquire("camera", muted = true)

        old.setMuted(false)
        old.close()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, old.connection.releases)
        assertFalse(current.connection.soundEnabled)
        assertTrue(current.connection.running)
        assertEquals(0, current.connection.releases)
        sessions.clear()
        current.close()
        assertEquals(1, current.connection.releases)
    }

    @Test
    fun changingRoutesOpensANewTransportAndRetiresOnlyTheOldRoute() = runTest {
        val sessions = sessions()
        val lan = sessions.acquire("wifi-camera", muted = true)
        val tailnet = sessions.acquire("tailnet-camera", muted = true)
        assertNotSame(lan.connection, tailnet.connection)
        lan.close()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, lan.connection.releases)
        assertTrue(tailnet.connection.running)
        assertEquals(0, tailnet.connection.releases)
        sessions.clear()
    }

    private class Connection(val url: String) : PlaybackConnection {
        var starts = 0
        var stops = 0
        var releases = 0
        var running = false
        var soundEnabled = false

        override fun start() {
            check(releases == 0)
            starts++
            running = true
        }

        override fun stop() {
            check(releases == 0)
            stops++
            running = false
        }

        override fun setAudible(audible: Boolean) {
            check(releases == 0)
            this.soundEnabled = audible
        }

        override fun release() {
            check(releases == 0)
            releases++
            running = false
        }
    }
}
