package com.example.ui.kool

import com.example.models.Part
import com.example.models.StudioNode

data class DecalRenderItem(
    val id: String,
    val parentPartId: String,
    val textureUri: String,
    val face: String,
    val transparency: Float,
    val colorHex: String,
    val zIndex: Int,
    val isTexture: Boolean,
    val studsPerTileU: Float,
    val studsPerTileV: Float,
    val offsetStudsU: Float,
    val offsetStudsV: Float
)

fun buildRenderableDecals(
    nodes: List<StudioNode>,
    parts: List<Part>
): List<DecalRenderItem> {
    val partsById = parts.associateBy { it.id }
    val partIdByNodeId = nodes.mapNotNull { node ->
        node.part?.let { part -> node.id to part.id }
    }.toMap()

    return nodes.mapNotNull { node ->
        val className = node.className.lowercase()
        val isTexture = className == "texture"
        if (className != "decal" && !isTexture) return@mapNotNull null

        val parentPartId = node.parentId?.let { parentId ->
            partIdByNodeId[parentId] ?: parentId.takeIf { it in partsById }
        } ?: return@mapNotNull null

        val textureUri = node.prop("Texture")
            .ifBlank { node.prop("TextureId") }
            .ifBlank { node.prop("TextureID") }
            .trim()
        if (textureUri.isBlank()) return@mapNotNull null

        DecalRenderItem(
            id = node.id,
            parentPartId = parentPartId,
            textureUri = textureUri,
            face = node.prop("Face").ifBlank { "Front" },
            transparency = node.prop("Transparency").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
            colorHex = node.prop("Color3").ifBlank { "#FFFFFF" },
            zIndex = node.prop("ZIndex").toIntOrNull() ?: 1,
            isTexture = isTexture,
            studsPerTileU = node.prop("StudsPerTileU").toFloatOrNull()?.takeIf { it > 0f } ?: 2f,
            studsPerTileV = node.prop("StudsPerTileV").toFloatOrNull()?.takeIf { it > 0f } ?: 2f,
            offsetStudsU = node.prop("OffsetStudsU").toFloatOrNull() ?: 0f,
            offsetStudsV = node.prop("OffsetStudsV").toFloatOrNull() ?: 0f
        )
    }
}

fun resolveRobloxTextureAssetPath(textureUri: String): String? {
    val trimmed = textureUri.trim().trim('"')
    if (trimmed.isBlank()) return null

    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("rbxasset://") -> {
            val path = trimmed.substringAfter("://")
                .replace('\\', '/')
                .removePrefix("/")
            localRbxAssetPath(path)
        }
        lower.startsWith("rbxassetid://") -> {
            val id = trimmed.substringAfter("://").filter { it.isDigit() }
            if (id.isBlank()) null else "https://assetdelivery.roblox.com/v1/asset/?id=$id"
        }
        lower.contains("roblox.com/asset/?id=") -> {
            val id = trimmed.substringAfter("id=", "").takeWhile { it.isDigit() }
            if (id.isBlank()) null else "https://assetdelivery.roblox.com/v1/asset/?id=$id"
        }
        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:") -> trimmed
        lower.startsWith("textures/") -> localRbxAssetPath(trimmed)
        else -> null
    }
}

private fun localRbxAssetPath(path: String): String? {
    val normalized = path.replace('\\', '/').removePrefix("/")
    return LOCAL_RBXASSET_TEXTURES[normalized.lowercase()] ?: normalized
}

private fun StudioNode.prop(name: String): String =
    nodeProperties.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

private val LOCAL_RBXASSET_TEXTURES = mapOf(
    "textures/spawnlocation.png" to "textures/SpawnLocation.png"
)
