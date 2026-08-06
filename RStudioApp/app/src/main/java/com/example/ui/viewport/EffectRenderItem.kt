package com.example.ui.viewport

import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3

data class RibbonRenderItem(
    val id: String,
    val start: Vector3,
    val end: Vector3,
    val colorHex: String,
    val transparency: Float,
    val width: Float,
    val enabled: Boolean
)

data class ParticleEmitterRenderItem(
    val id: String,
    val origin: Vector3,
    val colorHex: String,
    val transparency: Float,
    val size: Float,
    val count: Int,
    val enabled: Boolean
)

data class SurfaceGuiRenderItem(
    val id: String,
    val position: Vector3,
    val rotation: Vector3,
    val width: Float,
    val height: Float,
    val text: String,
    val textColorHex: String,
    val enabled: Boolean
)

fun buildRibbonEffects(nodes: List<StudioNode>, parts: List<Part>): List<RibbonRenderItem> {
    val nodesById = nodes.associateBy { it.id }
    val partsById = parts.associateBy { it.id }
    return nodes.mapNotNull { node ->
        if (node.className != StudioNode.CLASS_BEAM && node.className != StudioNode.CLASS_TRAIL) return@mapNotNull null
        val start = attachmentWorldPosition(node.prop("Attachment0"), nodesById, partsById) ?: return@mapNotNull null
        val end = attachmentWorldPosition(node.prop("Attachment1"), nodesById, partsById) ?: return@mapNotNull null
        RibbonRenderItem(
            id = node.id,
            start = start,
            end = end,
            colorHex = firstSequenceColor(node.prop("Color")).ifBlank { "#FFFFFF" },
            transparency = firstSequenceNumber(node.prop("Transparency"), 0f).coerceIn(0f, 1f),
            width = if (node.className == StudioNode.CLASS_BEAM) {
                maxOf(node.prop("Width0", "1").toFloatOrNull() ?: 1f, node.prop("Width1", "1").toFloatOrNull() ?: 1f)
            } else {
                firstSequenceNumber(node.prop("WidthScale"), 1f)
            }.coerceIn(0.02f, 50f),
            enabled = node.prop("Enabled", "true").toBooleanStrictOrNull() ?: true
        )
    }
}

fun buildParticleEmitters(nodes: List<StudioNode>, parts: List<Part>): List<ParticleEmitterRenderItem> {
    val nodesById = nodes.associateBy { it.id }
    val partsById = parts.associateBy { it.id }
    return nodes.mapNotNull { node ->
        if (node.className != StudioNode.CLASS_PARTICLE_EMITTER) return@mapNotNull null
        val host = resolveHostPart(node.parentId, nodesById, partsById) ?: return@mapNotNull null
        ParticleEmitterRenderItem(
            id = node.id,
            origin = host.currentPosition,
            colorHex = firstSequenceColor(node.prop("Color")).ifBlank { "#FFFFFF" },
            transparency = firstSequenceNumber(node.prop("Transparency"), 0f).coerceIn(0f, 1f),
            size = firstSequenceNumber(node.prop("Size"), 0.5f).coerceIn(0.05f, 20f),
            count = (node.prop("Rate", "5").toFloatOrNull()?.div(5f)?.toInt() ?: 1).coerceIn(1, 16),
            enabled = node.prop("Enabled", "true").toBooleanStrictOrNull() ?: true
        )
    }
}

fun buildSurfaceGuis(nodes: List<StudioNode>, parts: List<Part>): List<SurfaceGuiRenderItem> {
    val nodesById = nodes.associateBy { it.id }
    val partsById = parts.associateBy { it.id }
    val childrenByParent = nodes.groupBy { it.parentId }
    return nodes.mapNotNull { node ->
        if (node.className != StudioNode.CLASS_SURFACE_GUI) return@mapNotNull null
        val host = resolveHostPart(node.parentId, nodesById, partsById) ?: return@mapNotNull null
        val face = node.prop("Face", "Front")
        val canvas = parseVector2(node.prop("CanvasSize"), 800f, 600f)
        val pixelsPerStud = node.prop("PixelsPerStud", "50").toFloatOrNull()?.coerceAtLeast(1f) ?: 50f
        val localOffset = when (face.lowercase()) {
            "right" -> Vector3(host.size.x / 2f + 0.02f, 0f, 0f)
            "top" -> Vector3(0f, host.size.y / 2f + 0.02f, 0f)
            "back" -> Vector3(0f, 0f, -host.size.z / 2f - 0.02f)
            "left" -> Vector3(-host.size.x / 2f - 0.02f, 0f, 0f)
            "bottom" -> Vector3(0f, -host.size.y / 2f - 0.02f, 0f)
            else -> Vector3(0f, 0f, host.size.z / 2f + 0.02f)
        }
        val textNode = childrenByParent[node.id].orEmpty().firstOrNull {
            it.className in setOf(StudioNode.CLASS_TEXT_LABEL, StudioNode.CLASS_TEXT_BUTTON, StudioNode.CLASS_TEXT_BOX)
        }
        SurfaceGuiRenderItem(
            id = node.id,
            position = host.currentPosition + rotateDirection(localOffset, host.currentRotation),
            rotation = surfaceGuiRotation(face, host.currentRotation),
            width = (canvas.x / pixelsPerStud).coerceIn(0.1f, 100f),
            height = (canvas.y / pixelsPerStud).coerceIn(0.1f, 100f),
            text = textNode?.prop("Text")?.ifBlank { textNode.name } ?: node.name,
            textColorHex = textNode?.prop("TextColor3")?.ifBlank { "#FFFFFF" } ?: "#FFFFFF",
            enabled = node.prop("Enabled", "true").toBooleanStrictOrNull() ?: true
        )
    }
}

private fun attachmentWorldPosition(
    attachmentId: String,
    nodesById: Map<String, StudioNode>,
    partsById: Map<String, Part>
): Vector3? {
    val attachment = nodesById[attachmentId] ?: return null
    val host = resolveHostPart(attachment.parentId, nodesById, partsById) ?: return null
    val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(attachment.prop("CFrame"))
        .mapNotNull { it.value.toFloatOrNull() }
        .toList()
    val local = Vector3(numbers.getOrElse(0) { 0f }, numbers.getOrElse(1) { 0f }, numbers.getOrElse(2) { 0f })
    return host.currentPosition + rotateDirection(local, host.currentRotation)
}

private fun surfaceGuiRotation(face: String, hostRotation: Vector3): Vector3 {
    val faceRotation = when (face.lowercase()) {
        "right" -> Vector3(0f, 90f, 0f)
        "top" -> Vector3(-90f, 0f, 0f)
        "back" -> Vector3(0f, 180f, 0f)
        "left" -> Vector3(0f, -90f, 0f)
        "bottom" -> Vector3(90f, 0f, 0f)
        else -> Vector3.Zero
    }
    return hostRotation + faceRotation
}

private fun parseVector2(value: String, defaultX: Float, defaultY: Float): Vector3 {
    fun named(name: String): Float? = Regex("$name\\s*=\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).mapNotNull { it.value.toFloatOrNull() }.toList()
    return Vector3(named("x") ?: numbers.getOrElse(0) { defaultX }, named("y") ?: numbers.getOrElse(1) { defaultY }, 0f)
}

private fun StudioNode.prop(name: String, default: String = ""): String =
    nodeProperties.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: default

private fun firstSequenceColor(value: String): String =
    Regex("#[0-9a-fA-F]{6}").find(value)?.value ?: value.takeIf { it.startsWith("#") }.orEmpty()

private fun firstSequenceNumber(value: String, default: Float): Float {
    val firstPoint = value.substringBefore(';')
    val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(firstPoint).mapNotNull { it.value.toFloatOrNull() }.toList()
    return when {
        ':' in firstPoint && numbers.size >= 2 -> numbers[1]
        numbers.isNotEmpty() -> numbers[0]
        else -> default
    }
}
