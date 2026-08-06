package com.example.ui.viewport

import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightRenderItemTest {
    @Test
    fun resolvesNodeParentAndBuildsPointLight() {
        val part = Part("part", "Host", position = Vector3(1f, 2f, 3f))
        val partNode = StudioNode("part-node", "Host", StudioNode.CLASS_PART, part = part)
        val light = StudioNode(
            "light",
            "PointLight",
            StudioNode.CLASS_POINT_LIGHT,
            parentId = "part-node",
            nodeProperties = mapOf("Brightness" to "2.5", "Range" to "24", "Color" to "#FF8040")
        )

        val item = buildRenderableLights(listOf(partNode, light), listOf(part)).single()

        assertEquals(LocalLightType.POINT, item.type)
        assertEquals(Vector3(1f, 2f, 3f), item.position)
        assertEquals(2.5f, item.brightness)
        assertEquals(24f, item.range)
        assertEquals("#FF8040", item.colorHex)
    }

    @Test
    fun skipsOrphanLightsAndClampsProperties() {
        val orphan = StudioNode("light", "SpotLight", StudioNode.CLASS_SPOT_LIGHT, parentId = "missing")
        assertTrue(buildRenderableLights(listOf(orphan), emptyList()).isEmpty())

        val part = Part("part", "Host")
        val spot = orphan.copy(
            parentId = "part",
            nodeProperties = mapOf("Brightness" to "-4", "Range" to "-1", "Angle" to "500")
        )
        val item = buildRenderableLights(listOf(spot), listOf(part)).single()
        assertEquals(0f, item.brightness)
        assertEquals(0.1f, item.range)
        assertEquals(179f, item.angleDegrees)
    }
}
