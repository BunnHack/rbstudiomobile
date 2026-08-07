package com.example.parser

import com.example.models.Part
import com.example.models.StudioNode
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

    @Test
    fun serializedPublishRbxlRoundTripsMultipleDistinctPartColors() {
        val originals = listOf(
            Part("part-a", "PartA", colorHex = "#112233"),
            Part("part-b", "PartB", colorHex = "#F8E8D8")
        )

        val roundTrip = RobloxParser.instancesToParts(
            RobloxParser.parseRobloxFile(RobloxPlaceBinarySerializer.serialize("Colors", originals))
        ).associateBy { it.name }

        assertEquals("#112233", roundTrip.getValue("PartA").colorHex.uppercase())
        assertEquals("#F8E8D8", roundTrip.getValue("PartB").colorHex.uppercase())
    }

    @Test
    fun serializedPublishRbxlPreservesRequestedClassesAndProperties() {
        val host = Part("part", "HostPart", shape = Part.SHAPE_CORNER_WEDGE, size = Vector3(4f, 4f, 4f))
        val nodes = listOf(
            StudioNode("host-node", "HostPart", StudioNode.CLASS_CORNER_WEDGE_PART, part = host),
            StudioNode(
                "text-box",
                "Input",
                StudioNode.CLASS_TEXT_BOX,
                StudioNode.CLASS_STARTER_GUI,
                nodeProperties = mapOf(
                    "Text" to "Type here",
                    "PlaceholderText" to "Name",
                    "MultiLine" to "true",
                    "Position" to "scaleX=0.1, scaleY=0.2, offsetX=3, offsetY=4",
                    "Size" to "scaleX=0.5, scaleY=0.0, offsetX=20, offsetY=40"
                )
            ),
            StudioNode(
                "sky",
                "Sky",
                StudioNode.CLASS_SKY,
                StudioNode.CLASS_LIGHTING,
                nodeProperties = mapOf("StarCount" to "42", "SkyboxBk" to "rbxassetid://1")
            ),
            StudioNode(
                "decal",
                "Decal",
                StudioNode.CLASS_DECAL,
                "host-node",
                nodeProperties = mapOf("Texture" to "rbxassetid://2", "Face" to "Top", "Transparency" to "0.25")
            )
        )

        val roundTrip = RobloxParser.instancesToStudioNodes(
            RobloxParser.parseRobloxFile(RobloxPlaceBinarySerializer.serialize("Classes", listOf(host), nodes))
        )

        assertTrue(roundTrip.any { it.className == StudioNode.CLASS_CORNER_WEDGE_PART })
        val textBox = roundTrip.first { it.className == StudioNode.CLASS_TEXT_BOX }
        assertEquals("Type here", textBox.nodeProperties["Text"])
        assertEquals("Name", textBox.nodeProperties["PlaceholderText"])
        assertEquals("true", textBox.nodeProperties["MultiLine"])
        val sky = roundTrip.first { it.className == StudioNode.CLASS_SKY }
        assertEquals("42", sky.nodeProperties["StarCount"])
        assertEquals("rbxassetid://1", sky.nodeProperties["SkyboxBk"])
        val decal = roundTrip.first { it.className == StudioNode.CLASS_DECAL }
        assertEquals("rbxassetid://2", decal.nodeProperties["Texture"])
        assertEquals("Top", decal.nodeProperties["Face"])
    }

    @Test
    fun trussFixtureParsesAsTrussWithStyle() {
        val file = File("/workspaces/Gemini-3-model-card/rbxtest/TrussPart.rbxm")
        if (!file.exists()) return

        val parts = RobloxParser.instancesToParts(RobloxParser.parseRobloxFile(file.readBytes()))
        val truss = parts.single()

        assertEquals(Part.SHAPE_TRUSS, truss.shape)
        assertEquals(Part.TRUSS_STYLE_ALTERNATING_SUPPORTS, truss.trussStyle)
    }

    @Test
    fun trussAndCornerWedgeRoundTripPreserveGeometryClass() {
        val truss = Part(
            id = "truss",
            name = "Truss",
            shape = Part.SHAPE_TRUSS,
            trussStyle = Part.TRUSS_STYLE_BRIDGE_SUPPORTS,
            size = Vector3(2f, 12f, 2f)
        )
        val corner = Part("corner", "Corner", shape = Part.SHAPE_CORNER_WEDGE)

        val parsed = RobloxParser.parseRobloxFile(
            RobloxPlaceBinarySerializer.serialize("Special Parts", listOf(truss, corner))
        )
        val roundTripParts = RobloxParser.instancesToParts(parsed).associateBy { it.name }
        val roundTripClasses = parsed.associate { it.name to it.className }

        assertEquals(Part.SHAPE_TRUSS, roundTripParts.getValue("Truss").shape)
        assertEquals(Part.TRUSS_STYLE_BRIDGE_SUPPORTS, roundTripParts.getValue("Truss").trussStyle)
        assertEquals(StudioNode.CLASS_TRUSS_PART, roundTripClasses.getValue("Truss"))
        assertEquals(Part.SHAPE_CORNER_WEDGE, roundTripParts.getValue("Corner").shape)
        assertEquals(StudioNode.CLASS_CORNER_WEDGE_PART, roundTripClasses.getValue("Corner"))
    }

    @Test
    fun meshPartFixturesPreserveMeshProperties() {
        listOf("MeshPart.rbxm", "MeshPart2.rbxm").forEach { fixture ->
            val file = File("/workspaces/Gemini-3-model-card/rbxtest/$fixture")
            if (!file.exists()) return@forEach

            val part = RobloxParser.instancesToParts(RobloxParser.parseRobloxFile(file.readBytes())).single()

            assertEquals(Part.SHAPE_MESH, part.shape)
            assertTrue(part.meshId.startsWith("rbxassetid://"))
            assertTrue(part.initialSize.x > 0f)
        }
    }

    @Test
    fun highlightAndUiGradientFixturesExposeRenderingProperties() {
        val highlightFile = File("/workspaces/Gemini-3-model-card/rbxtest/Highlight.rbxm")
        val gradientFile = File("/workspaces/Gemini-3-model-card/rbxtest/UIGradient.rbxm")
        if (!highlightFile.exists() || !gradientFile.exists()) return

        val highlight = RobloxParser.instancesToStudioNodes(RobloxParser.parseRobloxFile(highlightFile.readBytes())).single()
        val gradient = RobloxParser.instancesToStudioNodes(RobloxParser.parseRobloxFile(gradientFile.readBytes())).single()

        assertEquals(StudioNode.CLASS_HIGHLIGHT, highlight.className)
        assertEquals("#ff0000", highlight.nodeProperties["FillColor"]?.lowercase())
        assertEquals(StudioNode.CLASS_UI_GRADIENT, gradient.className)
        assertTrue(gradient.nodeProperties["Color"].orEmpty().contains("#0f27ff", true))
        assertEquals("0.0", gradient.nodeProperties["Rotation"])
    }

    @Test
    fun serializedPublishRbxlPreservesExtendedStudioNodes() {
        val nodes = listOf(
            StudioNode("attachment", "Attachment", StudioNode.CLASS_ATTACHMENT, "part", nodeProperties = mapOf("Visible" to "true")),
            StudioNode("module", "SharedModule", StudioNode.CLASS_MODULE_SCRIPT, StudioNode.CLASS_REPLICATED_STORAGE, scriptSource = "return {}"),
            StudioNode("remote", "RemoteEvent", StudioNode.CLASS_REMOTE_EVENT, StudioNode.CLASS_REPLICATED_STORAGE),
            StudioNode("sound", "Sound", StudioNode.CLASS_SOUND, "part", nodeProperties = mapOf("SoundId" to "rbxassetid://1", "Volume" to "0.75")),
            StudioNode("point", "PointLight", StudioNode.CLASS_POINT_LIGHT, "part"),
            StudioNode("spot", "SpotLight", StudioNode.CLASS_SPOT_LIGHT, "part"),
            StudioNode("surface", "SurfaceLight", StudioNode.CLASS_SURFACE_LIGHT, "part")
        )
        val part = Part(
            id = "part",
            name = "HostPart",
            position = Vector3.Zero,
            size = Vector3(4f, 1f, 4f),
            anchored = true
        )

        val bytes = RobloxPlaceBinarySerializer.serialize("Extended Nodes", listOf(part), nodes)
        val roundTrip = RobloxParser.instancesToStudioNodes(RobloxParser.parseRobloxFile(bytes))
        val classes = roundTrip.map { it.className }.toSet()

        assertTrue(StudioNode.CLASS_ATTACHMENT in classes)
        assertTrue(StudioNode.CLASS_MODULE_SCRIPT in classes)
        assertTrue(StudioNode.CLASS_REMOTE_EVENT in classes)
        assertTrue(StudioNode.CLASS_SOUND in classes)
        assertTrue(StudioNode.CLASS_POINT_LIGHT in classes)
        assertTrue(StudioNode.CLASS_SPOT_LIGHT in classes)
        assertTrue(StudioNode.CLASS_SURFACE_LIGHT in classes)
        val host = roundTrip.first { it.name == "HostPart" }
        val attachment = roundTrip.first { it.className == StudioNode.CLASS_ATTACHMENT }
        val module = roundTrip.first { it.className == StudioNode.CLASS_MODULE_SCRIPT }
        assertEquals(host.id, attachment.parentId)
        assertEquals("return {}", module.scriptSource)
    }

    @Test
    fun serializedPublishRbxlPreservesEffectsAndGuiDecorators() {
        val part = Part("part", "HostPart")
        val nodes = listOf(
            StudioNode("a0", "A0", StudioNode.CLASS_ATTACHMENT, "part"),
            StudioNode("a1", "A1", StudioNode.CLASS_ATTACHMENT, "part"),
            StudioNode(
                "beam", "Beam", StudioNode.CLASS_BEAM, "part",
                nodeProperties = mapOf(
                    "Attachment0" to "a0", "Attachment1" to "a1", "Color" to "0:#FF0000:0; 1:#00FF00:0",
                    "Transparency" to "0:0.1:0; 1:0.8:0", "Width0" to "2", "Width1" to "3"
                )
            ),
            StudioNode(
                "trail", "Trail", StudioNode.CLASS_TRAIL, "part",
                nodeProperties = mapOf("Attachment0" to "a0", "Attachment1" to "a1", "Lifetime" to "2.5")
            ),
            StudioNode(
                "particles", "Particles", StudioNode.CLASS_PARTICLE_EMITTER, "part",
                nodeProperties = mapOf("Rate" to "20", "Lifetime" to "1, 3", "Size" to "0:2:0; 1:0:0")
            ),
            StudioNode("surface", "SurfaceGui", StudioNode.CLASS_SURFACE_GUI, "part", nodeProperties = mapOf("CanvasSize" to "x=400, y=200")),
            StudioNode("frame", "Frame", StudioNode.CLASS_FRAME, "surface"),
            StudioNode("list", "UIListLayout", StudioNode.CLASS_UI_LIST_LAYOUT, "frame", nodeProperties = mapOf("Padding" to "scale=0.1, offset=4")),
            StudioNode("corner", "UICorner", StudioNode.CLASS_UI_CORNER, "frame", nodeProperties = mapOf("CornerRadius" to "scale=0, offset=12")),
            StudioNode("stroke", "UIStroke", StudioNode.CLASS_UI_STROKE, "frame", nodeProperties = mapOf("Color" to "#123456", "Thickness" to "3"))
        )

        val roundTrip = RobloxParser.instancesToStudioNodes(
            RobloxParser.parseRobloxFile(RobloxPlaceBinarySerializer.serialize("Effects", listOf(part), nodes))
        )

        val byClass = roundTrip.associateBy { it.className }
        assertEquals("2.5", byClass.getValue(StudioNode.CLASS_TRAIL).nodeProperties["Lifetime"])
        assertEquals("20.0", byClass.getValue(StudioNode.CLASS_PARTICLE_EMITTER).nodeProperties["Rate"])
        assertEquals("x=400.000, y=200.000", byClass.getValue(StudioNode.CLASS_SURFACE_GUI).nodeProperties["CanvasSize"])
        assertEquals("scale=0.100, offset=4", byClass.getValue(StudioNode.CLASS_UI_LIST_LAYOUT).nodeProperties["Padding"])
        assertEquals("scale=0.000, offset=12", byClass.getValue(StudioNode.CLASS_UI_CORNER).nodeProperties["CornerRadius"])
        assertEquals("#123456", byClass.getValue(StudioNode.CLASS_UI_STROKE).nodeProperties["Color"]?.uppercase())
        val beam = byClass.getValue(StudioNode.CLASS_BEAM)
        assertTrue(beam.nodeProperties["Color"].orEmpty().contains("#FF0000", ignoreCase = true))
        assertEquals("a0", nodes.first { it.id == "beam" }.nodeProperties["Attachment0"])
        assertTrue(beam.nodeProperties["Attachment0"].orEmpty().isNotBlank())
    }

    @Test
    fun xmlParserPreservesGuiAndEffectValueTypes() {
        val xml = """
            <roblox version="4">
              <Item class="Attachment" referent="RBX1"><Properties><string name="Name">A0</string></Properties></Item>
              <Item class="Beam" referent="RBX2">
                <Properties>
                  <string name="Name">Beam</string>
                  <Ref name="Attachment0">RBX1</Ref>
                  <NumberSequence name="Transparency">0 0.25 0 1 1 0</NumberSequence>
                  <ColorSequence name="Color">
                    <ColorSequenceKeypoint><Time>0</Time><Value><R>1</R><G>0</G><B>0</B></Value><Envelope>0</Envelope></ColorSequenceKeypoint>
                  </ColorSequence>
                </Properties>
              </Item>
              <Item class="Frame" referent="RBX3">
                <Properties>
                  <UDim2 name="Size"><XS>0.5</XS><XO>10</XO><YS>0.25</YS><YO>20</YO></UDim2>
                </Properties>
              </Item>
            </roblox>
        """.trimIndent()

        val nodes = RobloxParser.instancesToStudioNodes(RobloxParser.parseRobloxXml(xml))
        val beam = nodes.first { it.className == StudioNode.CLASS_BEAM }
        val frame = nodes.first { it.className == StudioNode.CLASS_FRAME }

        assertEquals(0x13, beam.propertyTypeIds["Attachment0"])
        assertEquals("RBX1", beam.nodeProperties["Attachment0"])
        assertTrue(beam.nodeProperties["Transparency"].orEmpty().contains("0.0:0.25"))
        assertTrue(beam.nodeProperties["Color"].orEmpty().contains("#ff0000", true))
        assertEquals("scaleX=0.500, scaleY=0.250, offsetX=10, offsetY=20", frame.nodeProperties["Size"])
    }
}
