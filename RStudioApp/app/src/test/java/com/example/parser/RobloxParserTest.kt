package com.example.parser

import com.example.models.Part
import com.example.models.Vector3
import com.example.publish.RobloxPlaceBinarySerializer
import com.example.publish.RobloxPlaceXmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RobloxParserTest {
    @Test
    fun testParseBinaryRbxm() {
        val file = File("/workspaces/Gemini-3-model-card/binary.rbxm")
        if (!file.exists()) {
            println("SKIP: binary.rbxm not found")
            return
        }
        val data = file.readBytes()
        println("file size: ${data.size}")
        try {
            val instances = RobloxParser.parseRobloxFile(data)
            println("parsed ${instances.size} instances")
            instances.forEach { inst ->
                println("  ${inst.className}: ${inst.name} pos=${inst.properties.position} size=${inst.properties.size} color=${inst.properties.color} material=${inst.properties.material}")
            }
            val parts = RobloxParser.instancesToParts(instances)
            println("converted to ${parts.size} parts")
            parts.forEach { p ->
                println("  PART: ${p.name} shape=${p.shape} pos=${p.position} size=${p.size} color=${p.colorHex} mat=${p.material}")
            }
        } catch (e: Throwable) {
            println("PARSE FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun parseProvidedScriptRbxmKeepsScriptProperties() {
        val file = File("/workspaces/Gemini-3-model-card/script.rbxm")
        if (!file.exists()) {
            println("SKIP: script.rbxm not found")
            return
        }

        val instances = RobloxParser.parseRobloxFile(file.readBytes())
        val nodes = RobloxParser.instancesToStudioNodes(instances)
        val script = nodes.first { it.className == "Script" }

        assertEquals("Echo_Script", script.name)
        assertTrue("Expected Script source to import", script.scriptSource.contains("SetEcho"))
        assertTrue("Expected Source property to be visible", script.nodeProperties["Source"].orEmpty().contains("SetEcho"))
        assertTrue("Expected Disabled property", script.nodeProperties.containsKey("Disabled"))
        assertTrue("Expected LinkedSource property", script.nodeProperties.containsKey("LinkedSource"))
        assertTrue("Expected ScriptGuid property", script.nodeProperties.containsKey("ScriptGuid"))
    }

    @Test
    fun parseProvidedTextureRbxmKeepsTextureProperties() {
        val file = File("/workspaces/Gemini-3-model-card/texture.rbxm")
        if (!file.exists()) {
            println("SKIP: texture.rbxm not found")
            return
        }

        val instances = RobloxParser.parseRobloxFile(file.readBytes())
        val nodes = RobloxParser.instancesToStudioNodes(instances)
        val texture = nodes.first { it.className == "Texture" }

        assertEquals("Texture", texture.name)
        assertEquals("rbxassetid://6372755229", texture.nodeProperties["Texture"])
        assertTrue("Expected Face property", texture.nodeProperties.containsKey("Face"))
        assertTrue("Expected Transparency property", texture.nodeProperties.containsKey("Transparency"))
        assertTrue("Expected StudsPerTileU property", texture.nodeProperties.containsKey("StudsPerTileU"))
        assertTrue("Expected StudsPerTileV property", texture.nodeProperties.containsKey("StudsPerTileV"))
        assertTrue("Expected OffsetStudsU property", texture.nodeProperties.containsKey("OffsetStudsU"))
        assertTrue("Expected OffsetStudsV property", texture.nodeProperties.containsKey("OffsetStudsV"))
    }

    @Test
    fun parseProvidedGuiRbxmKeepsGuiInstances() {
        val file = File("/workspaces/Gemini-3-model-card/gui.rbxm")
        if (!file.exists()) {
            println("SKIP: gui.rbxm not found")
            return
        }

        val instances = RobloxParser.parseRobloxFile(file.readBytes())
        val nodes = RobloxParser.instancesToStudioNodes(instances)
        val classNames = nodes.map { it.className }.toSet()

        assertTrue("Expected ScreenGui", "ScreenGui" in classNames)
        assertTrue("Expected Frame", "Frame" in classNames)
        assertTrue("Expected TextLabel", "TextLabel" in classNames)
        assertTrue("Expected TextButton", "TextButton" in classNames)
        assertTrue("Expected ImageLabel", "ImageLabel" in classNames)
        assertTrue("Expected ImageButton", "ImageButton" in classNames)
        assertTrue("Expected LocalScript", "LocalScript" in classNames)
        assertTrue("Expected UIGridLayout", "UIGridLayout" in classNames)
        assertTrue(
            "Expected multiple shop item nodes",
            nodes.count { it.name.startsWith("item", ignoreCase = true) } >= 2
        )

        val guiObject = nodes.first { it.className == "Frame" || it.className == "TextLabel" || it.className == "ImageLabel" }
        assertTrue("Expected GUI Position property", guiObject.nodeProperties["Position"].orEmpty().contains("scaleX"))
        assertTrue("Expected GUI Size property", guiObject.nodeProperties["Size"].orEmpty().contains("scaleX"))
    }

    @Test
    fun parseProvidedGui2RbxmKeepsLocalScriptSource() {
        val file = File("/workspaces/Gemini-3-model-card/gui2.rbxm")
        if (!file.exists()) {
            println("SKIP: gui2.rbxm not found")
            return
        }

        val instances = RobloxParser.parseRobloxFile(file.readBytes())
        val nodes = RobloxParser.instancesToStudioNodes(instances)
        val scripts = nodes.filter { it.className == "LocalScript" }
        val screenGui = nodes.first { it.className == "ScreenGui" }

        assertTrue("Expected LocalScript instances", scripts.isNotEmpty())
        assertTrue("Expected imported LocalScript source", scripts.any { it.scriptSource.isNotBlank() && it.nodeProperties["Source"].orEmpty().isNotBlank() })
        assertEquals("MenuGui", screenGui.name)
        assertTrue("Expected ScreenGui ZIndexBehavior", screenGui.nodeProperties.containsKey("ZIndexBehavior"))
    }

    @Test
    fun parseProvidedRbxlSamplesFindsRenderableParts() {
        val samples = listOf(
            "/workspaces/Gemini-3-model-card/binary (3).rbxl" to 2,
            "/workspaces/Gemini-3-model-card/binary (4).rbxl" to 3,
            "/workspaces/Gemini-3-model-card/xml.rbxlx (2).txt" to 2,
            "/workspaces/Gemini-3-model-card/xml.rbxlx (3).txt" to 3
        )

        samples.forEach { (path, minParts) ->
            val file = File(path)
            if (!file.exists()) {
                println("SKIP: $path not found")
                return@forEach
            }

            val instances = RobloxParser.parseRobloxFile(file.readBytes())
            val parts = RobloxParser.instancesToParts(instances)
            println("${file.name}: instances=${instances.size}, parts=${parts.size}, classes=${instances.groupingBy { it.className }.eachCount()}")
            parts.forEach { part ->
                println("  ${part.name}: shape=${part.shape}, pos=${part.position}, size=${part.size}, color=${part.colorHex}, parent=${part.parentId}")
            }
            assertTrue(
                "Expected at least $minParts renderable parts in ${file.name}, got ${parts.size}",
                parts.size >= minParts
            )
            assertTrue(
                "Expected at least one imported part in ${file.name} to have non-default size",
                parts.any { it.size.x > 10f || it.size.y > 10f || it.size.z > 10f }
            )
            parts.filter { it.name == "Baseplate" }.forEach { baseplate ->
                assertTrue(
                    "Expected Baseplate in ${file.name} to import as a block, got ${baseplate.shape}",
                    baseplate.shape == Part.SHAPE_BLOCK
                )
                assertTrue(
                    "Expected Baseplate in ${file.name} to keep its large size, got ${baseplate.size}",
                    baseplate.size.x >= 100f && baseplate.size.z >= 100f
                )
            }

            if (file.name == "xml.rbxlx (2).txt") {
                val baseplate = parts.first { it.name == "Baseplate" }
                assertTrue("Expected Baseplate CanQuery to import", baseplate.canQuery)
                assertTrue("Expected Baseplate CanTouch to import", baseplate.canTouch)
                assertTrue("Expected Baseplate Locked to import", baseplate.locked)
                assertEquals("Default", baseplate.collisionGroup)
                assertEquals(0, baseplate.collisionGroupId)
                assertEquals(0, baseplate.rootPriority)
                assertEquals("Default", baseplate.customPhysicalProperties)
                assertEquals(Part.SURFACE_SMOOTH, baseplate.topSurface)
                assertEquals(Part.SURFACE_SMOOTH, baseplate.bottomSurface)
                assertEquals(-1L, baseplate.sourceAssetId)
                assertTrue("Expected Baseplate UniqueId to import", baseplate.uniqueId.isNotBlank())

                val spawn = parts.first { it.name == "SpawnLocation" }
                assertEquals(Part.SHAPE_SPAWN_LOCATION, spawn.shape)
                assertTrue("Expected SpawnLocation Enabled to import", spawn.spawnEnabled)
                assertTrue("Expected SpawnLocation Neutral to import", spawn.neutral)
                assertTrue("Expected SpawnLocation AllowTeamChangeOnTouch false to import", !spawn.allowTeamChangeOnTouch)
                assertEquals(0, spawn.duration)
                assertEquals(194, spawn.teamColor)
                assertEquals(1, spawn.formFactorRaw)
            }
        }
    }

    @Test
    fun serializedPublishRbxlxCanRoundTripThroughParser() {
        val original = Part(
            id = "publish-test-part",
            name = "PublishTestPart",
            shape = Part.SHAPE_BLOCK,
            position = Vector3(1f, 2f, 3f),
            size = Vector3(4f, 5f, 6f),
            colorHex = "#00A2FF",
            material = Part.MATERIAL_NEON,
            anchored = true,
            canCollide = true,
            canQuery = true,
            canTouch = true
        )

        val bytes = RobloxPlaceXmlSerializer.serialize("Publish Test", listOf(original))
        val instances = RobloxParser.parseRobloxFile(bytes)
        val parts = RobloxParser.instancesToParts(instances)
        val roundTripped = parts.firstOrNull { it.name == "PublishTestPart" }

        assertTrue("Expected serialized RBXLX to contain a renderable part", roundTripped != null)
        assertEquals(Vector3(4f, 5f, 6f), roundTripped!!.size)
        assertEquals(Part.MATERIAL_NEON, roundTripped.material)
    }

    @Test
    fun serializedPublishRbxlCanRoundTripThroughParser() {
        val original = Part(
            id = "publish-test-part",
            name = "PublishBinaryPart",
            shape = Part.SHAPE_CYLINDER,
            position = Vector3(1f, 2f, 3f),
            size = Vector3(4f, 5f, 6f),
            rotation = Vector3(0f, 30f, 0f),
            colorHex = "#00A2FF",
            material = Part.MATERIAL_NEON,
            anchored = true,
            canCollide = true,
            canQuery = true,
            canTouch = true
        )

        val bytes = RobloxPlaceBinarySerializer.serialize("Publish Test", listOf(original))
        assertTrue("Expected serialized RBXL to use Roblox binary magic", bytes.take(8).toByteArray().contentEquals("<roblox!".toByteArray()))

        val instances = RobloxParser.parseRobloxFile(bytes)
        val parts = RobloxParser.instancesToParts(instances)
        val roundTripped = parts.firstOrNull { it.name == "PublishBinaryPart" }

        assertTrue("Expected serialized RBXL to contain a renderable part", roundTripped != null)
        assertEquals(Part.SHAPE_CYLINDER, roundTripped!!.shape)
        assertEquals(Vector3(4f, 5f, 6f), roundTripped.size)
        assertEquals(Vector3(1f, 2f, 3f), roundTripped.position)
        assertEquals(Part.MATERIAL_NEON, roundTripped.material)
    }
}
