package com.example.ui.viewport

import com.example.models.Part
import com.example.models.StudioNode

data class HighlightRenderItem(
    val id: String,
    val targetPartId: String,
    val enabled: Boolean,
    val alwaysOnTop: Boolean,
    val fillColorHex: String,
    val fillTransparency: Float,
    val outlineColorHex: String,
    val outlineTransparency: Float
)

fun buildHighlights(nodes: List<StudioNode>, parts: List<Part>): List<HighlightRenderItem> {
    val nodesById = nodes.associateBy { it.id }
    val partsById = parts.associateBy { it.id }
    return nodes.filter { it.className == StudioNode.CLASS_HIGHLIGHT }.mapNotNull { node ->
        val reference = node.highlightProp("Adornee", "").takeIf { it.isNotBlank() && it != "-1" } ?: node.parentId
        val part = resolveHostPart(reference, nodesById, partsById) ?: return@mapNotNull null
        HighlightRenderItem(
            id = node.id,
            targetPartId = part.id,
            enabled = node.highlightProp("Enabled", "true").toBooleanStrictOrNull() ?: true,
            alwaysOnTop = node.highlightProp("DepthMode", "AlwaysOnTop").lowercase() !in setOf("occluded", "1"),
            fillColorHex = node.highlightProp("FillColor", "#FF0000"),
            fillTransparency = node.highlightProp("FillTransparency", "0.5").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f,
            outlineColorHex = node.highlightProp("OutlineColor", "#FFFFFF"),
            outlineTransparency = node.highlightProp("OutlineTransparency", "0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        )
    }
}

private fun StudioNode.highlightProp(name: String, default: String): String =
    nodeProperties.entries.firstOrNull { it.key.equals(name, true) }?.value ?: default
