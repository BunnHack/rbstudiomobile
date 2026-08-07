package com.example.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3
import com.example.toolbox.ToolboxAsset
import com.example.toolbox.ToolboxAssetType
import com.example.toolbox.ToolboxAssetTypes
import com.example.toolbox.ToolboxSearchState
import com.example.ui.viewport.StudioViewport
import com.example.viewmodels.StudioViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Move3d
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Trash2
import com.example.ui.explorer.ExplorerTree
import com.example.ui.properties.GridRow
import com.example.ui.properties.CollapsibleVector3Row
import com.example.ui.properties.CompactNumberInput
import com.example.ui.properties.CompactTextInput
import com.example.ui.properties.GridDropdownRow
import com.example.ui.properties.GridSwitchRow
import com.example.ui.properties.GridSliderRow
import com.example.ui.properties.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val toolboxThumbnailHttpClient = OkHttpClient()
private const val VIEWPORT_DOCUMENT_TAB_ID = "viewport"

private enum class ScriptDocumentKind {
    PART,
    NODE
}

private data class StudioScriptDocument(
    val id: String,
    val title: String,
    val targetPath: String,
    val initialSource: String,
    val targetKind: ScriptDocumentKind,
    val targetId: String
)

/** Holds a script document's editable text outside composition so it survives
 *  tab switches, recomposition, and editor teardown. */
class ScriptEditorState(initial: String) {
    var code by mutableStateOf(initial)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: StudioViewModel) {
    val activePlace by viewModel.activePlace.collectAsState()
    val parts by viewModel.parts.collectAsState()
    val selectedPart by viewModel.selectedPart.collectAsState()

    val activeTab by viewModel.activeTab.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val consoleLogs by viewModel.consoleLogs.collectAsState()
    val toolboxState by viewModel.toolboxState.collectAsState()
    val roblosecurityCookie by viewModel.roblosecurityCookie.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val explorerNodes by viewModel.explorerNodes.collectAsState()

    // Screen dimension classification
    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 640

    // Side panel visibility states
    var showLeftPanel by remember { mutableStateOf(isExpanded) }
    var showRightPanel by remember { mutableStateOf(isExpanded) }
    var leftPanelTab by remember { mutableStateOf(0) } // 0: Toolbox, 1: Console Output
    var openScriptDocuments by remember { mutableStateOf<List<StudioScriptDocument>>(emptyList()) }
    var activeDocumentTabId by remember { mutableStateOf(VIEWPORT_DOCUMENT_TAB_ID) }
    // Editable sources live at the screen level so switching to the Viewport tab and
    // back does not reset the editor to the part's last-saved script.
    val scriptDocumentStates = remember { mutableMapOf<String, ScriptEditorState>() }
    var showGameSettings by remember { mutableStateOf(false) }
    var showPublishDialog by remember { mutableStateOf(false) }

    fun openScriptDocumentForPart(part: Part) {
        val document = StudioScriptDocument(
            id = "part:${part.id}",
            title = "${part.name}.lua",
            targetPath = "Workspace.${part.name}",
            initialSource = part.script,
            targetKind = ScriptDocumentKind.PART,
            targetId = part.id
        )
        if (openScriptDocuments.none { it.id == document.id }) {
            openScriptDocuments = openScriptDocuments + document
        }
        activeDocumentTabId = document.id
    }

    fun openScriptDocumentForNode(node: StudioNode) {
        val source = node.scriptSource.ifBlank { node.nodeProperties["Source"].orEmpty() }
        val document = StudioScriptDocument(
            id = "node:${node.id}",
            title = "${node.name}.lua",
            targetPath = "Workspace.${node.name}",
            initialSource = source,
            targetKind = ScriptDocumentKind.NODE,
            targetId = node.id
        )
        if (openScriptDocuments.none { it.id == document.id }) {
            openScriptDocuments = openScriptDocuments + document
        }
        activeDocumentTabId = document.id
    }

    fun closeScriptDocument(documentId: String) {
        openScriptDocuments = openScriptDocuments.filterNot { it.id == documentId }
        scriptDocumentStates.remove(documentId)
        if (activeDocumentTabId == documentId) {
            activeDocumentTabId = VIEWPORT_DOCUMENT_TAB_ID
        }
    }

    // File picker for importing Roblox .rbxm/.rbxl/.rbxmx/.rbxlx files.
    val context = androidx.compose.ui.platform.LocalContext.current
    val robloxFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    // Extract a readable filename from the URI (fallback to "imported")
                    val fileName = runCatching {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
                    viewModel.importRobloxFile(bytes, fileName)
                }
            }.onFailure { viewModel.logSystem("❌ Failed to read file: ${it.message}") }
        }
    }
    val openRobloxFilePicker = {
        robloxFileLauncher.launch(arrayOf(
            "application/octet-stream",
            "application/xml",
            "text/xml",
            "*/*"
        ))
    }

    // File saver for exporting the active place as a binary .rbxl file.
    var pendingRbxlExport by remember { mutableStateOf<ByteArray?>(null) }
    val rbxlExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingRbxlExport
        pendingRbxlExport = null
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                } ?: error("Could not open output stream")
            }.onSuccess {
                viewModel.logSystem("● Exported ${bytes.size} bytes to .rbxl file.")
            }.onFailure {
                viewModel.logSystem("❌ Failed to export .rbxl: ${it.message}")
            }
        }
    }
    val exportActivePlaceAsRbxl = {
        val export = viewModel.exportActivePlaceAsRbxl()
        if (export == null) {
            viewModel.logSystem("❌ Export failed: no active place is open.")
        } else {
            pendingRbxlExport = export.second
            rbxlExportLauncher.launch(export.first)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudioMenuBar(
                onNew = {
                    viewModel.closePlace()
                    viewModel.logSystem("File: New place requested from launcher.")
                },
                onOpenFile = openRobloxFilePicker,
                onClosePlace = { viewModel.closePlace() },
                onSave = { viewModel.savePlace() },
                onExportRbxl = exportActivePlaceAsRbxl,
                onPublish = { showPublishDialog = true },
                onGameSettings = { showGameSettings = true },
                onGenericAction = { viewModel.logSystem("File: $it") }
            )

            // 1. Quick Access Bar / File Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161616))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Roblox studio tilted cube icon
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                            .clickable { viewModel.closePlace() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF161616), RoundedCornerShape(1.dp))
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activePlace?.name ?: "Studio Editor",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))

                    // Quick buttons
                    IconButton(onClick = { viewModel.savePlace() }, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Save", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { viewModel.undo() }, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Undo, contentDescription = "Undo", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { viewModel.redo() }, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Redo, contentDescription = "Redo", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Play/Stop simulation
                    Button(
                        onClick = { viewModel.togglePlaySimulation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) Color(0xFFD32F2F) else Color(0xFF00C853)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("play_simulation_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isPlaying) "Stop" else "Play", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(onClick = { viewModel.closePlace() }, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Divider(color = Color(0xFF2C2C2C), thickness = 1.dp)

            // 2. TABS RIBBON BAR (Home, Model, Test, View)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242424))
            ) {
                // Tab Selection Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf("Home", "Model", "Avatar", "Test", "View", "Plugins")
                    tabs.forEach { tab ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (selected) Color(0xFF2E2E2E) else Color.Transparent)
                                .clickable { viewModel.setActiveTab(tab) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = tab,
                                color = if (selected) Color(0xFF00A2FF) else Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Tab Content Ribbon (Action Bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2E2E2E))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (activeTab) {
                        "Home" -> {
                            // Clipboard
                            RibbonGroup(title = "Clipboard") {
                                IconButtonWithLabel(icon = Icons.Default.ContentPaste, label = "Paste", onClick = {
                                    viewModel.duplicateSelectedPart()
                                    viewModel.logSystem("Clipboard: Pasted part.")
                                })
                                IconButtonWithLabel(icon = Icons.Default.ContentCopy, label = "Copy", onClick = {
                                    viewModel.logSystem("Clipboard: Copied selection reference.")
                                })
                                IconButtonWithLabel(icon = Icons.Default.ContentCut, label = "Cut", onClick = {
                                    selectedPart?.let {
                                        viewModel.duplicateSelectedPart()
                                        viewModel.deleteSelectedPart()
                                        viewModel.logSystem("Clipboard: Cut selection Workspace.${it.name}")
                                    }
                                })
                            }
                            // Tools
                            RibbonGroup(title = "Tools") {
                                ToolToggleButton(icon = Icons.Default.TouchApp, label = "Select", active = activeTool == "SELECT", onClick = { viewModel.setActiveTool("SELECT") })
                                ToolToggleButton(icon = Icons.Default.OpenWith, label = "Move", active = activeTool == "MOVE", onClick = { viewModel.setActiveTool("MOVE") })
                                ToolToggleButton(icon = Icons.Default.AspectRatio, label = "Scale", active = activeTool == "SCALE", onClick = { viewModel.setActiveTool("SCALE") })
                                ToolToggleButton(icon = Icons.Default.Autorenew, label = "Rotate", active = activeTool == "ROTATE", onClick = { viewModel.setActiveTool("ROTATE") })
                            }
                            // Terrain
                            RibbonGroup(title = "Terrain") {
                                IconButtonWithLabel(icon = Icons.Default.Landscape, label = "Editor", onClick = {
                                    viewModel.logSystem("Terrain: Loaded custom voxel terrain generator.")
                                })
                            }
                            // Insert Part
                            RibbonGroup(title = "Insert") {
                                PartDropdownButton(onSpawn = { shape -> viewModel.addPart(shape) })
                                IconButtonWithLabel(icon = Icons.Default.Category, label = "Toolbox", onClick = {
                                    showLeftPanel = true
                                    viewModel.logSystem("Toolbox: Sidebar toggled visible.")
                                })
                            }
                            // Quick Edit colors & materials
                            RibbonGroup(title = "Edit & Style") {
                                ColorDropdownButton(
                                    currentColorHex = selectedPart?.colorHex ?: "#00A2FF",
                                    hasSelection = selectedPart != null,
                                    onColorSelected = { colorHex -> viewModel.changeColorSelectedPart(colorHex) }
                                )
                                var materialExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButtonWithLabel(icon = Icons.Default.Texture, label = "Material", onClick = { materialExpanded = true })
                                    DropdownMenu(
                                        expanded = materialExpanded,
                                        onDismissRequest = { materialExpanded = false },
                                        modifier = Modifier.background(Color(0xFF222222))
                                    ) {
                                        val materials = listOf(Part.MATERIAL_PLASTIC, Part.MATERIAL_SLATE, Part.MATERIAL_NEON, Part.MATERIAL_WOOD, Part.MATERIAL_BRICK, Part.MATERIAL_METAL, Part.MATERIAL_GLASS)
                                        materials.forEach { mat ->
                                            DropdownMenuItem(
                                                text = { Text(mat, color = Color.White, fontSize = 11.sp) },
                                                onClick = {
                                                    selectedPart?.let {
                                                        viewModel.updatePartProperty(it.copy(material = mat))
                                                        viewModel.logSystem("Set Workspace.${it.name} material to $mat")
                                                    }
                                                    materialExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            // Anchor toggles
                            RibbonGroup(title = "Attributes") {
                                val isAnchored = selectedPart?.anchored ?: true
                                IconButtonWithLabel(
                                    icon = if (isAnchored) Icons.Default.Anchor else Icons.Default.LinkOff,
                                    label = if (isAnchored) "Anchored" else "Physical",
                                    tint = if (isAnchored) Color(0xFF00A2FF) else Color.Gray,
                                    onClick = { viewModel.anchorSelectedPart(!isAnchored) }
                                )
                                IconButtonWithLabel(icon = Icons.Default.Layers, label = "Lock", tint = Color.Gray, onClick = {
                                    viewModel.logSystem("Attributes: Locked Workspace selection to grid.")
                                })
                            }
                            // Test controls
                            RibbonGroup(title = "Test simulation") {
                                IconButtonWithLabel(
                                    icon = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    label = if (isPlaying) "Stop" else "Play",
                                    tint = if (isPlaying) Color.Red else Color(0xFF00C853),
                                    onClick = { viewModel.togglePlaySimulation() }
                                )
                            }
                            // Settings
                            RibbonGroup(title = "Settings") {
                                IconButtonWithLabel(icon = Icons.Default.Settings, label = "Game Settings", onClick = {
                                    showGameSettings = true
                                })
                            }
                        }
                        "Model" -> {
                            // Tools
                            RibbonGroup(title = "Tools") {
                                ToolToggleButton(icon = Icons.Default.TouchApp, label = "Select", active = activeTool == "SELECT", onClick = { viewModel.setActiveTool("SELECT") })
                                ToolToggleButton(icon = Icons.Default.OpenWith, label = "Move", active = activeTool == "MOVE", onClick = { viewModel.setActiveTool("MOVE") })
                                ToolToggleButton(icon = Icons.Default.AspectRatio, label = "Scale", active = activeTool == "SCALE", onClick = { viewModel.setActiveTool("SCALE") })
                                ToolToggleButton(icon = Icons.Default.Autorenew, label = "Rotate", active = activeTool == "ROTATE", onClick = { viewModel.setActiveTool("ROTATE") })
                                IconButtonWithLabel(icon = Icons.Default.Transform, label = "Transform", onClick = {
                                    viewModel.setActiveTool("MOVE")
                                    viewModel.logSystem("Model: Custom transform grid activated.")
                                })
                            }
                            // Pivot & Positioning snap properties
                            RibbonGroup(title = "Snapping") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.GridOn, contentDescription = "Snap", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("0.5 Studs Grid", color = Color.LightGray, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Snap angle", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("15° Rotation", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                            // Pivot
                            RibbonGroup(title = "Pivot") {
                                IconButtonWithLabel(icon = Icons.Default.CenterFocusStrong, label = "Edit Pivot", onClick = { viewModel.logSystem("Pivot: Selection pivot centered.") })
                                IconButtonWithLabel(icon = Icons.Default.Close, label = "Clear Pivot", onClick = { viewModel.logSystem("Pivot: Pivot offsets cleared.") })
                            }
                            // Solid Modeling
                            RibbonGroup(title = "Solid Modeling") {
                                IconButtonWithLabel(icon = Icons.Default.Construction, label = "Union", onClick = { viewModel.logSystem("Modeling: Union created from workspace selection.") })
                                IconButtonWithLabel(icon = Icons.Default.LinkOff, label = "Negate", onClick = { viewModel.logSystem("Modeling: Subtraction negate applied.") })
                                IconButtonWithLabel(icon = Icons.Default.FlipToFront, label = "Separate", onClick = { viewModel.logSystem("Modeling: Disassembled union components.") })
                            }
                            // Constraints
                            RibbonGroup(title = "Constraints") {
                                IconButtonWithLabel(icon = Icons.Default.Link, label = "Weld", onClick = { viewModel.logSystem("Constraints: Weld joint constraint created.") })
                                IconButtonWithLabel(icon = Icons.Default.SyncAlt, label = "Hinge", onClick = { viewModel.logSystem("Constraints: Hinge rotation point created.") })
                            }
                            // Gameplay preset builders
                            RibbonGroup(title = "Gameplay") {
                                IconButtonWithLabel(icon = Icons.Default.Flag, label = "SpawnPoint", onClick = { viewModel.addPart(Part.SHAPE_SPAWN_LOCATION) })
                                IconButtonWithLabel(icon = Icons.Default.LocalFireDepartment, label = "Fire", onClick = { viewModel.addEffectToSelectedPart(Part.EFFECT_FIRE) })
                                IconButtonWithLabel(icon = Icons.Default.Cloud, label = "Smoke", onClick = { viewModel.addEffectToSelectedPart(Part.EFFECT_SMOKE) })
                                IconButtonWithLabel(icon = Icons.Default.Star, label = "Sparkles", onClick = { viewModel.addEffectToSelectedPart(Part.EFFECT_SPARKLES) })
                            }
                            // Advanced
                            RibbonGroup(title = "Advanced Scripts") {
                                IconButtonWithLabel(icon = Icons.Default.Code, label = "Script", onClick = {
                                    viewModel.insertObjectByClass(StudioNode.CLASS_SCRIPT)
                                })
                                IconButtonWithLabel(icon = Icons.Default.CodeOff, label = "LocalScript", onClick = {
                                    viewModel.insertObjectByClass(StudioNode.CLASS_LOCAL_SCRIPT)
                                })
                            }
                        }
                        "Avatar" -> {
                            RibbonGroup(title = "Tools") {
                                IconButtonWithLabel(icon = Icons.Default.Accessibility, label = "Rig Builder", onClick = { viewModel.logSystem("Avatar: Rig Builder initialized. Choose R15 / R6 archetype.") })
                                IconButtonWithLabel(icon = Icons.Default.DirectionsRun, label = "Animation Editor", onClick = { viewModel.logSystem("Avatar: Opened Keyframe Animation Timeline.") })
                            }
                            RibbonGroup(title = "Accessories") {
                                IconButtonWithLabel(icon = Icons.Default.ShoppingBag, label = "Accessory", onClick = { viewModel.logSystem("Avatar: Accessory attachment editor loaded.") })
                                IconButtonWithLabel(icon = Icons.Default.SquareFoot, label = "Rig Scale", onClick = { viewModel.logSystem("Avatar: Opened standard avatar size factors.") })
                            }
                        }
                        "Test" -> {
                            // Simulation tools
                            RibbonGroup(title = "Simulation") {
                                IconButtonWithLabel(icon = Icons.Default.PlayArrow, label = "Play", tint = Color(0xFF00C853), onClick = { viewModel.togglePlaySimulation() })
                                IconButtonWithLabel(icon = Icons.Default.RotateLeft, label = "Reset", onClick = { viewModel.togglePlaySimulation() })
                                IconButtonWithLabel(icon = Icons.Default.Pause, label = "Pause", onClick = { viewModel.logSystem("Simulation: Physics loop paused.") })
                            }
                            RibbonGroup(title = "Clients and Servers") {
                                IconButtonWithLabel(icon = Icons.Default.Dns, label = "Local Server", onClick = { viewModel.logSystem("Server: Localhost Server online on port 53821.") })
                                IconButtonWithLabel(icon = Icons.Default.CleaningServices, label = "Cleanup", onClick = { viewModel.logSystem("Server: Cleared all client processes.") })
                            }
                            RibbonGroup(title = "Audio") {
                                IconButtonWithLabel(icon = Icons.Default.VolumeOff, label = "Mute", onClick = { viewModel.logSystem("Audio: Sounds muted.") })
                            }
                        }
                        "View" -> {
                            // Custom viewport toggles
                            RibbonGroup(title = "Interface Windows") {
                                TogglePanelButton(active = showLeftPanel, label = "Toolbox/Log", icon = Icons.Default.SettingsInputAntenna) { showLeftPanel = !showLeftPanel }
                                TogglePanelButton(active = showRightPanel, label = "Explorer/Prop", icon = Icons.Default.ViewSidebar) { showRightPanel = !showRightPanel }
                            }
                            // Render details
                            RibbonGroup(title = "3D Graphics") {
                                val grid = viewModel.showGrid.collectAsState().value
                                val studs = viewModel.gridMaterial.collectAsState().value
                                val wire = viewModel.wireframe.collectAsState().value
                                
                                IconButtonWithLabel(icon = Icons.Default.Grid4x4, label = "Grid", tint = if (grid) Color(0xFF00A2FF) else Color.Gray) { viewModel.toggleGrid(!grid) }
                                IconButtonWithLabel(icon = Icons.Default.LensBlur, label = "Studs", tint = if (studs) Color(0xFF00A2FF) else Color.Gray) { viewModel.toggleGridMaterial(!studs) }
                                IconButtonWithLabel(icon = Icons.Default.BlurOn, label = "Wireframe", tint = if (wire) Color(0xFF00A2FF) else Color.Gray) { viewModel.toggleWireframe(!wire) }
                            }
                            // Camera utilities
                            RibbonGroup(title = "Camera Tool") {
                                IconButtonWithLabel(icon = Icons.Default.CenterFocusStrong, label = "Reset View", onClick = { viewModel.resetCamera() })
                            }
                            // Stats
                            RibbonGroup(title = "Diagnostics") {
                                IconButtonWithLabel(icon = Icons.Default.BarChart, label = "Performance Summary", onClick = {
                                    viewModel.logSystem("Diagnostics: Framerate: 60 FPS, Draw calls: 148, Triangle count: 1842.")
                                })
                            }
                        }
                        "Plugins" -> {
                            // High level plugins
                            RibbonGroup(title = "Automation") {
                                Button(
                                    onClick = {
                                        viewModel.addPart(Part.SHAPE_BLOCK)
                                        viewModel.logSystem("Auto-Plugin: Generated floor pad.")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Add Floor Pad", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        viewModel.logSystem("Plugins: Plugin directory loaded from project root.")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Plugins Folder", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 3. MAIN WORKSPACE AREA (Split Layout)
            Row(modifier = Modifier.weight(1f)) {
                // LEFT SIDE BAR: Toolbox & Console Output
                AnimatedVisibility(
                    visible = showLeftPanel,
                    enter = slideInHorizontally() + fadeIn(),
                    exit = slideOutHorizontally() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (isExpanded) 240.dp else 180.dp)
                            .background(Color(0xFF1B1B1B))
                            .border(BorderStroke(0.5.dp, Color(0xFF2E2E2E)))
                    ) {
                        // Segmented tabs for Left panel
                        TabRow(
                            selectedTabIndex = leftPanelTab,
                            containerColor = Color(0xFF1F1F1F),
                            contentColor = Color(0xFF00A2FF)
                        ) {
                            Tab(selected = leftPanelTab == 0, onClick = { leftPanelTab = 0 }) {
                                Text("Toolbox", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp), color = if (leftPanelTab == 0) Color.White else Color.Gray)
                            }
                            Tab(selected = leftPanelTab == 1, onClick = { leftPanelTab = 1 }) {
                                Text("Output", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp), color = if (leftPanelTab == 1) Color.White else Color.Gray)
                            }
                        }

                        // Left Tab Contents
                        if (leftPanelTab == 0) {
                            // Toolbox: Add high fidelity prefabs
                            ToolboxContent(
                                state = toolboxState,
                                onQueryChange = { viewModel.setToolboxSearchQuery(it) },
                                onAuthCookieChange = { viewModel.setToolboxRoblosecurityCookie(it) },
                                onAssetTypeSelected = { viewModel.setToolboxAssetType(it) },
                                onRefresh = { viewModel.refreshToolbox() },
                                onLoadMore = { viewModel.loadMoreToolboxResults() },
                                onInsertAsset = { viewModel.insertToolboxAsset(it) },
                                onInsertModel = { viewModel.insertToolboxModel(it) },
                                onUploadFile = {
                                    openRobloxFilePicker()
                                }
                            )
                        } else {
                            // Output Console: Print lists of simulation events
                            ConsoleOutputContent(logs = consoleLogs, onClear = { viewModel.clearLogs() })
                        }
                    }
                }

                // CENTER: document tabs + 3D viewport / script editor
                // key() on the active document tab: switching between Viewport and a
                // script document replaces this subtree entirely, so neither the kool
                // viewport nor a script editor can inherit a stale measured/layout
                // state from the other (this was the remaining source of the clipped
                // editor and of the viewport re-attaching into a half-disposed node).
                androidx.compose.runtime.key(activeDocumentTabId) {
                ViewportDocumentArea(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    viewModel = viewModel,
                    parts = parts,
                    explorerNodes = explorerNodes,
                    selectedNode = selectedNode,
                    roblosecurityCookie = roblosecurityCookie,
                    activeTool = activeTool,
                    isPlaying = isPlaying,
                    isExpanded = isExpanded,
                    showLeftPanel = showLeftPanel,
                    showRightPanel = showRightPanel,
                    scriptDocuments = openScriptDocuments,
                    scriptDocumentStates = scriptDocumentStates,
                    activeDocumentTabId = activeDocumentTabId,
                    onActiveDocumentTabChange = { activeDocumentTabId = it },
                    onCloseScriptDocument = { closeScriptDocument(it) },
                    onToggleLeftPanel = { showLeftPanel = !showLeftPanel },
                    onToggleRightPanel = { showRightPanel = !showRightPanel },
                    onSaveScriptDocument = { document, source ->
                        when (document.targetKind) {
                            ScriptDocumentKind.PART -> {
                                parts.firstOrNull { it.id == document.targetId }?.let { part ->
                                    viewModel.updatePartProperty(part.copy(script = source))
                                }
                            }
                            ScriptDocumentKind.NODE -> viewModel.updateNodeScript(document.targetId, source)
                        }
                    }
                )
                }

                // RIGHT SIDE BAR: Explorer Tree & Properties Inspector
                AnimatedVisibility(
                    visible = showRightPanel,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (isExpanded) 260.dp else 200.dp)
                            .background(Color(0xFF1C1C1C))
                            .border(BorderStroke(0.5.dp, Color(0xFF2E2E2E)))
                    ) {
                        // Explorer Tree Section
                        Box(
                            modifier = Modifier
                                .weight(0.9f)
                                .fillMaxWidth()
                        ) {
                            ExplorerTree(
                                nodes = explorerNodes,
                                selectedNode = selectedNode,
                                onSelect = { node ->
                                    viewModel.selectNode(node)
                                    if (node?.isScript == true) {
                                        openScriptDocumentForNode(node)
                                    }
                                },
                                onDuplicate = {
                                    viewModel.selectNode(it)
                                    viewModel.duplicateSelectedPart()
                                },
                                onCopy = {
                                    viewModel.selectNode(it)
                                    viewModel.copySelectedPart()
                                },
                                onPaste = {
                                    viewModel.selectNode(it)
                                    viewModel.pastePart()
                                },
                                onDelete = { viewModel.deleteNodeOrPart(it) },
                                onRename = { id, name -> viewModel.renameNode(id, name) },
                                onInsertObject = { shape -> viewModel.insertObjectByClass(shape) }
                            )
                        }

                        Divider(color = Color(0xFF2C2C2C), thickness = 1.dp)

                        // Properties Inspector Section
                        Box(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxWidth()
                        ) {
                            PropertiesInspectorPanel(
                                selectedPart = selectedPart,
                                selectedNode = selectedNode,
                                allParts = parts,
                                onUpdatePart = { viewModel.updatePartProperty(it) },
                                onUpdateNode = { viewModel.updateNode(it) },
                                onDelete = { viewModel.deleteSelectedPart() },
                                onDeleteNode = { node -> viewModel.deleteNodeOrPart(node) },
                                onEditPartScript = { openScriptDocumentForPart(it) },
                                onEditNodeScript = { openScriptDocumentForNode(it) }
                            )
                        }
                    }
                }
            }
        }

        if (showGameSettings) {
            GameSettingsDialog(
                place = activePlace,
                onDismiss = { showGameSettings = false },
                onSave = { name, description ->
                    viewModel.updateActivePlaceSettings(name, description)
                    showGameSettings = false
                }
            )
        }

        if (showPublishDialog) {
            PublishGameDialog(
                place = activePlace,
                initialRoblosecurityCookie = roblosecurityCookie,
                onDismiss = { showPublishDialog = false },
                onPublish = { name, description, roblosecurityCookie, openCloudApiKey, targetPlaceId ->
                    viewModel.publishActivePlaceToRoblox(
                        name = name,
                        description = description,
                        roblosecurityCookie = roblosecurityCookie,
                        openCloudApiKey = openCloudApiKey,
                        targetPlaceId = targetPlaceId
                    )
                    showPublishDialog = false
                }
            )
        }
    }
}

// --- Ribbon UI Components ---
@Composable
private fun RibbonGroup(title: String, content: @Composable RowScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .border(BorderStroke(0.5.dp, Color(0xFF3E3E3E)), RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp, horizontal = 6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = title, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconButtonWithLabel(icon: ImageVector, label: String, tint: Color = Color.LightGray, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(2.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = Color.LightGray, fontSize = 9.sp)
    }
}

@Composable
private fun ViewportDocumentArea(
    modifier: Modifier = Modifier,
    viewModel: StudioViewModel,
    parts: List<Part>,
    explorerNodes: List<StudioNode>,
    selectedNode: StudioNode?,
    roblosecurityCookie: String,
    activeTool: String,
    isPlaying: Boolean,
    isExpanded: Boolean,
    showLeftPanel: Boolean,
    showRightPanel: Boolean,
    scriptDocuments: List<StudioScriptDocument>,
    scriptDocumentStates: MutableMap<String, ScriptEditorState>,
    activeDocumentTabId: String,
    onActiveDocumentTabChange: (String) -> Unit,
    onCloseScriptDocument: (String) -> Unit,
    onToggleLeftPanel: () -> Unit,
    onToggleRightPanel: () -> Unit,
    onSaveScriptDocument: (StudioScriptDocument, String) -> Unit
) {
    val activeScriptDocument = scriptDocuments.firstOrNull { it.id == activeDocumentTabId }

    Column(
        modifier = modifier
            .background(Color(0xFF1B1B1B))
            .border(BorderStroke(0.5.dp, Color(0xFF2E2E2E)))
    ) {
        WorkspaceDocumentTabStrip(
            scriptDocuments = scriptDocuments,
            activeDocumentTabId = activeDocumentTabId,
            onSelect = onActiveDocumentTabChange,
            onClose = onCloseScriptDocument
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1B1B1B))) {
            if (activeScriptDocument != null) {
                val documentState = scriptDocumentStates.getOrPut(activeScriptDocument.id) {
                    ScriptEditorState(activeScriptDocument.initialSource)
                }
                // key() on the document id: without it, Compose may reuse the previous
                // editor's internal scroll/focus state for a different document, and
                // (more importantly) a reused slot table entry can deliver a stale
                // measured height to the text field, which is what made the code area
                // appear clipped at a fixed line.
                androidx.compose.runtime.key(activeScriptDocument.id) {
                    ScriptEditor(
                        viewModel = viewModel,
                        documentId = activeScriptDocument.id,
                        title = activeScriptDocument.title,
                        targetPath = activeScriptDocument.targetPath,
                        state = documentState,
                        onSaveSource = { onSaveScriptDocument(activeScriptDocument, it) }
                    )
                }
            } else {
                ViewportPane(
                    viewModel = viewModel,
                    parts = parts,
                    explorerNodes = explorerNodes,
                    selectedNode = selectedNode,
                    roblosecurityCookie = roblosecurityCookie,
                    activeTool = activeTool,
                    isPlaying = isPlaying,
                    isExpanded = isExpanded,
                    showLeftPanel = showLeftPanel,
                    showRightPanel = showRightPanel,
                    onToggleLeftPanel = onToggleLeftPanel,
                    onToggleRightPanel = onToggleRightPanel
                )
            }
        }
    }
}

@Composable
private fun WorkspaceDocumentTabStrip(
    scriptDocuments: List<StudioScriptDocument>,
    activeDocumentTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color(0xFF202020))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkspaceDocumentTab(
            title = "Viewport",
            selected = activeDocumentTabId == VIEWPORT_DOCUMENT_TAB_ID,
            icon = Icons.Default.ViewInAr,
            onSelect = { onSelect(VIEWPORT_DOCUMENT_TAB_ID) },
            onClose = null
        )
        scriptDocuments.forEach { document ->
            WorkspaceDocumentTab(
                title = document.title,
                selected = activeDocumentTabId == document.id,
                icon = Icons.Default.Code,
                onSelect = { onSelect(document.id) },
                onClose = { onClose(document.id) }
            )
        }
    }
}

@Composable
private fun WorkspaceDocumentTab(
    title: String,
    selected: Boolean,
    icon: ImageVector,
    onSelect: () -> Unit,
    onClose: (() -> Unit)?
) {
    val background = if (selected) Color(0xFF2E2E2E) else Color(0xFF252525)
    val border = if (selected) Color(0xFF3A78A8) else Color(0xFF303030)
    Row(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 112.dp, max = 190.dp)
            .background(background)
            .border(BorderStroke(1.dp, border))
            .clickable { onSelect() }
            .padding(start = 9.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF7EC7FF) else Color(0xFFAAAAAA),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = if (selected) Color.White else Color(0xFFBDBDBD),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close tab",
                    tint = Color(0xFFBDBDBD),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ViewportPane(
    viewModel: StudioViewModel,
    parts: List<Part>,
    explorerNodes: List<StudioNode>,
    selectedNode: StudioNode?,
    roblosecurityCookie: String,
    activeTool: String,
    isPlaying: Boolean,
    isExpanded: Boolean,
    showLeftPanel: Boolean,
    showRightPanel: Boolean,
    onToggleLeftPanel: () -> Unit,
    onToggleRightPanel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        StudioViewport(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        RobloxGuiOverlay(
            nodes = explorerNodes,
            selectedNodeId = selectedNode?.id,
            roblosecurityCookie = roblosecurityCookie,
            onSelectNode = { viewModel.selectNode(it) },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xBB000000), RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text("Active Tool: $activeTool", color = Color(0xFF00A2FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Active Parts: ${parts.size}", color = Color.White, fontSize = 10.sp)
            if (isPlaying) {
                Text("● SIMULATING PHYSICS", color = Color(0xFF00C853), fontWeight = FontWeight.Bold, fontSize = 10.sp)
            } else {
                Text("○ PAUSED / EDIT MODE", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }

        if (!isExpanded) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleLeftPanel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xDD222222)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (showLeftPanel) "Hide Assets" else "Show Assets", fontSize = 10.sp)
                }

                Button(
                    onClick = onToggleRightPanel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xDD222222)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (showRightPanel) "Hide Params" else "Show Params", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF00A2FF).copy(alpha = 0.2f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (active) Color(0xFF00A2FF) else Color.Transparent), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = if (active) Color(0xFF00A2FF) else Color.LightGray, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = if (active) Color.White else Color.LightGray, fontSize = 9.sp)
    }
}

private data class PartShapeOption(
    val shape: String,
    val label: String,
    val icon: ImageVector
)

@Composable
private fun PartDropdownButton(onSpawn: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shapes = listOf(
        PartShapeOption(Part.SHAPE_BLOCK, "Block", Icons.Default.CropSquare),
        PartShapeOption(Part.SHAPE_SPHERE, "Sphere", Icons.Default.Lens),
        PartShapeOption(Part.SHAPE_CYLINDER, "Cylinder", Icons.Default.PlayForWork),
        PartShapeOption(Part.SHAPE_WEDGE, "Wedge", Icons.Default.ChangeHistory)
    )

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onSpawn(Part.SHAPE_BLOCK) }
                .padding(horizontal = 2.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ViewInAr,
                contentDescription = "Part",
                tint = Color(0xFFBFD0FF),
                modifier = Modifier.size(21.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Part", color = Color.LightGray, fontSize = 9.sp)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Part shapes",
                    tint = Color(0xFFB9BDC7),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { expanded = true }
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF242424))
        ) {
            shapes.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(option.icon, contentDescription = null, tint = Color(0xFFE5E8EF), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option.label, color = Color.White, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        onSpawn(option.shape)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ColorDropdownButton(
    currentColorHex: String,
    hasSelection: Boolean,
    onColorSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedColor = parseHexColor(currentColorHex)

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(54.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (expanded) Color(0xFF3A3A3A) else Color.Transparent)
                .clickable { expanded = true }
                .padding(horizontal = 2.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1C1C1C))
                    .border(BorderStroke(1.dp, Color(0xFF4B4B4B)), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                        .border(1.dp, Color(0xFF101010), CircleShape)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color", color = Color.LightGray, fontSize = 9.sp)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Color picker",
                    tint = Color(0xFFB9BDC7),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF1E2026))
                .width(492.dp)
        ) {
            HexagonalColorPicker(
                selectedColorHex = currentColorHex,
                hasSelection = hasSelection,
                onColorSelected = { colorHex ->
                    onColorSelected(colorHex)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun HexagonalColorPicker(
    selectedColorHex: String,
    hasSelection: Boolean,
    onColorSelected: (String) -> Unit
) {
    val paletteRows = remember { buildHexagonalColorRows() }
    val grayscale = remember {
        listOf(
            "#050505", "#3A3A3A", "#777777", "#AFAFAF", "#D7D7D7", "#EFEFEF",
            "#FFFFFF", "#123038", "#626976", "#A7ACB6", "#E5E8EE", "#F8F8F8"
        )
    }
    val selected = normalizeHexColor(selectedColorHex)
    val cellWidth = 38f
    val cellHeight = 34f
    val xStep = 30f
    val yStep = 25f
    val maxColumns = paletteRows.maxOf { it.size }
    val gridWidth = (maxColumns - 1) * xStep + cellWidth
    val grayscaleWidth = (grayscale.size - 1) * xStep + cellWidth
    val grayscaleY = paletteRows.size * yStep + 26f

    Column(
        modifier = Modifier
            .background(Color(0xFF1E2026))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(gridWidth.dp)
                .height((grayscaleY + cellHeight + 4f).dp)
                .align(Alignment.CenterHorizontally)
        ) {
            paletteRows.forEachIndexed { rowIndex, row ->
                val rowWidth = (row.size - 1) * xStep + cellWidth
                val rowStart = (gridWidth - rowWidth) / 2f
                row.forEachIndexed { columnIndex, hex ->
                    val x = (rowStart + columnIndex * xStep).dp
                    val y = (rowIndex * yStep).dp
                    HexColorSwatch(
                        colorHex = hex,
                        selected = normalizeHexColor(hex) == selected,
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .size(cellWidth.dp, cellHeight.dp),
                        onClick = { onColorSelected(hex) }
                    )
                }
            }

            grayscale.forEachIndexed { index, hex ->
                HexColorSwatch(
                    colorHex = hex,
                    selected = normalizeHexColor(hex) == selected,
                    modifier = Modifier
                        .offset(
                            x = ((gridWidth - grayscaleWidth) / 2f + index * xStep).dp,
                            y = grayscaleY.dp
                        )
                        .size(cellWidth.dp, cellHeight.dp),
                    onClick = { onColorSelected(hex) }
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Click object to apply color",
                color = Color(0xFFC9CDD4),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF5C5D62))
                    .padding(3.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34363B))
                )
            }
        }
    }
}

@Composable
private fun HexColorSwatch(
    colorHex: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(HexagonSwatchShape)
            .background(parseHexColor(colorHex))
            .border(
                BorderStroke(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) Color(0xFFFFD400) else Color(0xFF070707)
                ),
                HexagonSwatchShape
            )
            .clickable { onClick() }
    )
}

private val HexagonSwatchShape = GenericShape { size, _ ->
    moveTo(size.width * 0.25f, 0f)
    lineTo(size.width * 0.75f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.75f, size.height)
    lineTo(size.width * 0.25f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

private fun buildHexagonalColorRows(): List<List<String>> = listOf(
    listOf("#173D22", "#466A4D", "#119FAA", "#142833", "#2A55B8", "#0719B8", "#082E78"),
    listOf("#207B22", "#2C8F4B", "#1CDAC8", "#636773", "#628CB7", "#3D6398", "#121CE0", "#4B2091"),
    listOf("#2C870F", "#39934A", "#6F9D5E", "#0FD9DB", "#9AACBB", "#90B0CE", "#1D7DAC", "#001BFF", "#551D5D"),
    listOf("#0B861B", "#489D4C", "#879B67", "#00F018", "#91DCD5", "#A7ADB6", "#1C9FD0", "#9160A6", "#7436B0", "#9B008E"),
    listOf("#8EA197", "#84A67C", "#9DB392", "#CFD7CB", "#AAE0F2", "#C7CDD7", "#00A2FF", "#D6A8F2", "#B162A3", "#743073", "#713473"),
    listOf("#C9C538", "#B7D85C", "#9CBD85", "#B7CEB0", "#E8E9E9", "#DCE5EA", "#A6D6E4", "#DAB4E8", "#F25AB3", "#FF00AA", "#B0008E", "#C80E0E", "#991515"),
    listOf("#FFB01F", "#FFFF00", "#ACC891", "#ADE0B7", "#BFFFE0", "#E2E3E4", "#D6EAF4", "#B7D3DD", "#ED8EC9", "#FF5F5F", "#FF0000", "#B92B24", "#961714"),
    listOf("#FFC13A", "#FFD338", "#FFE28C", "#FFF0A8", "#FFFFCF", "#EAECEF", "#D7DFE8", "#E6B8D0", "#DB6670", "#AC6465", "#8C5656", "#5E2B2B"),
    listOf("#E07035", "#D98B3F", "#ECE0BE", "#F7ECC7", "#FFFFFF", "#F3F5F5", "#E6B8C1", "#FDB8B8", "#FF8E8E", "#995858", "#8E4D20"),
    listOf("#C36300", "#E7A443", "#D7C699", "#E5DFC1", "#F9F9F9", "#EDE9E8", "#FFC58F", "#F4B998", "#D68376", "#9A4646", "#914B10"),
    listOf("#774327", "#B79158", "#CBB783", "#D0C9B4", "#C8C8C8", "#D4D7D8", "#C4B88F", "#D59A75", "#916D54"),
    listOf("#756756", "#B1965A", "#9F7C79", "#9D8E89", "#9E9E94", "#A48980", "#A76568", "#B76F35"),
    listOf("#4F392D", "#847342", "#676767", "#B5B5B5", "#6B6B62", "#574538", "#7C3A0C")
)

@Composable
private fun TogglePanelButton(active: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(2.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = if (active) Color(0xFF00A2FF) else Color.Gray, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = Color.LightGray, fontSize = 9.sp)
    }
}

// --- Left Panel Content Views ---
@Composable
private fun ToolboxContent(
    state: ToolboxSearchState,
    onQueryChange: (String) -> Unit,
    onAuthCookieChange: (String) -> Unit,
    onAssetTypeSelected: (ToolboxAssetType) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onInsertAsset: (ToolboxAsset) -> Unit,
    onInsertModel: (String) -> Unit,
    onUploadFile: () -> Unit
) {
    LaunchedEffect(Unit) {
        if (state.results.isEmpty() && !state.isLoading && state.error == null) {
            onRefresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202126))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color(0xFF1A1B20)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolboxTypeTab(ToolboxAssetTypes.Models, Icons.Default.ViewInAr, state.selectedType, onAssetTypeSelected)
            ToolboxTypeTab(ToolboxAssetTypes.Images, Icons.Default.Image, state.selectedType, onAssetTypeSelected)
            ToolboxTypeTab(ToolboxAssetTypes.Meshes, Icons.Default.Category, state.selectedType, onAssetTypeSelected)
            ToolboxTypeTab(ToolboxAssetTypes.Audio, Icons.Default.GraphicEq, state.selectedType, onAssetTypeSelected)
            ToolboxTypeTab(ToolboxAssetTypes.Plugins, Icons.Default.Extension, state.selectedType, onAssetTypeSelected)
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2A2B31))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.selectedType.label, color = Color(0xFFC9CCD4), fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFFC9CCD4), modifier = Modifier.size(15.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2A2B31))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8D929C), modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (state.query.isEmpty()) {
                                        Text("Search Marketplace", color = Color(0xFF8D929C), fontSize = 12.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2A2B31))
                        .clickable { onRefresh() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFFE0E3EA), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF26282F))
                    .border(BorderStroke(1.dp, Color(0xFF30333A)), RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF8D929C), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Auth", color = Color(0xFFB7BBC4), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = state.roblosecurityCookie,
                    onValueChange = onAuthCookieChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.roblosecurityCookie.isEmpty()) {
                                Text(".ROBLOSECURITY for private/user models", color = Color(0xFF737882), fontSize = 10.sp, maxLines = 1)
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            ToolboxSectionHeader("Local")
            ToolboxLocalGrid(
                items = listOf(
                    LocalToolboxTile("Upload", "", Icons.Default.FileUpload, Color(0xFF77808C), true),
                    LocalToolboxTile("Noob", "Roblox Noob", Icons.Default.AccessibilityNew, Color(0xFFE5A100)),
                    LocalToolboxTile("Tree", "Pine Tree", Icons.Default.Park, Color(0xFF36B36A)),
                    LocalToolboxTile("Lava", "Lava Spike", Icons.Default.Whatshot, Color(0xFFFF7043))
                ),
                onClick = { tile ->
                    if (tile.isUpload) onUploadFile() else onInsertModel(tile.modelName)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            ToolboxSectionHeader("Marketplace")

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00A2FF), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    }
                }
                state.error != null && state.results.isEmpty() -> {
                    ToolboxMessage(
                        text = state.error,
                        actionText = "Retry",
                        onAction = onRefresh
                    )
                }
                state.results.isEmpty() -> {
                    ToolboxMessage(
                        text = "No marketplace items loaded.",
                        actionText = "Refresh",
                        onAction = onRefresh
                    )
                }
                else -> {
                    ToolboxAssetGrid(
                        items = state.results,
                        insertingAssetId = state.insertingAssetId,
                        onClick = onInsertAsset
                    )
                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.error, color = Color(0xFFFF9E80), fontSize = 10.sp)
                    }
                    if (state.nextPageCursor != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF2A2B31))
                                .clickable(enabled = !state.isLoadingMore) { onLoadMore() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isLoadingMore) {
                                CircularProgressIndicator(color = Color(0xFF00A2FF), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Text("Load More", color = Color(0xFFE0E3EA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class LocalToolboxTile(
    val title: String,
    val modelName: String,
    val icon: ImageVector,
    val accent: Color,
    val isUpload: Boolean = false
)

@Composable
private fun ToolboxTypeTab(
    type: ToolboxAssetType,
    icon: ImageVector,
    selectedType: ToolboxAssetType,
    onClick: (ToolboxAssetType) -> Unit
) {
    val selected = selectedType == type
    Column(
        modifier = Modifier
            .width(42.dp)
            .fillMaxHeight()
            .clickable { onClick(type) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) Color(0xFF00A2FF) else Color.Transparent)
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) Color.White else Color(0xFF9DA2AC), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToolboxSectionHeader(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("See All >", color = Color(0xFFC3C6CE), fontSize = 10.sp)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ToolboxLocalGrid(items: List<LocalToolboxTile>, onClick: (LocalToolboxTile) -> Unit) {
    val rows = items.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { tile ->
                    ToolboxLocalTileView(tile, Modifier.weight(1f), onClick)
                }
                repeat(4 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolboxLocalTileView(tile: LocalToolboxTile, modifier: Modifier, onClick: (LocalToolboxTile) -> Unit) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2B2D33))
                .border(BorderStroke(1.dp, Color(0xFF292B31)), RoundedCornerShape(3.dp))
                .clickable { onClick(tile) }
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(tile.accent.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
            )
            Icon(tile.icon, contentDescription = tile.title, tint = tile.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tile.title,
            color = Color(0xFF63B7FF),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolboxAssetGrid(
    items: List<ToolboxAsset>,
    insertingAssetId: Long?,
    onClick: (ToolboxAsset) -> Unit
) {
    val rows = items.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { asset ->
                    ToolboxAssetTileView(
                        asset = asset,
                        isInserting = insertingAssetId == asset.assetId,
                        modifier = Modifier.weight(1f),
                        onClick = onClick
                    )
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolboxAssetTileView(
    asset: ToolboxAsset,
    isInserting: Boolean,
    modifier: Modifier,
    onClick: (ToolboxAsset) -> Unit
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2B2D33))
                .border(BorderStroke(1.dp, Color(0xFF30333A)), RoundedCornerShape(3.dp))
                .clickable(enabled = !isInserting) { onClick(asset) },
            contentAlignment = Alignment.Center
        ) {
            ToolboxNetworkThumbnail(
                url = asset.thumbnailUrl,
                fallbackIcon = if (asset.canInsertAsModel) Icons.Default.ViewInAr else Icons.Default.InsertPhoto,
                modifier = Modifier.matchParentSize()
            )
            if (isInserting) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = asset.name,
            color = Color(0xFF63B7FF),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (asset.creatorName.isNotBlank()) {
            Text(
                text = asset.creatorName,
                color = Color(0xFF9EA4AE),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolboxNetworkThumbnail(
    url: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        bitmap = null
        failed = false
        if (!url.isNullOrBlank()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    loadToolboxThumbnail(url)
                }
            }.onSuccess {
                bitmap = it
            }.onFailure {
                failed = true
            }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .background(if (failed) Color(0xFF353842) else Color(0xFF25272D))
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(fallbackIcon, contentDescription = null, tint = Color(0xFF9EA4AE), modifier = Modifier.size(28.dp))
        }
    }
}

private fun loadToolboxThumbnail(url: String): ImageBitmap? {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "image/*")
        .build()
    toolboxThumbnailHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}

@Composable
private fun ToolboxMessage(
    text: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF26282F))
            .border(BorderStroke(1.dp, Color(0xFF323640)), RoundedCornerShape(3.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, color = Color(0xFFC9CCD4), fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            actionText,
            color = Color(0xFF63B7FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onAction() }
        )
    }
}
@Composable
private fun ConsoleOutputContent(logs: List<String>, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OUTPUT CONSOLE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Clear",
                color = Color(0xFF00A2FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onClear() }
            )
        }

        Divider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0F0F0F))
                .padding(8.dp)
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = if (log.contains("❌")) Color(0xFFE53935) else if (log.contains("●")) Color(0xFF00FF66) else Color(0xFFB0BEC5),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

// --- Right Panel Content Views ---
@Composable
private fun PropertiesInspectorPanel(
    selectedPart: Part?,
    selectedNode: StudioNode?,
    allParts: List<Part>,
    onUpdatePart: (Part) -> Unit,
    onUpdateNode: (StudioNode) -> Unit,
    onDelete: () -> Unit,
    onDeleteNode: (StudioNode) -> Unit,
    onEditPartScript: (Part) -> Unit,
    onEditNodeScript: (StudioNode) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val inspectedNode = if (selectedPart == null) selectedNode else null

    Column(modifier = Modifier.fillMaxSize()) {
        // === Title Bar ===
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252525))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Properties", color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = when {
                    selectedPart != null -> " — ${selectedPart.shape.lowercase().replaceFirstChar { it.titlecase() }} '${selectedPart.name}'"
                    inspectedNode != null -> " — ${inspectedNode.className} '${inspectedNode.name}'"
                    else -> ""
                },
                color = Color(0xFF999999), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // === Filter Search Box (compact, same style as Explorer) ===
        if (selectedPart != null || inspectedNode != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(26.dp).background(Color(0xFF0F0F0F)).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 10.sp, color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentColor),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .background(Color(0xFF141414), RoundedCornerShape(2.dp))
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text("Filter Properties", color = Color(0xFF555555), fontSize = 9.sp)
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        if (selectedPart == null && inspectedNode == null) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)), contentAlignment = Alignment.Center) {
                Text("Select an object to inspect.", color = Color(0xFF555555), fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        } else if (inspectedNode != null) {
            NodePropertiesContent(
                node = inspectedNode,
                searchQuery = searchQuery,
                onUpdateNode = onUpdateNode,
                onDeleteNode = onDeleteNode,
                onEditScript = onEditNodeScript
            )
        } else {
            requireNotNull(selectedPart)
            val q = searchQuery.trim().lowercase()
            fun match(vararg labels: String): Boolean = q.isEmpty() || labels.any { it.contains(q) }

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)).verticalScroll(rememberScrollState())) {
                // === Transform ===
                CollapsibleSection("Transform", Lucide.Move3d, q, listOf("position", "rotation", "size")) {
                    if (match("position")) {
                        CollapsibleVector3Row("Position", selectedPart.position) { v ->
                            onUpdatePart(selectedPart.copy(position = v, currentPosition = v))
                        }
                    }
                    if (match("rotation")) {
                        CollapsibleVector3Row("Rotation", selectedPart.rotation) { v ->
                            onUpdatePart(selectedPart.copy(rotation = v, currentRotation = v))
                        }
                    }
                    if (match("size")) {
                        CollapsibleVector3Row("Size", selectedPart.size) { v ->
                            onUpdatePart(selectedPart.copy(size = Vector3(v.x.coerceAtLeast(0.1f), v.y.coerceAtLeast(0.1f), v.z.coerceAtLeast(0.1f))))
                        }
                    }
                }

                // === Appearance ===
                CollapsibleSection("Appearance", Lucide.Palette, q, listOf("color", "brickcolor", "shape", "material", "materialvariant", "transparency", "reflectance", "effect", "castshadow")) {
                    if (match("color")) {
                        GridColorRow("Color", selectedPart.colorHex) { onUpdatePart(selectedPart.copy(colorHex = it)) }
                    }
                    if (match("brickcolor")) {
                        GridDropdownRow("BrickColor", selectedPart.brickColor, Part.BRICK_COLORS.map { it.first }) { name ->
                            val hex = Part.brickColorToHex(name) ?: "#CCCCCC"
                            onUpdatePart(selectedPart.copy(brickColor = name, colorHex = hex))
                        }
                    }
                    if (match("shape")) {
                        GridDropdownRow("Shape", selectedPart.shape,
                            listOf(
                                Part.SHAPE_BLOCK,
                                Part.SHAPE_SPHERE,
                                Part.SHAPE_CYLINDER,
                                Part.SHAPE_WEDGE,
                                Part.SHAPE_CORNER_WEDGE,
                                Part.SHAPE_TRUSS,
                                Part.SHAPE_MESH,
                                Part.SHAPE_SPAWN_LOCATION
                            )) {
                            onUpdatePart(selectedPart.copy(shape = it))
                        }
                    }
                    if (selectedPart.shape == Part.SHAPE_MESH) {
                        if (match("meshid", "mesh id")) GridEditableTextRow("MeshId", selectedPart.meshId) { onUpdatePart(selectedPart.copy(meshId = it)) }
                        if (match("textureid", "texture id")) GridEditableTextRow("TextureID", selectedPart.textureId) { onUpdatePart(selectedPart.copy(textureId = it)) }
                        if (match("doublesided", "double sided")) GridSwitchRow("DoubleSided", selectedPart.doubleSided) { onUpdatePart(selectedPart.copy(doubleSided = it)) }
                        if (match("renderfidelity", "render fidelity")) {
                            GridDropdownRow("RenderFidelity", when (selectedPart.renderFidelity) { 1 -> "Precise"; 2 -> "Performance"; else -> "Automatic" }, listOf("Automatic", "Precise", "Performance")) {
                                onUpdatePart(selectedPart.copy(renderFidelity = when (it) { "Precise" -> 1; "Performance" -> 2; else -> 0 }))
                            }
                        }
                    }
                    if (selectedPart.shape == Part.SHAPE_TRUSS && match("trussstyle", "truss style", "style")) {
                        GridDropdownRow(
                            "TrussStyle",
                            when (selectedPart.trussStyle) {
                                Part.TRUSS_STYLE_BRIDGE_SUPPORTS -> "BridgeStyleSupports"
                                Part.TRUSS_STYLE_NO_SUPPORTS -> "NoSupports"
                                else -> "AlternatingSupports"
                            },
                            listOf("AlternatingSupports", "BridgeStyleSupports", "NoSupports")
                        ) { style ->
                            onUpdatePart(
                                selectedPart.copy(
                                    trussStyle = when (style) {
                                        "BridgeStyleSupports" -> Part.TRUSS_STYLE_BRIDGE_SUPPORTS
                                        "NoSupports" -> Part.TRUSS_STYLE_NO_SUPPORTS
                                        else -> Part.TRUSS_STYLE_ALTERNATING_SUPPORTS
                                    }
                                )
                            )
                        }
                    }
                    if (match("material")) {
                        GridDropdownRow("Material", selectedPart.material,
                            listOf(Part.MATERIAL_PLASTIC, Part.MATERIAL_WOOD, Part.MATERIAL_SLATE, Part.MATERIAL_BRICK, Part.MATERIAL_NEON, Part.MATERIAL_METAL, Part.MATERIAL_GLASS, Part.MATERIAL_FABRIC, Part.MATERIAL_MARBLE)) {
                            onUpdatePart(selectedPart.copy(material = it))
                        }
                    }
                    if (match("materialvariant", "material variant")) {
                        GridEditableTextRow("MaterialVariant", selectedPart.materialVariant) {
                            onUpdatePart(selectedPart.copy(materialVariant = it))
                        }
                    }
                    if (match("transparency")) {
                        GridSliderRow("Transparency", selectedPart.transparency, 0f..1f) { onUpdatePart(selectedPart.copy(transparency = it)) }
                    }
                    if (match("reflectance")) {
                        GridSliderRow("Reflectance", selectedPart.reflectance, 0f..1f) { onUpdatePart(selectedPart.copy(reflectance = it)) }
                    }
                    if (match("castshadow", "shadow")) {
                        GridSwitchRow("CastShadow", selectedPart.castShadow) { onUpdatePart(selectedPart.copy(castShadow = it)) }
                    }
                    if (match("effect")) {
                        GridDropdownRow("Effect", selectedPart.effect,
                            listOf(Part.EFFECT_NONE, Part.EFFECT_FIRE, Part.EFFECT_SMOKE, Part.EFFECT_SPARKLES, Part.EFFECT_POINTLIGHT)) {
                            onUpdatePart(selectedPart.copy(effect = it))
                        }
                    }
                }

                // === Behavior / Physics ===
                CollapsibleSection("Behavior", Lucide.Database, q, listOf("anchored", "cancollide", "canquery", "cantouch", "locked", "massless", "collisiongroup", "collisiongroupid", "rootpriority", "customphysicalproperties")) {
                    if (match("anchored")) {
                        GridSwitchRow("Anchored", selectedPart.anchored) { onUpdatePart(selectedPart.copy(anchored = it)) }
                    }
                    if (match("cancollide", "can collide")) {
                        GridSwitchRow("CanCollide", selectedPart.canCollide) { onUpdatePart(selectedPart.copy(canCollide = it)) }
                    }
                    if (match("canquery", "can query")) {
                        GridSwitchRow("CanQuery", selectedPart.canQuery) { onUpdatePart(selectedPart.copy(canQuery = it)) }
                    }
                    if (match("cantouch", "can touch")) {
                        GridSwitchRow("CanTouch", selectedPart.canTouch) { onUpdatePart(selectedPart.copy(canTouch = it)) }
                    }
                    if (match("locked")) {
                        GridSwitchRow("Locked", selectedPart.locked) { onUpdatePart(selectedPart.copy(locked = it)) }
                    }
                    if (match("massless")) {
                        GridSwitchRow("Massless", selectedPart.massless) { onUpdatePart(selectedPart.copy(massless = it)) }
                    }
                    if (match("collisiongroup", "collision group")) {
                        GridEditableTextRow("CollisionGroup", selectedPart.collisionGroup) {
                            onUpdatePart(selectedPart.copy(collisionGroup = it.ifBlank { "Default" }))
                        }
                    }
                    if (match("collisiongroupid", "collision group id")) {
                        GridIntInputRow("CollisionGroupId", selectedPart.collisionGroupId) {
                            onUpdatePart(selectedPart.copy(collisionGroupId = it.coerceAtLeast(0)))
                        }
                    }
                    if (match("rootpriority", "root priority")) {
                        GridIntInputRow("RootPriority", selectedPart.rootPriority) {
                            onUpdatePart(selectedPart.copy(rootPriority = it.coerceIn(-127, 127)))
                        }
                    }
                    if (match("customphysicalproperties", "physical")) {
                        GridReadOnlyTextRow("CustomPhysicalProperties", selectedPart.customPhysicalProperties)
                    }
                }

                // === Assembly ===
                CollapsibleSection("Assembly", Lucide.Move3d, q, listOf("velocity", "rotvelocity", "assemblylinearvelocity", "assemblyangularvelocity")) {
                    if (match("velocity", "assemblylinearvelocity", "assembly linear velocity")) {
                        CollapsibleVector3Row("Velocity", selectedPart.velocity) { v ->
                            onUpdatePart(selectedPart.copy(velocity = v))
                        }
                    }
                    if (match("rotvelocity", "assemblyangularvelocity", "rot velocity", "angular")) {
                        CollapsibleVector3Row("RotVelocity", selectedPart.rotVelocity) { v ->
                            onUpdatePart(selectedPart.copy(rotVelocity = v))
                        }
                    }
                }

                // === Surfaces ===
                CollapsibleSection("Surfaces", Lucide.Database, q, listOf("topsurface", "bottomsurface", "leftsurface", "rightsurface", "frontsurface", "backsurface")) {
                    if (match("topsurface", "top surface")) {
                        GridDropdownRow("TopSurface", selectedPart.topSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(topSurface = it))
                        }
                    }
                    if (match("bottomsurface", "bottom surface")) {
                        GridDropdownRow("BottomSurface", selectedPart.bottomSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(bottomSurface = it))
                        }
                    }
                    if (match("leftsurface", "left surface")) {
                        GridDropdownRow("LeftSurface", selectedPart.leftSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(leftSurface = it))
                        }
                    }
                    if (match("rightsurface", "right surface")) {
                        GridDropdownRow("RightSurface", selectedPart.rightSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(rightSurface = it))
                        }
                    }
                    if (match("frontsurface", "front surface")) {
                        GridDropdownRow("FrontSurface", selectedPart.frontSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(frontSurface = it))
                        }
                    }
                    if (match("backsurface", "back surface")) {
                        GridDropdownRow("BackSurface", selectedPart.backSurface, Part.SURFACE_TYPES) {
                            onUpdatePart(selectedPart.copy(backSurface = it))
                        }
                    }
                }

                if (selectedPart.shape == Part.SHAPE_SPAWN_LOCATION) {
                    CollapsibleSection("Spawn", Lucide.Database, q, listOf("enabled", "neutral", "allowteamchangeontouch", "duration", "teamcolor")) {
                        if (match("enabled")) {
                            GridSwitchRow("Enabled", selectedPart.spawnEnabled) {
                                onUpdatePart(selectedPart.copy(spawnEnabled = it))
                            }
                        }
                        if (match("neutral")) {
                            GridSwitchRow("Neutral", selectedPart.neutral) {
                                onUpdatePart(selectedPart.copy(neutral = it))
                            }
                        }
                        if (match("allowteamchangeontouch", "allow team change")) {
                            GridSwitchRow("AllowTeamChangeOnTouch", selectedPart.allowTeamChangeOnTouch) {
                                onUpdatePart(selectedPart.copy(allowTeamChangeOnTouch = it))
                            }
                        }
                        if (match("duration")) {
                            GridIntInputRow("Duration", selectedPart.duration) {
                                onUpdatePart(selectedPart.copy(duration = it.coerceAtLeast(0)))
                            }
                        }
                        if (match("teamcolor", "team color")) {
                            GridIntInputRow("TeamColor", selectedPart.teamColor) {
                                onUpdatePart(selectedPart.copy(teamColor = it.coerceAtLeast(0)))
                            }
                        }
                    }
                }

                // === Data ===
                CollapsibleSection("Data", Lucide.Database, q, listOf("name", "parent", "formfactorraw", "sourceassetid", "tags", "uniqueid", "historyid", "script")) {
                    if (match("name")) {
                        GridEditableTextRow("Name", selectedPart.name) {
                            onUpdatePart(selectedPart.copy(name = it))
                        }
                    }
                    if (match("parent")) {
                        val parentName = selectedPart.parentId?.let { pid ->
                            allParts.firstOrNull { it.id == pid }?.name ?: "Workspace"
                        } ?: "Workspace"
                        GridRow("Parent") { Text(parentName, color = Color(0xFF888888), fontSize = 10.sp) }
                    }
                    if (match("formfactorraw", "form factor")) {
                        GridIntInputRow("formFactorRaw", selectedPart.formFactorRaw) {
                            onUpdatePart(selectedPart.copy(formFactorRaw = it.coerceAtLeast(0)))
                        }
                    }
                    if (match("sourceassetid", "source asset")) {
                        GridLongInputRow("SourceAssetId", selectedPart.sourceAssetId) {
                            onUpdatePart(selectedPart.copy(sourceAssetId = it))
                        }
                    }
                    if (match("tags")) {
                        GridEditableTextRow("Tags", selectedPart.tags.joinToString(", ")) { input ->
                            onUpdatePart(selectedPart.copy(tags = input.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }))
                        }
                    }
                    if (match("uniqueid", "unique id")) {
                        GridReadOnlyTextRow("UniqueId", selectedPart.uniqueId)
                    }
                    if (match("historyid", "history id")) {
                        GridReadOnlyTextRow("HistoryId", selectedPart.historyId)
                    }
                    if (match("script")) {
                        GridRow("Script") {
                            Button(
                                onClick = { onEditPartScript(selectedPart) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1C)),
                                border = BorderStroke(1.dp, AccentColor),
                                modifier = Modifier.fillMaxWidth().height(24.dp),
                                shape = RoundedCornerShape(3.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(Lucide.Code, null, tint = AccentColor, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit Lua", fontSize = 9.sp, color = AccentColor)
                            }
                        }
                    }
                }

                // Delete
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(28.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Lucide.Trash2, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun NodePropertiesContent(
    node: StudioNode,
    searchQuery: String,
    onUpdateNode: (StudioNode) -> Unit,
    onDeleteNode: (StudioNode) -> Unit,
    onEditScript: (StudioNode) -> Unit
) {
    val q = searchQuery.trim().lowercase()
    fun match(vararg labels: String): Boolean = q.isEmpty() || labels.any { it.lowercase().contains(q) }
    fun prop(key: String): String = node.nodeProperties[key].orEmpty()
    fun updateProp(key: String, value: String) {
        val nextProps = node.nodeProperties + (key to value)
        onUpdateNode(
            node.copy(
                name = if (key == "Name") value.ifBlank { node.className } else node.name,
                scriptSource = if (key == "Source") value else node.scriptSource,
                nodeProperties = nextProps
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)).verticalScroll(rememberScrollState())) {
        CollapsibleSection("Identity", Lucide.Database, q, listOf("name", "classname", "parentid", "sourceassetid", "tags")) {
            if (match("name")) {
                GridEditableTextRow("Name", prop("Name").ifBlank { node.name }) { updateProp("Name", it) }
            }
            if (match("classname", "class name")) {
                GridReadOnlyTextRow("ClassName", prop("ClassName").ifBlank { node.className })
            }
            if (match("parentid", "parent")) {
                GridReadOnlyTextRow("ParentId", prop("ParentId").ifBlank { node.parentId ?: "Workspace" })
            }
            if (match("sourceassetid", "source asset")) {
                GridReadOnlyTextRow("SourceAssetId", prop("SourceAssetId"))
            }
            if (match("tags")) {
                GridReadOnlyTextRow("Tags", prop("Tags"))
            }
        }

        if (node.isModel) {
            CollapsibleSection(
                "Model",
                Lucide.Database,
                q,
                listOf("primarypart", "levelofdetail", "worldpivotdata", "modelmeshsize", "modelmeshcframe", "modelmeshdata", "needspivotmigration")
            ) {
                if (match("primarypart", "primary part")) GridReadOnlyTextRow("PrimaryPart", prop("PrimaryPart"))
                if (match("levelofdetail", "level of detail")) GridReadOnlyTextRow("LevelOfDetail", prop("LevelOfDetail"))
                if (match("needspivotmigration", "pivot migration")) GridReadOnlyTextRow("NeedsPivotMigration", prop("NeedsPivotMigration"))
                if (match("worldpivotdata", "world pivot")) GridReadOnlyTextRow("WorldPivotData", prop("WorldPivotData"))
                if (match("modelmeshsize", "model mesh size")) GridReadOnlyTextRow("ModelMeshSize", prop("ModelMeshSize"))
                if (match("modelmeshcframe", "model mesh cframe")) GridReadOnlyTextRow("ModelMeshCFrame", prop("ModelMeshCFrame"))
                if (match("modelmeshdata", "model mesh data")) GridReadOnlyTextRow("ModelMeshData", prop("ModelMeshData"))
            }
        }

        if (node.isScript) {
            val source = node.scriptSource.ifBlank { prop("Source") }
            val sourceLineCount = source.lines().size.coerceAtLeast(1)
            CollapsibleSection(
                "Script",
                Lucide.Code,
                q,
                listOf("source", "disabled", "linkedsource", "linked source", "runccontext", "runcontext", "scriptguid")
            ) {
                if (match("disabled")) {
                    GridSwitchRow("Disabled", prop("Disabled").equals("true", ignoreCase = true)) {
                        updateProp("Disabled", it.toString())
                    }
                }
                if (match("linkedsource", "linked source")) {
                    GridEditableTextRow("LinkedSource", prop("LinkedSource")) { updateProp("LinkedSource", it) }
                }
                if (match("runcontext", "run context")) {
                    GridEditableTextRow("RunContext", prop("RunContext").ifBlank { "Legacy" }) { updateProp("RunContext", it) }
                }
                if (match("scriptguid", "script guid")) {
                    GridReadOnlyTextRow("ScriptGuid", prop("ScriptGuid"))
                }
                if (match("source")) {
                    GridRow("Source") {
                        Button(
                            onClick = { onEditScript(node) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1C)),
                            border = BorderStroke(1.dp, AccentColor),
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            shape = RoundedCornerShape(3.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Lucide.Code, null, tint = AccentColor, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Lua ($sourceLineCount lines)", fontSize = 9.sp, color = AccentColor)
                        }
                    }
                }
            }
        }

        if (node.isDecal || node.isTexture) {
            val sectionTitle = if (node.isTexture) "Texture" else "Decal"
            CollapsibleSection(
                sectionTitle,
                Lucide.Palette,
                q,
                listOf("texture", "face", "transparency", "zindex", "color3", "studspertileu", "studspertilev", "offsetstudsu", "offsetstudsv")
            ) {
                if (match("texture")) GridEditableTextRow("Texture", prop("Texture")) { updateProp("Texture", it) }
                if (match("face")) GridEditableTextRow("Face", prop("Face").ifBlank { "Front" }) { updateProp("Face", it) }
                if (match("transparency")) GridEditableTextRow("Transparency", prop("Transparency")) { updateProp("Transparency", it) }
                if (match("zindex")) GridEditableTextRow("ZIndex", prop("ZIndex")) { updateProp("ZIndex", it) }
                if (match("color3", "color")) GridEditableTextRow("Color3", prop("Color3")) { updateProp("Color3", it) }
                if (node.isTexture) {
                    if (match("studspertileu", "studs per tile u")) GridEditableTextRow("StudsPerTileU", prop("StudsPerTileU")) { updateProp("StudsPerTileU", it) }
                    if (match("studspertilev", "studs per tile v")) GridEditableTextRow("StudsPerTileV", prop("StudsPerTileV")) { updateProp("StudsPerTileV", it) }
                    if (match("offsetstudsu", "offset studs u")) GridEditableTextRow("OffsetStudsU", prop("OffsetStudsU")) { updateProp("OffsetStudsU", it) }
                    if (match("offsetstudsv", "offset studs v")) GridEditableTextRow("OffsetStudsV", prop("OffsetStudsV")) { updateProp("OffsetStudsV", it) }
                }
            }
        }

        if (node.className in setOf(
                StudioNode.CLASS_POINT_LIGHT,
                StudioNode.CLASS_SPOT_LIGHT,
                StudioNode.CLASS_SURFACE_LIGHT
            )
        ) {
            CollapsibleSection(
                "Light",
                Lucide.Sun,
                q,
                listOf("enabled", "brightness", "color", "range", "shadows", "angle", "face")
            ) {
                if (match("enabled")) {
                    GridSwitchRow("Enabled", prop("Enabled").ifBlank { "true" }.equals("true", ignoreCase = true)) {
                        updateProp("Enabled", it.toString())
                    }
                }
                if (match("brightness")) GridEditableTextRow("Brightness", prop("Brightness").ifBlank { "1.0" }) { updateProp("Brightness", it) }
                if (match("color")) GridColorRow("Color", prop("Color").ifBlank { "#FFFFFF" }) { updateProp("Color", it) }
                if (match("range")) GridEditableTextRow("Range", prop("Range").ifBlank { "16.0" }) { updateProp("Range", it) }
                if (match("shadows", "shadow")) {
                    GridSwitchRow("Shadows", prop("Shadows").equals("true", ignoreCase = true)) {
                        updateProp("Shadows", it.toString())
                    }
                }
                if (node.className != StudioNode.CLASS_POINT_LIGHT) {
                    if (match("angle")) GridEditableTextRow("Angle", prop("Angle").ifBlank { "45.0" }) { updateProp("Angle", it) }
                    if (match("face")) {
                        GridDropdownRow("Face", prop("Face").ifBlank { "Front" }, listOf("Front", "Back", "Left", "Right", "Top", "Bottom")) {
                            updateProp("Face", it)
                        }
                    }
                }
            }
        }

        if (node.className == StudioNode.CLASS_SKY) {
            CollapsibleSection(
                "Sky",
                Lucide.Sun,
                q,
                listOf("celestialbodiesshown", "moonangularsize", "moontextureid", "skybox", "starcount", "sunangularsize", "suntextureid")
            ) {
                if (match("celestialbodiesshown")) {
                    GridSwitchRow("CelestialBodiesShown", prop("CelestialBodiesShown").ifBlank { "true" }.equals("true", true)) {
                        updateProp("CelestialBodiesShown", it.toString())
                    }
                }
                listOf("MoonAngularSize", "MoonTextureId", "SkyboxBk", "SkyboxDn", "SkyboxFt", "SkyboxLf", "SkyboxRt", "SkyboxUp", "StarCount", "SunAngularSize", "SunTextureId").forEach { key ->
                    if (match(key)) GridEditableTextRow(key, prop(key)) { updateProp(key, it) }
                }
            }
        }

        if (node.className == StudioNode.CLASS_LIGHTING) {
            CollapsibleSection(
                "Lighting",
                Lucide.Sun,
                q,
                listOf("brightness", "globalshadows", "timeofday", "technology", "ambient", "outdoorambient")
            ) {
                if (match("brightness")) GridEditableTextRow("Brightness", prop("Brightness").ifBlank { "2.0" }) { updateProp("Brightness", it) }
                if (match("globalshadows", "shadows")) {
                    GridSwitchRow("GlobalShadows", prop("GlobalShadows").ifBlank { "true" }.equals("true", true)) {
                        updateProp("GlobalShadows", it.toString())
                    }
                }
                if (match("timeofday", "time of day")) GridEditableTextRow("TimeOfDay", prop("TimeOfDay").ifBlank { "14:30:00" }) { updateProp("TimeOfDay", it) }
                if (match("technology")) GridEditableTextRow("Technology", prop("Technology").ifBlank { "3" }) { updateProp("Technology", it) }
                if (match("ambient")) GridColorRow("Ambient", prop("Ambient").ifBlank { "#808080" }) { updateProp("Ambient", it) }
                if (match("outdoorambient", "outdoor ambient")) GridColorRow("OutdoorAmbient", prop("OutdoorAmbient").ifBlank { "#808080" }) { updateProp("OutdoorAmbient", it) }
            }
        }

        if (node.className in StudioNode.EFFECT_CLASS_NAMES) {
            val keys = when (node.className) {
                StudioNode.CLASS_TRAIL -> listOf(
                    "Attachment0", "Attachment1", "Brightness", "Color", "Enabled", "FaceCamera", "Lifetime",
                    "MinLength", "Texture", "TextureLength", "TextureMode", "Transparency", "WidthScale"
                )
                StudioNode.CLASS_BEAM -> listOf(
                    "Attachment0", "Attachment1", "Brightness", "Color", "CurveSize0", "CurveSize1", "Enabled",
                    "FaceCamera", "Segments", "Texture", "TextureLength", "TextureMode", "TextureSpeed",
                    "Transparency", "Width0", "Width1", "ZOffset"
                )
                else -> listOf(
                    "Acceleration", "Brightness", "Color", "Drag", "EmissionDirection", "Enabled", "Lifetime",
                    "Rate", "Size", "Speed", "SpreadAngle", "Texture", "Transparency"
                )
            }
            CollapsibleSection(node.className, Lucide.Sparkles, q, keys.map(String::lowercase)) {
                keys.forEach { key ->
                    if (match(key)) {
                        if (key in setOf("Enabled", "FaceCamera")) {
                            GridSwitchRow(key, prop(key).ifBlank { "true" }.equals("true", true)) {
                                updateProp(key, it.toString())
                            }
                        } else if (key == "Color") {
                            GridEditableTextRow(key, prop(key)) { updateProp(key, it) }
                        } else {
                            GridEditableTextRow(key, prop(key)) { updateProp(key, it) }
                        }
                    }
                }
            }
        }

        if (node.className == StudioNode.CLASS_SURFACE_GUI) {
            val keys = listOf(
                "Active", "Adornee", "AlwaysOnTop", "Brightness", "CanvasSize", "Enabled", "Face",
                "LightInfluence", "PixelsPerStud", "ResetOnSpawn", "SizingMode", "ZIndexBehavior", "ZOffset"
            )
            CollapsibleSection("SurfaceGui", Lucide.Square, q, keys.map(String::lowercase)) {
                keys.forEach { key ->
                    if (match(key)) {
                        when (key) {
                            "Active", "AlwaysOnTop", "Enabled", "ResetOnSpawn" ->
                                GridSwitchRow(key, prop(key).ifBlank { "true" }.equals("true", true)) { updateProp(key, it.toString()) }
                            "Face" -> GridDropdownRow(key, prop(key).ifBlank { "Front" }, listOf("Front", "Back", "Left", "Right", "Top", "Bottom")) { updateProp(key, it) }
                            else -> GridEditableTextRow(key, prop(key)) { updateProp(key, it) }
                        }
                    }
                }
            }
        }

        if (node.className == StudioNode.CLASS_UI_LIST_LAYOUT) {
            val keys = listOf("FillDirection", "HorizontalAlignment", "Padding", "SortOrder", "VerticalAlignment", "Wraps")
            CollapsibleSection("UIListLayout", Lucide.Layers, q, keys.map(String::lowercase)) {
                if (match("filldirection")) GridDropdownRow("FillDirection", prop("FillDirection").ifBlank { "Vertical" }, listOf("Horizontal", "Vertical")) { updateProp("FillDirection", it) }
                if (match("horizontalalignment")) GridDropdownRow("HorizontalAlignment", prop("HorizontalAlignment").ifBlank { "Center" }, listOf("Left", "Center", "Right")) { updateProp("HorizontalAlignment", it) }
                if (match("padding")) GridEditableTextRow("Padding", prop("Padding")) { updateProp("Padding", it) }
                if (match("sortorder")) GridDropdownRow("SortOrder", prop("SortOrder").ifBlank { "LayoutOrder" }, listOf("Name", "Custom", "LayoutOrder")) { updateProp("SortOrder", it) }
                if (match("verticalalignment")) GridDropdownRow("VerticalAlignment", prop("VerticalAlignment").ifBlank { "Center" }, listOf("Top", "Center", "Bottom")) { updateProp("VerticalAlignment", it) }
                if (match("wraps")) GridSwitchRow("Wraps", prop("Wraps").equals("true", true)) { updateProp("Wraps", it.toString()) }
            }
        }

        if (node.className == StudioNode.CLASS_UI_CORNER) {
            CollapsibleSection("UICorner", Lucide.Square, q, listOf("cornerradius")) {
                if (match("cornerradius", "corner radius")) GridEditableTextRow("CornerRadius", prop("CornerRadius")) { updateProp("CornerRadius", it) }
            }
        }

        if (node.className == StudioNode.CLASS_UI_STROKE) {
            val keys = listOf("ApplyStrokeMode", "Color", "Enabled", "LineJoinMode", "Thickness", "Transparency")
            CollapsibleSection("UIStroke", Lucide.Square, q, keys.map(String::lowercase)) {
                if (match("applystrokemode")) GridDropdownRow("ApplyStrokeMode", prop("ApplyStrokeMode").ifBlank { "Border" }, listOf("Border", "Contextual")) { updateProp("ApplyStrokeMode", it) }
                if (match("color")) GridColorRow("Color", prop("Color").ifBlank { "#000000" }) { updateProp("Color", it) }
                if (match("enabled")) GridSwitchRow("Enabled", prop("Enabled").ifBlank { "true" }.equals("true", true)) { updateProp("Enabled", it.toString()) }
                if (match("linejoinmode")) GridDropdownRow("LineJoinMode", prop("LineJoinMode").ifBlank { "Round" }, listOf("Round", "Bevel", "Miter")) { updateProp("LineJoinMode", it) }
                if (match("thickness")) GridEditableTextRow("Thickness", prop("Thickness")) { updateProp("Thickness", it) }
                if (match("transparency")) GridEditableTextRow("Transparency", prop("Transparency")) { updateProp("Transparency", it) }
            }
        }

        if (node.className == StudioNode.CLASS_UI_GRADIENT) {
            val keys = listOf("Color", "Enabled", "Offset", "Rotation", "Transparency")
            CollapsibleSection("UIGradient", Lucide.Palette, q, keys.map(String::lowercase)) {
                if (match("color")) GridEditableTextRow("Color", prop("Color")) { updateProp("Color", it) }
                if (match("enabled")) GridSwitchRow("Enabled", prop("Enabled").ifBlank { "true" }.equals("true", true)) { updateProp("Enabled", it.toString()) }
                if (match("offset")) GridEditableTextRow("Offset", prop("Offset")) { updateProp("Offset", it) }
                if (match("rotation")) GridEditableTextRow("Rotation", prop("Rotation")) { updateProp("Rotation", it) }
                if (match("transparency")) GridEditableTextRow("Transparency", prop("Transparency")) { updateProp("Transparency", it) }
            }
        }

        if (node.className == StudioNode.CLASS_HIGHLIGHT) {
            val keys = listOf("Adornee", "DepthMode", "Enabled", "FillColor", "FillTransparency", "OutlineColor", "OutlineTransparency")
            CollapsibleSection("Highlight", Lucide.Sparkles, q, keys.map(String::lowercase)) {
                if (match("adornee")) GridEditableTextRow("Adornee", prop("Adornee")) { updateProp("Adornee", it) }
                if (match("depthmode")) GridDropdownRow("DepthMode", prop("DepthMode").ifBlank { "AlwaysOnTop" }, listOf("AlwaysOnTop", "Occluded")) { updateProp("DepthMode", it) }
                if (match("enabled")) GridSwitchRow("Enabled", prop("Enabled").ifBlank { "true" }.equals("true", true)) { updateProp("Enabled", it.toString()) }
                if (match("fillcolor")) GridColorRow("FillColor", prop("FillColor").ifBlank { "#FF0000" }) { updateProp("FillColor", it) }
                if (match("filltransparency")) GridEditableTextRow("FillTransparency", prop("FillTransparency")) { updateProp("FillTransparency", it) }
                if (match("outlinecolor")) GridColorRow("OutlineColor", prop("OutlineColor").ifBlank { "#FFFFFF" }) { updateProp("OutlineColor", it) }
                if (match("outlinetransparency")) GridEditableTextRow("OutlineTransparency", prop("OutlineTransparency")) { updateProp("OutlineTransparency", it) }
            }
        }

        if (node.isGuiObject) {
            if (node.className == StudioNode.CLASS_SCREEN_GUI) {
                CollapsibleSection(
                    "ScreenGui",
                    Lucide.Square,
                    q,
                    listOf("enabled", "resetonspawn", "ignoreguiinset", "displayorder", "zindexbehavior")
                ) {
                    if (match("enabled")) {
                        GridSwitchRow("Enabled", prop("Enabled").ifBlank { "true" }.equals("true", ignoreCase = true)) {
                            updateProp("Enabled", it.toString())
                        }
                    }
                    if (match("resetonspawn", "reset on spawn")) {
                        GridSwitchRow("ResetOnSpawn", prop("ResetOnSpawn").ifBlank { "true" }.equals("true", ignoreCase = true)) {
                            updateProp("ResetOnSpawn", it.toString())
                        }
                    }
                    if (match("ignoreguiinset", "ignore gui inset")) {
                        GridSwitchRow("IgnoreGuiInset", prop("IgnoreGuiInset").equals("true", ignoreCase = true)) {
                            updateProp("IgnoreGuiInset", it.toString())
                        }
                    }
                    if (match("displayorder", "display order")) GridEditableTextRow("DisplayOrder", prop("DisplayOrder").ifBlank { "0" }) { updateProp("DisplayOrder", it) }
                    if (match("zindexbehavior", "z index behavior")) GridEditableTextRow("ZIndexBehavior", prop("ZIndexBehavior").ifBlank { "Sibling" }) { updateProp("ZIndexBehavior", it) }
                }
            } else {
                CollapsibleSection(
                    "GuiObject",
                    Lucide.Square,
                    q,
                    listOf("active", "anchorpoint", "automaticsize", "backgroundcolor3", "backgroundtransparency", "bordercolor3", "bordersizepixel", "position", "rotation", "selectable", "size", "visible", "zindex")
                ) {
                    if (match("active")) {
                        GridSwitchRow("Active", prop("Active").equals("true", ignoreCase = true)) {
                            updateProp("Active", it.toString())
                        }
                    }
                    if (match("visible")) {
                        GridSwitchRow("Visible", prop("Visible").ifBlank { "true" }.equals("true", ignoreCase = true)) {
                            updateProp("Visible", it.toString())
                        }
                    }
                    if (match("position")) GridEditableTextRow("Position", prop("Position")) { updateProp("Position", it) }
                    if (match("size")) GridEditableTextRow("Size", prop("Size")) { updateProp("Size", it) }
                    if (match("anchorpoint", "anchor point")) GridEditableTextRow("AnchorPoint", prop("AnchorPoint")) { updateProp("AnchorPoint", it) }
                    if (match("rotation")) GridEditableTextRow("Rotation", prop("Rotation")) { updateProp("Rotation", it) }
                    if (match("zindex", "z index")) GridEditableTextRow("ZIndex", prop("ZIndex").ifBlank { "1" }) { updateProp("ZIndex", it) }
                    if (match("backgroundcolor3", "background color")) GridEditableTextRow("BackgroundColor3", prop("BackgroundColor3")) { updateProp("BackgroundColor3", it) }
                    if (match("backgroundtransparency", "background transparency")) GridEditableTextRow("BackgroundTransparency", prop("BackgroundTransparency")) { updateProp("BackgroundTransparency", it) }
                    if (match("bordercolor3", "border color")) GridEditableTextRow("BorderColor3", prop("BorderColor3")) { updateProp("BorderColor3", it) }
                    if (match("bordersizepixel", "border size")) GridEditableTextRow("BorderSizePixel", prop("BorderSizePixel")) { updateProp("BorderSizePixel", it) }
                    if (match("automaticsize", "automatic size")) GridEditableTextRow("AutomaticSize", prop("AutomaticSize")) { updateProp("AutomaticSize", it) }
                    if (match("selectable")) GridEditableTextRow("Selectable", prop("Selectable")) { updateProp("Selectable", it) }
                }
            }

            if (node.className == StudioNode.CLASS_TEXT_LABEL ||
                node.className == StudioNode.CLASS_TEXT_BUTTON ||
                node.className == StudioNode.CLASS_TEXT_BOX
            ) {
                CollapsibleSection(
                    "Text",
                    Lucide.Code,
                    q,
                    listOf("text", "textcolor3", "textscaled", "textsize", "texttransparency", "textwrapped", "richtext", "fontface", "textxalignment", "textyalignment")
                ) {
                    if (match("text")) GridEditableTextRow("Text", prop("Text")) { updateProp("Text", it) }
                    if (match("textcolor3", "text color")) GridEditableTextRow("TextColor3", prop("TextColor3")) { updateProp("TextColor3", it) }
                    if (match("textsize", "text size")) GridEditableTextRow("TextSize", prop("TextSize")) { updateProp("TextSize", it) }
                    if (match("textscaled", "text scaled")) {
                        GridSwitchRow("TextScaled", prop("TextScaled").equals("true", ignoreCase = true)) {
                            updateProp("TextScaled", it.toString())
                        }
                    }
                    if (match("textwrapped", "text wrapped")) {
                        GridSwitchRow("TextWrapped", prop("TextWrapped").equals("true", ignoreCase = true)) {
                            updateProp("TextWrapped", it.toString())
                        }
                    }
                    if (match("richtext", "rich text")) {
                        GridSwitchRow("RichText", prop("RichText").equals("true", ignoreCase = true)) {
                            updateProp("RichText", it.toString())
                        }
                    }
                    if (match("fontface", "font")) GridEditableTextRow("FontFace", prop("FontFace")) { updateProp("FontFace", it) }
                    if (match("texttransparency", "text transparency")) GridEditableTextRow("TextTransparency", prop("TextTransparency")) { updateProp("TextTransparency", it) }
                    if (match("textxalignment", "text x alignment")) GridEditableTextRow("TextXAlignment", prop("TextXAlignment")) { updateProp("TextXAlignment", it) }
                    if (match("textyalignment", "text y alignment")) GridEditableTextRow("TextYAlignment", prop("TextYAlignment")) { updateProp("TextYAlignment", it) }
                }
            }

            if (node.className == StudioNode.CLASS_TEXT_BOX) {
                CollapsibleSection(
                    "TextBox",
                    Lucide.Code,
                    q,
                    listOf("cleartextonfocus", "multiline", "placeholdercolor3", "placeholdertext", "texteditable")
                ) {
                    if (match("cleartextonfocus")) {
                        GridSwitchRow("ClearTextOnFocus", prop("ClearTextOnFocus").ifBlank { "true" }.equals("true", true)) {
                            updateProp("ClearTextOnFocus", it.toString())
                        }
                    }
                    if (match("multiline")) {
                        GridSwitchRow("MultiLine", prop("MultiLine").equals("true", true)) { updateProp("MultiLine", it.toString()) }
                    }
                    if (match("placeholdertext", "placeholder")) GridEditableTextRow("PlaceholderText", prop("PlaceholderText")) { updateProp("PlaceholderText", it) }
                    if (match("placeholdercolor3", "placeholder color")) GridColorRow("PlaceholderColor3", prop("PlaceholderColor3").ifBlank { "#B2B2B2" }) { updateProp("PlaceholderColor3", it) }
                    if (match("texteditable", "text editable")) {
                        GridSwitchRow("TextEditable", prop("TextEditable").ifBlank { "true" }.equals("true", true)) { updateProp("TextEditable", it.toString()) }
                    }
                }
            }

            if (node.className == StudioNode.CLASS_IMAGE_LABEL || node.className == StudioNode.CLASS_IMAGE_BUTTON) {
                CollapsibleSection(
                    "Image",
                    Lucide.Image,
                    q,
                    listOf("image", "hoverimage", "pressedimage", "imagecolor3", "imagerectoffset", "imagerectsize", "imagetransparency", "scaletype", "tilesize")
                ) {
                    if (match("image")) GridEditableTextRow("Image", prop("Image")) { updateProp("Image", it) }
                    if (match("hoverimage", "hover image")) GridEditableTextRow("HoverImage", prop("HoverImage")) { updateProp("HoverImage", it) }
                    if (match("pressedimage", "pressed image")) GridEditableTextRow("PressedImage", prop("PressedImage")) { updateProp("PressedImage", it) }
                    if (match("imagecolor3", "image color")) GridEditableTextRow("ImageColor3", prop("ImageColor3")) { updateProp("ImageColor3", it) }
                    if (match("imagetransparency", "image transparency")) GridEditableTextRow("ImageTransparency", prop("ImageTransparency")) { updateProp("ImageTransparency", it) }
                    if (match("imagerectoffset", "image rect offset")) GridEditableTextRow("ImageRectOffset", prop("ImageRectOffset")) { updateProp("ImageRectOffset", it) }
                    if (match("imagerectsize", "image rect size")) GridEditableTextRow("ImageRectSize", prop("ImageRectSize")) { updateProp("ImageRectSize", it) }
                    if (match("scaletype", "scale type")) GridEditableTextRow("ScaleType", prop("ScaleType")) { updateProp("ScaleType", it) }
                    if (match("tilesize", "tile size")) GridEditableTextRow("TileSize", prop("TileSize")) { updateProp("TileSize", it) }
                }
            }
        }

        if (node.isWeld) {
            CollapsibleSection("Weld", Lucide.Move3d, q, listOf("part0", "part1", "c0", "c1", "enabled")) {
                if (match("part0")) GridReadOnlyTextRow("Part0", prop("Part0"))
                if (match("part1")) GridReadOnlyTextRow("Part1", prop("Part1"))
                if (match("c0")) GridReadOnlyTextRow("C0", prop("C0"))
                if (match("c1")) GridReadOnlyTextRow("C1", prop("C1"))
                if (match("enabled")) GridReadOnlyTextRow("Enabled", prop("Enabled"))
            }
        }

        val shownKeys = setOf(
            "ClassName", "Name", "ParentId", "SourceAssetId", "Tags",
            "PrimaryPart", "LevelOfDetail", "NeedsPivotMigration", "WorldPivotData", "ModelMeshSize", "ModelMeshCFrame", "ModelMeshData",
            "Texture", "Face", "Transparency", "ZIndex", "Color3", "StudsPerTileU", "StudsPerTileV", "OffsetStudsU", "OffsetStudsV",
            "Source", "Disabled", "LinkedSource", "RunContext", "ScriptGuid",
            "Enabled", "ResetOnSpawn", "IgnoreGuiInset", "DisplayOrder", "ZIndexBehavior",
            "Active", "AnchorPoint", "AutomaticSize", "BackgroundColor3", "BackgroundTransparency", "BorderColor3", "BorderMode",
            "BorderSizePixel", "Position", "Rotation", "Selectable", "SelectionImageObject", "Size", "SizeConstraint", "Visible",
            "Text", "TextColor3", "TextDirection", "TextScaled", "TextSize", "TextStrokeColor3", "TextStrokeTransparency",
            "TextTransparency", "TextTruncate", "TextWrapped", "TextXAlignment", "TextYAlignment", "RichText", "FontFace",
            "Image", "HoverImage", "PressedImage", "ImageColor3", "ImageRectOffset", "ImageRectSize", "ImageTransparency", "ScaleType", "TileSize",
            "Part0", "Part1", "C0", "C1", "Enabled",
            "Brightness", "Color", "Range", "Shadows", "Angle", "Face",
            "CelestialBodiesShown", "MoonAngularSize", "MoonTextureId", "SkyboxBk", "SkyboxDn", "SkyboxFt", "SkyboxLf", "SkyboxRt", "SkyboxUp", "StarCount", "SunAngularSize", "SunTextureId",
            "GlobalShadows", "TimeOfDay", "Technology", "Ambient", "OutdoorAmbient",
            "ClearTextOnFocus", "CursorPosition", "MultiLine", "PlaceholderColor3", "PlaceholderText", "SelectionStart", "ShowNativeInput", "TextEditable",
            "Attachment0", "Attachment1", "FaceCamera", "Lifetime", "MinLength", "TextureLength", "TextureMode", "WidthScale",
            "CurveSize0", "CurveSize1", "Segments", "TextureSpeed", "Width0", "Width1", "ZOffset", "Acceleration", "Drag",
            "EmissionDirection", "Rate", "Speed", "SpreadAngle", "Adornee", "AlwaysOnTop", "CanvasSize", "LightInfluence",
            "PixelsPerStud", "SizingMode", "FillDirection", "HorizontalAlignment", "Padding", "SortOrder", "VerticalAlignment",
            "Wraps", "CornerRadius", "ApplyStrokeMode", "LineJoinMode", "Thickness", "Offset",
            "DepthMode", "FillColor", "FillTransparency", "OutlineColor", "OutlineTransparency"
        )
        val remaining = node.nodeProperties
            .filterKeys { it !in shownKeys }
            .filter { (key, value) -> match(key, value) }

        CollapsibleSection("All Properties", Lucide.Database, q, remaining.keys.map { it.lowercase() } + listOf("properties")) {
            if (remaining.isEmpty()) {
                GridReadOnlyTextRow("Properties", "No additional properties")
            } else {
                remaining.forEach { (key, value) ->
                    GridReadOnlyTextRow(key, value)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (!node.isService) Button(
            onClick = { onDeleteNode(node) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(28.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Lucide.Trash2, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

// ===== Collapsible section — header controls content visibility =====
@Composable
private fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    searchQuery: String,
    propertyNames: List<String>,
    content: @Composable () -> Unit
) {
    var expanded by remember(title, searchQuery) { mutableStateOf(searchQuery.isNotEmpty()) }
    val groupMatches = searchQuery.isEmpty() || propertyNames.any { it.contains(searchQuery) }
    if (!groupMatches) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = Color(0xFF666666), modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Icon(icon, null, tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(title, color = Color(0xFFCCCCCC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (expanded) { content() }
    }
}

@Composable
private fun GridEditableTextRow(label: String, value: String, onValueChange: (String) -> Unit) {
    GridRow(label) {
        CompactTextInput(value, onValueChange, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GridIntInputRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    GridRow(label) {
        CompactTextInput(
            value = value.toString(),
            onValueChange = { input -> input.trim().toIntOrNull()?.let(onValueChange) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GridLongInputRow(label: String, value: Long, onValueChange: (Long) -> Unit) {
    GridRow(label) {
        CompactTextInput(
            value = value.toString(),
            onValueChange = { input -> input.trim().toLongOrNull()?.let(onValueChange) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GridReadOnlyTextRow(label: String, value: String) {
    GridRow(label) {
        Text(
            text = value.ifBlank { "(empty)" },
            color = Color(0xFF888888),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ===== Grid color row =====
@Composable
private fun GridColorRow(label: String, colorHex: String, onColorChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val swatchColor = parseHexColor(colorHex)

    GridRow(label = label) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).background(swatchColor)
                    .border(1.dp, Color(0xFF3E3E3E), RoundedCornerShape(3.dp)).clickable { showPicker = !showPicker }
            )
            CompactTextInput(
                value = colorHex,
                onValueChange = { input ->
                    val filtered = if (input.startsWith("#")) input else "#${input.removePrefix("#")}"
                    if (filtered.length <= 7 && filtered.drop(1).all { it in "0123456789abcdefABCDEF" }) {
                        if (filtered.length == 7) onColorChange(filtered)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showPicker) {
        DropdownMenu(expanded = true, onDismissRequest = { showPicker = false }, modifier = Modifier.background(Color(0xFF222222)).width(200.dp)) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Part.BRICK_COLORS.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (name, hex) ->
                            val c = parseHexColor(hex)
                            Box(
                                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)).background(c)
                                    .border(
                                        BorderStroke(if (hex == colorHex) 2.dp else 0.5.dp, if (hex == colorHex) Color.White else Color(0xFF444444)),
                                        RoundedCornerShape(3.dp)
                                    ).clickable { onColorChange(hex); showPicker = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeHexColor(hex: String): String {
    val raw = hex.trim().removePrefix("#")
    val expanded = if (raw.length == 3 && raw.all { it in "0123456789abcdefABCDEF" }) {
        raw.map { "$it$it" }.joinToString("")
    } else {
        raw
    }
    return if (expanded.length == 6 && expanded.all { it in "0123456789abcdefABCDEF" }) {
        "#${expanded.uppercase()}"
    } else {
        "#808080"
    }
}

private fun parseHexColor(hex: String): Color = runCatching {
    val h = normalizeHexColor(hex).removePrefix("#")
    Color(h.substring(0, 2).toInt(16) / 255f, h.substring(2, 4).toInt(16) / 255f, h.substring(4, 6).toInt(16) / 255f)
}.getOrDefault(Color.Gray)
