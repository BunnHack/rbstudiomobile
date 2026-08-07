package com.example.ui.viewport

import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3
import kotlin.math.cos
import kotlin.math.sin

enum class LocalLightType { POINT, SPOT, SURFACE }

data class LocalLightRenderItem(
    val id: String,
    val hostPartId: String,
    val type: LocalLightType,
    val position: Vector3,
    val direction: Vector3,
    val colorHex: String,
    val brightness: Float,
    val range: Float,
    val angleDegrees: Float,
    val shadows: Boolean,
    val enabled: Boolean
)

fun buildRenderableLights(nodes: List<StudioNode>, parts: List<Part>): List<LocalLightRenderItem> {
    val partsById = parts.associateBy { it.id }
    val nodesById = nodes.associateBy { it.id }

    return nodes.mapNotNull { node ->
        val type = when (node.className) {
            StudioNode.CLASS_POINT_LIGHT -> LocalLightType.POINT
            StudioNode.CLASS_SPOT_LIGHT -> LocalLightType.SPOT
            StudioNode.CLASS_SURFACE_LIGHT -> LocalLightType.SURFACE
            else -> return@mapNotNull null
        }
        val part = resolveHostPart(node.parentId, nodesById, partsById) ?: return@mapNotNull null
        val face = node.prop("Face", "Front")
        val localDirection = faceDirection(face)
        val worldDirection = rotateDirection(localDirection, part.currentRotation)
        val position = if (type == LocalLightType.POINT) {
            part.currentPosition
        } else {
            part.currentPosition + rotateDirection(faceOffset(face, part.size), part.currentRotation)
        }
        LocalLightRenderItem(
            id = node.id,
            hostPartId = part.id,
            type = type,
            position = position,
            direction = worldDirection,
            colorHex = node.prop("Color", "#FFFFFF"),
            brightness = node.prop("Brightness", "1").toFloatOrNull()?.coerceIn(0f, 100f) ?: 1f,
            range = node.prop("Range", if (type == LocalLightType.POINT) "8" else "16")
                .toFloatOrNull()?.coerceIn(0.1f, 1000f) ?: 16f,
            angleDegrees = node.prop("Angle", if (type == LocalLightType.SPOT) "45" else "90")
                .toFloatOrNull()?.coerceIn(1f, 179f) ?: 90f,
            shadows = node.prop("Shadows", "false").toBooleanStrictOrNull() ?: false,
            enabled = node.prop("Enabled", "true").toBooleanStrictOrNull() ?: true
        )
    }
}

private fun StudioNode.prop(name: String, default: String): String =
    nodeProperties.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: default

private fun faceDirection(face: String): Vector3 = when (face.lowercase()) {
    "right" -> Vector3(1f, 0f, 0f)
    "top" -> Vector3(0f, 1f, 0f)
    "back" -> Vector3(0f, 0f, -1f)
    "left" -> Vector3(-1f, 0f, 0f)
    "bottom" -> Vector3(0f, -1f, 0f)
    else -> Vector3(0f, 0f, 1f)
}

private fun faceOffset(face: String, size: Vector3): Vector3 = when (face.lowercase()) {
    "right" -> Vector3(size.x / 2f, 0f, 0f)
    "top" -> Vector3(0f, size.y / 2f, 0f)
    "back" -> Vector3(0f, 0f, -size.z / 2f)
    "left" -> Vector3(-size.x / 2f, 0f, 0f)
    "bottom" -> Vector3(0f, -size.y / 2f, 0f)
    else -> Vector3(0f, 0f, size.z / 2f)
}

internal fun rotateDirection(direction: Vector3, rotation: Vector3): Vector3 {
    val rx = Math.toRadians(rotation.x.toDouble())
    val ry = Math.toRadians(rotation.y.toDouble())
    val rz = Math.toRadians(rotation.z.toDouble())
    val cx = cos(rx).toFloat()
    val sx = sin(rx).toFloat()
    val cy = cos(ry).toFloat()
    val sy = sin(ry).toFloat()
    val cz = cos(rz).toFloat()
    val sz = sin(rz).toFloat()
    return Vector3(
        direction.x * cy * cz + direction.y * (cz * sx * sy - cx * sz) + direction.z * (sx * sz + cx * cz * sy),
        direction.x * cy * sz + direction.y * (cx * cz + sx * sy * sz) + direction.z * (cx * sy * sz - cz * sx),
        direction.x * -sy + direction.y * cy * sx + direction.z * cx * cy
    )
}

internal fun resolveHostPart(
    parentId: String?,
    nodesById: Map<String, StudioNode>,
    partsById: Map<String, Part>
): Part? {
    var currentId = parentId
    val visited = mutableSetOf<String>()
    while (currentId != null && visited.add(currentId)) {
        partsById[currentId]?.let { return it }
        val node = nodesById[currentId] ?: return null
        node.part?.let { part -> return partsById[part.id] ?: part }
        currentId = node.parentId
    }
    return null
}
