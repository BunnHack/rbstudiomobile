package com.example.models

/**
 * Pure helpers for keeping the Explorer hierarchy and renderable Part list in sync.
 */
object StudioNodeGraph {
    fun syncPartBackedNodes(nodes: List<StudioNode>, parts: List<Part>): List<StudioNode> {
        val partsById = parts.associateBy { it.id }
        val synced = nodes.mapNotNull { node ->
            val partId = node.part?.id ?: return@mapNotNull node
            val latestPart = partsById[partId] ?: return@mapNotNull null
            node.copy(
                name = latestPart.name,
                part = latestPart
            )
        }
        val nodePartIds = synced.mapNotNullTo(mutableSetOf()) { it.part?.id }
        val validNodeIds = synced.mapTo(mutableSetOf()) { it.id }.apply {
            addAll(parts.map { it.id })
        }
        val missingPartNodes = parts
            .filter { it.id !in nodePartIds }
            .map { part ->
                val className = when (part.shape) {
                    Part.SHAPE_SPHERE -> StudioNode.CLASS_BALL_PART
                    Part.SHAPE_WEDGE -> StudioNode.CLASS_WEDGE_PART
                    Part.SHAPE_CORNER_WEDGE -> StudioNode.CLASS_CORNER_WEDGE_PART
                    Part.SHAPE_TRUSS -> StudioNode.CLASS_TRUSS_PART
                    Part.SHAPE_SPAWN_LOCATION -> StudioNode.CLASS_SPAWN_LOCATION
                    else -> StudioNode.CLASS_PART
                }
                val parentId = part.parentId?.takeIf { it in validNodeIds }
                StudioNode(
                    id = part.id,
                    name = part.name,
                    className = className,
                    parentId = parentId,
                    part = part
                )
            }
        return synced + missingPartNodes
    }

    fun collectSubtreeIds(nodes: List<StudioNode>, rootId: String): Set<String> {
        val ids = linkedSetOf(rootId)
        var changed = true
        while (changed) {
            changed = false
            nodes.forEach { node ->
                if (node.parentId in ids && node.id !in ids) {
                    ids += node.id
                    changed = true
                }
            }
        }
        return ids
    }

    fun partIdsForNodes(nodes: List<StudioNode>, nodeIds: Set<String>): Set<String> =
        nodes.filter { it.id in nodeIds }.mapNotNullTo(linkedSetOf()) { it.part?.id }

    fun nodeForPart(part: Part, nodes: List<StudioNode>): StudioNode =
        syncPartBackedNodes(nodes, listOf(part)).firstOrNull { it.part?.id == part.id }
            ?: StudioNode(part.id, part.name, StudioNode.CLASS_PART, part.parentId, part = part)

    fun resolveNode(node: StudioNode?, nodes: List<StudioNode>, parts: List<Part>): StudioNode? {
        if (node == null || node.isService) return node
        val partId = node.part?.id
        if (partId != null) {
            val latestPart = parts.firstOrNull { it.id == partId } ?: return null
            return node.copy(name = latestPart.name, part = latestPart)
        }
        return nodes.firstOrNull { it.id == node.id }
    }
}
