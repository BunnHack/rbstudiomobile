package com.example.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.RobloxAuthStore
import com.example.database.AppDatabase
import com.example.database.PlaceRepository
import com.example.models.Part
import com.example.models.Place
import com.example.models.StudioNode
import com.example.models.StudioNodeGraph
import com.example.models.Vector3
import com.example.publish.RobloxPlaceBinarySerializer
import com.example.publish.RobloxPublishClient
import com.example.toolbox.RobloxToolboxClient
import com.example.toolbox.ToolboxAsset
import com.example.toolbox.ToolboxAssetType
import com.example.toolbox.ToolboxSearchState
import com.example.toolbox.ToolboxAssetTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PlaceRepository
    private val publishClient = RobloxPublishClient()
    private val toolboxClient = RobloxToolboxClient()
    private val authStore = RobloxAuthStore(application)
    val places: StateFlow<List<Place>>

    private val _roblosecurityCookie = MutableStateFlow(authStore.getRoblosecurityCookie())
    val roblosecurityCookie = _roblosecurityCookie.asStateFlow()

    // Active Place & Workspace
    private val _activePlace = MutableStateFlow<Place?>(null)
    val activePlace = _activePlace.asStateFlow()

    private val _parts = MutableStateFlow<List<Part>>(emptyList())
    val parts = _parts.asStateFlow()

    private val _selectedPart = MutableStateFlow<Part?>(null)
    val selectedPart = _selectedPart.asStateFlow()

    // === StudioNode hierarchy (Folder / Model / Script / Part) ===
    private val _nodes = MutableStateFlow<List<StudioNode>>(emptyList())
    val nodes = _nodes.asStateFlow()

    private val _selectedNode = MutableStateFlow<StudioNode?>(null)
    val selectedNode = _selectedNode.asStateFlow()

    /** Service node IDs that can be selected as insertion targets. */
    val serviceNodeIds = StudioNode.SERVICE_CLASS_NAMES

    // Screen State
    private val _isLauncherActive = MutableStateFlow(true)
    val isLauncherActive = _isLauncherActive.asStateFlow()

    // Tab State (HOME, MODEL, VIEW, TEST, PLUGINS, SCRIPT)
    private val _activeTab = MutableStateFlow("Home")
    val activeTab = _activeTab.asStateFlow()

    // Tool State (SELECT, MOVE, SCALE, ROTATE)
    private val _activeTool = MutableStateFlow("SELECT")
    val activeTool = _activeTool.asStateFlow()

    // Visual options
    private val _showGrid = MutableStateFlow(true)
    val showGrid = _showGrid.asStateFlow()

    private val _gridMaterial = MutableStateFlow(true)
    val gridMaterial = _gridMaterial.asStateFlow()

    private val _wireframe = MutableStateFlow(false)
    val wireframe = _wireframe.asStateFlow()

    // 3D Camera Controls
    private val _cameraYaw = MutableStateFlow(45f)
    val cameraYaw = _cameraYaw.asStateFlow()

    private val _cameraPitch = MutableStateFlow(30f)
    val cameraPitch = _cameraPitch.asStateFlow()

    private val _cameraZoom = MutableStateFlow(32f)
    val cameraZoom = _cameraZoom.asStateFlow()

    private val _cameraOffsetX = MutableStateFlow(0f)
    val cameraOffsetX = _cameraOffsetX.asStateFlow()

    private val _cameraOffsetY = MutableStateFlow(0f)
    val cameraOffsetY = _cameraOffsetY.asStateFlow()

    // Simulation Engine
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs = _consoleLogs.asStateFlow()

    private val _toolboxState = MutableStateFlow(
        ToolboxSearchState(roblosecurityCookie = _roblosecurityCookie.value)
    )
    val toolboxState = _toolboxState.asStateFlow()
    private var toolboxSearchJob: Job? = null

    // Undo/Redo History Stack
    private val history = mutableListOf<List<Part>>()
    private var historyIndex = -1

    // Physics Loop Job
    private var simulationJob: Job? = null
    private var simulationOriginalState: List<Part> = emptyList()

    init {
        val db = AppDatabase.getDatabase(application)
        repository = PlaceRepository(db.placeDao())
        
        // Setup places and auto-seed if database is empty
        places = repository.allPlaces.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Seed if first launch
        viewModelScope.launch {
            places.collect { list ->
                if (list.isEmpty()) {
                    repository.seedDatabaseIfEmpty(list)
                }
            }
        }
    }

    fun openPlace(place: Place) {
        historyCommitJob?.cancel()
        historyCommitJob = null
        _activePlace.value = place
        val rawParts = repository.parsePartsJson(place.partsJson)
        val loadedNodes = normalizeStoredNodes(repository.parseNodesJson(place.nodesJson))
        val loadedParts = normalizeSpecialPartShapes(rawParts, loadedNodes)
        _parts.value = loadedParts
        _selectedPart.value = null
        _nodes.value = StudioNodeGraph.syncPartBackedNodes(loadedNodes, loadedParts)
        _selectedNode.value = null
        _isLauncherActive.value = false
        _isPlaying.value = false
        stopSimulation()
        clearLogs()
        logSystem("Loaded place '${place.name}' with ${loadedParts.size} instances.")
        
        // Reset history stack
        history.clear()
        history.add(loadedParts)
        historyIndex = 0
    }

    fun closePlace() {
        historyCommitJob?.cancel()
        historyCommitJob = null
        _isLauncherActive.value = true
        _isPlaying.value = false
        stopSimulation()
        _selectedPart.value = null
        _selectedNode.value = null
        _parts.value = emptyList()
        _nodes.value = emptyList()
        _activePlace.value = null
    }

    fun savePlace() {
        val currentPlace = _activePlace.value ?: return
        val currentParts = if (_isPlaying.value) simulationOriginalState else _parts.value
        val updatedPartsJson = repository.partsToJson(currentParts)
        val updatedPlace = currentPlace.copy(
            partsJson = updatedPartsJson,
            nodesJson = repository.nodesToJson(_nodes.value),
            lastSaved = System.currentTimeMillis()
        )
        viewModelScope.launch {
            repository.update(updatedPlace)
            _activePlace.value = updatedPlace
            logSystem("Saved place '${updatedPlace.name}' to database.")
        }
    }

    fun updatePlace(place: Place) {
        viewModelScope.launch {
            repository.update(place)
        }
    }

    fun updateActivePlaceSettings(name: String, description: String) {
        val currentPlace = _activePlace.value ?: return
        val currentParts = if (_isPlaying.value) simulationOriginalState else _parts.value
        val updatedPlace = currentPlace.copy(
            name = name,
            description = description,
            partsJson = repository.partsToJson(currentParts),
            nodesJson = repository.nodesToJson(_nodes.value),
            lastSaved = System.currentTimeMillis()
        )
        viewModelScope.launch {
            repository.update(updatedPlace)
            _activePlace.value = updatedPlace
            logSystem("Updated game settings for '${updatedPlace.name}'.")
        }
    }

    fun publishActivePlaceToRoblox(
        name: String,
        description: String,
        roblosecurityCookie: String,
        openCloudApiKey: String,
        targetPlaceId: Long?
    ) {
        if (roblosecurityCookie.isNotBlank()) {
            setRobloxAuthCookie(roblosecurityCookie, logUpdate = false)
        }
        val currentPlace = _activePlace.value
        if (currentPlace == null) {
            logSystem("❌ Publish failed: no active place is open.")
            return
        }

        val currentParts = if (_isPlaying.value) simulationOriginalState else _parts.value
        val resolvedPlaceId = targetPlaceId ?: currentPlace.robloxPlaceId
        val templatePlaceId = robloxTemplatePlaceIdFor(currentPlace.templateId, currentPlace.name, name)
        viewModelScope.launch {
            runCatching {
                logSystem("Publish: Serializing '${name}' to Roblox binary place file...")
                val rbxlBytes = RobloxPlaceBinarySerializer.serialize(name, currentParts, _nodes.value)
                logSystem("Publish: Uploading ${rbxlBytes.size} bytes to Roblox with template $templatePlaceId...")
                publishClient.publish(
                    roblosecurityCookie = roblosecurityCookie,
                    openCloudApiKey = openCloudApiKey,
                    name = name,
                    description = description,
                    rbxlxBytes = rbxlBytes,
                    existingPlaceId = resolvedPlaceId,
                    existingUniverseId = currentPlace.robloxUniverseId,
                    templatePlaceId = templatePlaceId
                )
            }.onSuccess { result ->
                val updatedPlace = currentPlace.copy(
                    name = name,
                    description = description,
                    partsJson = repository.partsToJson(currentParts),
                    nodesJson = repository.nodesToJson(_nodes.value),
                    lastSaved = System.currentTimeMillis(),
                    robloxUniverseId = result.universeId ?: currentPlace.robloxUniverseId,
                    robloxPlaceId = result.placeId
                )
                repository.update(updatedPlace)
                _activePlace.value = updatedPlace
                val version = result.versionNumber?.let { " version $it" } ?: ""
                val method = result.uploadMethod.ifBlank { "Roblox publish API" }
                logSystem("● Published '${updatedPlace.name}' to Roblox place ${result.placeId}$version via $method.")
                result.settingsWarning?.let { warning ->
                    logSystem("⚠ $warning")
                }
            }.onFailure { error ->
                logSystem("❌ Publish failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun createNewPlace(name: String, templateId: String) {
        viewModelScope.launch {
            val parts = when (templateId) {
                "obby" -> repository.createDefaultTemplates().find { it.templateId == "obby" }?.let { repository.parsePartsJson(it.partsJson) } ?: emptyList()
                "mars" -> repository.createDefaultTemplates().find { it.templateId == "mars" }?.let { repository.parsePartsJson(it.partsJson) } ?: emptyList()
                else -> repository.createDefaultTemplates().find { it.templateId == "baseplate" }?.let { repository.parsePartsJson(it.partsJson) } ?: emptyList()
            }
            val newPlace = Place(
                name = name,
                description = "Custom workspace created by creator.",
                partsJson = repository.partsToJson(parts),
                templateId = templateId
            )
            val newId = repository.insert(newPlace)
            val savedPlace = newPlace.copy(id = newId.toInt())
            openPlace(savedPlace)
        }
    }

    private fun robloxTemplatePlaceIdFor(templateId: String, vararg placeNames: String): Long = when {
        templateId == "obby" || placeNames.any { it.contains("classic obby", ignoreCase = true) } -> {
            CLASSIC_OBBY_TEMPLATE_PLACE_ID
        }
        templateId == "classic-baseplate" ||
            templateId == "classic_baseplate" ||
            placeNames.any { it.contains("classic baseplate", ignoreCase = true) } -> {
            CLASSIC_BASEPLATE_TEMPLATE_PLACE_ID
        }
        else -> BASEPLATE_TEMPLATE_PLACE_ID
    }

    fun deletePlace(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
            logSystem("Deleted place ID $id.")
        }
    }

    // --- History Control ---
    private fun commitHistory(
        newParts: List<Part>,
        nodesForSync: List<StudioNode> = _nodes.value
    ) {
        // Cancel any pending debounced commit — this is a direct, immediate commit.
        historyCommitJob?.cancel()
        historyCommitJob = null

        // Clear anything beyond index
        if (historyIndex < history.size - 1) {
            history.subList(historyIndex + 1, history.size).clear()
        }
        history.add(newParts)
        historyIndex = history.size - 1
        setPartsAndSyncNodes(newParts, nodesForSync)
    }

    private fun setPartsAndSyncNodes(
        newParts: List<Part>,
        nodesForSync: List<StudioNode> = _nodes.value
    ) {
        _parts.value = newParts
        syncNodesWithParts(newParts, nodesForSync)
    }

    private fun syncNodesWithParts(
        parts: List<Part> = _parts.value,
        nodesForSync: List<StudioNode> = _nodes.value
    ) {
        _nodes.value = StudioNodeGraph.syncPartBackedNodes(nodesForSync, parts)
        _selectedPart.value = _selectedPart.value?.let { selected ->
            parts.find { it.id == selected.id }
        }
        _selectedNode.value = StudioNodeGraph.resolveNode(_selectedNode.value, _nodes.value, parts)
    }

    fun undo() {
        historyCommitJob?.cancel()
        historyCommitJob = null
        if (historyIndex > 0) {
            historyIndex--
            setPartsAndSyncNodes(history[historyIndex])
            _selectedPart.value = _selectedPart.value?.let { selected ->
                _parts.value.find { it.id == selected.id }
            }
            logSystem("Undo action executed.")
        }
    }

    fun redo() {
        historyCommitJob?.cancel()
        historyCommitJob = null
        if (historyIndex < history.size - 1) {
            historyIndex++
            setPartsAndSyncNodes(history[historyIndex])
            _selectedPart.value = _selectedPart.value?.let { selected ->
                _parts.value.find { it.id == selected.id }
            }
            logSystem("Redo action executed.")
        }
    }

    // --- Camera Control ---
    fun rotateCamera(deltaYaw: Float, deltaPitch: Float) {
        _cameraYaw.value = (_cameraYaw.value + deltaYaw) % 360f
        _cameraPitch.value = (_cameraPitch.value + deltaPitch).coerceIn(5f, 85f)
    }

    fun zoomCamera(factor: Float) {
        _cameraZoom.value = (_cameraZoom.value * factor).coerceIn(10f, 120f)
    }

    fun panCamera(dx: Float, dy: Float) {
        _cameraOffsetX.value += dx
        _cameraOffsetY.value += dy
    }

    fun resetCamera() {
        _cameraYaw.value = 45f
        _cameraPitch.value = 30f
        _cameraZoom.value = 32f
        _cameraOffsetX.value = 0f
        _cameraOffsetY.value = 0f
    }

    // --- Ribbon State Controls ---
    fun setActiveTab(tab: String) {
        _activeTab.value = tab
    }

    fun setActiveTool(tool: String) {
        _activeTool.value = tool
        logSystem("Switched tool to: $tool")
    }

    fun toggleGrid(show: Boolean) {
        _showGrid.value = show
    }

    fun toggleGridMaterial(show: Boolean) {
        _gridMaterial.value = show
    }

    fun toggleWireframe(show: Boolean) {
        _wireframe.value = show
    }

    // --- Selection and Edit Operations ---
    fun selectPart(part: Part?) {
        _selectedPart.value = part
        // Also update selectedNode to match
        _selectedNode.value = if (part != null) {
            StudioNodeGraph.nodeForPart(part, _nodes.value)
        } else null
    }

    fun selectNode(node: StudioNode?) {
        val resolved = StudioNodeGraph.resolveNode(node, _nodes.value, _parts.value)
        _selectedNode.value = resolved
        // Sync selectedPart for rendering/properties
        _selectedPart.value = resolved?.part
    }

    private fun insertionParentId(selected: StudioNode?): String? = when {
        selected == null -> null
        selected.className == StudioNode.CLASS_WORKSPACE -> null
        selected.isService -> selected.id
        else -> selected.id
    }

    private fun defaultServiceNodes(): List<StudioNode> = StudioNode.SERVICE_CLASS_NAMES.map { className ->
        val properties = linkedMapOf(
            "ClassName" to className,
            "Name" to className,
            "ParentId" to ""
        )
        properties += when (className) {
            StudioNode.CLASS_LIGHTING -> mapOf(
                "Brightness" to "2.0",
                "GlobalShadows" to "true",
                "TimeOfDay" to "14:30:00",
                "Technology" to "3",
                "Ambient" to "#808080",
                "OutdoorAmbient" to "#808080"
            )
            StudioNode.CLASS_SOUND_SERVICE -> mapOf(
                "AmbientReverb" to "0",
                "DistanceFactor" to "3.33",
                "DopplerScale" to "1.0",
                "RespectFilteringEnabled" to "true",
                "RolloffScale" to "1.0"
            )
            else -> emptyMap()
        }
        StudioNode(
            id = className,
            name = className,
            className = className,
            isService = true,
            nodeProperties = properties
        )
    }

    private fun normalizeStoredNodes(nodes: List<StudioNode>): List<StudioNode> {
        val serviceOverrides = nodes
            .filter { it.className in StudioNode.SERVICE_CLASS_NAMES }
            .associateBy { it.className }
        val services = defaultServiceNodes().map { fallback ->
            serviceOverrides[fallback.className]?.copy(
                id = fallback.id,
                name = fallback.name,
                parentId = null,
                part = null,
                isService = true,
                nodeProperties = fallback.nodeProperties + serviceOverrides.getValue(fallback.className).nodeProperties
            ) ?: fallback
        }
        return services + nodes.filterNot { it.className in StudioNode.SERVICE_CLASS_NAMES }
    }

    private fun normalizeSpecialPartShapes(parts: List<Part>, nodes: List<StudioNode>): List<Part> {
        val classByPartId = nodes.mapNotNull { node -> node.part?.id?.let { it to node.className } }.toMap()
        val styleByPartId = nodes.mapNotNull { node ->
            node.part?.id?.let { partId ->
                partId to (node.nodeProperties.entries.firstOrNull { it.key.equals("style", true) }?.value?.toIntOrNull()
                    ?: Part.TRUSS_STYLE_ALTERNATING_SUPPORTS)
            }
        }.toMap()
        return parts.map { part ->
            when (classByPartId[part.id]) {
                StudioNode.CLASS_CORNER_WEDGE_PART -> part.copy(shape = Part.SHAPE_CORNER_WEDGE)
                StudioNode.CLASS_TRUSS_PART -> part.copy(
                    shape = Part.SHAPE_TRUSS,
                    trussStyle = styleByPartId[part.id]?.coerceIn(0, 2) ?: Part.TRUSS_STYLE_ALTERNATING_SUPPORTS
                )
                StudioNode.CLASS_MESH_PART -> part.copy(shape = Part.SHAPE_MESH)
                else -> part
            }
        }
    }

    private fun defaultScriptSource(className: String): String =
        when (className) {
            StudioNode.CLASS_LOCAL_SCRIPT -> "-- LocalScript\nprint('Hello from client')\n"
            StudioNode.CLASS_MODULE_SCRIPT -> "-- ModuleScript\nlocal module = {}\n\nreturn module\n"
            else -> "-- Script\nprint('Hello world!')\n"
        }

    private fun defaultScriptProperties(className: String, parentId: String?): Map<String, String> {
        val source = defaultScriptSource(className)
        return mapOf(
            "ClassName" to className,
            "Name" to className,
            "Disabled" to "false",
            "LinkedSource" to "",
            "RunContext" to "Legacy",
            "ScriptGuid" to UUID.randomUUID().toString(),
            "Source" to source,
            "SourceAssetId" to "-1",
            "Tags" to "",
            "ParentId" to (parentId ?: "Workspace")
        )
    }

    private fun defaultObjectProperties(className: String, parentId: String?): Map<String, String> {
        val identity = linkedMapOf(
            "ClassName" to className,
            "Name" to className,
            "ParentId" to (parentId ?: StudioNode.CLASS_WORKSPACE),
            "SourceAssetId" to "-1",
            "Tags" to ""
        )
        identity += when (className) {
            StudioNode.CLASS_ATTACHMENT -> mapOf(
                "CFrame" to "pos 0.000, 0.000, 0.000; rot identity",
                "Visible" to "true"
            )
            StudioNode.CLASS_REMOTE_EVENT -> emptyMap()
            StudioNode.CLASS_SOUND -> mapOf(
                "SoundId" to "",
                "Volume" to "0.5",
                "PlaybackSpeed" to "1.0",
                "Looped" to "false",
                "Playing" to "false",
                "PlayOnRemove" to "false",
                "TimePosition" to "0.0",
                "RollOffMinDistance" to "10.0",
                "RollOffMaxDistance" to "10000.0"
            )
            StudioNode.CLASS_POINT_LIGHT -> mapOf(
                "Brightness" to "1.0",
                "Color" to "#FFFFFF",
                "Enabled" to "true",
                "Range" to "8.0",
                "Shadows" to "false"
            )
            StudioNode.CLASS_SPOT_LIGHT -> mapOf(
                "Angle" to "45.0",
                "Brightness" to "1.0",
                "Color" to "#FFFFFF",
                "Enabled" to "true",
                "Face" to "Front",
                "Range" to "16.0",
                "Shadows" to "false"
            )
            StudioNode.CLASS_SURFACE_LIGHT -> mapOf(
                "Angle" to "90.0",
                "Brightness" to "1.0",
                "Color" to "#FFFFFF",
                "Enabled" to "true",
                "Face" to "Front",
                "Range" to "16.0",
                "Shadows" to "false"
            )
            StudioNode.CLASS_SKY -> mapOf(
                "CelestialBodiesShown" to "true",
                "MoonAngularSize" to "11.0",
                "MoonTextureId" to "rbxasset://sky/moon.jpg",
                "SkyboxBk" to "rbxasset://textures/sky/sky512_bk.tex",
                "SkyboxDn" to "rbxasset://textures/sky/sky512_dn.tex",
                "SkyboxFt" to "rbxasset://textures/sky/sky512_ft.tex",
                "SkyboxLf" to "rbxasset://textures/sky/sky512_lf.tex",
                "SkyboxRt" to "rbxasset://textures/sky/sky512_rt.tex",
                "SkyboxUp" to "rbxasset://textures/sky/sky512_up.tex",
                "StarCount" to "3000",
                "SunAngularSize" to "21.0",
                "SunTextureId" to "rbxasset://sky/sun.jpg"
            )
            StudioNode.CLASS_CLICK_DETECTOR -> mapOf(
                "CursorIcon" to "",
                "MaxActivationDistance" to "32.0"
            )
            StudioNode.CLASS_TRAIL -> mapOf(
                "Attachment0" to "",
                "Attachment1" to "",
                "Brightness" to "1.0",
                "Color" to "0:#FF7A00:0; 1:#C23D00:0",
                "Enabled" to "true",
                "FaceCamera" to "false",
                "Lifetime" to "0.5",
                "MinLength" to "0.1",
                "Texture" to "",
                "TextureLength" to "1.0",
                "TextureMode" to "0",
                "Transparency" to "0:0.5:0; 1:0.5:0",
                "WidthScale" to "0:1:0; 1:1:0"
            )
            StudioNode.CLASS_BEAM -> mapOf(
                "Attachment0" to "",
                "Attachment1" to "",
                "Brightness" to "1.0",
                "Color" to "0:#FFD29D:0; 1:#FFD29D:0",
                "CurveSize0" to "0.0",
                "CurveSize1" to "0.0",
                "Enabled" to "true",
                "FaceCamera" to "true",
                "Segments" to "1",
                "Texture" to "",
                "TextureLength" to "1.0",
                "TextureMode" to "0",
                "TextureSpeed" to "0.0",
                "Transparency" to "0:0.75:0; 1:0.75:0",
                "Width0" to "1.0",
                "Width1" to "1.0",
                "ZOffset" to "0.0"
            )
            StudioNode.CLASS_PARTICLE_EMITTER -> mapOf(
                "Acceleration" to "0.0, 0.0, 0.0",
                "Brightness" to "1.0",
                "Color" to "0:#FFFFFF:0; 1:#FFFFFF:0",
                "Drag" to "0.0",
                "EmissionDirection" to "Front",
                "Enabled" to "true",
                "Lifetime" to "1.0, 1.0",
                "Rate" to "5.0",
                "Size" to "0:0.5:0; 1:0.0:0",
                "Speed" to "1.0, 1.0",
                "SpreadAngle" to "x=0.0, y=0.0",
                "Texture" to "",
                "Transparency" to "0:0:0; 1:1:0"
            )
            StudioNode.CLASS_HIGHLIGHT -> mapOf(
                "Adornee" to "",
                "DepthMode" to "AlwaysOnTop",
                "Enabled" to "true",
                "FillColor" to "#FF0000",
                "FillTransparency" to "0.5",
                "OutlineColor" to "#FFFFFF",
                "OutlineTransparency" to "0.0"
            )
            else -> emptyMap()
        }
        return identity
    }

    private fun objectInsertionParentId(className: String, selected: StudioNode?): String? = when (className) {
        StudioNode.CLASS_MODULE_SCRIPT,
        StudioNode.CLASS_REMOTE_EVENT -> selected?.id ?: StudioNode.CLASS_REPLICATED_STORAGE
        StudioNode.CLASS_ATTACHMENT,
        StudioNode.CLASS_SOUND,
        StudioNode.CLASS_POINT_LIGHT,
        StudioNode.CLASS_SPOT_LIGHT,
        StudioNode.CLASS_SURFACE_LIGHT,
        StudioNode.CLASS_TRAIL,
        StudioNode.CLASS_BEAM,
        StudioNode.CLASS_PARTICLE_EMITTER -> selected?.id ?: null
        StudioNode.CLASS_HIGHLIGHT -> selected?.id ?: StudioNode.CLASS_WORKSPACE
        StudioNode.CLASS_SKY -> StudioNode.CLASS_LIGHTING
        StudioNode.CLASS_SURFACE_GUI -> selected?.id ?: null
        else -> insertionParentId(selected)
    }

    private fun defaultGuiProperties(className: String, parentId: String?): Map<String, String> {
        val name = className
        val identity = linkedMapOf(
            "ClassName" to className,
            "Name" to name,
            "ParentId" to (parentId ?: StudioNode.CLASS_STARTER_GUI),
            "SourceAssetId" to "-1",
            "Tags" to ""
        )

        if (className == StudioNode.CLASS_SCREEN_GUI) {
            identity += mapOf(
                "Enabled" to "true",
                "ResetOnSpawn" to "true",
                "IgnoreGuiInset" to "false",
                "DisplayOrder" to "0",
                "ZIndexBehavior" to "Sibling"
            )
            return identity
        }

        if (className == StudioNode.CLASS_SURFACE_GUI) {
            identity += mapOf(
                "Active" to "true",
                "AlwaysOnTop" to "false",
                "Brightness" to "1.0",
                "CanvasSize" to "x=800.0, y=600.0",
                "Enabled" to "true",
                "Face" to "Front",
                "LightInfluence" to "0.0",
                "PixelsPerStud" to "50.0",
                "ResetOnSpawn" to "true",
                "SizingMode" to "0",
                "ZIndexBehavior" to "0",
                "ZOffset" to "0.0"
            )
            return identity
        }

        if (className == StudioNode.CLASS_UI_GRADIENT) {
            identity += mapOf(
                "Color" to "0:#FFFFFF:0; 1:#FFFFFF:0",
                "Enabled" to "true",
                "Offset" to "x=0.0, y=0.0",
                "Rotation" to "0.0",
                "Transparency" to "0:0:0; 1:0:0"
            )
            return identity
        }

        identity += mapOf(
            "Active" to (className.endsWith("Button") || className == StudioNode.CLASS_TEXT_BOX).toString(),
            "AnchorPoint" to "0.000, 0.000",
            "AutomaticSize" to "None",
            "BackgroundColor3" to "#ffffff",
            "BackgroundTransparency" to if (className.contains("Label")) "1.0" else "0.0",
            "BorderColor3" to "#000000",
            "BorderMode" to "Outline",
            "BorderSizePixel" to "1",
            "Position" to "scaleX=0.0, scaleY=0.0, offsetX=0, offsetY=0",
            "Rotation" to "0.0",
            "Selectable" to "false",
            "SelectionImageObject" to "",
            "Size" to "scaleX=0.0, scaleY=0.0, offsetX=200, offsetY=50",
            "SizeConstraint" to "RelativeXY",
            "Visible" to "true",
            "ZIndex" to "1"
        )

        if (className == StudioNode.CLASS_TEXT_LABEL ||
            className == StudioNode.CLASS_TEXT_BUTTON ||
            className == StudioNode.CLASS_TEXT_BOX
        ) {
            identity += mapOf(
                "FontFace" to "family=SourceSans, weight=400, style=0",
                "RichText" to "false",
                "Text" to className,
                "TextColor3" to "#000000",
                "TextDirection" to "Auto",
                "TextScaled" to "false",
                "TextSize" to "14.0",
                "TextStrokeColor3" to "#000000",
                "TextStrokeTransparency" to "1.0",
                "TextTransparency" to "0.0",
                "TextTruncate" to "None",
                "TextWrapped" to "false",
                "TextXAlignment" to "Center",
                "TextYAlignment" to "Center"
            )
        }

        if (className == StudioNode.CLASS_TEXT_BOX) {
            identity += mapOf(
                "ClearTextOnFocus" to "true",
                "CursorPosition" to "-1",
                "MultiLine" to "false",
                "PlaceholderColor3" to "#B2B2B2",
                "PlaceholderText" to "",
                "SelectionStart" to "-1",
                "ShowNativeInput" to "true",
                "TextEditable" to "true"
            )
        }

        if (className == StudioNode.CLASS_IMAGE_LABEL || className == StudioNode.CLASS_IMAGE_BUTTON) {
            identity += mapOf(
                "Image" to "",
                "ImageColor3" to "#ffffff",
                "ImageRectOffset" to "x=0.0, y=0.0",
                "ImageRectSize" to "x=0.0, y=0.0",
                "ImageTransparency" to "0.0",
                "ScaleType" to "Stretch",
                "TileSize" to "scaleX=1.0, scaleY=1.0, offsetX=0, offsetY=0"
            )
        }

        return identity
    }

    private fun guiInsertionParentId(
        className: String,
        selected: StudioNode?,
        extraNodes: MutableList<StudioNode>
    ): String? {
        if (className !in StudioNode.GUI_CLASS_NAMES &&
            className !in StudioNode.GUI_LAYOUT_CLASS_NAMES &&
            className !in StudioNode.GUI_DECORATOR_CLASS_NAMES
        ) return null
        val nodes = _nodes.value
        if (className == StudioNode.CLASS_SCREEN_GUI) return StudioNode.CLASS_STARTER_GUI
        if (className in StudioNode.GUI_LAYOUT_CLASS_NAMES || className in StudioNode.GUI_DECORATOR_CLASS_NAMES) {
            return selected?.takeIf { it.isGuiObject || it.isGuiContainer }?.id
                ?: selected?.parentId?.takeIf { parentId -> nodes.any { it.id == parentId && (it.isGuiObject || it.isGuiContainer) } }
        }
        if (selected?.isGuiContainer == true) return selected.id
        val selectedParent = selected?.parentId?.let { parentId -> nodes.firstOrNull { it.id == parentId } }
        if (selectedParent?.isGuiContainer == true) return selectedParent.id

        nodes.firstOrNull { it.className == StudioNode.CLASS_SCREEN_GUI }?.let { return it.id }

        val screenGuiId = UUID.randomUUID().toString()
        extraNodes += StudioNode(
            id = screenGuiId,
            name = StudioNode.CLASS_SCREEN_GUI,
            className = StudioNode.CLASS_SCREEN_GUI,
            parentId = StudioNode.CLASS_STARTER_GUI,
            nodeProperties = defaultGuiProperties(StudioNode.CLASS_SCREEN_GUI, StudioNode.CLASS_STARTER_GUI)
        )
        return screenGuiId
    }

    // === StudioNode insertion ===

    /**
     * Inserts a new [StudioNode] of the given [className] as a child of the selected node
     * (or under Workspace if nothing is selected / a service is selected).
     * For Part-type nodes, also creates the underlying [Part] and syncs _parts.
     */
    fun insertNode(className: String) {
        val selected = _selectedNode.value
        val parentId = if (className == StudioNode.CLASS_MODULE_SCRIPT) {
            selected?.id ?: StudioNode.CLASS_REPLICATED_STORAGE
        } else {
            insertionParentId(selected)
        }
        val pivot = selected?.part?.currentPosition ?: Vector3(0f, 4f, 0f)

        val nodeId = UUID.randomUUID().toString()
        val node = when (className) {
            StudioNode.CLASS_FOLDER -> StudioNode(
                id = nodeId, name = "Folder", className = StudioNode.CLASS_FOLDER, parentId = parentId
            )
            StudioNode.CLASS_MODEL -> StudioNode(
                id = nodeId,
                name = "Model",
                className = StudioNode.CLASS_MODEL,
                parentId = parentId,
                nodeProperties = mapOf(
                    "ClassName" to StudioNode.CLASS_MODEL,
                    "Name" to "Model",
                    "PrimaryPart" to "",
                    "LevelOfDetail" to "Automatic",
                    "NeedsPivotMigration" to "false",
                    "WorldPivotData" to "pos 0.000, 0.000, 0.000; rot identity",
                    "ParentId" to (parentId ?: "Workspace")
                )
            )
            in StudioNode.SCRIPT_CLASS_NAMES -> StudioNode(
                id = nodeId,
                name = className,
                className = className,
                parentId = parentId,
                scriptSource = defaultScriptSource(className),
                nodeProperties = defaultScriptProperties(className, parentId)
            )
            else -> {
                // Part-like: create Part + node
                val shape = when (className) {
                    StudioNode.CLASS_BALL_PART -> Part.SHAPE_SPHERE
                    StudioNode.CLASS_WEDGE_PART -> Part.SHAPE_WEDGE
                    StudioNode.CLASS_CORNER_WEDGE_PART -> Part.SHAPE_CORNER_WEDGE
                    StudioNode.CLASS_TRUSS_PART -> Part.SHAPE_TRUSS
                    StudioNode.CLASS_MESH_PART -> Part.SHAPE_MESH
                    StudioNode.CLASS_SPAWN_LOCATION -> Part.SHAPE_SPAWN_LOCATION
                    else -> Part.SHAPE_BLOCK
                }
                val part = Part(
                    id = UUID.randomUUID().toString(),
                    name = "New${shape.lowercase().replaceFirstChar { it.titlecase() }}",
                    shape = shape,
                    position = pivot + Vector3(3f, 0f, 0f),
                    size = Vector3(2f, 2f, 2f),
                    parentId = parentId,
                    currentPosition = pivot + Vector3(3f, 0f, 0f)
                )
                val partNode = StudioNode(
                    id = nodeId, name = part.name, className = className, parentId = parentId, part = part
                )
                commitHistory(_parts.value + part, _nodes.value + partNode)
                _selectedPart.value = part
                _selectedNode.value = _nodes.value.firstOrNull { it.id == partNode.id } ?: partNode
                logSystem("Inserted $className '${partNode.name}' into workspace.")
                return
            }
        }

        _nodes.value = _nodes.value + node
        _selectedNode.value = node
        logSystem("Inserted $className '${node.name}' into workspace.")
    }

    fun renameNode(nodeId: String, newName: String) {
        _nodes.value = _nodes.value.map {
            if (it.id == nodeId) it.copy(name = newName, nodeProperties = it.nodeProperties + ("Name" to newName)) else it
        }
        // Sync part name if this node has a part
        val node = _nodes.value.find { it.id == nodeId }
        if (node?.part != null) {
            val updatedPart = node.part!!.copy(name = newName)
            val list = _parts.value.map { if (it.id == updatedPart.id) updatedPart else it }
            commitHistory(list)
            _selectedPart.value = updatedPart
        }
        _selectedNode.value = _selectedNode.value?.let {
            if (it.id == nodeId) it.copy(name = newName, nodeProperties = it.nodeProperties + ("Name" to newName)) else it
        }
    }

    fun deleteNode(nodeId: String) {
        val nodesBeforeDelete = _nodes.value
        val node = nodesBeforeDelete.find { it.id == nodeId } ?: return
        // Collect all descendant ids
        val toDelete = StudioNodeGraph.collectSubtreeIds(nodesBeforeDelete, nodeId)
        val partsToDelete = StudioNodeGraph.partIdsForNodes(nodesBeforeDelete, toDelete)
        // Remove nodes
        _nodes.value = nodesBeforeDelete.filter { it.id !in toDelete }
        // Remove parts belonging to deleted nodes
        if (partsToDelete.isNotEmpty()) {
            val list = _parts.value.filter { it.id !in partsToDelete }
            commitHistory(list)
        }
        if (_selectedNode.value?.id in toDelete) {
            _selectedNode.value = null
            _selectedPart.value = null
        }
        logSystem("Deleted '${node.name}' and ${toDelete.size - 1} children.")
    }

    fun deleteNodeOrPart(node: StudioNode) {
        if (node.isService) return
        val storedNode = _nodes.value.find { it.id == node.id }
        when {
            storedNode != null -> deleteNode(storedNode.id)
            node.part != null -> deletePart(node.part.id)
        }
    }

    fun updateNodeScript(nodeId: String, source: String) {
        _nodes.value = _nodes.value.map {
            if (it.id == nodeId) {
                it.copy(
                    scriptSource = source,
                    nodeProperties = it.nodeProperties + ("Source" to source)
                )
            } else {
                it
            }
        }
        _selectedNode.value = _selectedNode.value?.let {
            if (it.id == nodeId) {
                it.copy(
                    scriptSource = source,
                    nodeProperties = it.nodeProperties + ("Source" to source)
                )
            } else {
                it
            }
        }
    }

    fun updateNode(updated: StudioNode) {
        if (updated.part != null) return
        _nodes.value = _nodes.value.map { if (it.id == updated.id) updated else it }
        _selectedNode.value = _selectedNode.value?.let { if (it.id == updated.id) updated else it }
    }

    /** StateFlow of explorer nodes (services + user nodes + auto-wrapped parts). */
    val explorerNodes: StateFlow<List<StudioNode>> = combine(_nodes, _parts) { userNodes, parts ->
        val services = defaultServiceNodes().map { fallback ->
            userNodes.firstOrNull { it.isService && it.className == fallback.className } ?: fallback
        }
        val syncedUserNodes = StudioNodeGraph.syncPartBackedNodes(userNodes.filterNot { it.isService }, parts)
        val wrappedParts = parts.filter { p -> syncedUserNodes.none { it.part?.id == p.id } }.map { p ->
            StudioNode(p.id, p.name, "Part", p.parentId, part = p)
        }
        services + syncedUserNodes + wrappedParts
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Compose-friendly alias for collecting explorer nodes. */
    fun buildExplorerNodesAsState(): StateFlow<List<StudioNode>> = explorerNodes

    fun updatePartProperty(updated: Part) {
        if (_isPlaying.value) {
            // Edit runtime state directly
            setPartsAndSyncNodes(_parts.value.map { if (it.id == updated.id) updated else it })
            _selectedPart.value = updated
        } else {
            // Update live state immediately for responsive UI, but debounce history commits
            // so rapid edits (e.g. typing in a text field) don't spam the undo stack.
            val list = _parts.value.map { if (it.id == updated.id) updated else it }
            setPartsAndSyncNodes(list)
            _selectedPart.value = updated
            scheduleHistoryCommit(list)
        }
    }

    fun updatePartsBatch(updated: List<Part>) {
        if (updated.isEmpty()) return
        val replacements = updated.associateBy { it.id }
        val list = _parts.value.map { replacements[it.id] ?: it }
        setPartsAndSyncNodes(list)
        _selectedPart.value = _selectedPart.value?.let { replacements[it.id] ?: it }
        _selectedNode.value = StudioNodeGraph.resolveNode(_selectedNode.value, _nodes.value, list)
        if (!_isPlaying.value) scheduleHistoryCommit(list)
    }

    fun selectedTransformParts(): List<Part> {
        _selectedPart.value?.let { return listOf(it) }
        val selected = _selectedNode.value ?: return emptyList()
        if (selected.className != StudioNode.CLASS_MODEL) return emptyList()
        val descendantIds = StudioNodeGraph.collectSubtreeIds(_nodes.value, selected.id)
        val partIds = _nodes.value.asSequence()
            .filter { it.id in descendantIds }
            .mapNotNull { it.part?.id }
            .toSet()
        return _parts.value.filter { it.id in partIds }
    }

    private var historyCommitJob: kotlinx.coroutines.Job? = null

    /**
     * Debounces history commits: rapid successive property edits within 400ms are
     * collapsed into a single undo entry, preventing the undo stack from being
     * spammed on every keystroke / slider tick.
     */
    private fun scheduleHistoryCommit(list: List<Part>) {
        historyCommitJob?.cancel()
        historyCommitJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            commitHistory(list)
        }
    }

    fun addPart(shape: String) {
        val newPart = Part(
            id = UUID.randomUUID().toString(),
            name = "Part_${shape.lowercase()}",
            shape = shape,
            position = Vector3(0f, 4f, 0f),
            size = when (shape) {
                Part.SHAPE_BLOCK -> Vector3(4f, 4f, 4f)
                Part.SHAPE_SPHERE -> Vector3(4f, 4f, 4f)
                Part.SHAPE_CYLINDER -> Vector3(4f, 6f, 4f)
                Part.SHAPE_WEDGE -> Vector3(4f, 4f, 6f)
                Part.SHAPE_SPAWN_LOCATION -> Vector3(5f, 0.4f, 5f)
                else -> Vector3(3f, 3f, 3f)
            },
            colorHex = getRandomColorHex(),
            material = Part.MATERIAL_PLASTIC,
            anchored = true,
            canCollide = true
        )
        val list = _parts.value + newPart
        commitHistory(list)
        _selectedPart.value = newPart
        logSystem("Created new $shape: ${newPart.name}")
    }

    fun duplicateSelectedPart() {
        val selected = _selectedPart.value ?: return
        val duplicated = selected.copy(
            id = UUID.randomUUID().toString(),
            name = "${selected.name}_Dup",
            position = selected.position + Vector3(3f, 0f, 0f),
            currentPosition = selected.currentPosition + Vector3(3f, 0f, 0f)
        )
        val list = _parts.value + duplicated
        commitHistory(list)
        _selectedPart.value = duplicated
        logSystem("Duplicated ${selected.name} as ${duplicated.name}")
    }

    // Clipboard for copy/paste
    private var clipboard: Part? = null

    fun copySelectedPart() {
        val selected = _selectedPart.value ?: return
        clipboard = selected
        logSystem("Copied ${selected.name} to clipboard.")
    }

    fun pastePart() {
        val clip = clipboard ?: run {
            logSystem("Clipboard is empty.")
            return
        }
        val pasted = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name}_Paste",
            position = clip.position + Vector3(5f, 0f, 0f),
            currentPosition = clip.currentPosition + Vector3(5f, 0f, 0f)
        )
        val list = _parts.value + pasted
        commitHistory(list)
        _selectedPart.value = pasted
        logSystem("Pasted ${pasted.name} into workspace.")
    }

    fun renamePart(partId: String, newName: String) {
        val list = _parts.value.map { if (it.id == partId) it.copy(name = newName) else it }
        commitHistory(list)
        _selectedPart.value = _selectedPart.value?.let { if (it.id == partId) it.copy(name = newName) else it }
        logSystem("Renamed part to: $newName")
    }

    fun deletePart(partId: String) {
        val rootNodeIds = _nodes.value.filter { it.part?.id == partId }.map { it.id }
        val descendantIds = rootNodeIds.flatMapTo(linkedSetOf()) {
            StudioNodeGraph.collectSubtreeIds(_nodes.value, it)
        }
        val remainingNodes = _nodes.value.filter { it.id !in descendantIds }
        val list = _parts.value.filter { it.id != partId }
        commitHistory(list, remainingNodes)
        if (_selectedPart.value?.id == partId) _selectedPart.value = null
        logSystem("Deleted part.")
    }

    fun insertObjectIntoSelected(shape: String) {
        // Map shape string to className for insertNode
        val className = when (shape) {
            Part.SHAPE_SPHERE -> StudioNode.CLASS_BALL_PART
                Part.SHAPE_WEDGE -> StudioNode.CLASS_WEDGE_PART
                Part.SHAPE_CORNER_WEDGE -> StudioNode.CLASS_CORNER_WEDGE_PART
                Part.SHAPE_TRUSS -> StudioNode.CLASS_TRUSS_PART
                Part.SHAPE_MESH -> StudioNode.CLASS_MESH_PART
            Part.SHAPE_SPAWN_LOCATION -> StudioNode.CLASS_SPAWN_LOCATION
            else -> StudioNode.CLASS_PART
        }
        insertNode(className)
    }

    /** Insert a node by className from the Insert Object popup. */
    fun insertObjectByClass(className: String) {
        when (className) {
            "CylinderPart" -> {
                // Special case: Part with Cylinder shape
                val selected = _selectedNode.value
                val parentId = insertionParentId(selected)
                val pivot = selected?.part?.currentPosition ?: Vector3(0f, 4f, 0f)
                val part = Part(
                    id = UUID.randomUUID().toString(),
                    name = "NewCylinder",
                    shape = Part.SHAPE_CYLINDER,
                    position = pivot + Vector3(3f, 0f, 0f),
                    size = Vector3(2f, 2f, 2f),
                    parentId = parentId,
                    currentPosition = pivot + Vector3(3f, 0f, 0f)
                )
                val node = StudioNode(UUID.randomUUID().toString(), part.name, "CylinderPart", parentId, part = part)
                commitHistory(_parts.value + part, _nodes.value + node)
                _selectedPart.value = part
                _selectedNode.value = _nodes.value.firstOrNull { it.id == node.id } ?: node
            }
            StudioNode.CLASS_DECAL, StudioNode.CLASS_TEXTURE, StudioNode.CLASS_WELD, StudioNode.CLASS_WELD_CONSTRAINT,
            StudioNode.CLASS_ATTACHMENT, StudioNode.CLASS_REMOTE_EVENT, StudioNode.CLASS_SOUND,
            StudioNode.CLASS_POINT_LIGHT, StudioNode.CLASS_SPOT_LIGHT, StudioNode.CLASS_SURFACE_LIGHT,
            StudioNode.CLASS_SCREEN_GUI, StudioNode.CLASS_FRAME, StudioNode.CLASS_TEXT_LABEL, StudioNode.CLASS_TEXT_BUTTON,
            StudioNode.CLASS_TEXT_BOX,
            StudioNode.CLASS_IMAGE_LABEL, StudioNode.CLASS_IMAGE_BUTTON,
            StudioNode.CLASS_SKY, StudioNode.CLASS_CLICK_DETECTOR,
            StudioNode.CLASS_TRAIL, StudioNode.CLASS_BEAM, StudioNode.CLASS_PARTICLE_EMITTER,
            StudioNode.CLASS_SURFACE_GUI, StudioNode.CLASS_UI_LIST_LAYOUT,
            StudioNode.CLASS_UI_CORNER, StudioNode.CLASS_UI_STROKE, StudioNode.CLASS_UI_GRADIENT,
            StudioNode.CLASS_HIGHLIGHT,
            "ProximityPrompt", "Dialog" -> {
                // Non-renderable 3D interface — create as node without part
                val selected = _selectedNode.value
                val extraNodes = mutableListOf<StudioNode>()
                val parentId = guiInsertionParentId(className, selected, extraNodes)
                    ?: objectInsertionParentId(className, selected)
                val defaultProperties = when (className) {
                    StudioNode.CLASS_DECAL -> mapOf(
                        "ClassName" to StudioNode.CLASS_DECAL,
                        "Name" to "Decal",
                        "Texture" to "",
                        "Face" to "Front",
                        "Transparency" to "0.0",
                        "ZIndex" to "1",
                        "Color3" to "#FFFFFF",
                        "ParentId" to (parentId ?: "Workspace")
                    )
                    StudioNode.CLASS_TEXTURE -> mapOf(
                        "ClassName" to StudioNode.CLASS_TEXTURE,
                        "Name" to "Texture",
                        "Texture" to "",
                        "Face" to "Front",
                        "Transparency" to "0.0",
                        "ZIndex" to "1",
                        "Color3" to "#FFFFFF",
                        "StudsPerTileU" to "2.0",
                        "StudsPerTileV" to "2.0",
                        "OffsetStudsU" to "0.0",
                        "OffsetStudsV" to "0.0",
                        "ParentId" to (parentId ?: "Workspace")
                    )
                    StudioNode.CLASS_WELD, StudioNode.CLASS_WELD_CONSTRAINT -> mapOf(
                        "ClassName" to className,
                        "Name" to className,
                        "Part0" to (selected?.part?.id ?: ""),
                        "Part1" to "",
                        "C0" to "pos 0.000, 0.000, 0.000; rot identity",
                        "C1" to "pos 0.000, 0.000, 0.000; rot identity",
                        "Enabled" to "true",
                        "ParentId" to (parentId ?: "Workspace")
                    )
                    in StudioNode.GUI_CLASS_NAMES -> defaultGuiProperties(className, parentId)
                    StudioNode.CLASS_ATTACHMENT,
                    StudioNode.CLASS_REMOTE_EVENT,
                    StudioNode.CLASS_SOUND,
                    StudioNode.CLASS_POINT_LIGHT,
                    StudioNode.CLASS_SPOT_LIGHT,
                    StudioNode.CLASS_SURFACE_LIGHT,
                    StudioNode.CLASS_TRAIL,
                    StudioNode.CLASS_BEAM,
                    StudioNode.CLASS_PARTICLE_EMITTER -> defaultObjectProperties(className, parentId)
                    StudioNode.CLASS_HIGHLIGHT -> defaultObjectProperties(className, parentId)
                    StudioNode.CLASS_SKY,
                    StudioNode.CLASS_CLICK_DETECTOR -> defaultObjectProperties(className, parentId)
                    StudioNode.CLASS_SURFACE_GUI -> defaultGuiProperties(className, parentId)
                    StudioNode.CLASS_UI_LIST_LAYOUT -> mapOf(
                        "ClassName" to className,
                        "Name" to className,
                        "FillDirection" to "Vertical",
                        "HorizontalAlignment" to "Center",
                        "Padding" to "scale=0.0, offset=0",
                        "SortOrder" to "LayoutOrder",
                        "VerticalAlignment" to "Center",
                        "Wraps" to "false",
                        "ParentId" to (parentId ?: StudioNode.CLASS_STARTER_GUI)
                    )
                    StudioNode.CLASS_UI_CORNER -> mapOf(
                        "ClassName" to className,
                        "Name" to className,
                        "CornerRadius" to "scale=0.0, offset=8",
                        "ParentId" to (parentId ?: StudioNode.CLASS_STARTER_GUI)
                    )
                    StudioNode.CLASS_UI_STROKE -> mapOf(
                        "ClassName" to className,
                        "Name" to className,
                        "ApplyStrokeMode" to "Border",
                        "Color" to "#000000",
                        "Enabled" to "true",
                        "LineJoinMode" to "Round",
                        "Thickness" to "1.0",
                        "Transparency" to "0.0",
                        "ParentId" to (parentId ?: StudioNode.CLASS_STARTER_GUI)
                    )
                    StudioNode.CLASS_UI_GRADIENT -> defaultGuiProperties(className, parentId)
                    else -> mapOf(
                        "ClassName" to className,
                        "Name" to className,
                        "ParentId" to (parentId ?: "Workspace")
                    )
                }
                val node = StudioNode(
                    UUID.randomUUID().toString(),
                    defaultProperties["Name"] ?: className,
                    className,
                    parentId,
                    nodeProperties = defaultProperties
                )
                _nodes.value = _nodes.value + extraNodes + node
                _selectedNode.value = node
            }
            else -> insertNode(className)
        }
        logSystem("Inserted $className.")
    }

    fun deleteSelectedPart() {
        val selected = _selectedPart.value ?: return
        deletePart(selected.id)
    }

    fun anchorSelectedPart(anchored: Boolean) {
        val selected = _selectedPart.value ?: return
        val updated = selected.copy(anchored = anchored)
        updatePartProperty(updated)
        logSystem("${selected.name} Anchored status set to: $anchored")
    }

    fun changeColorSelectedPart(colorHex: String) {
        val selected = _selectedPart.value ?: return
        val updated = selected.copy(colorHex = colorHex)
        updatePartProperty(updated)
    }

    fun changeMaterialSelectedPart(material: String) {
        val selected = _selectedPart.value ?: return
        val updated = selected.copy(material = material)
        updatePartProperty(updated)
        logSystem("Changed ${selected.name} material to $material")
    }

    fun addEffectToSelectedPart(effect: String) {
        val selected = _selectedPart.value ?: return
        val updated = selected.copy(effect = effect)
        updatePartProperty(updated)
        logSystem("Added effect '$effect' to ${selected.name}")
    }

    // --- Roblox Toolbox Marketplace ---
    fun setToolboxSearchQuery(query: String) {
        _toolboxState.update { it.copy(query = query) }
        scheduleToolboxSearch()
    }

    fun setToolboxRoblosecurityCookie(cookie: String) {
        setRobloxAuthCookie(cookie, logUpdate = false)
    }

    fun saveRobloxLoginCookie(cookie: String) {
        setRobloxAuthCookie(cookie, logUpdate = true)
    }

    private fun setRobloxAuthCookie(cookie: String, logUpdate: Boolean) {
        val normalized = RobloxAuthStore.normalizeRoblosecurityCookie(cookie)
        _roblosecurityCookie.value = normalized
        _toolboxState.update { it.copy(roblosecurityCookie = normalized) }
        authStore.saveRoblosecurityCookie(normalized)
        if (logUpdate && normalized.isNotBlank()) {
            logSystem("Roblox login saved for Toolbox and Publish.")
        }
    }

    fun setToolboxAssetType(assetType: ToolboxAssetType) {
        if (_toolboxState.value.selectedType == assetType) return
        _toolboxState.update {
            it.copy(
                selectedType = assetType,
                results = emptyList(),
                nextPageCursor = null,
                error = null
            )
        }
        searchToolbox(reset = true)
    }

    fun refreshToolbox() {
        searchToolbox(reset = true)
    }

    fun loadMoreToolboxResults() {
        val state = _toolboxState.value
        if (state.nextPageCursor.isNullOrBlank() || state.isLoading || state.isLoadingMore) return
        searchToolbox(reset = false)
    }

    private fun scheduleToolboxSearch() {
        toolboxSearchJob?.cancel()
        toolboxSearchJob = viewModelScope.launch {
            delay(450)
            performToolboxSearch(reset = true)
        }
    }

    private fun searchToolbox(reset: Boolean) {
        toolboxSearchJob?.cancel()
        toolboxSearchJob = viewModelScope.launch {
            performToolboxSearch(reset)
        }
    }

    private suspend fun performToolboxSearch(reset: Boolean) {
        val state = _toolboxState.value
        val cursor = if (reset) null else state.nextPageCursor
        _toolboxState.update {
            it.copy(
                isLoading = reset,
                isLoadingMore = !reset,
                error = null,
                nextPageCursor = if (reset) null else it.nextPageCursor,
                results = if (reset) emptyList() else it.results
            )
        }

        try {
            val page = toolboxClient.searchMarketplace(
                query = state.query,
                assetType = state.selectedType,
                cursor = cursor
            )
            _toolboxState.update { current ->
                val merged = if (reset) {
                    page.assets
                } else {
                    (current.results + page.assets).distinctBy { it.assetId }
                }
                current.copy(
                    results = merged,
                    nextPageCursor = page.nextPageCursor,
                    isLoading = false,
                    isLoadingMore = false,
                    error = if (merged.isEmpty()) "No ${state.selectedType.label.lowercase()} found." else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toolboxState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message ?: "Toolbox search failed."
                )
            }
            logSystem("❌ Toolbox search failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun insertToolboxAsset(asset: ToolboxAsset) {
        if (asset.assetTypeId == ToolboxAssetTypes.Meshes.assetTypeId) {
            val selected = _selectedNode.value
            val parentId = insertionParentId(selected)
            val pivot = (selected?.part?.currentPosition ?: Vector3(0f, 4f, 0f)) + Vector3(3f, 0f, 0f)
            val part = Part(
                id = UUID.randomUUID().toString(),
                name = asset.name.ifBlank { "MeshPart" },
                shape = Part.SHAPE_MESH,
                position = pivot,
                currentPosition = pivot,
                size = Vector3(2f, 2f, 2f),
                initialSize = Vector3(2f, 2f, 2f),
                meshId = "rbxassetid://${asset.assetId}",
                sourceAssetId = asset.assetId,
                parentId = parentId
            )
            val node = StudioNode(
                id = UUID.randomUUID().toString(),
                name = part.name,
                className = StudioNode.CLASS_MESH_PART,
                parentId = parentId,
                part = part
            )
            commitHistory(_parts.value + part, _nodes.value + node)
            _selectedPart.value = part
            _selectedNode.value = node
            logSystem("● Inserted MeshPart '${part.name}' from Toolbox.")
            return
        }
        if (asset.assetTypeId != null && asset.assetTypeId != ToolboxAssetTypes.Models.assetTypeId) {
            val type = asset.assetTypeName.ifBlank { "asset type ${asset.assetTypeId}" }
            logSystem("Toolbox: '$type' items can be browsed, but only Model assets can be inserted right now.")
            return
        }

        viewModelScope.launch {
            _toolboxState.update { it.copy(insertingAssetId = asset.assetId) }
            try {
                logSystem("Toolbox: Downloading '${asset.name}' (${asset.assetId}) from AssetDelivery...")
                val data = toolboxClient.downloadAsset(
                    assetId = asset.assetId,
                    roblosecurityCookie = _toolboxState.value.roblosecurityCookie
                )
                val parsedImport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val instances = com.example.parser.RobloxParser.parseRobloxFile(data)
                    parseRobloxImport(instances)
                }
                val selectedNodeSnapshot = _selectedNode.value
                val selectedPartSnapshot = _selectedPart.value
                val preparedImport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    prepareRobloxImport(
                        parsed = parsedImport,
                        sourceAssetId = asset.assetId,
                        offsetToSelection = true,
                        selectedNode = selectedNodeSnapshot,
                        selectedPart = selectedPartSnapshot
                    )
                }
                if (preparedImport.parts.isEmpty() && preparedImport.nodes.isEmpty()) {
                    logSystem("● Toolbox asset '${asset.name}' downloaded, but it has no supported instances.")
                    return@launch
                }

                addPreparedRobloxImport(preparedImport)
                val focusPart = preparedImport.parts.firstOrNull()
                _selectedPart.value = focusPart
                _selectedNode.value = focusPart?.let { StudioNodeGraph.nodeForPart(it, _nodes.value) }
                    ?: preparedImport.nodes.firstOrNull()
                logSystem("● Inserted '${asset.name}' from Toolbox: ${preparedImport.parts.size} parts, ${preparedImport.nodes.size} nodes added.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logSystem("❌ Toolbox insert failed for '${asset.name}': ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _toolboxState.update {
                    if (it.insertingAssetId == asset.assetId) it.copy(insertingAssetId = null) else it
                }
            }
        }
    }

    private data class ParsedRobloxImport(
        val parts: List<Part>,
        val nodes: List<StudioNode>
    )

    private data class PreparedRobloxImport(
        val parts: List<Part>,
        val nodes: List<StudioNode>
    )

    private fun parseRobloxImport(instances: List<com.example.parser.RobloxParser.RobloxInstance>): ParsedRobloxImport {
        val parts = com.example.parser.RobloxParser.instancesToParts(instances)
        val nodes = com.example.parser.RobloxParser.instancesToStudioNodes(instances, parts)
        return ParsedRobloxImport(parts = parts, nodes = nodes)
    }

    private fun prepareRobloxImport(
        parsed: ParsedRobloxImport,
        sourceAssetId: Long? = null,
        offsetToSelection: Boolean,
        selectedNode: StudioNode? = _selectedNode.value,
        selectedPart: Part? = _selectedPart.value
    ): PreparedRobloxImport {
        val parsedNodeIds = parsed.nodes.asSequence().map { it.id }.toHashSet()
        val idMap = parsed.nodes.associate { node ->
            node.id to if (node.className in StudioNode.SERVICE_CLASS_NAMES) node.className else UUID.randomUUID().toString()
        } +
            parsed.parts.filter { part -> part.id !in parsedNodeIds }
                .associate { it.id to UUID.randomUUID().toString() }
        val targetParentId = insertionParentId(selectedNode)
        val offset = if (offsetToSelection && parsed.parts.isNotEmpty()) {
            val targetCenter = (selectedPart?.currentPosition ?: Vector3.Zero) + Vector3(0f, 6f, 0f)
            targetCenter - partsBoundsCenter(parsed.parts)
        } else {
            Vector3.Zero
        }

        fun remapPartParent(parentId: String?): String? {
            if (parentId == null) return targetParentId
            return idMap[parentId] ?: targetParentId
        }

        fun remapNodeParent(node: StudioNode): String? {
            if (node.className == StudioNode.CLASS_SCREEN_GUI) return StudioNode.CLASS_STARTER_GUI
            val fallbackParentId = if (node.className == StudioNode.CLASS_SCREEN_GUI || node.isGuiObject) {
                StudioNode.CLASS_STARTER_GUI
            } else {
                targetParentId
            }
            return node.parentId?.let { idMap[it] ?: fallbackParentId } ?: fallbackParentId
        }

        fun remapProperties(properties: Map<String, String>, propertyTypeIds: Map<String, Int>): Map<String, String> {
            return properties.mapValues { (key, value) ->
                when {
                    key == "SourceAssetId" && sourceAssetId != null -> sourceAssetId.toString()
                    key == "ParentId" || propertyTypeIds[key] == 0x13 || key in setOf(
                        "PrimaryPart", "Part0", "Part1", "Attachment0", "Attachment1", "Adornee",
                        "SelectionImageObject", "RootLocalizationTable", "NextSelectionDown",
                        "NextSelectionLeft", "NextSelectionRight", "NextSelectionUp"
                    ) -> idMap[value] ?: value
                    else -> value
                }
            }
        }

        val preparedPartsByOldId = parsed.parts.associate { part ->
            val position = part.position + offset
            val currentPosition = part.currentPosition + offset
            part.id to part.copy(
                id = idMap[part.id] ?: UUID.randomUUID().toString(),
                parentId = remapPartParent(part.parentId),
                position = position,
                currentPosition = currentPosition,
                sourceAssetId = sourceAssetId ?: part.sourceAssetId
            )
        }

        val preparedNodes = parsed.nodes.map { node ->
            if (node.className in StudioNode.SERVICE_CLASS_NAMES) {
                return@map node.copy(
                    id = node.className,
                    name = node.className,
                    parentId = null,
                    part = null,
                    isService = true,
                    nodeProperties = remapProperties(node.nodeProperties, node.propertyTypeIds)
                )
            }
            val preparedPart = node.part?.let { preparedPartsByOldId[it.id] }
            val newId = idMap[node.id] ?: UUID.randomUUID().toString()
            val parentId = remapNodeParent(node)
            node.copy(
                id = newId,
                parentId = parentId,
                part = preparedPart,
                nodeProperties = remapProperties(node.nodeProperties, node.propertyTypeIds)
                    .plus("ParentId" to (parentId ?: "Workspace")),
                propertyTypeIds = node.propertyTypeIds
            )
        }

        return PreparedRobloxImport(
            parts = preparedPartsByOldId.values.toList(),
            nodes = preparedNodes
        )
    }

    private fun addPreparedRobloxImport(prepared: PreparedRobloxImport) {
        val combinedNodes = normalizeStoredNodes(_nodes.value + prepared.nodes)
        val combinedParts = _parts.value + prepared.parts
        if (prepared.parts.isNotEmpty()) commitHistory(combinedParts, combinedNodes)
        else syncNodesWithParts(combinedParts, combinedNodes)
    }

    private fun partsBoundsCenter(parts: List<Part>): Vector3 {
        if (parts.isEmpty()) return Vector3.Zero

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        parts.forEach { part ->
            val half = part.size * 0.5f
            minX = minOf(minX, part.position.x - half.x)
            minY = minOf(minY, part.position.y - half.y)
            minZ = minOf(minZ, part.position.z - half.z)
            maxX = maxOf(maxX, part.position.x + half.x)
            maxY = maxOf(maxY, part.position.y + half.y)
            maxZ = maxOf(maxZ, part.position.z + half.z)
        }

        return Vector3(
            x = (minX + maxX) * 0.5f,
            y = (minY + maxY) * 0.5f,
            z = (minZ + maxZ) * 0.5f
        )
    }

    // --- Insert Preset Models from Toolbox ---
    fun insertToolboxModel(modelName: String) {
        val pivot = Vector3(0f, 6f, 0f)
        val list = _parts.value.toMutableList()
        
        when (modelName) {
            "Roblox Noob" -> {
                val headId = UUID.randomUUID().toString()
                val torsoId = UUID.randomUUID().toString()
                val leftArmId = UUID.randomUUID().toString()
                val rightArmId = UUID.randomUUID().toString()
                val leftLegId = UUID.randomUUID().toString()
                val rightLegId = UUID.randomUUID().toString()

                // Torso
                val torso = Part(torsoId, "NoobTorso", Part.SHAPE_BLOCK, pivot + Vector3(0f, 1f, 0f), Vector3(3f, 3f, 1.5f), colorHex = "#0055A5", anchored = false)
                // Head
                val head = Part(headId, "NoobHead", Part.SHAPE_SPHERE, pivot + Vector3(0f, 3.2f, 0f), Vector3(2f, 2f, 2f), colorHex = "#E5A100", anchored = false, effect = Part.EFFECT_SPARKLES)
                // Arms
                val lArm = Part(leftArmId, "NoobLeftArm", Part.SHAPE_BLOCK, pivot + Vector3(-2.2f, 1f, 0f), Vector3(1.2f, 3f, 1.2f), colorHex = "#E5A100", anchored = false)
                val rArm = Part(rightArmId, "NoobRightArm", Part.SHAPE_BLOCK, pivot + Vector3(2.2f, 1f, 0f), Vector3(1.2f, 3f, 1.2f), colorHex = "#E5A100", anchored = false)
                // Legs
                val lLeg = Part(leftLegId, "NoobLeftLeg", Part.SHAPE_BLOCK, pivot + Vector3(-0.8f, -1.8f, 0f), Vector3(1.2f, 3f, 1.2f), colorHex = "#A5A500", anchored = false)
                val rLeg = Part(rightLegId, "NoobRightLeg", Part.SHAPE_BLOCK, pivot + Vector3(0.8f, -1.8f, 0f), Vector3(1.2f, 3f, 1.2f), colorHex = "#A5A500", anchored = false)

                list.addAll(listOf(torso, head, lArm, rArm, lLeg, rLeg))
                logSystem("Inserted Roblox Noob model containing 6 joint parts!")
            }
            "Pine Tree" -> {
                val trunkId = UUID.randomUUID().toString()
                val leavesId = UUID.randomUUID().toString()

                val trunk = Part(trunkId, "TreeTrunk", Part.SHAPE_CYLINDER, pivot + Vector3(0f, 1.5f, 0f), Vector3(1.5f, 5f, 1.5f), colorHex = "#5C4033", material = Part.MATERIAL_WOOD, anchored = true)
                val leaves = Part(leavesId, "TreeLeaves", Part.SHAPE_SPHERE, pivot + Vector3(0f, 5f, 0f), Vector3(6f, 6f, 6f), colorHex = "#228B22", material = Part.MATERIAL_FABRIC, anchored = true)

                list.addAll(listOf(trunk, leaves))
                logSystem("Inserted Pine Tree model.")
            }
            "Lava Spike" -> {
                val spike = Part(UUID.randomUUID().toString(), "LavaSpike", Part.SHAPE_WEDGE, pivot, Vector3(3f, 6f, 3f), colorHex = "#FF4500", material = Part.MATERIAL_NEON, anchored = true, script = "while true do\n  wait(0.2)\n  script.Parent.Color = Color3.fromRGB(255, 69, 0)\n  wait(0.2)\n  script.Parent.Color = Color3.fromRGB(255, 0, 0)\nend", effect = Part.EFFECT_FIRE)
                list.add(spike)
                logSystem("Inserted Lava Spike obstacle with pulsing fire scripts.")
            }
            "Science Lab Dome" -> {
                val dome = Part(UUID.randomUUID().toString(), "LabDome", Part.SHAPE_SPHERE, pivot, Vector3(12f, 12f, 12f), colorHex = "#00FFDD", material = Part.MATERIAL_GLASS, anchored = true, effect = Part.EFFECT_POINTLIGHT)
                list.add(dome)
                logSystem("Inserted premium translucent Science Dome module.")
            }
            "Energy Tower" -> {
                val base = Part(UUID.randomUUID().toString(), "TowerBase", Part.SHAPE_CYLINDER, pivot, Vector3(4f, 8f, 4f), colorHex = "#777777", material = Part.MATERIAL_METAL, anchored = true)
                val orb = Part(UUID.randomUUID().toString(), "EnergyOrb", Part.SHAPE_SPHERE, pivot + Vector3(0f, 6f, 0f), Vector3(3f, 3f, 3f), colorHex = "#00FFFF", material = Part.MATERIAL_NEON, anchored = true, effect = Part.EFFECT_SPARKLES)
                list.addAll(listOf(base, orb))
                logSystem("Inserted high-tech Energy Grid Transmitter Tower.")
            }
        }
        commitHistory(list)
    }

    /**
     * Imports a Roblox .rbxm/.rbxl (binary) or .rbxmx/.rbxlx (XML) file, parsing it into
     * [Part]s and adding them to the workspace.
     */
    /** Serializes the current scene to .rbxl bytes. Returns null when no place is open. */
    fun exportActivePlaceAsRbxl(): Pair<String, ByteArray>? {
        val currentPlace = _activePlace.value ?: return null
        val currentParts = if (_isPlaying.value) simulationOriginalState else _parts.value
        val fileName = currentPlace.name.ifBlank { "place" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_") + ".rbxl"
        val bytes = RobloxPlaceBinarySerializer.serialize(currentPlace.name, currentParts, _nodes.value)
        return fileName to bytes
    }

    fun importRobloxFile(data: ByteArray, fileName: String) {
        viewModelScope.launch {
        try {
            val selectedNodeSnapshot = _selectedNode.value
            val selectedPartSnapshot = _selectedPart.value
            val preparedImport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val instances = com.example.parser.RobloxParser.parseRobloxFile(data)
                val parsedImport = parseRobloxImport(instances)
                prepareRobloxImport(
                    parsed = parsedImport,
                    sourceAssetId = null,
                    offsetToSelection = false,
                    selectedNode = selectedNodeSnapshot,
                    selectedPart = selectedPartSnapshot
                )
            }
            if (preparedImport.parts.isEmpty() && preparedImport.nodes.isEmpty()) {
                logSystem("● No supported instances found in '$fileName'.")
                return@launch
            }
            addPreparedRobloxImport(preparedImport)
            val focusPart = preparedImport.parts.firstOrNull { !it.name.equals("Baseplate", ignoreCase = true) }
                ?: preparedImport.parts.firstOrNull()
            _selectedPart.value = focusPart
            _selectedNode.value = focusPart?.let { StudioNodeGraph.nodeForPart(it, _nodes.value) }
                ?: preparedImport.nodes.firstOrNull()
            val previewNames = preparedImport.nodes.take(4).joinToString { it.name }
            val suffix = if (preparedImport.nodes.size > 4) ", ..." else ""
            logSystem("● Imported $fileName: ${preparedImport.parts.size} parts, ${preparedImport.nodes.size} nodes added ($previewNames$suffix).")
        } catch (e: Exception) {
            logSystem("❌ Failed to import '$fileName': ${e.message}")
        }
        }
    }

    // --- Physics Simulation Loop ---
    fun togglePlaySimulation() {
        if (_isPlaying.value) {
            stopSimulation()
        } else {
            startSimulation()
        }
    }

    private fun startSimulation() {
        _isPlaying.value = true
        simulationOriginalState = _parts.value.map { it.copy() } // Save original values
        logSystem("Workspace physics simulation is active. Running scripts and gravity updates...")
        
        var scriptTime = 0f
        simulationJob = viewModelScope.launch {
            val gravity = -9.8f // World gravity
            val dt = 0.04f // 40ms simulation steps (~25 fps)
            
            while (_isPlaying.value) {
                delay(40)
                scriptTime += dt
                
                val currentList = _parts.value.toMutableList()
                val length = currentList.size
                
                // 1. Run Scripts in memory
                for (i in 0 until length) {
                    val p = currentList[i]
                    if (p.script.isNotBlank()) {
                        currentList[i] = runScriptStep(p, scriptTime)
                    }
                }

                // 2. Physics solver for unanchored objects
                for (i in 0 until length) {
                    val p = currentList[i]
                    if (p.anchored) continue
                    if (p.id == "baseplate") continue

                    // Calculate gravity
                    val currentVel = p.velocity
                    val newVelY = currentVel.y + gravity * dt
                    val newVel = Vector3(currentVel.x, newVelY, currentVel.z)
                    
                    var newPos = p.currentPosition + newVel * dt
                    var resolvedVel = newVel
                    
                    // Collision check: Baseplate
                    val baseplate = currentList.find { it.id == "baseplate" }
                    if (baseplate != null && p.canCollide) {
                        val baseHeight = baseplate.currentPosition.y + baseplate.size.y / 2
                        val bottomY = newPos.y - p.size.y / 2
                        if (bottomY <= baseHeight) {
                            // Bounce!
                            newPos = Vector3(newPos.x, baseHeight + p.size.y / 2, newPos.z)
                            val bounceY = if (newVel.y < -1f) -newVel.y * 0.35f else 0f // Restitution
                            resolvedVel = Vector3(newVel.x, bounceY, newVel.z)
                            
                            if (newVel.y < -3f) {
                                logSystem("Part '${p.name}' collided with Baseplate.")
                            }
                        }
                    }

                    // Collision check: Other solid anchored parts
                    for (j in 0 until length) {
                        if (i == j) continue
                        val other = currentList[j]
                        if (!other.canCollide || !p.canCollide) continue
                        
                        // Simple bounding box height stacks
                        if (isOverlappingXZ(newPos, p.size, other.currentPosition, other.size)) {
                            val otherTop = other.currentPosition.y + other.size.y / 2
                            val selfBottom = newPos.y - p.size.y / 2
                            if (selfBottom <= otherTop && p.currentPosition.y - p.size.y / 2 >= otherTop - 0.5f) {
                                // Rest on top of the box!
                                newPos = Vector3(newPos.x, otherTop + p.size.y / 2, newPos.z)
                                resolvedVel = Vector3(newVel.x, 0f, newVel.z)
                            }
                        }
                    }

                    currentList[i] = p.applyPhysicsStep(newPos, p.currentRotation, resolvedVel)
                }

                setPartsAndSyncNodes(currentList)
                // Refresh selection ref
                _selectedPart.value = _selectedPart.value?.let { selected ->
                    currentList.find { it.id == selected.id }
                }
            }
        }
    }

    private fun stopSimulation() {
        _isPlaying.value = false
        simulationJob?.cancel()
        simulationJob = null
        if (simulationOriginalState.isNotEmpty()) {
            setPartsAndSyncNodes(simulationOriginalState.map { it.toRuntimeReset() })
            _selectedPart.value = _selectedPart.value?.let { selected ->
                _parts.value.find { it.id == selected.id }
            }
            simulationOriginalState = emptyList()
        }
        logSystem("Simulation halted. Resetting workspace positions.")
    }

    // A highly interactive simplified script solver for visual cues
    private fun runScriptStep(p: Part, time: Float): Part {
        val s = p.script.lowercase()
        var pos = p.currentPosition
        var rot = p.currentRotation
        var color = p.colorHex

        // 1. SPINNER Script: rotates Y axis continuously
        if (s.contains("rotate") || s.contains("spinner") || s.contains("rotation")) {
            rot = rot.copy(y = (rot.y + 4f) % 360f)
        }

        // 2. HOVER/BOUNCE Script: moves part vertically on a sine wave
        if (s.contains("sin") || s.contains("hover") || s.contains("bounce")) {
            val originalY = p.position.y
            pos = pos.copy(y = originalY + kotlin.math.sin(time * 3f) * 1.5f)
        }

        // 3. DISCO Script: cycles through bright colors
        if (s.contains("disco") || s.contains("color") || s.contains("color3")) {
            val colors = listOf("#FF0000", "#FFCC00", "#00FF00", "#00FFFF", "#0055FF", "#FF00FF")
            val index = (time * 2f).toInt() % colors.size
            color = colors[index]
        }

        // 4. TELEPORT Script: moves part sideways
        if (s.contains("teleport") || s.contains("move")) {
            val originalX = p.position.x
            pos = pos.copy(x = originalX + kotlin.math.sin(time * 1.5f) * 4f)
        }

        return p.copy(
            currentPosition = pos,
            currentRotation = rot,
            colorHex = color
        )
    }

    private fun isOverlappingXZ(pos1: Vector3, size1: Vector3, pos2: Vector3, size2: Vector3): Boolean {
        val halfX1 = size1.x / 2
        val halfZ1 = size1.z / 2
        val halfX2 = size2.x / 2
        val halfZ2 = size2.z / 2

        val xOverlap = (pos1.x - halfX1 < pos2.x + halfX2) && (pos1.x + halfX1 > pos2.x - halfX2)
        val zOverlap = (pos1.z - halfZ1 < pos2.z + halfZ2) && (pos1.z + halfZ1 > pos2.z - halfZ2)
        return xOverlap && zOverlap
    }

    // --- Logging engine ---
    fun logSystem(msg: String) {
        val timestamp = getConsoleTimestamp()
        _consoleLogs.value = _consoleLogs.value + "[$timestamp] $msg"
    }

    fun logError(msg: String) {
        val timestamp = getConsoleTimestamp()
        _consoleLogs.value = _consoleLogs.value + "[$timestamp] ❌ ERROR: $msg"
    }

    fun clearLogs() {
        _consoleLogs.value = emptyList()
    }

    private fun getConsoleTimestamp(): String {
        val calendar = java.util.Calendar.getInstance()
        return "%02d:%02d:%02d".format(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
    }

    private fun getRandomColorHex(): String {
        val colors = listOf("#FF5722", "#E91E63", "#9C27B0", "#3F51B5", "#00BCD4", "#4CAF50", "#FFEB3B", "#FF9800", "#FFD700", "#00FF66", "#AA00FF")
        return colors.random()
    }

    private companion object {
        private const val BASEPLATE_TEMPLATE_PLACE_ID = 95206881L
        private const val CLASSIC_OBBY_TEMPLATE_PLACE_ID = 203812057L
        private const val CLASSIC_BASEPLATE_TEMPLATE_PLACE_ID = 6560363541L
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        historyCommitJob?.cancel()
    }
}
