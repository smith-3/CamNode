package com.fragmind.camnode.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The window background (XML) and Compose must match, or launch flashes a seam. */
class BrandColorsTest {

    private val xmlColors: Map<String, Int> =
        Regex("""<color name="(\w+)">#([0-9A-Fa-f]{8})</color>""")
            .findAll(File("src/main/res/values/brand_colors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }

    @Test
    fun paletteMirrorsBrandXml() {
        assertBrand("camnode_night", Palette.Night)
    }

    private fun assertBrand(name: String, color: Color) {
        assertEquals(name, xmlColors.getValue(name), color.toArgb())
    }
}
