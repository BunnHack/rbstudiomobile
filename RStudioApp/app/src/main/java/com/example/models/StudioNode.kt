package com.example.models

import com.squareup.moshi.JsonClass

/**
 * Universal workspace node — represents any Roblox instance type.
 *
 * Renderable nodes have [part] != null (Part, WedgePart, BallPart, SpawnLocation, etc.).
 * Non-renderable nodes (Folder, Model, Script, Lighting, etc.) have [part] == null and
 * are purely organizational — they show in the Explorer tree but produce no 3D geometry.
 *
 * Script source is stored on the node itself (not on Part), so Scripts can exist
 * independently of geometry.
 */
@JsonClass(generateAdapter = true)
data class StudioNode(
    val id: String,
    val name: String,
    val className: String = "Part",
    val parentId: String? = null,
    val part: Part? = null,
    val scriptSource: String = "",
    val isService: Boolean = false,
    val iconHint: String = "",
    val nodeProperties: Map<String, String> = emptyMap(),
    val propertyTypeIds: Map<String, Int> = emptyMap()
) {
    val isRenderable: Boolean get() = part != null
    val isFolder: Boolean get() = className == "Folder"
    val isModel: Boolean get() = className == "Model"
    val isDecal: Boolean get() = className == "Decal"
    val isTexture: Boolean get() = className == "Texture"
    val isWeld: Boolean get() = className == "Weld" || className == "WeldConstraint"
    val isScript: Boolean get() = className in SCRIPT_CLASS_NAMES || scriptSource.isNotEmpty()
    val isGuiObject: Boolean get() = className in GUI_CLASS_NAMES
    val isGuiContainer: Boolean get() = className in GUI_CONTAINER_CLASS_NAMES
    val isLighting: Boolean get() = className == "Lighting"

    companion object {
        const val CLASS_PART = "Part"
        const val CLASS_FOLDER = "Folder"
        const val CLASS_MODEL = "Model"
        const val CLASS_SCRIPT = "Script"
        const val CLASS_LOCAL_SCRIPT = "LocalScript"
        const val CLASS_MODULE_SCRIPT = "ModuleScript"
        const val CLASS_DECAL = "Decal"
        const val CLASS_TEXTURE = "Texture"
        const val CLASS_WELD = "Weld"
        const val CLASS_WELD_CONSTRAINT = "WeldConstraint"
        const val CLASS_SPAWN_LOCATION = "SpawnLocation"
        const val CLASS_WEDGE_PART = "WedgePart"
        const val CLASS_CORNER_WEDGE_PART = "CornerWedgePart"
        const val CLASS_TRUSS_PART = "TrussPart"
        const val CLASS_MESH_PART = "MeshPart"
        const val CLASS_BALL_PART = "BallPart"
        const val CLASS_SCREEN_GUI = "ScreenGui"
        const val CLASS_FRAME = "Frame"
        const val CLASS_TEXT_LABEL = "TextLabel"
        const val CLASS_TEXT_BUTTON = "TextButton"
        const val CLASS_TEXT_BOX = "TextBox"
        const val CLASS_IMAGE_LABEL = "ImageLabel"
        const val CLASS_IMAGE_BUTTON = "ImageButton"
        const val CLASS_SCROLLING_FRAME = "ScrollingFrame"
        const val CLASS_ATTACHMENT = "Attachment"
        const val CLASS_REMOTE_EVENT = "RemoteEvent"
        const val CLASS_SOUND = "Sound"
        const val CLASS_POINT_LIGHT = "PointLight"
        const val CLASS_SPOT_LIGHT = "SpotLight"
        const val CLASS_SURFACE_LIGHT = "SurfaceLight"
        const val CLASS_SKY = "Sky"
        const val CLASS_CLICK_DETECTOR = "ClickDetector"
        const val CLASS_TRAIL = "Trail"
        const val CLASS_BEAM = "Beam"
        const val CLASS_PARTICLE_EMITTER = "ParticleEmitter"
        const val CLASS_SURFACE_GUI = "SurfaceGui"
        const val CLASS_UI_GRID_LAYOUT = "UIGridLayout"
        const val CLASS_UI_LIST_LAYOUT = "UIListLayout"
        const val CLASS_UI_CORNER = "UICorner"
        const val CLASS_UI_STROKE = "UIStroke"
        const val CLASS_UI_GRADIENT = "UIGradient"
        const val CLASS_HIGHLIGHT = "Highlight"

        // Service class names
        const val CLASS_WORKSPACE = "Workspace"
        const val CLASS_REPLICATED_STORAGE = "ReplicatedStorage"
        const val CLASS_REPLICATED_FIRST = "ReplicatedFirst"
        const val CLASS_SERVER_SCRIPT_SERVICE = "ServerScriptService"
        const val CLASS_SERVER_STORAGE = "ServerStorage"
        const val CLASS_STARTER_GUI = "StarterGui"
        const val CLASS_STARTER_PACK = "StarterPack"
        const val CLASS_LIGHTING = "Lighting"
        const val CLASS_PLAYERS = "Players"
        const val CLASS_TEAMS = "Teams"
        const val CLASS_CHAT = "Chat"
        const val CLASS_SOUND_SERVICE = "SoundService"

        val GUI_CLASS_NAMES: Set<String> = setOf(
            CLASS_SCREEN_GUI,
            CLASS_FRAME,
            CLASS_TEXT_LABEL,
            CLASS_TEXT_BUTTON,
            CLASS_TEXT_BOX,
            CLASS_IMAGE_LABEL,
            CLASS_IMAGE_BUTTON,
            CLASS_SCROLLING_FRAME
        )

        val SCRIPT_CLASS_NAMES: Set<String> = setOf(
            CLASS_SCRIPT,
            CLASS_LOCAL_SCRIPT,
            CLASS_MODULE_SCRIPT
        )

        val GUI_CONTAINER_CLASS_NAMES: Set<String> = setOf(
            CLASS_SCREEN_GUI,
            CLASS_SURFACE_GUI,
            CLASS_FRAME,
            CLASS_SCROLLING_FRAME
        )

        val GUI_LAYOUT_CLASS_NAMES: Set<String> = setOf(
            CLASS_UI_GRID_LAYOUT,
            CLASS_UI_LIST_LAYOUT
        )

        val GUI_DECORATOR_CLASS_NAMES: Set<String> = setOf(
            CLASS_UI_CORNER,
            CLASS_UI_STROKE,
            CLASS_UI_GRADIENT
        )

        val EFFECT_CLASS_NAMES: Set<String> = setOf(
            CLASS_TRAIL,
            CLASS_BEAM,
            CLASS_PARTICLE_EMITTER
        )

        val SERVICE_CLASS_NAMES: Set<String> = setOf(
            CLASS_WORKSPACE,
            CLASS_REPLICATED_FIRST,
            CLASS_REPLICATED_STORAGE,
            CLASS_SERVER_SCRIPT_SERVICE,
            CLASS_SERVER_STORAGE,
            CLASS_STARTER_GUI,
            CLASS_STARTER_PACK,
            CLASS_LIGHTING,
            CLASS_PLAYERS,
            CLASS_TEAMS,
            CLASS_CHAT,
            CLASS_SOUND_SERVICE
        )
    }
}
