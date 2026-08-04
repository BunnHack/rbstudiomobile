package com.example.ui.viewport

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.example.models.Part
import com.example.viewmodels.StudioViewModel
import com.google.android.filament.Texture
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.geometries.UvScale
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.LineNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU 3D viewport backed by Filament via SceneView. Replaces the kool engine.
 *
 * SceneView owns the full EGL/surface lifecycle through Compose, so tab switches and
 * place switches create/destroy the surface cleanly — the "black viewport after
 * switching tabs" class of bugs we hit with kool's process-wide singleton context
 * does not exist here.
 *
 *  - Each [Part] becomes a primitive node (cube / sphere / cylinder / wedge-cube).
 *  - Decals & Textures (Roblox image assets) render as textured [PlaneNode]s hugging
 *    the part face, loaded via [RobloxTextureLoader].
 *  - The selected part gets a thin selection plane overlay.
 *  - Tap a node to select the backing part; orbit camera follows ViewModel yaw/pitch/zoom.
 */
@Composable
fun StudioViewport(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val parts by viewModel.parts.collectAsState()
    val yaw by viewModel.cameraYaw.collectAsState()
    val pitch by viewModel.cameraPitch.collectAsState()
    val zoom by viewModel.cameraZoom.collectAsState()
    val selectedPart by viewModel.selectedPart.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val nodes by viewModel.explorerNodes.collectAsState()
    val roblosecurityCookie by viewModel.roblosecurityCookie.collectAsState()
    val decals = remember(nodes, parts) { buildRenderableDecals(nodes, parts) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)

    val scope = rememberCoroutineScope()
    val textureLoader = remember { RobloxTextureLoader(engine, OkHttpClient()) }
    // Loaded decal textures by "uri|repeat"; populated asynchronously.
    val decalTextures = remember { mutableStateMapOf<String, Texture>() }

    LaunchedEffect(roblosecurityCookie) {
        textureLoader.roblosecurityCookie = roblosecurityCookie
    }

    // Kick off async texture loads for any decal that isn't loaded yet.
    decals.forEach { decal ->
        val key = "${decal.textureUri}|${decal.isTexture}"
        if (!decalTextures.containsKey(key)) {
            scope.launch {
                textureLoader.load(decal.textureUri, decal.isTexture)?.let { tex ->
                    decalTextures[key] = tex
                }
            }
        }
    }

    // Orbit camera from ViewModel state.
    LaunchedEffect(yaw, pitch, zoom) {
        val yawR = Math.toRadians(yaw.toDouble())
        val pitchR = Math.toRadians(pitch.toDouble().coerceIn(-89.0, 89.0))
        val dist = zoom.coerceAtLeast(0.5f)
        val x = (dist * cos(pitchR) * sin(yawR)).toFloat()
        val y = (dist * sin(pitchR)).toFloat()
        val z = (dist * cos(pitchR) * cos(yawR)).toFloat()
        cameraNode.position = Position(x, y, z)
        cameraNode.lookAt(Position(0f, 0f, 0f))
    }

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        environmentLoader = environmentLoader,
        cameraNode = cameraNode,
        // Strict per-node placement — our parts are authored in world space.
        autoCenterContent = false,
        // Orbit/pan/zoom camera gestures. Default orbitSpeed (0.003) feels far too
        // slow on a phone, so build the manipulator with a faster orbit + zoom.
        cameraManipulator = rememberCameraManipulator(
            creator = {
                io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
                    com.google.android.filament.utils.Manipulator.Builder()
                        .orbitSpeed(0.008f, 0.008f)
                        .zoomSpeed(0.12f)
                        .build(com.google.android.filament.utils.Manipulator.Mode.ORBIT)
                )
            }
        ),
        onTouchEvent = { event, hitResult ->
            if (event.action == MotionEvent.ACTION_UP) {
                val partId = hitResult?.node?.name?.removePrefix("part:")
                viewModel.selectPart(parts.firstOrNull { it.id == partId })
            }
            // Return false so the gesture detector still receives the event and camera
            // orbit/pan/zoom keep working (returning true would consume every touch).
            false
        }
    ) {
        parts.forEach { part ->
            PartNode(
                materialLoader = materialLoader,
                part = part,
                isSelected = part.id == selectedPart?.id,
                activeTool = activeTool,
                onSelect = { viewModel.selectPart(part) },
                onTransformEdited = { updated -> viewModel.updatePartProperty(updated) }
            )
        }

        // Decal / Texture overlays on part faces.
        val partsById = parts.associateBy { it.id }
        decals.sortedBy { it.zIndex }.forEach { decal ->
            val part = partsById[decal.parentPartId] ?: return@forEach
            val tex = decalTextures["${decal.textureUri}|${decal.isTexture}"] ?: return@forEach
            DecalNode(materialLoader, decal, part, tex)
        }

        // Selection highlight: a transparent cyan cube slightly larger than the part.
        selectedPart?.let { part ->
            SelectionNode(materialLoader, part)
        }
    }
}

@Composable
private fun SceneScope.PartNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    isSelected: Boolean,
    activeTool: String,
    onSelect: () -> Unit,
    onTransformEdited: (Part) -> Unit
) {
    val material = remember(part.colorHex, part.transparency) {
        materialLoader.createColorInstance(
            dev.romainguy.kotlin.math.Float4(
                colorR(part.colorHex), colorG(part.colorHex), colorB(part.colorHex),
                1f - part.transparency.coerceIn(0f, 1f)
            )
        )
    }
    val position = Position(part.position.x, part.position.y, part.position.z)
    val rotation = Rotation(part.rotation.x, part.rotation.y, part.rotation.z)
    val scale = Scale(part.size.x, part.size.y, part.size.z)

    // Live-editable transform state. Position/rotation/scale are State-backed so the
    // gesture edits (which mutate node.position etc.) are reflected without fighting
    // the part's immutable props; the ViewModel is only written back on gesture end.
    val editConfig: io.github.sceneview.node.Node.() -> Unit = {
        name = "part:${part.id}"
        // Tap selects this part.
        onSingleTapUp = { onSelect(); true }
        // Editing: enable only the active tool's gesture for the selected part.
        isEditable = isSelected && activeTool != "SELECT"
        isPositionEditable = isSelected && activeTool == "MOVE"
        isRotationEditable = isSelected && activeTool == "ROTATE"
        isScaleEditable = isSelected && activeTool == "SCALE"
        // Write the dragged transform back to the ViewModel when the gesture ends, and
        // also live-sync during editing so the properties panel updates while dragging.
        onEditingChanged = { onTransformEdited(part.withTransformFrom(worldPosition, rotation, scale)) }
        onMoveEnd = { _, _ -> onTransformEdited(part.withTransformFrom(worldPosition, rotation, scale)) }
        onRotateEnd = { _, _ -> onTransformEdited(part.withTransformFrom(worldPosition, rotation, scale)) }
        onScaleEnd = { _, _ -> onTransformEdited(part.withTransformFrom(worldPosition, rotation, scale)) }
    }

    when (part.shape) {
        Part.SHAPE_SPHERE -> SphereNode(
            radius = 0.5f,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = editConfig
        )
        Part.SHAPE_CYLINDER -> CylinderNode(
            radius = 0.5f,
            height = 1f,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = editConfig
        )
        Part.SHAPE_WEDGE -> WedgeNode(
            engine = engine,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = editConfig
        )
        else -> CubeNode(
            size = Size(1f, 1f, 1f),
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = editConfig
        )
    }
}

/**
 * A wedge (triangular prism): a 1x1x1 box with the top-back edge collapsed onto the
 * top-front edge, built with SceneView's Geometry builder. Scaled by the part's size.
 */
@Composable
private fun SceneScope.WedgeNode(
    engine: com.google.android.filament.Engine,
    materialInstance: com.google.android.filament.MaterialInstance,
    position: Position,
    rotation: Rotation,
    scale: Scale,
    apply: io.github.sceneview.node.MeshNode.() -> Unit
) {
    val geometry = remember(engine) { buildWedgeGeometry(engine) }
    MeshNode(
        primitiveType = com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = geometry.vertexBuffer,
        indexBuffer = geometry.indexBuffer,
        boundingBox = geometry.boundingBox,
        materialInstance = materialInstance,
        apply = {
            this.position = position
            this.rotation = rotation
            this.scale = scale
            apply()
        }
    )
}

private fun buildWedgeGeometry(engine: com.google.android.filament.Engine): io.github.sceneview.geometries.Geometry {
    // Unit wedge: square base, vertical front face, slanted back->front-top face.
    // x: left(-)/right(+), y: down(-)/up(+), z: front(-)/back(+)
    val v = listOf(
        Position(-0.5f, -0.5f, -0.5f), // 0 front-bottom-left
        Position(0.5f, -0.5f, -0.5f),  // 1 front-bottom-right
        Position(0.5f, -0.5f, 0.5f),   // 2 back-bottom-right
        Position(-0.5f, -0.5f, 0.5f),  // 3 back-bottom-left
        Position(-0.5f, 0.5f, -0.5f),  // 4 front-top-left
        Position(0.5f, 0.5f, -0.5f)    // 5 front-top-right
    )
    val triangles = listOf(
        0, 2, 1, 0, 3, 2,       // bottom
        0, 1, 5, 0, 5, 4,       // front
        1, 2, 5,                // right
        3, 0, 4,                // left
        3, 4, 5, 3, 5, 2        // slanted top (back-bottom -> front-top)
    )
    return io.github.sceneview.geometries.Geometry.Builder(
        com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES
    )
        .vertices(v.map { io.github.sceneview.geometries.Geometry.Vertex(position = it) })
        .indices(triangles)
        .build(engine)
}

/** Copy this part with a new world transform read back from the edited render node. */
private fun Part.withTransformFrom(
    worldPosition: Position,
    rotation: Rotation,
    scale: Scale
): Part = copy(
    position = com.example.models.Vector3(worldPosition.x, worldPosition.y, worldPosition.z),
    rotation = com.example.models.Vector3(rotation.x, rotation.y, rotation.z),
    size = com.example.models.Vector3(
        (size.x * scale.x).coerceAtLeast(0.05f),
        (size.y * scale.y).coerceAtLeast(0.05f),
        (size.z * scale.z).coerceAtLeast(0.05f)
    )
)

@Composable
private fun SceneScope.DecalNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    decal: DecalRenderItem,
    part: Part,
    texture: Texture
) {
    // Face normal + in-plane size derived from the part's local axes.
    val (normal, quadSize, faceOffset) = when (decal.face.lowercase()) {
        "top" -> Triple(Direction(0f, 1f, 0f), Size(part.size.x, part.size.z), part.size.y / 2f)
        "bottom" -> Triple(Direction(0f, -1f, 0f), Size(part.size.x, part.size.z), part.size.y / 2f)
        "left" -> Triple(Direction(-1f, 0f, 0f), Size(part.size.z, part.size.y), part.size.x / 2f)
        "right" -> Triple(Direction(1f, 0f, 0f), Size(part.size.z, part.size.y), part.size.x / 2f)
        "back" -> Triple(Direction(0f, 0f, -1f), Size(part.size.x, part.size.y), part.size.z / 2f)
        else -> Triple(Direction(0f, 0f, 1f), Size(part.size.x, part.size.y), part.size.z / 2f) // Front
    }
    // Push the quad a hair off the face to avoid z-fighting.
    val epsilon = 0.01f
    val offset = Position(normal.x * (faceOffset + epsilon), normal.y * (faceOffset + epsilon), normal.z * (faceOffset + epsilon))

    val material = remember(texture, decal.transparency, decal.colorHex) {
        materialLoader.createTextureInstance(texture, isOpaque = decal.transparency <= 0f)
            .apply {
                setParameter(
                    "color",
                    colorR(decal.colorHex), colorG(decal.colorHex), colorB(decal.colorHex),
                    1f - decal.transparency.coerceIn(0f, 1f)
                )
            }
    }

    PlaneNode(
        size = quadSize,
        normal = normal,
        uvScale = UvScale(
            if (decal.isTexture) (part.size.x / decal.studsPerTileU) else 1f,
            if (decal.isTexture) (part.size.y / decal.studsPerTileV) else 1f
        ),
        materialInstance = material,
        position = Position(
            part.position.x + offset.x,
            part.position.y + offset.y,
            part.position.z + offset.z
        ),
        rotation = Rotation(part.rotation.x, part.rotation.y, part.rotation.z),
        apply = { name = "decal:${decal.id}" }
    )
}

/** Selection highlight: 12 cyan edge lines around the part's bounding box. */
@Composable
private fun SceneScope.SelectionNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part
) {
    val material = remember {
        materialLoader.createUnlitColorInstance(
            dev.romainguy.kotlin.math.Float4(0.0f, 0.7f, 1.0f, 1.0f)
        )
    }
    // A slightly oversized unit box's 12 edges, centered on the part.
    val h = 0.53f
    val corners = listOf(
        Position(-h, -h, -h), Position(h, -h, -h), Position(h, -h, h), Position(-h, -h, h), // bottom
        Position(-h, h, -h), Position(h, h, -h), Position(h, h, h), Position(-h, h, h)      // top
    )
    val edges = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,   // bottom loop
        4 to 5, 5 to 6, 6 to 7, 7 to 4,   // top loop
        0 to 4, 1 to 5, 2 to 6, 3 to 7    // verticals
    )
    val center = Position(part.position.x, part.position.y, part.position.z)
    val rot = Rotation(part.rotation.x, part.rotation.y, part.rotation.z)
    val scl = Scale(part.size.x, part.size.y, part.size.z)

    edges.forEach { (a, b) ->
        LineNode(
            start = corners[a],
            end = corners[b],
            materialInstance = material,
            position = center,
            rotation = rot,
            scale = scl,
            apply = { isHittable = false; isTouchable = false }
        )
    }
}

private fun colorR(hex: String): Float = ((parseHex(hex) shr 16) and 0xFF) / 255f
private fun colorG(hex: String): Float = ((parseHex(hex) shr 8) and 0xFF) / 255f
private fun colorB(hex: String): Float = (parseHex(hex) and 0xFF) / 255f

private fun parseHex(hex: String): Int {
    val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
    return runCatching { rgb.toInt(16) }.getOrDefault(0xCCCCCC)
}
