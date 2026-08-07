package com.example.ui.viewport

import com.example.models.Part
import com.example.models.StudioNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRenderItemTest {
    @Test
    fun resolvesParentPartWhenAdorneeIsEmpty() {
        val part = Part("part", "Part")
        val highlight = StudioNode(
            "highlight", "Highlight", StudioNode.CLASS_HIGHLIGHT, "part",
            nodeProperties = mapOf("FillColor" to "#123456", "OutlineTransparency" to "0.25")
        )

        val item = buildHighlights(listOf(highlight), listOf(part)).single()

        assertEquals("part", item.targetPartId)
        assertEquals("#123456", item.fillColorHex)
        assertTrue(item.alwaysOnTop)
        assertEquals(0.25f, item.outlineTransparency)
    }
}
