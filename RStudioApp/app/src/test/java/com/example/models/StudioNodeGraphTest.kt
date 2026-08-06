package com.example.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioNodeGraphTest {
    @Test
    fun syncPartBackedNodesRefreshesPartDataAndDropsMissingParts() {
        val folder = StudioNode("folder", "Folder", StudioNode.CLASS_FOLDER)
        val stalePart = Part("part-1", "OldPart")
        val partNode = StudioNode("node-1", "OldPart", StudioNode.CLASS_PART, "folder", stalePart)
        val missingPartNode = StudioNode("node-2", "MissingPart", StudioNode.CLASS_PART, "folder", Part("missing", "MissingPart"))

        val synced = StudioNodeGraph.syncPartBackedNodes(
            nodes = listOf(folder, partNode, missingPartNode),
            parts = listOf(stalePart.copy(name = "UpdatedPart"))
        )

        assertEquals(listOf("folder", "node-1"), synced.map { it.id })
        val syncedPartNode = synced.first { it.id == "node-1" }
        assertEquals("UpdatedPart", syncedPartNode.name)
        assertEquals("UpdatedPart", syncedPartNode.part?.name)
    }

    @Test
    fun collectSubtreeIdsIncludesNestedDescendantsOnly() {
        val nodes = listOf(
            StudioNode("folder", "Folder", StudioNode.CLASS_FOLDER),
            StudioNode("model", "Model", StudioNode.CLASS_MODEL, parentId = "folder"),
            StudioNode("part-node", "Part", StudioNode.CLASS_PART, parentId = "model", part = Part("part-1", "Part")),
            StudioNode("sibling", "Sibling", StudioNode.CLASS_FOLDER)
        )

        val ids = StudioNodeGraph.collectSubtreeIds(nodes, "folder")

        assertEquals(setOf("folder", "model", "part-node"), ids)
        assertEquals(setOf("part-1"), StudioNodeGraph.partIdsForNodes(nodes, ids))
    }

    @Test
    fun nodeForPartUsesExistingNodeWhenAvailable() {
        val latestPart = Part("part-1", "LatestName")
        val existingNode = StudioNode("node-1", "OldName", StudioNode.CLASS_WEDGE_PART, part = latestPart.copy(name = "OldName"))

        val node = StudioNodeGraph.nodeForPart(latestPart, listOf(existingNode))

        assertEquals("node-1", node.id)
        assertEquals("LatestName", node.name)
        assertEquals("LatestName", node.part?.name)
    }

    @Test
    fun explicitPartNodePreventsFallbackDuplicate() {
        val part = Part("part-1", "Part")
        val explicitNode = StudioNode(
            id = "node-1",
            name = "Part",
            className = StudioNode.CLASS_TRUSS_PART,
            parentId = "folder",
            part = part,
            nodeProperties = mapOf("Style" to "0")
        )

        val synced = StudioNodeGraph.syncPartBackedNodes(listOf(explicitNode), listOf(part))

        assertEquals(1, synced.count { it.part?.id == part.id })
        assertEquals("node-1", synced.single().id)
        assertEquals(StudioNode.CLASS_TRUSS_PART, synced.single().className)
        assertEquals("folder", synced.single().parentId)
    }

    @Test
    fun resolveNodeReturnsNullForDeletedNonPartNode() {
        val deletedFolder = StudioNode("folder", "Folder", StudioNode.CLASS_FOLDER)

        val resolved = StudioNodeGraph.resolveNode(
            node = deletedFolder,
            nodes = emptyList(),
            parts = emptyList()
        )

        assertNull(resolved)
    }
}
