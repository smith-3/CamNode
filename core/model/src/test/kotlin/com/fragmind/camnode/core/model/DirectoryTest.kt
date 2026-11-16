package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryTest {
    private val kitchen = NodeId("kitchen")
    private val garage = NodeId("garage")

    private fun camera(id: NodeId, name: String, at: Long, host: String = "100.64.0.1") =
        Member(id, name, NodeRole.CAMERA, listOf(NodeEndpoint(host)), updatedAtMillis = at)

    @Test
    fun newerRecordWinsRegardlessOfMergeOrder() {
        val old = Directory(listOf(camera(kitchen, "Cocina", at = 1)))
        val renamed = Directory(listOf(camera(kitchen, "Cocina nueva", at = 2)))

        assertEquals("Cocina nueva", old.merge(renamed, ownerKey = null)[kitchen]?.name)
        assertEquals("Cocina nueva", renamed.merge(old, ownerKey = null)[kitchen]?.name)
    }

    @Test
    fun mergeIsCommutativeAssociativeAndIdempotent() {
        val a = Directory(listOf(camera(kitchen, "A", at = 5), camera(garage, "G1", at = 1)))
        val b = Directory(listOf(camera(kitchen, "B", at = 5), camera(garage, "G2", at = 3)))
        val c = Directory(listOf(camera(garage, "G3", at = 3, host = "100.64.0.9")))

        assertEquals(a.merge(b, null), b.merge(a, null))
        assertEquals(a.merge(b, null).merge(c, null), a.merge(b.merge(c, null), null))
        assertEquals(a.merge(b, null), a.merge(b, null).merge(b, null))
    }

    @Test
    fun tombstoneIsNotResurrectedByAStalePeer() {
        val live = camera(garage, "Garaje", at = 10)
        val removed = live.copy(removed = true, updatedAtMillis = 11)

        val afterSync = Directory(listOf(removed)).merge(Directory(listOf(live)), ownerKey = null)

        assertTrue(afterSync[garage]!!.removed)
        assertTrue(afterSync.cameras.isEmpty())
    }

    @Test
    fun camerasIncludeUnreachableCamerasButNotViewers() {
        val viewer = Member(NodeId("phone"), "Mi teléfono", NodeRole.VIEWER, updatedAtMillis = 1)
        val dark = Member(NodeId("dark"), "Sin red", NodeRole.CAMERA, updatedAtMillis = 1)
        val directory = Directory(listOf(viewer, dark, camera(kitchen, "Cocina", at = 1)))

        assertEquals(listOf(NodeId("dark"), kitchen), directory.cameras.map(Member::id))
    }
}
