package com.fragmind.camnode.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PhotoStoreTest {

    @Test
    fun namesAreSortableAndKeepTheCameraName() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("CamNode_Cocina_20261116_143205.jpg", PhotoStore.fileName("Cocina", 1_794_839_525_000))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun separatorsAndSymbolsBecomeUnderscores() {
        val name = PhotoStore.fileName("  Sala / Puerta: 2 ", 0)
        assertTrue(name, name.startsWith("CamNode_Sala_Puerta_2_"))
    }

    @Test
    fun accentsStayAndEmptyNamesGetAFallback() {
        assertTrue(PhotoStore.fileName("Habitación", 0).startsWith("CamNode_Habitación_"))
        assertTrue(PhotoStore.fileName("///", 0).startsWith("CamNode_camara_"))
    }
}
