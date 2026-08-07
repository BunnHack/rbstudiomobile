package com.example.publish

import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Serializes the current scene into the Roblox binary place format (.rbxl).
 *
 * The layout follows the spec implemented by github.com/RobloxAPI/rbxfile:
 *  - Header: "<roblox!" + 0x89FF0D0A1A0A + version(0) + classCount + instanceCount + 8 reserved
 *  - Chunks: META, SSTR, INST (one per class), PROP (one per class+property), PRNT, END
 *  - Chunks are stored uncompressed (compressedLength = 0); the server accepts both.
 *  - Referents are sequential (0..n) assigned in DataModel-tree order, services first.
 *  - Referent/int32 arrays are zigzag + big-endian + byte-interleaved.
 *  - Float arrays use the Roblox float transform (rotl(bits,1)), big-endian, interleaved.
 *  - Vector3/Color3 use separate component arrays: all X/R, then Y/G, then Z/B.
 *  - CFrame rotations are written as special orientation IDs (0x02 = identity, 0x00 = raw matrix).
 *  - Chunk order matches Studio: INST chunks sorted by class name, then PROP chunks per class.
 */
object RobloxPlaceBinarySerializer {
    fun serialize(placeName: String, parts: List<Part>, nodes: List<StudioNode> = emptyList()): ByteArray {
        val instances = buildInstances(parts, nodes)
        // Sequential referents in declaration order (DataModel tree order).
        instances.forEachIndexed { index, instance -> instance.referent = index }

        // Group by class, chunks sorted by class name like Studio does.
        val classes = instances
            .groupBy { it.className }
            .toSortedMap()
        val classIds = classes.keys.withIndex().associate { (index, className) -> className to index }

        val writer = BinaryWriter()
        writer.writeBytes(MAGIC)
        writer.writeBytes(SIGNATURE)
        writer.writeUInt16LE(0)
        writer.writeInt32LE(classes.size)
        writer.writeInt32LE(instances.size)
        writer.writeBytes(ByteArray(8))

        writeMetaChunk(writer)
        writeSharedStringsChunk(writer)

        classes.forEach { (className, classInstances) ->
            writeInstanceChunk(writer, classIds.getValue(className), className, classInstances)
        }
        classes.forEach { (className, classInstances) ->
            writePropertyChunks(writer, classIds.getValue(className), className, classInstances)
        }
        writeParentChunk(writer, instances)
        writeChunk(writer, "END", "</roblox>".toByteArray(Charsets.UTF_8))

        return writer.toByteArray()
    }

    private fun buildInstances(parts: List<Part>, nodes: List<StudioNode>): List<InstanceRecord> {
        val instances = mutableListOf<InstanceRecord>()
        val instancesById = mutableMapOf<String, InstanceRecord>()
        val pendingParents = mutableMapOf<InstanceRecord, String?>()
        fun add(className: String, name: String, parent: InstanceRecord?): InstanceRecord {
            val instance = InstanceRecord(className, name, parent)
            instances.add(instance)
            return instance
        }

        // Core services required for a place to load and run.
        val workspace = add("Workspace", "Workspace", null).apply {
            props["Gravity"] = 196.2f
            props["FallenPartsDestroyHeight"] = -500f
            props["StreamingEnabled"] = false
            props["DistributedGameTime"] = 0.0
            props["GlobalWind"] = Vector3.Zero
            props["ExplicitAutoJoints"] = true
        }
        instancesById[StudioNode.CLASS_WORKSPACE] = workspace
        val players = add("Players", "Players", null).apply {
            props["MaxPlayersInternal"] = 6
            props["PreferredPlayersInternal"] = 6
            props["CharacterAutoLoads"] = true
            props["RespawnTime"] = 3f
        }
        instancesById[StudioNode.CLASS_PLAYERS] = players
        val lighting = add("Lighting", "Lighting", null).apply {
            props["Brightness"] = 2f
            props["GlobalShadows"] = true
            props["TimeOfDay"] = "14:30:00"
            props["Technology"] = 3
        }
        instancesById[StudioNode.CLASS_LIGHTING] = lighting
        listOf(
            StudioNode.CLASS_REPLICATED_FIRST, StudioNode.CLASS_REPLICATED_STORAGE,
            StudioNode.CLASS_SERVER_SCRIPT_SERVICE, StudioNode.CLASS_SERVER_STORAGE,
            StudioNode.CLASS_STARTER_GUI, StudioNode.CLASS_STARTER_PACK,
            "StarterPlayer", StudioNode.CLASS_TEAMS, StudioNode.CLASS_CHAT, StudioNode.CLASS_SOUND_SERVICE
        ).forEach { className ->
            instancesById[className] = add(className, className, null)
        }

        nodes.filter { it.className in SERVICE_CLASSES }.forEach { node ->
            instancesById[node.className]?.let { service -> addNodeProps(service, node) }
        }

        parts.forEachIndexed { index, part ->
            val partNode = nodes.firstOrNull { it.part?.id == part.id }
            val className = partNode?.className?.takeIf { it in BASE_PART_CLASSES } ?: classNameFor(part)
            val instance = add(className, part.name.ifBlank { "Part${index + 1}" }, workspace)
            instancesById[part.id] = instance
            partNode?.let { instancesById[it.id] = instance }
            pendingParents[instance] = partNode?.parentId ?: part.parentId
            addBasePartProps(instance, part)
            if (instance.className == "Part" || instance.className == "SpawnLocation") {
                instance.props["Shape"] = shapeToken(part.shape)
            }
            if (instance.className == StudioNode.CLASS_TRUSS_PART) {
                instance.props["Style"] = part.trussStyle
            }
            if (instance.className == StudioNode.CLASS_MESH_PART) {
                instance.props["MeshId"] = part.meshId
                instance.props["TextureID"] = part.textureId
                instance.props["DoubleSided"] = part.doubleSided
                instance.props["RenderFidelity"] = part.renderFidelity
                instance.props["InitialSize"] = part.initialSize
            }
            if (instance.className == "SpawnLocation") {
                instance.props["AllowTeamChangeOnTouch"] = part.allowTeamChangeOnTouch
                instance.props["Duration"] = part.duration
                instance.props["Enabled"] = part.spawnEnabled
                instance.props["Neutral"] = part.neutral
            }
            if (part.script.isNotBlank()) {
                val script = add("Script", "Script", instance)
                script.props["Source"] = part.script
                script.props["Disabled"] = false
            }
        }

        val userNodes = nodes.filter { it.className !in SERVICE_CLASSES && it.part == null }
        val pending = userNodes.toMutableList()
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val node = iterator.next()
                val parent = node.parentId?.let(instancesById::get) ?: workspace
                if (node.parentId != null && node.parentId !in instancesById && userNodes.any { it.id == node.parentId }) {
                    continue
                }
                val instance = add(node.className, node.name.ifBlank { node.className }, parent)
                addNodeProps(instance, node)
                instancesById[node.id] = instance
                iterator.remove()
                madeProgress = true
            }
        } while (madeProgress && pending.isNotEmpty())

        // Malformed / cyclic imported hierarchies fall back to Workspace rather than
        // being silently dropped from the published place.
        pending.forEach { node ->
            val instance = add(node.className, node.name.ifBlank { node.className }, workspace)
            addNodeProps(instance, node)
            instancesById[node.id] = instance
        }


        pendingParents.forEach { (instance, parentId) ->
            instance.parent = parentId?.let(instancesById::get) ?: workspace
        }

        instances.forEach { instance ->
            listOf("PrimaryPart", "Part0", "Part1", "Attachment0", "Attachment1", "Adornee", "SelectionImageObject").forEach { name ->
                val targetId = instance.props[name] as? String ?: return@forEach
                instancesById[targetId]?.let { target -> instance.props[name] = target }
                    ?: instance.props.remove(name)
            }
        }

        return instances
    }

    private fun addNodeProps(instance: InstanceRecord, node: StudioNode) {
        fun prop(name: String, default: String = "") = node.nodeProperties.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value ?: default
        fun bool(name: String, default: Boolean) = prop(name, default.toString()).toBooleanStrictOrNull() ?: default
        fun float(name: String, default: Float) = prop(name, default.toString()).toFloatOrNull() ?: default
        fun double(name: String, default: Double) = prop(name, default.toString()).toDoubleOrNull() ?: default
        fun int(name: String, default: Int) = prop(name, default.toString()).toIntOrNull() ?: default

        when (node.className) {
            StudioNode.CLASS_LIGHTING -> {
                instance.props["Brightness"] = float("Brightness", 2f)
                instance.props["GlobalShadows"] = bool("GlobalShadows", true)
                instance.props["TimeOfDay"] = prop("TimeOfDay", "14:30:00")
                instance.props["Technology"] = int("Technology", 3)
                instance.props["Ambient"] = colorRgbFromHex(prop("Ambient", "#808080"))
                instance.props["OutdoorAmbient"] = colorRgbFromHex(prop("OutdoorAmbient", "#808080"))
            }
            StudioNode.CLASS_SOUND_SERVICE -> {
                instance.props["AmbientReverb"] = int("AmbientReverb", 0)
                instance.props["DistanceFactor"] = float("DistanceFactor", 3.33f)
                instance.props["DopplerScale"] = float("DopplerScale", 1f)
                instance.props["RespectFilteringEnabled"] = bool("RespectFilteringEnabled", true)
                instance.props["RolloffScale"] = float("RolloffScale", 1f)
            }
            StudioNode.CLASS_SCRIPT,
            StudioNode.CLASS_LOCAL_SCRIPT,
            StudioNode.CLASS_MODULE_SCRIPT -> {
                instance.props["Source"] = node.scriptSource.ifBlank { prop("Source") }
                instance.props["LinkedSource"] = prop("LinkedSource")
                instance.props["ScriptGuid"] = prop("ScriptGuid", UUID.randomUUID().toString())
                if (node.className != StudioNode.CLASS_MODULE_SCRIPT) {
                    instance.props["Disabled"] = bool("Disabled", false)
                }
            }
            StudioNode.CLASS_ATTACHMENT -> {
                instance.props["CFrame"] = CFrameValue(Vector3.Zero, rotationMatrix(Vector3.Zero))
                instance.props["Visible"] = bool("Visible", true)
            }
            StudioNode.CLASS_SOUND -> {
                instance.props["SoundId"] = prop("SoundId")
                instance.props["Volume"] = float("Volume", 0.5f)
                instance.props["PlaybackSpeed"] = float("PlaybackSpeed", 1f)
                instance.props["Looped"] = bool("Looped", false)
                instance.props["Playing"] = bool("Playing", false)
                instance.props["PlayOnRemove"] = bool("PlayOnRemove", false)
                instance.props["TimePosition"] = double("TimePosition", 0.0)
                instance.props["RollOffMinDistance"] = float("RollOffMinDistance", 10f)
                instance.props["RollOffMaxDistance"] = float("RollOffMaxDistance", 10_000f)
                instance.props["RollOffMode"] = int("RollOffMode", 0)
            }
            StudioNode.CLASS_POINT_LIGHT,
            StudioNode.CLASS_SPOT_LIGHT,
            StudioNode.CLASS_SURFACE_LIGHT -> {
                instance.props["Brightness"] = float("Brightness", 1f)
                instance.props["Color"] = colorRgbFromHex(prop("Color", "#FFFFFF"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["Range"] = float("Range", if (node.className == StudioNode.CLASS_POINT_LIGHT) 8f else 16f)
                instance.props["Shadows"] = bool("Shadows", false)
                if (node.className != StudioNode.CLASS_POINT_LIGHT) {
                    instance.props["Angle"] = float("Angle", if (node.className == StudioNode.CLASS_SPOT_LIGHT) 45f else 90f)
                    instance.props["Face"] = faceToken(prop("Face", "Front"))
                }
            }
            StudioNode.CLASS_DECAL,
            StudioNode.CLASS_TEXTURE -> {
                instance.props["Texture"] = prop("Texture")
                instance.props["Face"] = faceToken(prop("Face", "Front"))
                instance.props["Transparency"] = float("Transparency", 0f)
                instance.props["ZIndex"] = int("ZIndex", 1)
                instance.props["Color3"] = colorRgbFromHex(prop("Color3", "#FFFFFF"))
                if (node.className == StudioNode.CLASS_TEXTURE) {
                    instance.props["StudsPerTileU"] = float("StudsPerTileU", 2f)
                    instance.props["StudsPerTileV"] = float("StudsPerTileV", 2f)
                    instance.props["OffsetStudsU"] = float("OffsetStudsU", 0f)
                    instance.props["OffsetStudsV"] = float("OffsetStudsV", 0f)
                }
            }
            StudioNode.CLASS_MODEL -> {
                instance.props["PrimaryPart"] = prop("PrimaryPart")
                instance.props["LevelOfDetail"] = enumToken(prop("LevelOfDetail", "Automatic"), MODEL_LEVEL_OF_DETAIL)
                instance.props["NeedsPivotMigration"] = bool("NeedsPivotMigration", false)
            }
            StudioNode.CLASS_WELD,
            StudioNode.CLASS_WELD_CONSTRAINT -> {
                instance.props["Part0"] = prop("Part0")
                instance.props["Part1"] = prop("Part1")
                instance.props["Enabled"] = bool("Enabled", true)
                if (node.className == StudioNode.CLASS_WELD) {
                    instance.props["C0"] = parseCFrame(prop("C0"))
                    instance.props["C1"] = parseCFrame(prop("C1"))
                }
            }
            StudioNode.CLASS_CLICK_DETECTOR -> {
                instance.props["CursorIcon"] = prop("CursorIcon")
                instance.props["MaxActivationDistance"] = float("MaxActivationDistance", 32f)
            }
            StudioNode.CLASS_SKY -> {
                instance.props["CelestialBodiesShown"] = bool("CelestialBodiesShown", true)
                instance.props["MoonAngularSize"] = float("MoonAngularSize", 11f)
                instance.props["MoonTextureId"] = prop("MoonTextureId")
                listOf("SkyboxBk", "SkyboxDn", "SkyboxFt", "SkyboxLf", "SkyboxRt", "SkyboxUp").forEach {
                    instance.props[it] = prop(it)
                }
                instance.props["StarCount"] = int("StarCount", 3000)
                instance.props["SunAngularSize"] = float("SunAngularSize", 21f)
                instance.props["SunTextureId"] = prop("SunTextureId")
            }
            StudioNode.CLASS_TRAIL -> {
                instance.props["Attachment0"] = prop("Attachment0")
                instance.props["Attachment1"] = prop("Attachment1")
                instance.props["Brightness"] = float("Brightness", 1f)
                instance.props["Color"] = parseColorSequence(prop("Color", "0:#FFFFFF:0; 1:#FFFFFF:0"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["FaceCamera"] = bool("FaceCamera", false)
                instance.props["Lifetime"] = float("Lifetime", 0.5f)
                instance.props["MinLength"] = float("MinLength", 0.1f)
                instance.props["Texture"] = prop("Texture")
                instance.props["TextureLength"] = float("TextureLength", 1f)
                instance.props["TextureMode"] = int("TextureMode", 0)
                instance.props["Transparency"] = parseNumberSequence(prop("Transparency", "0:0:0; 1:1:0"))
                instance.props["WidthScale"] = parseNumberSequence(prop("WidthScale", "0:1:0; 1:1:0"))
            }
            StudioNode.CLASS_BEAM -> {
                instance.props["Attachment0"] = prop("Attachment0")
                instance.props["Attachment1"] = prop("Attachment1")
                instance.props["Brightness"] = float("Brightness", 1f)
                instance.props["Color"] = parseColorSequence(prop("Color", "0:#FFFFFF:0; 1:#FFFFFF:0"))
                instance.props["CurveSize0"] = float("CurveSize0", 0f)
                instance.props["CurveSize1"] = float("CurveSize1", 0f)
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["FaceCamera"] = bool("FaceCamera", true)
                instance.props["Segments"] = int("Segments", 1)
                instance.props["Texture"] = prop("Texture")
                instance.props["TextureLength"] = float("TextureLength", 1f)
                instance.props["TextureMode"] = int("TextureMode", 0)
                instance.props["TextureSpeed"] = float("TextureSpeed", 0f)
                instance.props["Transparency"] = parseNumberSequence(prop("Transparency", "0:0:0; 1:0:0"))
                instance.props["Width0"] = float("Width0", 1f)
                instance.props["Width1"] = float("Width1", 1f)
                instance.props["ZOffset"] = float("ZOffset", 0f)
            }
            StudioNode.CLASS_PARTICLE_EMITTER -> {
                instance.props["Acceleration"] = parseVector3(prop("Acceleration", "0, 0, 0"))
                instance.props["Brightness"] = float("Brightness", 1f)
                instance.props["Color"] = parseColorSequence(prop("Color", "0:#FFFFFF:0; 1:#FFFFFF:0"))
                instance.props["Drag"] = float("Drag", 0f)
                instance.props["EmissionDirection"] = faceToken(prop("EmissionDirection", "Front"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["Lifetime"] = parseNumberRange(prop("Lifetime", "1, 1"), 1f)
                instance.props["Rate"] = float("Rate", 5f)
                instance.props["Size"] = parseNumberSequence(prop("Size", "0:0.5:0; 1:0:0"))
                instance.props["Speed"] = parseNumberRange(prop("Speed", "1, 1"), 1f)
                instance.props["SpreadAngle"] = parseVector2(prop("SpreadAngle", "0, 0"))
                instance.props["Texture"] = prop("Texture")
                instance.props["Transparency"] = parseNumberSequence(prop("Transparency", "0:0:0; 1:1:0"))
            }
            StudioNode.CLASS_SURFACE_GUI -> {
                instance.props["Active"] = bool("Active", true)
                instance.props["Adornee"] = prop("Adornee")
                instance.props["AlwaysOnTop"] = bool("AlwaysOnTop", false)
                instance.props["Brightness"] = float("Brightness", 1f)
                instance.props["CanvasSize"] = parseVector2(prop("CanvasSize", "800, 600"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["Face"] = faceToken(prop("Face", "Front"))
                instance.props["LightInfluence"] = float("LightInfluence", 0f)
                instance.props["PixelsPerStud"] = float("PixelsPerStud", 50f)
                instance.props["ResetOnSpawn"] = bool("ResetOnSpawn", true)
                instance.props["SizingMode"] = int("SizingMode", 0)
                instance.props["ZIndexBehavior"] = int("ZIndexBehavior", 0)
                instance.props["ZOffset"] = float("ZOffset", 0f)
            }
            StudioNode.CLASS_UI_LIST_LAYOUT -> {
                instance.props["FillDirection"] = enumToken(prop("FillDirection", "Vertical"), FILL_DIRECTION)
                instance.props["HorizontalAlignment"] = enumToken(prop("HorizontalAlignment", "Center"), HORIZONTAL_ALIGNMENT)
                instance.props["Padding"] = parseUDim(prop("Padding", "scale=0, offset=0"))
                instance.props["SortOrder"] = enumToken(prop("SortOrder", "LayoutOrder"), SORT_ORDER)
                instance.props["VerticalAlignment"] = enumToken(prop("VerticalAlignment", "Center"), VERTICAL_ALIGNMENT)
                instance.props["Wraps"] = bool("Wraps", false)
            }
            StudioNode.CLASS_UI_CORNER -> instance.props["CornerRadius"] = parseUDim(prop("CornerRadius", "scale=0, offset=8"))
            StudioNode.CLASS_UI_STROKE -> {
                instance.props["ApplyStrokeMode"] = enumToken(prop("ApplyStrokeMode", "Border"), APPLY_STROKE_MODE)
                instance.props["Color"] = colorRgbFromHex(prop("Color", "#000000"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["LineJoinMode"] = enumToken(prop("LineJoinMode", "Round"), LINE_JOIN_MODE)
                instance.props["Thickness"] = float("Thickness", 1f)
                instance.props["Transparency"] = float("Transparency", 0f)
            }
            StudioNode.CLASS_UI_GRADIENT -> {
                instance.props["Color"] = parseColorSequence(prop("Color", "0:#FFFFFF:0; 1:#FFFFFF:0"))
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["Offset"] = parseVector2(prop("Offset", "0, 0"))
                instance.props["Rotation"] = float("Rotation", 0f)
                instance.props["Transparency"] = parseNumberSequence(prop("Transparency", "0:0:0; 1:0:0"))
            }
            StudioNode.CLASS_HIGHLIGHT -> {
                instance.props["Adornee"] = prop("Adornee")
                instance.props["DepthMode"] = if (prop("DepthMode", "AlwaysOnTop").equals("Occluded", true)) 1 else 0
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["FillColor"] = colorRgbFromHex(prop("FillColor", "#FF0000"))
                instance.props["FillTransparency"] = float("FillTransparency", 0.5f)
                instance.props["OutlineColor"] = colorRgbFromHex(prop("OutlineColor", "#FFFFFF"))
                instance.props["OutlineTransparency"] = float("OutlineTransparency", 0f)
            }
            StudioNode.CLASS_SCREEN_GUI -> {
                instance.props["Enabled"] = bool("Enabled", true)
                instance.props["ResetOnSpawn"] = bool("ResetOnSpawn", true)
                instance.props["IgnoreGuiInset"] = bool("IgnoreGuiInset", false)
                instance.props["DisplayOrder"] = int("DisplayOrder", 0)
                instance.props["ZIndexBehavior"] = enumToken(prop("ZIndexBehavior", "Sibling"), Z_INDEX_BEHAVIOR)
            }
            in StudioNode.GUI_CLASS_NAMES -> addGuiObjectProps(instance, node, ::prop, ::bool, ::float, ::int)
        }
        instance.props["SourceAssetId"] = prop("SourceAssetId", "-1").toLongOrNull() ?: -1L
        instance.props["Tags"] = prop("Tags")
    }

    private fun addGuiObjectProps(
        instance: InstanceRecord,
        node: StudioNode,
        prop: (String, String) -> String,
        bool: (String, Boolean) -> Boolean,
        float: (String, Float) -> Float,
        int: (String, Int) -> Int
    ) {
        instance.props["Active"] = bool("Active", node.className.endsWith("Button") || node.className == StudioNode.CLASS_TEXT_BOX)
        instance.props["AnchorPoint"] = parseVector2(prop("AnchorPoint", "0, 0"))
        instance.props["AutomaticSize"] = enumToken(prop("AutomaticSize", "None"), AUTOMATIC_SIZE)
        instance.props["BackgroundColor3"] = colorRgbFromHex(prop("BackgroundColor3", "#FFFFFF"))
        instance.props["BackgroundTransparency"] = float("BackgroundTransparency", 0f)
        instance.props["BorderColor3"] = colorRgbFromHex(prop("BorderColor3", "#000000"))
        instance.props["BorderMode"] = enumToken(prop("BorderMode", "Outline"), BORDER_MODE)
        instance.props["BorderSizePixel"] = int("BorderSizePixel", 1)
        instance.props["ClipsDescendants"] = bool("ClipsDescendants", false)
        instance.props["LayoutOrder"] = int("LayoutOrder", 0)
        instance.props["Position"] = parseUDim2(prop("Position", ""))
        instance.props["Rotation"] = float("Rotation", 0f)
        instance.props["Selectable"] = bool("Selectable", false)
        instance.props["SelectionImageObject"] = prop("SelectionImageObject", "")
        instance.props["Size"] = parseUDim2(prop("Size", "offsetX=200, offsetY=50"))
        instance.props["SizeConstraint"] = enumToken(prop("SizeConstraint", "RelativeXY"), SIZE_CONSTRAINT)
        instance.props["Visible"] = bool("Visible", true)
        instance.props["ZIndex"] = int("ZIndex", 1)

        if (node.className in TEXT_GUI_CLASSES) {
            instance.props["FontFace"] = parseFont(prop("FontFace", "family=SourceSans, weight=400, style=0"))
            instance.props["RichText"] = bool("RichText", false)
            instance.props["Text"] = prop("Text", node.className)
            instance.props["TextColor3"] = colorRgbFromHex(prop("TextColor3", "#000000"))
            instance.props["TextDirection"] = enumToken(prop("TextDirection", "Auto"), TEXT_DIRECTION)
            instance.props["TextScaled"] = bool("TextScaled", false)
            instance.props["TextSize"] = float("TextSize", 14f)
            instance.props["TextStrokeColor3"] = colorRgbFromHex(prop("TextStrokeColor3", "#000000"))
            instance.props["TextStrokeTransparency"] = float("TextStrokeTransparency", 1f)
            instance.props["TextTransparency"] = float("TextTransparency", 0f)
            instance.props["TextTruncate"] = enumToken(prop("TextTruncate", "None"), TEXT_TRUNCATE)
            instance.props["TextWrapped"] = bool("TextWrapped", false)
            instance.props["TextXAlignment"] = enumToken(prop("TextXAlignment", "Center"), TEXT_X_ALIGNMENT)
            instance.props["TextYAlignment"] = enumToken(prop("TextYAlignment", "Center"), TEXT_Y_ALIGNMENT)
        }

        if (node.className == StudioNode.CLASS_TEXT_BOX) {
            instance.props["ClearTextOnFocus"] = bool("ClearTextOnFocus", true)
            instance.props["CursorPosition"] = int("CursorPosition", -1)
            instance.props["MultiLine"] = bool("MultiLine", false)
            instance.props["PlaceholderColor3"] = colorRgbFromHex(prop("PlaceholderColor3", "#B2B2B2"))
            instance.props["PlaceholderText"] = prop("PlaceholderText", "")
            instance.props["SelectionStart"] = int("SelectionStart", -1)
            instance.props["ShowNativeInput"] = bool("ShowNativeInput", true)
            instance.props["TextEditable"] = bool("TextEditable", true)
        }

        if (node.className in IMAGE_GUI_CLASSES) {
            instance.props["Image"] = prop("Image", "")
            instance.props["ImageColor3"] = colorRgbFromHex(prop("ImageColor3", "#FFFFFF"))
            instance.props["ImageRectOffset"] = parseVector2(prop("ImageRectOffset", "0, 0"))
            instance.props["ImageRectSize"] = parseVector2(prop("ImageRectSize", "0, 0"))
            instance.props["ImageTransparency"] = float("ImageTransparency", 0f)
            instance.props["ScaleType"] = enumToken(prop("ScaleType", "Stretch"), SCALE_TYPE)
            instance.props["TileSize"] = parseUDim2(prop("TileSize", "scaleX=1, scaleY=1"))
            if (node.className == StudioNode.CLASS_IMAGE_BUTTON) {
                instance.props["HoverImage"] = prop("HoverImage", "")
                instance.props["PressedImage"] = prop("PressedImage", "")
            }
        }
    }

    private fun classNameFor(part: Part): String = when (part.shape) {
        Part.SHAPE_WEDGE -> "WedgePart"
        Part.SHAPE_CORNER_WEDGE -> StudioNode.CLASS_CORNER_WEDGE_PART
        Part.SHAPE_TRUSS -> StudioNode.CLASS_TRUSS_PART
        Part.SHAPE_MESH -> StudioNode.CLASS_MESH_PART
        Part.SHAPE_SPAWN_LOCATION -> "SpawnLocation"
        else -> "Part"
    }

    private fun addBasePartProps(instance: InstanceRecord, part: Part) {
        instance.props["Anchored"] = part.anchored
        instance.props["CanCollide"] = part.canCollide
        instance.props["CanQuery"] = part.canQuery
        instance.props["CanTouch"] = part.canTouch
        instance.props["CastShadow"] = part.castShadow
        instance.props["Locked"] = part.locked
        instance.props["Massless"] = part.massless
        instance.props["Transparency"] = part.transparency
        instance.props["Reflectance"] = part.reflectance
        instance.props["CollisionGroup"] = part.collisionGroup.ifBlank { "Default" }
        instance.props["CollisionGroupId"] = part.collisionGroupId
        instance.props["RootPriority"] = part.rootPriority
        instance.props["MaterialVariantSerialized"] = part.materialVariant
        instance.props["Material"] = materialToken(part.material)
        instance.props["size"] = part.size
        instance.props["Color3uint8"] = colorFromHex(part.colorHex)
        instance.props["CFrame"] = CFrameValue(part.position, rotationMatrix(part.rotation))
        instance.props["PivotOffset"] = CFrameValue(Vector3.Zero, rotationMatrix(Vector3.Zero))
        instance.props["Velocity"] = part.velocity
        instance.props["RotVelocity"] = part.rotVelocity
        instance.props["formFactorRaw"] = part.formFactorRaw
        instance.props["TopSurface"] = surfaceToken(part.topSurface)
        instance.props["BottomSurface"] = surfaceToken(part.bottomSurface)
        instance.props["LeftSurface"] = surfaceToken(part.leftSurface)
        instance.props["RightSurface"] = surfaceToken(part.rightSurface)
        instance.props["FrontSurface"] = surfaceToken(part.frontSurface)
        instance.props["BackSurface"] = surfaceToken(part.backSurface)
        instance.props["CustomPhysicalProperties"] = 0.toByte() // PhysicalProperties: CustomPhysics = false
        instance.props["SourceAssetId"] = -1L
        instance.props["UniqueId"] = randomUniqueId()
    }

    private fun writeMetaChunk(writer: BinaryWriter) {
        val chunk = BinaryWriter()
        val entries = listOf("ExplicitAutoJoints" to "true")
        chunk.writeUInt32LE(entries.size)
        entries.forEach { (key, value) ->
            chunk.writeString(key)
            chunk.writeString(value)
        }
        writeChunk(writer, "META", chunk.toByteArray())
    }

    private fun writeSharedStringsChunk(writer: BinaryWriter) {
        val chunk = BinaryWriter()
        chunk.writeUInt32LE(0) // version
        chunk.writeUInt32LE(0) // count
        writeChunk(writer, "SSTR", chunk.toByteArray())
    }

    private fun writeInstanceChunk(
        writer: BinaryWriter,
        classId: Int,
        className: String,
        instances: List<InstanceRecord>
    ) {
        val chunk = BinaryWriter()
        chunk.writeInt32LE(classId)
        chunk.writeString(className)
        val service = className in SERVICE_CLASSES
        chunk.writeUInt8(if (service) 1 else 0)
        chunk.writeInt32LE(instances.size)
        chunk.writeInterleavedInts(instances.map { it.referent }.toIntArray(), zigzag = true, accumulate = true)
        if (service) {
            chunk.writeBytes(ByteArray(instances.size) { 1 })
        }
        writeChunk(writer, "INST", chunk.toByteArray())
    }

    private fun writePropertyChunks(
        writer: BinaryWriter,
        classId: Int,
        className: String,
        instances: List<InstanceRecord>
    ) {
        writeStringProp(writer, classId, "Name", instances.map { it.name })

        when (className) {
            "Workspace" -> {
                writeFloatProp(writer, classId, "Gravity", instances.map { it.props["Gravity"] as Float })
                writeFloatProp(writer, classId, "FallenPartsDestroyHeight", instances.map { it.props["FallenPartsDestroyHeight"] as Float })
                writeBoolProp(writer, classId, "StreamingEnabled", instances.map { it.props["StreamingEnabled"] as Boolean })
                writeDoubleProp(writer, classId, "DistributedGameTime", instances.map { it.props["DistributedGameTime"] as Double })
                writeVector3Prop(writer, classId, "GlobalWind", instances.map { it.props["GlobalWind"] as Vector3 })
                writeBoolProp(writer, classId, "ExplicitAutoJoints", instances.map { it.props["ExplicitAutoJoints"] as Boolean })
            }
            "Players" -> {
                writeIntProp(writer, classId, "MaxPlayersInternal", instances.map { it.props["MaxPlayersInternal"] as Int })
                writeIntProp(writer, classId, "PreferredPlayersInternal", instances.map { it.props["PreferredPlayersInternal"] as Int })
                writeBoolProp(writer, classId, "CharacterAutoLoads", instances.map { it.props["CharacterAutoLoads"] as Boolean })
                writeFloatProp(writer, classId, "RespawnTime", instances.map { it.props["RespawnTime"] as Float })
            }
            "Lighting" -> {
                writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as Float })
                writeBoolProp(writer, classId, "GlobalShadows", instances.map { it.props["GlobalShadows"] as Boolean })
                writeStringProp(writer, classId, "TimeOfDay", instances.map { it.props["TimeOfDay"] as String })
                writeEnumProp(writer, classId, "Technology", instances.map { it.props["Technology"] as Int })
                writeColor3Prop(writer, classId, "Ambient", instances.map { it.props["Ambient"] as? RgbColor ?: colorRgbFromHex("#808080") })
                writeColor3Prop(writer, classId, "OutdoorAmbient", instances.map { it.props["OutdoorAmbient"] as? RgbColor ?: colorRgbFromHex("#808080") })
            }
            in BASE_PART_CLASSES -> {
                writeBasePartProps(writer, classId, instances, className == "Part" || className == "SpawnLocation")
                if (className == StudioNode.CLASS_TRUSS_PART) {
                    writeEnumProp(writer, classId, "style", instances.map { it.props["Style"] as? Int ?: 0 })
                }
                if (className == StudioNode.CLASS_MESH_PART) {
                    writeBoolProp(writer, classId, "DoubleSided", instances.map { it.props["DoubleSided"] as? Boolean ?: false })
                    writeVector3Prop(writer, classId, "InitialSize", instances.map { it.props["InitialSize"] as? Vector3 ?: Vector3(2f, 2f, 2f) })
                    writeStringProp(writer, classId, "MeshId", instances.map { it.props["MeshId"] as? String ?: "" })
                    writeEnumProp(writer, classId, "RenderFidelity", instances.map { it.props["RenderFidelity"] as? Int ?: 0 })
                    writeStringProp(writer, classId, "TextureID", instances.map { it.props["TextureID"] as? String ?: "" })
                }
            }
            "Script", "LocalScript", "ModuleScript" -> {
                writeStringProp(writer, classId, "Source", instances.map { it.props["Source"] as? String ?: "" })
                writeStringProp(writer, classId, "LinkedSource", instances.map { it.props["LinkedSource"] as? String ?: "" })
                writeStringProp(writer, classId, "ScriptGuid", instances.map { it.props["ScriptGuid"] as? String ?: "" })
                if (className != "ModuleScript") {
                    writeBoolProp(writer, classId, "Disabled", instances.map { it.props["Disabled"] as? Boolean ?: false })
                }
            }
            "Attachment" -> {
                writeCFrameProp(writer, classId, "CFrame", instances.map { it.props["CFrame"] as CFrameValue })
                writeBoolProp(writer, classId, "Visible", instances.map { it.props["Visible"] as Boolean })
            }
            "Sound" -> {
                writeStringProp(writer, classId, "SoundId", instances.map { it.props["SoundId"] as String })
                writeFloatProp(writer, classId, "Volume", instances.map { it.props["Volume"] as Float })
                writeFloatProp(writer, classId, "PlaybackSpeed", instances.map { it.props["PlaybackSpeed"] as Float })
                writeBoolProp(writer, classId, "Looped", instances.map { it.props["Looped"] as Boolean })
                writeBoolProp(writer, classId, "Playing", instances.map { it.props["Playing"] as Boolean })
                writeBoolProp(writer, classId, "PlayOnRemove", instances.map { it.props["PlayOnRemove"] as Boolean })
                writeDoubleProp(writer, classId, "TimePosition", instances.map { it.props["TimePosition"] as Double })
                writeFloatProp(writer, classId, "RollOffMinDistance", instances.map { it.props["RollOffMinDistance"] as Float })
                writeFloatProp(writer, classId, "RollOffMaxDistance", instances.map { it.props["RollOffMaxDistance"] as Float })
                writeEnumProp(writer, classId, "RollOffMode", instances.map { it.props["RollOffMode"] as Int })
            }
            "PointLight", "SpotLight", "SurfaceLight" -> {
                writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as Float })
                writeColor3Prop(writer, classId, "Color", instances.map { it.props["Color"] as RgbColor })
                writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as Boolean })
                writeFloatProp(writer, classId, "Range", instances.map { it.props["Range"] as Float })
                writeBoolProp(writer, classId, "Shadows", instances.map { it.props["Shadows"] as Boolean })
                if (className != "PointLight") {
                    writeFloatProp(writer, classId, "Angle", instances.map { it.props["Angle"] as Float })
                    writeEnumProp(writer, classId, "Face", instances.map { it.props["Face"] as Int })
                }
            }
            StudioNode.CLASS_DECAL, StudioNode.CLASS_TEXTURE -> {
                writeStringProp(writer, classId, "Texture", instances.map { it.props["Texture"] as? String ?: "" })
                writeEnumProp(writer, classId, "Face", instances.map { it.props["Face"] as? Int ?: 5 })
                writeFloatProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? Float ?: 0f })
                writeIntProp(writer, classId, "ZIndex", instances.map { it.props["ZIndex"] as? Int ?: 1 })
                writeColor3Prop(writer, classId, "Color3", instances.map { it.props["Color3"] as? RgbColor ?: colorRgbFromHex("#FFFFFF") })
                if (className == StudioNode.CLASS_TEXTURE) {
                    listOf("StudsPerTileU", "StudsPerTileV", "OffsetStudsU", "OffsetStudsV").forEach { name ->
                        writeFloatProp(writer, classId, name, instances.map { it.props[name] as? Float ?: 0f })
                    }
                }
            }
            StudioNode.CLASS_MODEL -> {
                writeRefProp(writer, classId, "PrimaryPart", instances.map { it.props["PrimaryPart"] as? InstanceRecord })
                writeEnumProp(writer, classId, "LevelOfDetail", instances.map { it.props["LevelOfDetail"] as? Int ?: 0 })
                writeBoolProp(writer, classId, "NeedsPivotMigration", instances.map { it.props["NeedsPivotMigration"] as? Boolean ?: false })
            }
            StudioNode.CLASS_WELD, StudioNode.CLASS_WELD_CONSTRAINT -> {
                writeRefProp(writer, classId, "Part0", instances.map { it.props["Part0"] as? InstanceRecord })
                writeRefProp(writer, classId, "Part1", instances.map { it.props["Part1"] as? InstanceRecord })
                writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
                if (className == StudioNode.CLASS_WELD) {
                    writeCFrameProp(writer, classId, "C0", instances.map { it.props["C0"] as? CFrameValue ?: identityCFrame() })
                    writeCFrameProp(writer, classId, "C1", instances.map { it.props["C1"] as? CFrameValue ?: identityCFrame() })
                }
            }
            StudioNode.CLASS_CLICK_DETECTOR -> {
                writeStringProp(writer, classId, "CursorIcon", instances.map { it.props["CursorIcon"] as? String ?: "" })
                writeFloatProp(writer, classId, "MaxActivationDistance", instances.map { it.props["MaxActivationDistance"] as? Float ?: 32f })
            }
            StudioNode.CLASS_SKY -> writeSkyProps(writer, classId, instances)
            StudioNode.CLASS_TRAIL -> writeTrailProps(writer, classId, instances)
            StudioNode.CLASS_BEAM -> writeBeamProps(writer, classId, instances)
            StudioNode.CLASS_PARTICLE_EMITTER -> writeParticleEmitterProps(writer, classId, instances)
            StudioNode.CLASS_SURFACE_GUI -> writeSurfaceGuiProps(writer, classId, instances)
            StudioNode.CLASS_UI_LIST_LAYOUT -> {
                writeEnumProp(writer, classId, "FillDirection", instances.map { it.props["FillDirection"] as? Int ?: 1 })
                writeEnumProp(writer, classId, "HorizontalAlignment", instances.map { it.props["HorizontalAlignment"] as? Int ?: 1 })
                writeUDimProp(writer, classId, "Padding", instances.map { it.props["Padding"] as? UDimValue ?: UDimValue() })
                writeEnumProp(writer, classId, "SortOrder", instances.map { it.props["SortOrder"] as? Int ?: 2 })
                writeEnumProp(writer, classId, "VerticalAlignment", instances.map { it.props["VerticalAlignment"] as? Int ?: 1 })
                writeBoolProp(writer, classId, "Wraps", instances.map { it.props["Wraps"] as? Boolean ?: false })
            }
            StudioNode.CLASS_UI_CORNER -> writeUDimProp(writer, classId, "CornerRadius", instances.map { it.props["CornerRadius"] as? UDimValue ?: UDimValue(offset = 8) })
            StudioNode.CLASS_UI_STROKE -> {
                writeEnumProp(writer, classId, "ApplyStrokeMode", instances.map { it.props["ApplyStrokeMode"] as? Int ?: 0 })
                writeColor3Prop(writer, classId, "Color", instances.map { it.props["Color"] as? RgbColor ?: colorRgbFromHex("#000000") })
                writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
                writeEnumProp(writer, classId, "LineJoinMode", instances.map { it.props["LineJoinMode"] as? Int ?: 0 })
                writeFloatProp(writer, classId, "Thickness", instances.map { it.props["Thickness"] as? Float ?: 1f })
                writeFloatProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? Float ?: 0f })
            }
            StudioNode.CLASS_UI_GRADIENT -> {
                writeColorSequenceProp(writer, classId, "Color", instances.map { it.props["Color"] as? List<ColorSequencePoint> ?: defaultColorSequence() })
                writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
                writeVector2Prop(writer, classId, "Offset", instances.map { it.props["Offset"] as? Vector2Value ?: Vector2Value(0f, 0f) })
                writeFloatProp(writer, classId, "Rotation", instances.map { it.props["Rotation"] as? Float ?: 0f })
                writeNumberSequenceProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? List<NumberSequencePoint> ?: defaultNumberSequence(0f, 0f) })
            }
            StudioNode.CLASS_HIGHLIGHT -> {
                writeRefProp(writer, classId, "Adornee", instances.map { it.props["Adornee"] as? InstanceRecord })
                writeEnumProp(writer, classId, "DepthMode", instances.map { it.props["DepthMode"] as? Int ?: 0 })
                writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
                writeColor3Prop(writer, classId, "FillColor", instances.map { it.props["FillColor"] as? RgbColor ?: colorRgbFromHex("#FF0000") })
                writeFloatProp(writer, classId, "FillTransparency", instances.map { it.props["FillTransparency"] as? Float ?: 0.5f })
                writeColor3Prop(writer, classId, "OutlineColor", instances.map { it.props["OutlineColor"] as? RgbColor ?: colorRgbFromHex("#FFFFFF") })
                writeFloatProp(writer, classId, "OutlineTransparency", instances.map { it.props["OutlineTransparency"] as? Float ?: 0f })
            }
            StudioNode.CLASS_SCREEN_GUI -> writeScreenGuiProps(writer, classId, instances)
            in StudioNode.GUI_CLASS_NAMES -> writeGuiObjectProps(writer, classId, className, instances)
            StudioNode.CLASS_SOUND_SERVICE -> {
                writeEnumProp(writer, classId, "AmbientReverb", instances.map { it.props["AmbientReverb"] as? Int ?: 0 })
                writeFloatProp(writer, classId, "DistanceFactor", instances.map { it.props["DistanceFactor"] as? Float ?: 3.33f })
                writeFloatProp(writer, classId, "DopplerScale", instances.map { it.props["DopplerScale"] as? Float ?: 1f })
                writeBoolProp(writer, classId, "RespectFilteringEnabled", instances.map { it.props["RespectFilteringEnabled"] as? Boolean ?: true })
                writeFloatProp(writer, classId, "RolloffScale", instances.map { it.props["RolloffScale"] as? Float ?: 1f })
            }
        }

        if (className in USER_NODE_CLASSES) {
            writeInt64Prop(writer, classId, "SourceAssetId", instances.map { it.props["SourceAssetId"] as? Long ?: -1L })
            writeTagsProp(writer, classId, "Tags", instances.map { it.props["Tags"] as? String ?: "" })
        }

        if (className == "SpawnLocation") {
            writeBoolProp(writer, classId, "AllowTeamChangeOnTouch", instances.map { it.props["AllowTeamChangeOnTouch"] as Boolean })
            writeIntProp(writer, classId, "Duration", instances.map { it.props["Duration"] as Int })
            writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as Boolean })
            writeBoolProp(writer, classId, "Neutral", instances.map { it.props["Neutral"] as Boolean })
        }
    }

    private fun writeBasePartProps(
        writer: BinaryWriter,
        classId: Int,
        instances: List<InstanceRecord>,
        includeShape: Boolean
    ) {
        writeBoolProp(writer, classId, "Anchored", instances.map { it.props["Anchored"] as Boolean })
        writeBoolProp(writer, classId, "CanCollide", instances.map { it.props["CanCollide"] as Boolean })
        writeBoolProp(writer, classId, "CanQuery", instances.map { it.props["CanQuery"] as Boolean })
        writeBoolProp(writer, classId, "CanTouch", instances.map { it.props["CanTouch"] as Boolean })
        writeBoolProp(writer, classId, "CastShadow", instances.map { it.props["CastShadow"] as Boolean })
        writeBoolProp(writer, classId, "Locked", instances.map { it.props["Locked"] as Boolean })
        writeBoolProp(writer, classId, "Massless", instances.map { it.props["Massless"] as Boolean })
        writeFloatProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as Float })
        writeFloatProp(writer, classId, "Reflectance", instances.map { it.props["Reflectance"] as Float })
        writeStringProp(writer, classId, "CollisionGroup", instances.map { it.props["CollisionGroup"] as String })
        writeIntProp(writer, classId, "CollisionGroupId", instances.map { it.props["CollisionGroupId"] as Int })
        writeIntProp(writer, classId, "RootPriority", instances.map { it.props["RootPriority"] as Int })
        writeStringProp(writer, classId, "MaterialVariantSerialized", instances.map { it.props["MaterialVariantSerialized"] as String })
        writeEnumProp(writer, classId, "Material", instances.map { it.props["Material"] as Int })
        writeVector3Prop(writer, classId, "size", instances.map { it.props["size"] as Vector3 })
        writeColor3uint8Prop(writer, classId, "Color3uint8", instances.map { it.props["Color3uint8"] as Int })
        writeCFrameProp(writer, classId, "CFrame", instances.map { it.props["CFrame"] as CFrameValue })
        writeCFrameProp(writer, classId, "PivotOffset", instances.map { it.props["PivotOffset"] as CFrameValue })
        writePhysicalPropertiesProp(writer, classId, "CustomPhysicalProperties", instances.size)
        writeVector3Prop(writer, classId, "Velocity", instances.map { it.props["Velocity"] as Vector3 })
        writeVector3Prop(writer, classId, "RotVelocity", instances.map { it.props["RotVelocity"] as Vector3 })
        writeEnumProp(writer, classId, "formFactorRaw", instances.map { it.props["formFactorRaw"] as Int })
        listOf("TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface").forEach { name ->
            writeEnumProp(writer, classId, name, instances.map { it.props[name] as Int })
        }
        if (includeShape) {
            writeEnumProp(writer, classId, "shape", instances.map { it.props["Shape"] as Int })
        }
        writeInt64Prop(writer, classId, "SourceAssetId", instances.map { it.props["SourceAssetId"] as Long })
        writeUniqueIdProp(writer, classId, "UniqueId", instances.map { it.props["UniqueId"] as String })
    }

    private fun writeScreenGuiProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
        writeBoolProp(writer, classId, "ResetOnSpawn", instances.map { it.props["ResetOnSpawn"] as? Boolean ?: true })
        writeBoolProp(writer, classId, "IgnoreGuiInset", instances.map { it.props["IgnoreGuiInset"] as? Boolean ?: false })
        writeIntProp(writer, classId, "DisplayOrder", instances.map { it.props["DisplayOrder"] as? Int ?: 0 })
        writeEnumProp(writer, classId, "ZIndexBehavior", instances.map { it.props["ZIndexBehavior"] as? Int ?: 1 })
    }

    private fun writeGuiObjectProps(
        writer: BinaryWriter,
        classId: Int,
        className: String,
        instances: List<InstanceRecord>
    ) {
        writeBoolProp(writer, classId, "Active", instances.map { it.props["Active"] as? Boolean ?: false })
        writeVector2Prop(writer, classId, "AnchorPoint", instances.map { it.props["AnchorPoint"] as? Vector2Value ?: Vector2Value(0f, 0f) })
        writeEnumProp(writer, classId, "AutomaticSize", instances.map { it.props["AutomaticSize"] as? Int ?: 0 })
        writeColor3Prop(writer, classId, "BackgroundColor3", instances.map { it.props["BackgroundColor3"] as? RgbColor ?: colorRgbFromHex("#FFFFFF") })
        writeFloatProp(writer, classId, "BackgroundTransparency", instances.map { it.props["BackgroundTransparency"] as? Float ?: 0f })
        writeColor3Prop(writer, classId, "BorderColor3", instances.map { it.props["BorderColor3"] as? RgbColor ?: colorRgbFromHex("#000000") })
        writeEnumProp(writer, classId, "BorderMode", instances.map { it.props["BorderMode"] as? Int ?: 0 })
        writeIntProp(writer, classId, "BorderSizePixel", instances.map { it.props["BorderSizePixel"] as? Int ?: 1 })
        writeBoolProp(writer, classId, "ClipsDescendants", instances.map { it.props["ClipsDescendants"] as? Boolean ?: false })
        writeIntProp(writer, classId, "LayoutOrder", instances.map { it.props["LayoutOrder"] as? Int ?: 0 })
        writeUDim2Prop(writer, classId, "Position", instances.map { it.props["Position"] as? UDim2Value ?: UDim2Value() })
        writeFloatProp(writer, classId, "Rotation", instances.map { it.props["Rotation"] as? Float ?: 0f })
        writeBoolProp(writer, classId, "Selectable", instances.map { it.props["Selectable"] as? Boolean ?: false })
        writeRefProp(writer, classId, "SelectionImageObject", instances.map { it.props["SelectionImageObject"] as? InstanceRecord })
        writeUDim2Prop(writer, classId, "Size", instances.map { it.props["Size"] as? UDim2Value ?: UDim2Value(offsetX = 200, offsetY = 50) })
        writeEnumProp(writer, classId, "SizeConstraint", instances.map { it.props["SizeConstraint"] as? Int ?: 0 })
        writeBoolProp(writer, classId, "Visible", instances.map { it.props["Visible"] as? Boolean ?: true })
        writeIntProp(writer, classId, "ZIndex", instances.map { it.props["ZIndex"] as? Int ?: 1 })

        if (className in TEXT_GUI_CLASSES) {
            writeFontProp(writer, classId, "FontFace", instances.map { it.props["FontFace"] as? FontValue ?: defaultFont() })
            writeBoolProp(writer, classId, "RichText", instances.map { it.props["RichText"] as? Boolean ?: false })
            writeStringProp(writer, classId, "Text", instances.map { it.props["Text"] as? String ?: "" })
            writeColor3Prop(writer, classId, "TextColor3", instances.map { it.props["TextColor3"] as? RgbColor ?: colorRgbFromHex("#000000") })
            writeEnumProp(writer, classId, "TextDirection", instances.map { it.props["TextDirection"] as? Int ?: 0 })
            writeBoolProp(writer, classId, "TextScaled", instances.map { it.props["TextScaled"] as? Boolean ?: false })
            writeFloatProp(writer, classId, "TextSize", instances.map { it.props["TextSize"] as? Float ?: 14f })
            writeColor3Prop(writer, classId, "TextStrokeColor3", instances.map { it.props["TextStrokeColor3"] as? RgbColor ?: colorRgbFromHex("#000000") })
            writeFloatProp(writer, classId, "TextStrokeTransparency", instances.map { it.props["TextStrokeTransparency"] as? Float ?: 1f })
            writeFloatProp(writer, classId, "TextTransparency", instances.map { it.props["TextTransparency"] as? Float ?: 0f })
            writeEnumProp(writer, classId, "TextTruncate", instances.map { it.props["TextTruncate"] as? Int ?: 0 })
            writeBoolProp(writer, classId, "TextWrapped", instances.map { it.props["TextWrapped"] as? Boolean ?: false })
            writeEnumProp(writer, classId, "TextXAlignment", instances.map { it.props["TextXAlignment"] as? Int ?: 2 })
            writeEnumProp(writer, classId, "TextYAlignment", instances.map { it.props["TextYAlignment"] as? Int ?: 1 })
        }

        if (className == StudioNode.CLASS_TEXT_BOX) {
            writeBoolProp(writer, classId, "ClearTextOnFocus", instances.map { it.props["ClearTextOnFocus"] as? Boolean ?: true })
            writeIntProp(writer, classId, "CursorPosition", instances.map { it.props["CursorPosition"] as? Int ?: -1 })
            writeBoolProp(writer, classId, "MultiLine", instances.map { it.props["MultiLine"] as? Boolean ?: false })
            writeColor3Prop(writer, classId, "PlaceholderColor3", instances.map { it.props["PlaceholderColor3"] as? RgbColor ?: colorRgbFromHex("#B2B2B2") })
            writeStringProp(writer, classId, "PlaceholderText", instances.map { it.props["PlaceholderText"] as? String ?: "" })
            writeIntProp(writer, classId, "SelectionStart", instances.map { it.props["SelectionStart"] as? Int ?: -1 })
            writeBoolProp(writer, classId, "ShowNativeInput", instances.map { it.props["ShowNativeInput"] as? Boolean ?: true })
            writeBoolProp(writer, classId, "TextEditable", instances.map { it.props["TextEditable"] as? Boolean ?: true })
        }

        if (className in IMAGE_GUI_CLASSES) {
            writeStringProp(writer, classId, "Image", instances.map { it.props["Image"] as? String ?: "" })
            writeColor3Prop(writer, classId, "ImageColor3", instances.map { it.props["ImageColor3"] as? RgbColor ?: colorRgbFromHex("#FFFFFF") })
            writeVector2Prop(writer, classId, "ImageRectOffset", instances.map { it.props["ImageRectOffset"] as? Vector2Value ?: Vector2Value(0f, 0f) })
            writeVector2Prop(writer, classId, "ImageRectSize", instances.map { it.props["ImageRectSize"] as? Vector2Value ?: Vector2Value(0f, 0f) })
            writeFloatProp(writer, classId, "ImageTransparency", instances.map { it.props["ImageTransparency"] as? Float ?: 0f })
            writeEnumProp(writer, classId, "ScaleType", instances.map { it.props["ScaleType"] as? Int ?: 0 })
            writeUDim2Prop(writer, classId, "TileSize", instances.map { it.props["TileSize"] as? UDim2Value ?: UDim2Value(scaleX = 1f, scaleY = 1f) })
            if (className == StudioNode.CLASS_IMAGE_BUTTON) {
                writeStringProp(writer, classId, "HoverImage", instances.map { it.props["HoverImage"] as? String ?: "" })
                writeStringProp(writer, classId, "PressedImage", instances.map { it.props["PressedImage"] as? String ?: "" })
            }
        }
    }

    private fun writeSkyProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeBoolProp(writer, classId, "CelestialBodiesShown", instances.map { it.props["CelestialBodiesShown"] as? Boolean ?: true })
        writeFloatProp(writer, classId, "MoonAngularSize", instances.map { it.props["MoonAngularSize"] as? Float ?: 11f })
        writeStringProp(writer, classId, "MoonTextureId", instances.map { it.props["MoonTextureId"] as? String ?: "" })
        listOf("SkyboxBk", "SkyboxDn", "SkyboxFt", "SkyboxLf", "SkyboxRt", "SkyboxUp").forEach { name ->
            writeStringProp(writer, classId, name, instances.map { it.props[name] as? String ?: "" })
        }
        writeIntProp(writer, classId, "StarCount", instances.map { it.props["StarCount"] as? Int ?: 3000 })
        writeFloatProp(writer, classId, "SunAngularSize", instances.map { it.props["SunAngularSize"] as? Float ?: 21f })
        writeStringProp(writer, classId, "SunTextureId", instances.map { it.props["SunTextureId"] as? String ?: "" })
    }

    private fun writeTrailProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeRefProp(writer, classId, "Attachment0", instances.map { it.props["Attachment0"] as? InstanceRecord })
        writeRefProp(writer, classId, "Attachment1", instances.map { it.props["Attachment1"] as? InstanceRecord })
        writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as? Float ?: 1f })
        writeColorSequenceProp(writer, classId, "Color", instances.map { it.props["Color"] as? List<ColorSequencePoint> ?: defaultColorSequence() })
        writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
        writeBoolProp(writer, classId, "FaceCamera", instances.map { it.props["FaceCamera"] as? Boolean ?: false })
        writeFloatProp(writer, classId, "Lifetime", instances.map { it.props["Lifetime"] as? Float ?: 0.5f })
        writeFloatProp(writer, classId, "MinLength", instances.map { it.props["MinLength"] as? Float ?: 0.1f })
        writeStringProp(writer, classId, "Texture", instances.map { it.props["Texture"] as? String ?: "" })
        writeFloatProp(writer, classId, "TextureLength", instances.map { it.props["TextureLength"] as? Float ?: 1f })
        writeEnumProp(writer, classId, "TextureMode", instances.map { it.props["TextureMode"] as? Int ?: 0 })
        writeNumberSequenceProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? List<NumberSequencePoint> ?: defaultNumberSequence(0f, 1f) })
        writeNumberSequenceProp(writer, classId, "WidthScale", instances.map { it.props["WidthScale"] as? List<NumberSequencePoint> ?: defaultNumberSequence(1f, 1f) })
    }

    private fun writeBeamProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeRefProp(writer, classId, "Attachment0", instances.map { it.props["Attachment0"] as? InstanceRecord })
        writeRefProp(writer, classId, "Attachment1", instances.map { it.props["Attachment1"] as? InstanceRecord })
        writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as? Float ?: 1f })
        writeColorSequenceProp(writer, classId, "Color", instances.map { it.props["Color"] as? List<ColorSequencePoint> ?: defaultColorSequence() })
        listOf("CurveSize0", "CurveSize1", "TextureLength", "TextureSpeed", "Width0", "Width1", "ZOffset").forEach { name ->
            writeFloatProp(writer, classId, name, instances.map { it.props[name] as? Float ?: if (name.startsWith("Width") || name == "TextureLength") 1f else 0f })
        }
        writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
        writeBoolProp(writer, classId, "FaceCamera", instances.map { it.props["FaceCamera"] as? Boolean ?: true })
        writeIntProp(writer, classId, "Segments", instances.map { it.props["Segments"] as? Int ?: 1 })
        writeStringProp(writer, classId, "Texture", instances.map { it.props["Texture"] as? String ?: "" })
        writeEnumProp(writer, classId, "TextureMode", instances.map { it.props["TextureMode"] as? Int ?: 0 })
        writeNumberSequenceProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? List<NumberSequencePoint> ?: defaultNumberSequence(0f, 0f) })
    }

    private fun writeParticleEmitterProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeVector3Prop(writer, classId, "Acceleration", instances.map { it.props["Acceleration"] as? Vector3 ?: Vector3.Zero })
        writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as? Float ?: 1f })
        writeColorSequenceProp(writer, classId, "Color", instances.map { it.props["Color"] as? List<ColorSequencePoint> ?: defaultColorSequence() })
        writeFloatProp(writer, classId, "Drag", instances.map { it.props["Drag"] as? Float ?: 0f })
        writeEnumProp(writer, classId, "EmissionDirection", instances.map { it.props["EmissionDirection"] as? Int ?: 5 })
        writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
        writeNumberRangeProp(writer, classId, "Lifetime", instances.map { it.props["Lifetime"] as? NumberRange ?: NumberRange(1f, 1f) })
        writeFloatProp(writer, classId, "Rate", instances.map { it.props["Rate"] as? Float ?: 5f })
        writeNumberSequenceProp(writer, classId, "Size", instances.map { it.props["Size"] as? List<NumberSequencePoint> ?: defaultNumberSequence(0.5f, 0f) })
        writeNumberRangeProp(writer, classId, "Speed", instances.map { it.props["Speed"] as? NumberRange ?: NumberRange(1f, 1f) })
        writeVector2Prop(writer, classId, "SpreadAngle", instances.map { it.props["SpreadAngle"] as? Vector2Value ?: Vector2Value(0f, 0f) })
        writeStringProp(writer, classId, "Texture", instances.map { it.props["Texture"] as? String ?: "" })
        writeNumberSequenceProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as? List<NumberSequencePoint> ?: defaultNumberSequence(0f, 1f) })
    }

    private fun writeSurfaceGuiProps(writer: BinaryWriter, classId: Int, instances: List<InstanceRecord>) {
        writeBoolProp(writer, classId, "Active", instances.map { it.props["Active"] as? Boolean ?: true })
        writeRefProp(writer, classId, "Adornee", instances.map { it.props["Adornee"] as? InstanceRecord })
        writeBoolProp(writer, classId, "AlwaysOnTop", instances.map { it.props["AlwaysOnTop"] as? Boolean ?: false })
        writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as? Float ?: 1f })
        writeVector2Prop(writer, classId, "CanvasSize", instances.map { it.props["CanvasSize"] as? Vector2Value ?: Vector2Value(800f, 600f) })
        writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as? Boolean ?: true })
        writeEnumProp(writer, classId, "Face", instances.map { it.props["Face"] as? Int ?: 5 })
        writeFloatProp(writer, classId, "LightInfluence", instances.map { it.props["LightInfluence"] as? Float ?: 0f })
        writeFloatProp(writer, classId, "PixelsPerStud", instances.map { it.props["PixelsPerStud"] as? Float ?: 50f })
        writeBoolProp(writer, classId, "ResetOnSpawn", instances.map { it.props["ResetOnSpawn"] as? Boolean ?: true })
        writeEnumProp(writer, classId, "SizingMode", instances.map { it.props["SizingMode"] as? Int ?: 0 })
        writeEnumProp(writer, classId, "ZIndexBehavior", instances.map { it.props["ZIndexBehavior"] as? Int ?: 0 })
        writeFloatProp(writer, classId, "ZOffset", instances.map { it.props["ZOffset"] as? Float ?: 0f })
    }

    private fun writeParentChunk(writer: BinaryWriter, instances: List<InstanceRecord>) {
        val chunk = BinaryWriter()
        chunk.writeUInt8(0) // version
        chunk.writeInt32LE(instances.size)
        chunk.writeInterleavedInts(instances.map { it.referent }.toIntArray(), zigzag = true, accumulate = true)
        chunk.writeInterleavedInts(instances.map { it.parent?.referent ?: -1 }.toIntArray(), zigzag = true, accumulate = true)
        writeChunk(writer, "PRNT", chunk.toByteArray())
    }

    private fun writeStringProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_STRING) { chunk -> values.forEach { chunk.writeString(it) } }

    private fun writeBoolProp(writer: BinaryWriter, classId: Int, name: String, values: List<Boolean>) =
        writeProp(writer, classId, name, TYPE_BOOL) { chunk -> values.forEach { chunk.writeUInt8(if (it) 1 else 0) } }

    private fun writeIntProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_INT32) { chunk -> chunk.writeInterleavedInts(values.toIntArray(), zigzag = true) }

    private fun writeInt64Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Long>) =
        writeProp(writer, classId, name, TYPE_INT64) { chunk -> chunk.writeInterleavedInt64(values.toLongArray()) }

    private fun writeFloatProp(writer: BinaryWriter, classId: Int, name: String, values: List<Float>) =
        writeProp(writer, classId, name, TYPE_FLOAT32) { chunk -> chunk.writeInterleavedFloats(values.toFloatArray()) }

    private fun writeEnumProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_ENUM) { chunk -> chunk.writeInterleavedInts(values.toIntArray(), zigzag = false) }

    private fun writeRefProp(writer: BinaryWriter, classId: Int, name: String, values: List<InstanceRecord?>) =
        writeProp(writer, classId, name, TYPE_REF) { chunk ->
            chunk.writeInterleavedInts(values.map { it?.referent ?: -1 }.toIntArray(), zigzag = true, accumulate = true)
        }

    private fun writeVector2Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Vector2Value>) =
        writeProp(writer, classId, name, TYPE_VECTOR2) { chunk ->
            chunk.writeInterleavedFloats(values.map { it.x }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.y }.toFloatArray())
        }

    private fun writeUDim2Prop(writer: BinaryWriter, classId: Int, name: String, values: List<UDim2Value>) =
        writeProp(writer, classId, name, TYPE_UDIM2) { chunk ->
            chunk.writeInterleavedFloats(values.map { it.scaleX }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.scaleY }.toFloatArray())
            chunk.writeInterleavedInts(values.map { it.offsetX }.toIntArray(), zigzag = true)
            chunk.writeInterleavedInts(values.map { it.offsetY }.toIntArray(), zigzag = true)
        }

    private fun writeUDimProp(writer: BinaryWriter, classId: Int, name: String, values: List<UDimValue>) =
        writeProp(writer, classId, name, TYPE_UDIM) { chunk ->
            chunk.writeInterleavedFloats(values.map { it.scale }.toFloatArray())
            chunk.writeInterleavedInts(values.map { it.offset }.toIntArray(), zigzag = true)
        }

    private fun writeNumberSequenceProp(writer: BinaryWriter, classId: Int, name: String, values: List<List<NumberSequencePoint>>) =
        writeProp(writer, classId, name, TYPE_NUMBER_SEQUENCE) { chunk ->
            values.forEach { sequence ->
                chunk.writeInt32LE(sequence.size)
                sequence.forEach { point ->
                    chunk.writeFloat32LE(point.time)
                    chunk.writeFloat32LE(point.value)
                    chunk.writeFloat32LE(point.envelope)
                }
            }
        }

    private fun writeColorSequenceProp(writer: BinaryWriter, classId: Int, name: String, values: List<List<ColorSequencePoint>>) =
        writeProp(writer, classId, name, TYPE_COLOR_SEQUENCE) { chunk ->
            values.forEach { sequence ->
                chunk.writeInt32LE(sequence.size)
                sequence.forEach { point ->
                    chunk.writeFloat32LE(point.time)
                    chunk.writeFloat32LE(point.color.r)
                    chunk.writeFloat32LE(point.color.g)
                    chunk.writeFloat32LE(point.color.b)
                    chunk.writeFloat32LE(point.envelope)
                }
            }
        }

    private fun writeNumberRangeProp(writer: BinaryWriter, classId: Int, name: String, values: List<NumberRange>) =
        writeProp(writer, classId, name, TYPE_NUMBER_RANGE) { chunk ->
            values.forEach { range ->
                chunk.writeFloat32LE(range.min)
                chunk.writeFloat32LE(range.max)
            }
        }

    private fun writeFontProp(writer: BinaryWriter, classId: Int, name: String, values: List<FontValue>) =
        writeProp(writer, classId, name, TYPE_FONT) { chunk ->
            values.forEach { font ->
                chunk.writeString(font.family)
                chunk.writeUInt16LE(font.weight)
                chunk.writeUInt8(font.style)
                chunk.writeString(font.cachedFaceId)
            }
        }

    private fun writeTagsProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_TAGS) { chunk ->
            values.forEach { value ->
                val blob = BinaryWriter()
                value.split(',', ';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { tag ->
                        val bytes = tag.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                        blob.writeUInt8(bytes.size)
                        blob.writeBytes(bytes)
                    }
                val bytes = blob.toByteArray()
                chunk.writeInt32LE(bytes.size)
                chunk.writeBytes(bytes)
            }
        }

    private fun writeVector3Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Vector3>) =
        writeProp(writer, classId, name, TYPE_VECTOR3) { chunk ->
            // Three separate interleaved blocks: all X, then all Y, then all Z.
            chunk.writeInterleavedFloats(values.map { it.x }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.y }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.z }.toFloatArray())
        }

    private fun writeColor3uint8Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_COLOR3UINT8) { chunk ->
            values.forEach { chunk.writeUInt8((it shr 16) and 0xFF) }
            values.forEach { chunk.writeUInt8((it shr 8) and 0xFF) }
            values.forEach { chunk.writeUInt8(it and 0xFF) }
        }

    private fun writeColor3Prop(writer: BinaryWriter, classId: Int, name: String, values: List<RgbColor>) =
        writeProp(writer, classId, name, TYPE_COLOR3) { chunk ->
            chunk.writeInterleavedFloats(values.map { it.r }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.g }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.b }.toFloatArray())
        }

    private fun writeDoubleProp(writer: BinaryWriter, classId: Int, name: String, values: List<Double>) =
        writeProp(writer, classId, name, TYPE_DOUBLE) { chunk ->
            values.forEach { chunk.writeFloat64LE(it) }
        }

    private fun writePhysicalPropertiesProp(writer: BinaryWriter, classId: Int, name: String, count: Int) =
        writeProp(writer, classId, name, TYPE_PHYSICALPROPS) { chunk ->
            // 0x00 = default physical properties (no custom values follow)
            repeat(count) { chunk.writeUInt8(0) }
        }

    private fun writeUniqueIdProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_UNIQUEID) { chunk ->
            // 16 bytes: version(4 LE) + variant(4 LE) + timestamp(8 LE). All zero is acceptable.
            values.forEach { chunk.writeBytes(ByteArray(16)) }
        }

    private fun writeCFrameProp(writer: BinaryWriter, classId: Int, name: String, values: List<CFrameValue>) =
        writeProp(writer, classId, name, TYPE_CFRAME) { chunk ->
            values.forEach { value ->
                val special = cframeSpecialId(value.rotation)
                chunk.writeUInt8(special)
                if (special == 0) {
                    value.rotation.forEach { chunk.writeFloat32LE(it) }
                }
            }
            // Positions are written as three separate interleaved blocks (X, then Y, then Z),
            // matching Roblox's reader which deinterleaves per axis.
            chunk.writeInterleavedFloats(values.map { it.position.x }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.position.y }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.position.z }.toFloatArray())
        }

    private fun writeProp(
        writer: BinaryWriter,
        classId: Int,
        name: String,
        typeId: Int,
        writeValues: (BinaryWriter) -> Unit
    ) {
        val chunk = BinaryWriter()
        chunk.writeInt32LE(classId)
        chunk.writeString(name)
        chunk.writeUInt8(typeId)
        writeValues(chunk)
        writeChunk(writer, "PROP", chunk.toByteArray())
    }

    private fun writeChunk(writer: BinaryWriter, name: String, data: ByteArray) {
        writer.writeAscii4(name)
        writer.writeUInt32LE(0) // compressedLength = 0 -> uncompressed
        writer.writeUInt32LE(data.size)
        writer.writeUInt32LE(0) // reserved
        writer.writeBytes(data)
    }

    private fun rotationMatrix(rotation: Vector3): FloatArray {
        val rx = Math.toRadians(rotation.x.toDouble())
        val ry = Math.toRadians(rotation.y.toDouble())
        val rz = Math.toRadians(rotation.z.toDouble())
        val cx = cos(rx).toFloat()
        val sx = sin(rx).toFloat()
        val cy = cos(ry).toFloat()
        val sy = sin(ry).toFloat()
        val cz = cos(rz).toFloat()
        val sz = sin(rz).toFloat()

        return floatArrayOf(
            cy * cz,
            cz * sx * sy - cx * sz,
            sx * sz + cx * cz * sy,
            cy * sz,
            cx * cz + sx * sy * sz,
            cx * sy * sz - cz * sx,
            -sy,
            cy * sx,
            cx * cy
        )
    }

    /** Maps an axis-aligned rotation matrix to its Roblox special orientation ID (0x02..0x23), or 0 for a raw matrix. */
    private fun cframeSpecialId(rotation: FloatArray): Int {
        val index = CFRAME_SPECIAL_MATRICES.indexOfFirst { special ->
            rotation.indices.all { i -> kotlin.math.abs(rotation[i] - special[i]) < 0.0001f }
        }
        return if (index >= 0) CFRAME_SPECIAL_IDS[index] else 0
    }

    private fun colorFromHex(hex: String): Int {
        val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
        val value = runCatching { rgb.toInt(16) }.getOrDefault(0xCCCCCC)
        return (0xFF shl 24) or value
    }

    private fun colorRgbFromHex(hex: String): RgbColor {
        val value = hex.removePrefix("#").padEnd(6, '0').take(6).toIntOrNull(16) ?: 0xFFFFFF
        return RgbColor(
            ((value shr 16) and 0xFF) / 255f,
            ((value shr 8) and 0xFF) / 255f,
            (value and 0xFF) / 255f
        )
    }

    private fun enumToken(value: String, values: Map<String, Int>): Int =
        value.toIntOrNull() ?: values.entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.value ?: 0

    private fun parseVector2(value: String): Vector2Value {
        fun named(name: String): Float? = Regex("$name\\s*=\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).mapNotNull { it.value.toFloatOrNull() }.toList()
        return Vector2Value(
            named("x") ?: numbers.getOrElse(0) { 0f },
            named("y") ?: numbers.getOrElse(1) { 0f }
        )
    }

    private fun parseUDim2(value: String): UDim2Value {
        fun float(name: String): Float = Regex("$name\\s*=\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
        fun int(name: String): Int = Regex("$name\\s*=\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return UDim2Value(float("scaleX"), float("scaleY"), int("offsetX"), int("offsetY"))
    }

    private fun parseUDim(value: String): UDimValue {
        fun named(name: String): Float? = Regex("$name\\s*=\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val numbers = numbersIn(value)
        return UDimValue(named("scale") ?: numbers.getOrElse(0) { 0f }, (named("offset") ?: numbers.getOrElse(1) { 0f }).toInt())
    }

    private fun parseVector3(value: String): Vector3 {
        val numbers = numbersIn(value)
        return Vector3(numbers.getOrElse(0) { 0f }, numbers.getOrElse(1) { 0f }, numbers.getOrElse(2) { 0f })
    }

    private fun parseNumberRange(value: String, default: Float): NumberRange {
        val numbers = numbersIn(value)
        return NumberRange(numbers.getOrElse(0) { default }, numbers.getOrElse(1) { numbers.getOrElse(0) { default } })
    }

    private fun parseNumberSequence(value: String): List<NumberSequencePoint> =
        value.split(';').mapNotNull { entry ->
            val numbers = numbersIn(entry)
            if (numbers.size < 2) null else NumberSequencePoint(numbers[0], numbers[1], numbers.getOrElse(2) { 0f })
        }.ifEmpty { defaultNumberSequence(0f, 0f) }

    private fun parseColorSequence(value: String): List<ColorSequencePoint> =
        value.split(';').mapNotNull { entry ->
            val colorHex = Regex("#[0-9a-fA-F]{6}").find(entry)?.value ?: return@mapNotNull null
            val numbers = numbersIn(entry.replace(colorHex, ""))
            ColorSequencePoint(numbers.getOrElse(0) { 0f }, colorRgbFromHex(colorHex), numbers.getOrElse(1) { 0f })
        }.ifEmpty { defaultColorSequence() }

    private fun numbersIn(value: String): List<Float> =
        Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").findAll(value).mapNotNull { it.value.toFloatOrNull() }.toList()

    private fun defaultNumberSequence(first: Float, last: Float) =
        listOf(NumberSequencePoint(0f, first, 0f), NumberSequencePoint(1f, last, 0f))

    private fun defaultColorSequence() =
        listOf(ColorSequencePoint(0f, colorRgbFromHex("#FFFFFF"), 0f), ColorSequencePoint(1f, colorRgbFromHex("#FFFFFF"), 0f))

    private fun parseFont(value: String): FontValue {
        fun token(name: String): String? = Regex("$name\\s*=\\s*([^,]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.trim()
        val family = token("family").orEmpty().ifBlank { "rbxasset://fonts/families/SourceSansPro.json" }
        val normalizedFamily = if (family.contains("://")) family else "rbxasset://fonts/families/$family.json"
        return FontValue(
            family = normalizedFamily,
            weight = token("weight")?.toIntOrNull() ?: 400,
            style = token("style")?.toIntOrNull() ?: 0,
            cachedFaceId = token("cachedFaceId").orEmpty()
        )
    }

    private fun parseCFrame(value: String): CFrameValue {
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).mapNotNull { it.value.toFloatOrNull() }.toList()
        val position = Vector3(
            numbers.getOrElse(0) { 0f },
            numbers.getOrElse(1) { 0f },
            numbers.getOrElse(2) { 0f }
        )
        val rotation = if (numbers.size >= 6) Vector3(numbers[3], numbers[4], numbers[5]) else Vector3.Zero
        return CFrameValue(position, rotationMatrix(rotation))
    }

    private fun identityCFrame() = CFrameValue(Vector3.Zero, rotationMatrix(Vector3.Zero))

    private fun defaultFont() = FontValue("rbxasset://fonts/families/SourceSansPro.json", 400, 0, "")

    private fun faceToken(face: String): Int = when (face.lowercase()) {
        "right" -> 0
        "top" -> 1
        "back" -> 2
        "left" -> 3
        "bottom" -> 4
        else -> 5
    }

    private fun randomUniqueId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun materialToken(material: String): Int = when (material) {
        Part.MATERIAL_NEON -> 272
        Part.MATERIAL_WOOD -> 288
        Part.MATERIAL_GLASS -> 336
        Part.MATERIAL_MARBLE -> 400
        Part.MATERIAL_FABRIC -> 432
        Part.MATERIAL_BRICK -> 528
        Part.MATERIAL_METAL -> 784
        Part.MATERIAL_SLATE -> 1056
        else -> 256
    }

    private fun shapeToken(shape: String): Int = when (shape) {
        Part.SHAPE_SPHERE -> 0
        Part.SHAPE_CYLINDER -> 2
        else -> 1
    }

    private fun surfaceToken(surface: String): Int = when (surface) {
        Part.SURFACE_GLUE -> 1
        Part.SURFACE_WELD -> 2
        Part.SURFACE_STUDS -> 3
        Part.SURFACE_INLET -> 4
        Part.SURFACE_UNIVERSAL -> 5
        Part.SURFACE_HINGE -> 6
        Part.SURFACE_MOTOR -> 7
        Part.SURFACE_STEPPING_MOTOR -> 8
        Part.SURFACE_SMOOTH_NO_OUTLINES -> 10
        else -> 0
    }

    private class InstanceRecord(
        val className: String,
        val name: String,
        var parent: InstanceRecord?,
        val props: MutableMap<String, Any> = linkedMapOf()
    ) {
        var referent: Int = -1
    }

    private data class CFrameValue(val position: Vector3, val rotation: FloatArray)
    private data class RgbColor(val r: Float, val g: Float, val b: Float)
    private data class Vector2Value(val x: Float, val y: Float)
    private data class UDimValue(val scale: Float = 0f, val offset: Int = 0)
    private data class UDim2Value(
        val scaleX: Float = 0f,
        val scaleY: Float = 0f,
        val offsetX: Int = 0,
        val offsetY: Int = 0
    )
    private data class FontValue(val family: String, val weight: Int, val style: Int, val cachedFaceId: String)
    private data class NumberSequencePoint(val time: Float, val value: Float, val envelope: Float)
    private data class ColorSequencePoint(val time: Float, val color: RgbColor, val envelope: Float)
    private data class NumberRange(val min: Float, val max: Float)

    private class BinaryWriter {
        private val out = ByteArrayOutputStream()

        fun writeBytes(value: ByteArray) {
            out.write(value)
        }

        fun writeAscii4(value: String) {
            val bytes = ByteArray(4)
            value.toByteArray(Charsets.US_ASCII).copyInto(bytes, endIndex = minOf(4, value.length))
            writeBytes(bytes)
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeUInt32LE(bytes.size)
            writeBytes(bytes)
        }

        fun writeUInt8(value: Int) {
            out.write(value and 0xFF)
        }

        fun writeUInt16LE(value: Int) {
            writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }

        fun writeUInt32LE(value: Int) {
            writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun writeInt32LE(value: Int) = writeUInt32LE(value)

        fun writeFloat32LE(value: Float) {
            writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }

        fun writeFloat64LE(value: Double) {
            writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        }

        /**
         * Writes int32 values as big-endian, byte-interleaved.
         * [zigzag] applies zigzag encoding (referents, Int32 props).
         * [accumulate] converts values to delta-from-previous first (referent arrays).
         */
        fun writeInterleavedInts(values: IntArray, zigzag: Boolean, accumulate: Boolean = false) {
            if (values.isEmpty()) return
            val encoded = IntArray(values.size)
            var last = 0
            for (i in values.indices) {
                var v = values[i]
                if (accumulate) {
                    val delta = v - last
                    last = v
                    v = delta
                }
                encoded[i] = if (zigzag) (v shl 1) xor (v shr 31) else v
            }
            writeInterleaved(encoded)
        }

        /** Writes int64 values as zigzag + big-endian + byte-interleaved. */
        fun writeInterleavedInt64(values: LongArray) {
            if (values.isEmpty()) return
            val bytes = ByteArray(values.size * 8)
            values.forEachIndexed { index, value ->
                val encoded = (value shl 1) xor (value shr 63)
                val base = index * 8
                for (b in 0 until 8) {
                    bytes[base + b] = (encoded ushr (56 - b * 8)).toByte()
                }
            }
            repeat(8) { byteIndex ->
                values.indices.forEach { index -> writeUInt8(bytes[index * 8 + byteIndex].toInt()) }
            }
        }

        /** Writes floats with the Roblox float transform (rotl(bits, 1)), big-endian, interleaved. */
        fun writeInterleavedFloats(values: FloatArray) {
            if (values.isEmpty()) return
            val encoded = IntArray(values.size) { i -> Integer.rotateLeft(values[i].toRawBits(), 1) }
            writeInterleaved(encoded)
        }

        private fun writeInterleaved(encoded: IntArray) {
            repeat(4) { byteIndex ->
                encoded.forEach { value ->
                    writeUInt8((value ushr (24 - byteIndex * 8)) and 0xFF)
                }
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val MAGIC = byteArrayOf(0x3C, 0x72, 0x6F, 0x62, 0x6C, 0x6F, 0x78, 0x21)
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private val SERVICE_CLASSES = setOf(
        "Workspace",
        "Players",
        "Lighting",
        "ReplicatedFirst",
        "ReplicatedStorage",
        "ServerScriptService",
        "ServerStorage",
        "StarterGui",
        "StarterPack",
        "StarterPlayer",
        "Teams",
        "SoundService",
        "Chat",
        "MaterialService"
    )

    // Axis-aligned rotation matrices and their special orientation IDs (rbxfile spec).
    private val CFRAME_SPECIAL_IDS = intArrayOf(
        0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x10, 0x11,
        0x14, 0x15, 0x17, 0x18, 0x19, 0x1B, 0x1C, 0x1E, 0x1F, 0x20, 0x22, 0x23
    )
    private val CFRAME_SPECIAL_MATRICES = arrayOf(
        floatArrayOf(+1f, +0f, +0f, +0f, +1f, +0f, +0f, +0f, +1f),
        floatArrayOf(+1f, +0f, +0f, +0f, +0f, -1f, +0f, +1f, +0f),
        floatArrayOf(+1f, +0f, +0f, +0f, -1f, +0f, +0f, +0f, -1f),
        floatArrayOf(+1f, +0f, +0f, +0f, +0f, +1f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +1f, +0f, +0f, +0f, +0f, -1f),
        floatArrayOf(+0f, +0f, +1f, +1f, +0f, +0f, +0f, +1f, +0f),
        floatArrayOf(+0f, -1f, +0f, +1f, +0f, +0f, +0f, +0f, +1f),
        floatArrayOf(+0f, +0f, -1f, +1f, +0f, +0f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +0f, +0f, +1f, +1f, +0f, +0f),
        floatArrayOf(+0f, +0f, -1f, +0f, +1f, +0f, +1f, +0f, +0f),
        floatArrayOf(+0f, -1f, +0f, +0f, +0f, -1f, +1f, +0f, +0f),
        floatArrayOf(+0f, +0f, +1f, +0f, -1f, +0f, +1f, +0f, +0f),
        floatArrayOf(-1f, +0f, +0f, +0f, +1f, +0f, +0f, +0f, -1f),
        floatArrayOf(-1f, +0f, +0f, +0f, +0f, +1f, +0f, +1f, +0f),
        floatArrayOf(-1f, +0f, +0f, +0f, -1f, +0f, +0f, +0f, +1f),
        floatArrayOf(-1f, +0f, +0f, +0f, +0f, -1f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, -1f, +0f, +0f, +0f, +0f, +1f),
        floatArrayOf(+0f, +0f, -1f, -1f, +0f, +0f, +0f, +1f, +0f),
        floatArrayOf(+0f, -1f, +0f, -1f, +0f, +0f, +0f, +0f, -1f),
        floatArrayOf(+0f, +0f, +1f, -1f, +0f, +0f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +0f, +0f, -1f, -1f, +0f, +0f),
        floatArrayOf(+0f, +0f, +1f, +0f, +1f, +0f, -1f, +0f, +0f),
        floatArrayOf(+0f, -1f, +0f, +0f, +0f, +1f, -1f, +0f, +0f),
        floatArrayOf(+0f, +0f, -1f, +0f, -1f, +0f, -1f, +0f, +0f)
    )

    private const val TYPE_STRING = 0x01
    private const val TYPE_BOOL = 0x02
    private const val TYPE_INT32 = 0x03
    private const val TYPE_FLOAT32 = 0x04
    private const val TYPE_DOUBLE = 0x05
    private const val TYPE_UDIM = 0x06
    private const val TYPE_UDIM2 = 0x07
    private const val TYPE_COLOR3 = 0x0C
    private const val TYPE_VECTOR2 = 0x0D
    private const val TYPE_PHYSICALPROPS = 0x19
    private const val TYPE_COLOR3UINT8 = 0x1A
    private const val TYPE_VECTOR3 = 0x0E
    private const val TYPE_CFRAME = 0x10
    private const val TYPE_ENUM = 0x12
    private const val TYPE_REF = 0x13
    private const val TYPE_NUMBER_SEQUENCE = 0x15
    private const val TYPE_COLOR_SEQUENCE = 0x16
    private const val TYPE_NUMBER_RANGE = 0x17
    private const val TYPE_INT64 = 0x1B
    private const val TYPE_UNIQUEID = 0x1F
    private const val TYPE_FONT = 0x20
    private const val TYPE_TAGS = 0x29

    private val USER_NODE_CLASSES = setOf(
        "Script", "LocalScript", "ModuleScript", "Attachment", "RemoteEvent",
        "Sound", "PointLight", "SpotLight", "SurfaceLight", "Folder", "Model",
        "Weld", "WeldConstraint", "ClickDetector", "Decal", "Texture", "Sky",
        "Trail", "Beam", "ParticleEmitter", "SurfaceGui", "UIListLayout", "UICorner", "UIStroke", "Highlight", "UIGradient"
    ) + StudioNode.GUI_CLASS_NAMES

    private val BASE_PART_CLASSES = setOf(
        "Part", "WedgePart", "SpawnLocation", "CornerWedgePart", "TrussPart", "MeshPart"
    )

    private val TEXT_GUI_CLASSES = setOf(
        StudioNode.CLASS_TEXT_LABEL,
        StudioNode.CLASS_TEXT_BUTTON,
        StudioNode.CLASS_TEXT_BOX
    )

    private val IMAGE_GUI_CLASSES = setOf(
        StudioNode.CLASS_IMAGE_LABEL,
        StudioNode.CLASS_IMAGE_BUTTON
    )

    private val AUTOMATIC_SIZE = mapOf("None" to 0, "X" to 1, "Y" to 2, "XY" to 3)
    private val BORDER_MODE = mapOf("Outline" to 0, "Middle" to 1, "Inset" to 2)
    private val SIZE_CONSTRAINT = mapOf("RelativeXY" to 0, "RelativeXX" to 1, "RelativeYY" to 2)
    private val Z_INDEX_BEHAVIOR = mapOf("Global" to 0, "Sibling" to 1)
    private val TEXT_DIRECTION = mapOf("Auto" to 0, "LeftToRight" to 1, "RightToLeft" to 2)
    private val TEXT_TRUNCATE = mapOf("None" to 0, "AtEnd" to 1, "SplitWord" to 2)
    private val TEXT_X_ALIGNMENT = mapOf("Left" to 0, "Right" to 1, "Center" to 2)
    private val TEXT_Y_ALIGNMENT = mapOf("Top" to 0, "Center" to 1, "Bottom" to 2)
    private val SCALE_TYPE = mapOf("Stretch" to 0, "Slice" to 1, "Tile" to 2, "Fit" to 3, "Crop" to 4)
    private val MODEL_LEVEL_OF_DETAIL = mapOf("Automatic" to 0, "StreamingMesh" to 1, "Disabled" to 2)
    private val FILL_DIRECTION = mapOf("Horizontal" to 0, "Vertical" to 1)
    private val HORIZONTAL_ALIGNMENT = mapOf("Left" to 0, "Center" to 1, "Right" to 2)
    private val VERTICAL_ALIGNMENT = mapOf("Top" to 0, "Center" to 1, "Bottom" to 2)
    private val SORT_ORDER = mapOf("Name" to 0, "Custom" to 1, "LayoutOrder" to 2)
    private val APPLY_STROKE_MODE = mapOf("Border" to 0, "Contextual" to 1)
    private val LINE_JOIN_MODE = mapOf("Round" to 0, "Bevel" to 1, "Miter" to 2)
}
