package com.example.ui.explorer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.FolderArchive
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Cylinder
import com.composables.icons.lucide.Triangle
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Box
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.MousePointerClick
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Layers
import com.example.models.Part
import com.example.models.StudioNode

private val SidebarBg = Color(0xFF2C2C2C)
private val SearchBg = Color(0xFF3A3A3A)
private val NodeSelectedBg = Color(0xFF36506B)
private val NodeSelectedText = Color(0xFFFFFFFF)
private val NodeIconColor = Color(0xFFB9D6FF)
private val GuideLineColor = Color.White.copy(alpha = 0.09f)
private val InsertButtonColor = Color(0xFF00A2FF)
private val TitleBarColor = Color(0xFF303030)
private val PopupBg = Color(0xFF222222)
private val PopupBorder = Color(0xFF3E3E3E)
private val SectionHeaderColor = Color(0xFF888888)
private val ItemTextColor = Color(0xFFCCCCCC)

// === Insert Object definitions ===

data class InsertableObject(
    val name: String,
    val icon: ImageVector,
    val iconColor: Color,
    val category: String,
    val className: String
)

private val INSERT_OBJECTS = listOf(
    // Frequently Used
    InsertableObject("Part", Lucide.Box, Color(0xFFAAAAAA), "Frequently Used", StudioNode.CLASS_PART),
    InsertableObject("Sphere", Lucide.Circle, Color(0xFFAAAAAA), "Frequently Used", StudioNode.CLASS_BALL_PART),
    InsertableObject("Cylinder", Lucide.Cylinder, Color(0xFFAAAAAA), "Frequently Used", "CylinderPart"),
    InsertableObject("Wedge", Lucide.Triangle, Color(0xFFAAAAAA), "Frequently Used", StudioNode.CLASS_WEDGE_PART),
    InsertableObject("Script", Lucide.FileCode, Color(0xFFEEEEEE), "Frequently Used", StudioNode.CLASS_SCRIPT),
    InsertableObject("LocalScript", Lucide.FileCode, Color(0xFF9BD5FF), "Frequently Used", StudioNode.CLASS_LOCAL_SCRIPT),
    InsertableObject("Folder", Lucide.Folder, Color(0xFFFFD700), "Frequently Used", StudioNode.CLASS_FOLDER),
    InsertableObject("SpawnLocation", Lucide.Sparkles, Color(0xFFFFD700), "Frequently Used", StudioNode.CLASS_SPAWN_LOCATION),
    InsertableObject("Model", Lucide.Layers, Color(0xFFFF69B4), "Frequently Used", StudioNode.CLASS_MODEL),
    // GUI
    InsertableObject("ScreenGui", Lucide.Square, Color(0xFF69D2FF), "GUI", StudioNode.CLASS_SCREEN_GUI),
    InsertableObject("Frame", Lucide.Square, Color(0xFFB8D8FF), "GUI", StudioNode.CLASS_FRAME),
    InsertableObject("TextLabel", Lucide.Code, Color(0xFFE6E6E6), "GUI", StudioNode.CLASS_TEXT_LABEL),
    InsertableObject("TextButton", Lucide.Code, Color(0xFFFFFFFF), "GUI", StudioNode.CLASS_TEXT_BUTTON),
    InsertableObject("ImageLabel", Lucide.Image, Color(0xFF83C7FF), "GUI", StudioNode.CLASS_IMAGE_LABEL),
    InsertableObject("ImageButton", Lucide.Image, Color(0xFF4DB6FF), "GUI", StudioNode.CLASS_IMAGE_BUTTON),
    // 3D Interfaces
    InsertableObject("Decal", Lucide.Image, Color(0xFF00A2FF), "3D Interfaces", StudioNode.CLASS_DECAL),
    InsertableObject("Texture", Lucide.Image, Color(0xFF00D0A2), "3D Interfaces", StudioNode.CLASS_TEXTURE),
    InsertableObject("Weld", Lucide.Maximize2, Color(0xFFFFA726), "3D Interfaces", StudioNode.CLASS_WELD),
    InsertableObject("ClickDetector", Lucide.MousePointerClick, Color(0xFF00FFAA), "3D Interfaces", "ClickDetector"),
    InsertableObject("ProximityPrompt", Lucide.Hand, Color(0xFF00FFAA), "3D Interfaces", "ProximityPrompt"),
    InsertableObject("Dialog", Lucide.MessageSquare, Color(0xFF00A2FF), "3D Interfaces", "Dialog")
)

// === Tree types ===

sealed class ExplorerEntry {
    abstract val name: String
    abstract val depth: Int

    data class Service(
        override val name: String,
        override val depth: Int,
        val node: StudioNode,
        val icon: ImageVector,
        val expandable: Boolean,
        val children: List<ExplorerEntry>
    ) : ExplorerEntry()

    data class UserNode(
        override val name: String,
        override val depth: Int,
        val node: StudioNode,
        val children: List<ExplorerEntry>
    ) : ExplorerEntry()
}

fun buildExplorerTree(nodes: List<StudioNode>): List<ExplorerEntry> {
    val services = nodes.filter { it.isService }
    val userNodes = nodes.filter { !it.isService }
    val validParentIds = (services.map { it.id } + userNodes.map { it.id }).toSet()
    val byParent = userNodes.groupBy { node ->
        node.parentId?.takeIf { pid -> pid in validParentIds && pid != StudioNode.CLASS_WORKSPACE }
    }

    fun buildChildren(parentId: String?, depth: Int): List<ExplorerEntry> {
        val children = byParent[parentId] ?: emptyList()
        return children.map { node ->
            ExplorerEntry.UserNode(node.name, depth, node, buildChildren(node.id, depth + 1))
        }
    }

    return services.map { svc ->
        val svcChildren = if (svc.className == StudioNode.CLASS_WORKSPACE) buildChildren(null, 1)
                          else buildChildren(svc.id, 1)
        ExplorerEntry.Service(svc.name, 0, svc, iconForService(svc.className), true, svcChildren)
    }
}

private fun iconForService(className: String): ImageVector = when (className) {
    StudioNode.CLASS_WORKSPACE -> Lucide.Globe
    StudioNode.CLASS_REPLICATED_STORAGE -> Lucide.FolderArchive
    StudioNode.CLASS_SERVER_SCRIPT_SERVICE -> Lucide.Server
    StudioNode.CLASS_STARTER_GUI -> Lucide.Square
    StudioNode.CLASS_STARTER_PACK -> Lucide.FolderArchive
    StudioNode.CLASS_LIGHTING -> Lucide.Sun
    StudioNode.CLASS_PLAYERS -> Lucide.Users
    else -> Lucide.Globe
}

private fun colorForService(className: String): Color = when (className) {
    StudioNode.CLASS_WORKSPACE -> Color(0xFF5CC8FF)
    StudioNode.CLASS_REPLICATED_STORAGE -> Color(0xFFE8C86A)
    StudioNode.CLASS_SERVER_SCRIPT_SERVICE -> Color(0xFFE2E2E2)
    StudioNode.CLASS_STARTER_GUI -> Color(0xFF69D2FF)
    StudioNode.CLASS_STARTER_PACK -> Color(0xFFFFD76A)
    StudioNode.CLASS_LIGHTING -> Color(0xFFFFD76A)
    StudioNode.CLASS_PLAYERS -> Color(0xFF80C7FF)
    else -> NodeIconColor
}

private fun iconForNode(node: StudioNode): ImageVector = when {
    node.isFolder -> Lucide.Folder
    node.isModel -> Lucide.Layers
    node.isDecal -> Lucide.Image
    node.isTexture -> Lucide.Image
    node.isWeld -> Lucide.Maximize2
    node.isScript -> Lucide.FileCode
    node.isGuiObject -> when (node.className) {
        StudioNode.CLASS_IMAGE_LABEL, StudioNode.CLASS_IMAGE_BUTTON -> Lucide.Image
        StudioNode.CLASS_TEXT_LABEL, StudioNode.CLASS_TEXT_BUTTON -> Lucide.Code
        else -> Lucide.Square
    }
    node.part != null -> when (node.part.shape) {
        Part.SHAPE_BLOCK -> Lucide.Box
        Part.SHAPE_SPHERE -> Lucide.Circle
        Part.SHAPE_CYLINDER -> Lucide.Cylinder
        Part.SHAPE_WEDGE -> Lucide.Triangle
        else -> Lucide.Square
    }
    else -> Lucide.Square
}

private fun colorForNode(node: StudioNode): Color = when {
    node.isFolder -> Color(0xFFFFD76A)
    node.isModel -> Color(0xFFEF77C8)
    node.isDecal -> Color(0xFF4DB6FF)
    node.isTexture -> Color(0xFF00D0A2)
    node.isWeld -> Color(0xFFFFA726)
    node.isScript -> Color(0xFFEEEEEE)
    node.isGuiObject -> Color(0xFF83C7FF)
    else -> NodeIconColor
}

fun flattenTree(entries: List<ExplorerEntry>, expandedKeys: Set<String>): List<Pair<ExplorerEntry, Boolean>> {
    val result = mutableListOf<Pair<ExplorerEntry, Boolean>>()
    fun walk(entry: ExplorerEntry) {
        when (entry) {
            is ExplorerEntry.Service -> {
                val key = "svc:${entry.name}"
                result.add(entry to entry.children.isNotEmpty())
                if (key in expandedKeys) entry.children.forEach { walk(it) }
            }
            is ExplorerEntry.UserNode -> {
                val key = "node:${entry.node.id}"
                result.add(entry to entry.children.isNotEmpty())
                if (key in expandedKeys) entry.children.forEach { walk(it) }
            }
        }
    }
    entries.forEach { walk(it) }
    return result
}

// === Main ExplorerTree composable ===

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerTree(
    nodes: List<StudioNode>,
    selectedNode: StudioNode?,
    onSelect: (StudioNode?) -> Unit,
    onDuplicate: (StudioNode) -> Unit,
    onCopy: (StudioNode) -> Unit,
    onPaste: (StudioNode?) -> Unit,
    onDelete: (StudioNode) -> Unit,
    onRename: (String, String) -> Unit,
    onInsertObject: (String) -> Unit
) {
    var expandedKeys by remember { mutableStateOf(setOf("svc:Workspace")) }
    var searchQuery by remember { mutableStateOf("") }
    var contextMenuEntry by remember { mutableStateOf<ExplorerEntry?>(null) }
    var showInsertPopup by remember { mutableStateOf(false) }
    var insertPopupAnchorPos by remember { mutableStateOf<Offset?>(null) }
    var renamingPartId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val tree = remember(nodes) { buildExplorerTree(nodes) }
    val flatList = remember(tree, expandedKeys, searchQuery) {
        val all = flattenTree(tree, expandedKeys)
        if (searchQuery.isBlank()) all
        else all.filter { (entry, _) ->
            entry.name.contains(searchQuery, ignoreCase = true) ||
            (entry is ExplorerEntry.UserNode && entry.node.part?.shape?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    fun toggleExpand(entry: ExplorerEntry) {
        val key = when (entry) {
            is ExplorerEntry.Service -> "svc:${entry.name}"
            is ExplorerEntry.UserNode -> "node:${entry.node.id}"
        }
        expandedKeys = if (key in expandedKeys) expandedKeys - key else expandedKeys + key
    }

    Column(modifier = Modifier.fillMaxSize().background(SidebarBg)) {
        // === Title Bar ===
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp).background(TitleBarColor).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(44.dp))
            Text(
                "Explorer",
                color = Color(0xFFE5E5E5),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Row(modifier = Modifier.width(44.dp), horizontalArrangement = Arrangement.End) {
                Box(modifier = Modifier.size(20.dp).clickable { }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Lucide.Maximize2, "Undock", tint = Color(0xFFE0E0E0), modifier = Modifier.size(13.dp))
                }
                Box(modifier = Modifier.size(20.dp).clickable { }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Lucide.X, "Close", tint = Color(0xFFE0E0E0), modifier = Modifier.size(14.dp))
                }
            }
        }

        // === Search Bar + ... button ===
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).background(SidebarBg).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(SearchBg)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Lucide.Search, null, tint = Color(0xFFC8C8C8), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                        cursorBrush = SolidColor(InsertButtonColor),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text("Search", color = Color(0xFFC0C0C0), fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(SearchBg)
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(Lucide.History, "History", tint = Color(0xFFE0E0E0), modifier = Modifier.size(15.dp))
            }
            Spacer(modifier = Modifier.width(5.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SearchBg)
                        .clickable { showOverflowMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(Lucide.Ellipsis, "Options", tint = Color(0xFFE0E0E0), modifier = Modifier.size(17.dp))
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                    modifier = Modifier.background(Color(0xFF2A2A2A))
                ) {
                    DropdownMenuItem(text = { Text("Sort by Name", color = Color.White, fontSize = 11.sp) }, onClick = { showOverflowMenu = false })
                    DropdownMenuItem(text = { Text("Show Hidden", color = Color.White, fontSize = 11.sp) }, onClick = { showOverflowMenu = false })
                    DropdownMenuItem(
                        text = { Text("Expand All", color = Color.White, fontSize = 11.sp) },
                        onClick = {
                            expandedKeys = flatList.mapNotNull { (e, _) -> when (e) {
                                is ExplorerEntry.Service -> "svc:${e.name}"
                                is ExplorerEntry.UserNode -> "node:${e.node.id}"
                            }}.toSet()
                            showOverflowMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Collapse All", color = Color.White, fontSize = 11.sp) },
                        onClick = { expandedKeys = setOf("svc:Workspace"); showOverflowMenu = false }
                    )
                }
            }
        }

        // === Tree list ===
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(SidebarBg)) {
            items(flatList, key = { entryKey(it.first) }) { (entry, hasChildren) ->
                val isSelected = when (entry) {
                    is ExplorerEntry.Service -> selectedNode?.id == entry.node.id
                    is ExplorerEntry.UserNode -> selectedNode?.id == entry.node.id
                }
                val isRenaming = renamingPartId != null && renamingPartId == (entry as? ExplorerEntry.UserNode)?.node?.id

                ExplorerRow(
                    entry = entry,
                    isSelected = isSelected,
                    hasChildren = hasChildren,
                    isExpanded = when (entry) {
                        is ExplorerEntry.Service -> "svc:${entry.name}" in expandedKeys
                        is ExplorerEntry.UserNode -> "node:${entry.node.id}" in expandedKeys
                    },
                    isRenaming = isRenaming,
                    renameText = renameText,
                    onRenameTextChange = { renameText = it },
                    onRenameSubmit = {
                        val pn = entry as? ExplorerEntry.UserNode
                        if (pn != null && renameText.isNotBlank()) onRename(pn.node.id, renameText)
                        renamingPartId = null
                        keyboardController?.hide()
                    },
                    onToggleExpand = { toggleExpand(entry) },
                    onClick = {
                        when (entry) {
                            is ExplorerEntry.UserNode -> onSelect(entry.node)
                            is ExplorerEntry.Service -> onSelect(entry.node)
                        }
                    },
                    onLongClick = { contextMenuEntry = entry },
                    onInsertClick = {
                        when (entry) {
                            is ExplorerEntry.UserNode -> onSelect(entry.node)
                            is ExplorerEntry.Service -> onSelect(entry.node)
                        }
                        showInsertPopup = true
                        insertPopupAnchorPos = null
                    }
                )
            }
        }
    }

    // === Contextual menu (right-click / long-press) ===
    val menuEntry = contextMenuEntry
    if (menuEntry != null) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { contextMenuEntry = null },
            modifier = Modifier.background(Color(0xFF2A2A2A)).width(200.dp)
        ) {
            Text(menuEntry.name, color = InsertButtonColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            Divider(color = Color(0xFF3E3E3E), thickness = 1.dp)

            val targetNode = when (menuEntry) {
                is ExplorerEntry.Service -> menuEntry.node
                is ExplorerEntry.UserNode -> menuEntry.node
            }
            val userNode = (menuEntry as? ExplorerEntry.UserNode)?.node
            val isPart = userNode?.part != null
            val part = userNode?.part
            val canEditUserNode = userNode != null && part?.id != "baseplate"

            if (isPart && part?.id != "baseplate") {
                DropdownMenuItem(text = { MenuRow(Lucide.Copy, "Copy") }, onClick = { userNode?.let(onCopy); contextMenuEntry = null })
                DropdownMenuItem(text = { MenuRow(Lucide.Clipboard, "Paste") }, onClick = { onPaste(userNode); contextMenuEntry = null })
                DropdownMenuItem(text = { MenuRow(Lucide.Box, "Duplicate") }, onClick = { userNode?.let(onDuplicate); contextMenuEntry = null })
                Divider(color = Color(0xFF3E3E3E), thickness = 0.5.dp)
            }

            if (canEditUserNode) {
                DropdownMenuItem(
                    text = { MenuRow(Lucide.Pencil, "Rename") },
                    onClick = {
                        renamingPartId = userNode?.id
                        renameText = userNode?.name.orEmpty()
                        contextMenuEntry = null
                    }
                )
            }

            DropdownMenuItem(
                text = { MenuRow(Lucide.Plus, "Insert Object") },
                onClick = {
                    onSelect(targetNode)
                    contextMenuEntry = null
                    showInsertPopup = true
                }
            )

            if (canEditUserNode) {
                Divider(color = Color(0xFF3E3E3E), thickness = 0.5.dp)
                DropdownMenuItem(
                    text = { MenuRow(Lucide.Trash2, "Delete", tint = Color(0xFFE53935)) },
                    onClick = { userNode?.let(onDelete); contextMenuEntry = null }
                )
            }
        }
    }

    // === Insert Object popup ===
    if (showInsertPopup) {
        InsertObjectPopup(
            onDismiss = { showInsertPopup = false },
            onInsert = { obj ->
                // Pass className to ViewModel — it handles Part vs Folder/Model/Script
                onInsertObject(obj.className)
                showInsertPopup = false
            }
        )
    }
}

// === Insert Object Popup — Roblox Studio style ===

@Composable
private fun InsertObjectPopup(
    onDismiss: () -> Unit,
    onInsert: (InsertableObject) -> Unit
) {
    var search by remember { mutableStateOf("") }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        alignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(200.dp)
                .heightIn(max = 320.dp)
                .background(PopupBg)
                .border(1.dp, PopupBorder)
                .clip(RoundedCornerShape(2.dp))
        ) {
            // === Search row ===
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp).background(PopupBg).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 10.sp, color = Color.White),
                    cursorBrush = SolidColor(InsertButtonColor),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(SearchBg, RoundedCornerShape(2.dp))
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (search.isEmpty()) {
                                Text("Search object", color = Color(0xFF555555), fontSize = 10.sp)
                            }
                            innerTextField()
                        }
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                // List/grid toggle icon
                Box(modifier = Modifier.size(20.dp).clickable { }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Lucide.Layers, null, tint = Color(0xFF666666), modifier = Modifier.size(14.dp))
                }
                // Thin divider
                Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0xFF3E3E3E)))
                Spacer(modifier = Modifier.width(4.dp))
                // ... options icon
                Box(modifier = Modifier.size(20.dp).clickable { }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Lucide.Ellipsis, null, tint = Color(0xFF666666), modifier = Modifier.size(14.dp))
                }
            }

            Divider(color = Color(0xFF3E3E3E), thickness = 1.dp)

            // === Filtered object list ===
            val filtered = if (search.isBlank()) INSERT_OBJECTS
                else INSERT_OBJECTS.filter { it.name.contains(search, ignoreCase = true) }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found", color = Color(0xFF666666), fontSize = 10.sp)
                }
            } else {
                // Group by category
                val grouped = filtered.groupBy { it.category }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    grouped.forEach { (category, items) ->
                        item(key = "header_$category") {
                            Text(
                                text = category,
                                color = SectionHeaderColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        items(items, key = { it.name }) { obj ->
                            InsertObjectRow(obj) { onInsert(obj) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsertObjectRow(obj: InsertableObject, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = obj.icon,
            contentDescription = null,
            tint = obj.iconColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = obj.name,
            color = ItemTextColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// === Helpers ===

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = Color.LightGray) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

private fun entryKey(entry: ExplorerEntry): String = when (entry) {
    is ExplorerEntry.Service -> "svc:${entry.name}:${entry.depth}"
    is ExplorerEntry.UserNode -> "node:${entry.node.id}"
}

// === Explorer Row ===

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerRow(
    entry: ExplorerEntry,
    isSelected: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    isRenaming: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onRenameSubmit: () -> Unit,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInsertClick: () -> Unit
) {
    val depth = entry.depth
    val icon = when (entry) {
        is ExplorerEntry.Service -> entry.icon
        is ExplorerEntry.UserNode -> iconForNode(entry.node)
    }
    val iconColor = when (entry) {
        is ExplorerEntry.Service -> colorForService(entry.node.className)
        is ExplorerEntry.UserNode -> colorForNode(entry.node)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(if (isSelected) NodeSelectedBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tree guide lines
        if (depth > 0) {
            Canvas(modifier = Modifier.width((depth * 14).dp).height(22.dp)) {
                val indent = 14.dp.toPx()
                for (d in 0 until depth) {
                    val x = (d * indent) + indent / 2f
                    drawLine(GuideLineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                val parentX = ((depth - 1) * indent) + indent / 2f
                drawLine(GuideLineColor, Offset(parentX, size.height / 2f), Offset(depth * indent, size.height / 2f), 1.dp.toPx())
            }
        }

        // Solid triangle expand/collapse (8dp)
        if (hasChildren) {
            Box(modifier = Modifier.size(14.dp).clickable { onToggleExpand() }, contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    val w = size.width; val h = size.height; val p = Path()
                    if (isExpanded) {
                        p.moveTo(0f, h * 0.2f); p.lineTo(w, h * 0.2f); p.lineTo(w * 0.5f, h * 0.8f)
                    } else {
                        p.moveTo(w * 0.2f, 0f); p.lineTo(w * 0.2f, h); p.lineTo(w * 0.8f, h * 0.5f)
                    }
                    p.close()
                    drawPath(p, color = Color(0xFF999999), style = Fill)
                }
            }
        } else {
            Spacer(modifier = Modifier.width(14.dp))
        }

        // Icon
        androidx.compose.material3.Icon(icon, null, tint = iconColor, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(5.dp))

        // Name / rename
        if (isRenaming) {
            BasicTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                cursorBrush = SolidColor(InsertButtonColor),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.height(22.dp).weight(1f)
                            .background(Color(0xFF141414), RoundedCornerShape(2.dp))
                            .border(1.dp, InsertButtonColor, RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) { innerTextField() }
                },
                keyboardActions = KeyboardActions(onDone = { onRenameSubmit() })
            )
        } else {
            Text(
                entry.name,
                color = if (isSelected) NodeSelectedText else Color(0xFFE0E0E0),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (isSelected) {
            Box(modifier = Modifier.size(20.dp).clickable { onInsertClick() }, contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(Lucide.Plus, "Insert", tint = InsertButtonColor.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
            }
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }
    }
}
