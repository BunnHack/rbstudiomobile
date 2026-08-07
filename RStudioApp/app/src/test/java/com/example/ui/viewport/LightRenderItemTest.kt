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
        assertEquals("part", item.hostPartId)
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

    @Test
    fun usesRuntimeTransformForAttachedLight() {
        val part = Part(
            "part",
            "Host",
            position = Vector3.Zero,
            currentPosition = Vector3(9f, 8f, 7f),
            currentRotation = Vector3(0f, 90f, 0f)
        )
        val light = StudioNode(
            "light",
            "SpotLight",
            StudioNode.CLASS_SPOT_LIGHT,
            parentId = "part",
            nodeProperties = mapOf("Face" to "Front")
        )

        val item = buildRenderableLights(listOf(light), listOf(part)).single()

        assertEquals(Vector3(10f, 8f, 7f), item.position)
        assertEquals(1f, item.direction.x, 0.0001f)
        assertEquals(0f, item.direction.z, 0.0001f)
    }

    @Test
    fun reflectsPointLightColorAndRangePropertyChanges() {
        val part = Part("part", "Host")
        val initial = StudioNode(
            "light",
            "PointLight",
            StudioNode.CLASS_POINT_LIGHT,
            parentId = "part",
            nodeProperties = mapOf("Color" to "#FFFFFF", "Range" to "8")
        )
        val updated = initial.copy(nodeProperties = initial.nodeProperties + mapOf("Color" to "#00FF80", "Range" to "32"))

        val before = buildRenderableLights(listOf(initial), listOf(part)).single()
        val after = buildRenderableLights(listOf(updated), listOf(part)).single()

        assertEquals("#FFFFFF", before.colorHex)
        assertEquals(8f, before.range)
        assertEquals("#00FF80", after.colorHex)
        assertEquals(32f, after.range)
    }
}
