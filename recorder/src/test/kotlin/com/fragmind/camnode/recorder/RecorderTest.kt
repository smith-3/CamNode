package com.fragmind.camnode.recorder

import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.HouseholdId
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class RecorderTest {
    @get:Rule val folder = TemporaryFolder()

    private val state = RecorderState(
        household = Household(HouseholdId("casa"), "Casa", HouseholdSecret.generate()),
        self = LocalNode(NodeId("pc"), "PC sala"),
        storageDir = "/tmp/x",
    )

    @Test
    fun announcesItselfAsARecorderThatReceivesAlerts() {
        val announced = state.announced(listOf(NodeEndpoint("192.168.1.2")), nowMillis = 10)

        val me = announced.directory[NodeId("pc")]!!
        assertEquals(NodeRole.RECORDER, me.role)
        assertTrue(me.receivesAlerts)
        assertSame(announced, announced.announced(listOf(NodeEndpoint("192.168.1.2")), nowMillis = 99))
    }

    @Test
    fun announcementsPreserveTheNameChosenByAnotherDevice() {
        val announced = state.announced(listOf(NodeEndpoint("192.168.1.2")), nowMillis = 10)
        val renamed = announced.copy(directory = announced.directory.upsert(
            announced.directory[state.self.id]!!.copy(name = "Grabador de casa", updatedAtMillis = 20),
        ))

        val moved = renamed.announced(listOf(NodeEndpoint("192.168.1.3")), nowMillis = 30)

        assertEquals("Grabador de casa", moved.directory[state.self.id]?.name)
    }

    @Test
    fun retentionDeletesOnlyOldRecordings() {
        val now = Instant.parse("2026-11-14T12:00:00Z")
        val old = folder.newFile("old.mp4").toPath()
        val recent = folder.newFile("recent.mp4").toPath()
        val log = folder.newFile("ffmpeg.log").toPath()
        Files.setLastModifiedTime(old, FileTime.from(now.minus(Duration.ofDays(8))))
        Files.setLastModifiedTime(log, FileTime.from(now.minus(Duration.ofDays(30))))
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofDays(1))))

        assertEquals(1, pruneRecordings(folder.root.toPath(), days = 7, now = now))
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(recent) && Files.exists(log))
    }

    @Test
    fun ffmpegErrorsNeverShowTheTicket() {
        assertEquals(
            "Error opening input file rtsp://<oculto>",
            maskUrls("Error opening input file rtsp://611b:secretTicket@192.168.77.163:8554/"),
        )
    }
}
