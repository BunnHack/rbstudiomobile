package com.example.database

import com.example.models.Part
import com.example.models.Place
import com.example.models.Vector3
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import java.util.UUID

class PlaceRepository(private val placeDao: PlaceDao) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val partListType = Types.newParameterizedType(List::class.java, Part::class.java)
    private val partsAdapter = moshi.adapter<List<Part>>(partListType)

    // Expose all places, and automatically seed database with template scenes if empty
    val allPlaces: Flow<List<Place>> = placeDao.getAllPlaces()

    suspend fun getPlaceById(id: Int): Place? = placeDao.getPlaceById(id)

    suspend fun insert(place: Place): Long = placeDao.insertPlace(place)

    suspend fun update(place: Place) = placeDao.updatePlace(place)

    suspend fun deleteById(id: Int) = placeDao.deletePlaceById(id)

    // Parse JSON string to Part list
    fun parsePartsJson(json: String): List<Part> {
        return try {
            partsAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Convert Part list to JSON string
    fun partsToJson(parts: List<Part>): String {
        return try {
            partsAdapter.toJson(parts)
        } catch (e: Exception) {
            "[]"
        }
    }

    // Seed method to insert templates
    suspend fun seedDatabaseIfEmpty(currentList: List<Place>) {
        if (currentList.isEmpty()) {
            createDefaultTemplates().forEach {
                placeDao.insertPlace(it)
            }
        }
    }

    fun createDefaultTemplates(): List<Place> {
        return listOf(
            Place(
                name = "Flat Baseplate",
                description = "A clean, infinite standard gray baseplate. Perfect for starting from scratch.",
                partsJson = partsToJson(createFlatBaseplateParts()),
                templateId = "baseplate"
            ),
            Place(
                name = "Classic Obby Course",
                description = "A challenging obstacle course featuring neon jump bars, rotating floating parts, ramp wedges, physical rolling spheres, and a shiny trophy win area!",
                partsJson = partsToJson(createObbyParts()),
                templateId = "obby"
            ),
            Place(
                name = "Mars Colony Sandbox",
                description = "Build a space outpost on a red planet with low-gravity physics, science modules, glowing pylons, and unanchored capsule blocks.",
                partsJson = partsToJson(createMarsParts()),
                templateId = "mars"
            )
        )
    }

    private fun createFlatBaseplateParts(): List<Part> {
        return listOf(
            Part(
                id = "baseplate",
                name = "Baseplate",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, -1f, 0f),
                size = Vector3(100f, 2f, 100f),
                colorHex = "#2B2B2B",
                material = Part.MATERIAL_SLATE,
                anchored = true,
                canCollide = true
            ),
            Part(
                id = UUID.randomUUID().toString(),
                name = "SpawnLocation",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, 0.1f, 0f),
                size = Vector3(4f, 0.2f, 4f),
                colorHex = "#00A2FF",
                material = Part.MATERIAL_PLASTIC,
                anchored = true,
                canCollide = true,
                effect = Part.EFFECT_POINTLIGHT
            )
        )
    }

    private fun createObbyParts(): List<Part> {
        val list = mutableListOf<Part>()
        // 1. Baseplate
        list.add(
            Part(
                id = "baseplate",
                name = "Baseplate",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, -1f, 0f),
                size = Vector3(100f, 2f, 100f),
                colorHex = "#1F1F1F",
                material = Part.MATERIAL_SLATE,
                anchored = true,
                canCollide = true
            )
        )
        // 2. Spawn point
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "SpawnLocation",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, 0.1f, 35f),
                size = Vector3(5f, 0.2f, 5f),
                colorHex = "#00FF66",
                material = Part.MATERIAL_NEON,
                anchored = true,
                canCollide = true
            )
        )
        // 3. Floating yellow stepping stone
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "Step_Yellow",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, 1f, 23f),
                size = Vector3(4f, 0.5f, 4f),
                colorHex = "#FFCC00",
                material = Part.MATERIAL_WOOD,
                anchored = true
            )
        )
        // 4. Floating cyan stepping stone
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "Step_Cyan",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(-6f, 2.5f, 15f),
                size = Vector3(4f, 0.5f, 4f),
                colorHex = "#00E5FF",
                material = Part.MATERIAL_PLASTIC,
                anchored = true
            )
        )
        // 5. Wedge Ramp
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "Ramp_Wedge",
                shape = Part.SHAPE_WEDGE,
                position = Vector3(6f, 2.5f, 6f),
                size = Vector3(4f, 4f, 8f),
                rotation = Vector3(0f, 180f, 0f),
                colorHex = "#FF5722",
                material = Part.MATERIAL_BRICK,
                anchored = true
            )
        )
        // 6. Danger lava bar (Red Neon, kills player or spawns effects)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "LavaBar",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, 4.5f, -5f),
                size = Vector3(10f, 0.5f, 2f),
                colorHex = "#FF0000",
                material = Part.MATERIAL_NEON,
                anchored = true,
                script = "while true do\n  wait(0.5)\n  script.Parent.Color = Color3.fromRGB(255,0,0)\n  wait(0.5)\n  script.Parent.Color = Color3.fromRGB(150,0,0)\nend",
                effect = Part.EFFECT_FIRE
            )
        )
        // 7. Spinner part (Has Spinner script)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "SpinningHex",
                shape = Part.SHAPE_CYLINDER,
                position = Vector3(-5f, 6.5f, -15f),
                size = Vector3(4f, 0.8f, 4f),
                rotation = Vector3(90f, 0f, 0f), // cylinders face along Z normally
                colorHex = "#AA00FF",
                material = Part.MATERIAL_METAL,
                anchored = true,
                script = "while true do\n  script.Parent.Rotation.Y = script.Parent.Rotation.Y + 2\n  wait()\nend"
            )
        )
        // 8. Bouncing ball (Has Hover/Bounce script)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "HoverBall",
                shape = Part.SHAPE_SPHERE,
                position = Vector3(5f, 8f, -24f),
                size = Vector3(3f, 3f, 3f),
                colorHex = "#FF00AA",
                material = Part.MATERIAL_NEON,
                anchored = true,
                script = "local initialY = script.Parent.Position.Y\nlocal t = 0\nwhile true do\n  t = t + 0.1\n  script.Parent.Position.Y = initialY + math.sin(t) * 2\n  wait()\nend",
                effect = Part.EFFECT_SMOKE
            )
        )
        // 9. Unanchored Physical ball (rolls around!)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "PhysicsSphere",
                shape = Part.SHAPE_SPHERE,
                position = Vector3(0f, 15f, -15f),
                size = Vector3(4f, 4f, 4f),
                colorHex = "#00FFCC",
                material = Part.MATERIAL_METAL,
                anchored = false,
                canCollide = true
            )
        )
        // 10. Golden Win Trophy
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "GoldenCup",
                shape = Part.SHAPE_CYLINDER,
                position = Vector3(0f, 2.5f, -36f),
                size = Vector3(3f, 5f, 3f),
                rotation = Vector3(0f, 0f, 0f),
                colorHex = "#FFD700",
                material = Part.MATERIAL_METAL,
                anchored = true,
                effect = Part.EFFECT_SPARKLES
            )
        )
        return list
    }

    private fun createMarsParts(): List<Part> {
        val list = mutableListOf<Part>()
        // 1. Rust Ground Baseplate
        list.add(
            Part(
                id = "baseplate",
                name = "MarsBaseplate",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(0f, -1f, 0f),
                size = Vector3(100f, 2f, 100f),
                colorHex = "#9E472A",
                material = Part.MATERIAL_SLATE,
                anchored = true
            )
        )
        // 2. Outpost Hub (Large dome cylinder)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "OutpostDome",
                shape = Part.SHAPE_SPHERE,
                position = Vector3(0f, 0f, 0f),
                size = Vector3(14f, 14f, 14f),
                colorHex = "#EEEEEE",
                material = Part.MATERIAL_GLASS,
                anchored = true,
                effect = Part.EFFECT_POINTLIGHT
            )
        )
        // 3. Power Pylon (Glowing neon pylon)
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "PowerPylon",
                shape = Part.SHAPE_CYLINDER,
                position = Vector3(15f, 5f, 15f),
                size = Vector3(2f, 10f, 2f),
                colorHex = "#00FFFF",
                material = Part.MATERIAL_NEON,
                anchored = true,
                effect = Part.EFFECT_SPARKLES
            )
        )
        // 4. Physical capsule boxes
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "Cargo_A",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(-12f, 4f, -10f),
                size = Vector3(3f, 3f, 3f),
                colorHex = "#666666",
                material = Part.MATERIAL_METAL,
                anchored = false
            )
        )
        list.add(
            Part(
                id = UUID.randomUUID().toString(),
                name = "Cargo_B",
                shape = Part.SHAPE_BLOCK,
                position = Vector3(-12f, 8f, -10f),
                size = Vector3(2.5f, 2.5f, 2.5f),
                colorHex = "#AA8855",
                material = Part.MATERIAL_METAL,
                anchored = false
            )
        )
        return list
    }
}
