package com.example.ui.viewport

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.sceneview.node.ConeNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.LineNode
import io.github.sceneview.node.TorusNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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

    val initialCameraPosition = remember {
        orbitCameraPosition(yaw = yaw, pitch = pitch, distance = zoom)
    }
    val cameraManipulator = remember {
        DistanceScaledCameraManipulator(initialEye = initialCameraPosition)
    }
    var gizmoDrag by remember { mutableStateOf<GizmoDragState?>(null) }

    val scope = rememberCoroutineScope()
    val textureLoader = remember { RobloxTextureLoader(engine, OkHttpClient()) }
    // Loaded decal textures by "uri|repeat"; populated asynchronously.
    val decalTextures = remember { mutableStateMapOf<String, Texture>() }

    LaunchedEffect(roblosecurityCookie) {
        textureLoader.roblosecurityCookie = roblosecurityCookie
    }

    // Kick off async texture loads for any decal that isn't loaded yet.
    decals.forEach { decal ->
        val key = decal.textureUri
        if (!decalTextures.containsKey(key)) {
            scope.launch {
                textureLoader.load(
                    textureUri = decal.textureUri,
                    repeating = decal.isTexture,
                    tint = RobloxTextureLoader.TintColor(
                        colorR(decal.colorHex), colorG(decal.colorHex), colorB(decal.colorHex)
                    ),
                    alpha = 1f - decal.transparency.coerceIn(0f, 1f)
                )?.let { tex -> decalTextures[key] = tex }
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
        // Orbit/pan/zoom camera gestures with a distance-scaled speed: the default
        // fixed speeds feel frozen on large scenes. We scale grab (orbit/pan) and
        // pinch-zoom deltas by the current camera-to-target distance so dragging is
        // proportional to how far you're zoomed out.
        cameraManipulator = cameraManipulator,
        onTouchEvent = { event, hitResult ->
            val handle = hitResult?.node?.name?.let(::parseGizmoHandle)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (handle != null && selectedPart != null) {
                        gizmoDrag = createGizmoDragState(
                            part = selectedPart!!,
                            handle = handle,
                            event = event,
                            cameraNode = cameraNode
                        )
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    gizmoDrag?.let { drag ->
                        viewModel.updatePartProperty(drag.updatedPart(event))
                        true
                    } ?: false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val drag = gizmoDrag
                    if (drag != null) {
                        viewModel.updatePartProperty(drag.updatedPart(event))
                        gizmoDrag = null
                        true
                    } else {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val partId = hitResult?.node?.name?.removePrefix("part:")
                            viewModel.selectPart(parts.firstOrNull { it.id == partId })
                        }
                        false
                    }
                }
                else -> gizmoDrag != null
            }
        }
    ) {
        parts.forEach { part ->
            PartNode(
                materialLoader = materialLoader,
                part = part,
                onSelect = { viewModel.selectPart(part) }
            )
        }

        // Decal / Texture overlays on part faces.
        val partsById = parts.associateBy { it.id }
        decals.sortedBy { it.zIndex }.forEach { decal ->
            val part = partsById[decal.parentPartId] ?: return@forEach
            val tex = decalTextures[decal.textureUri] ?: return@forEach
            DecalNode(materialLoader, decal, part, tex)
        }

        // Selection highlight: a transparent cyan cube slightly larger than the part.
        selectedPart?.let { part ->
            SelectionNode(materialLoader, part)
            if (activeTool != "SELECT") {
                TransformGizmo(materialLoader, part, activeTool)
            }
        }
    }
}

@Composable
private fun SceneScope.PartNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    onSelect: () -> Unit
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

    val editConfig: io.github.sceneview.node.Node.() -> Unit = {
        name = "part:${part.id}"
        onSingleTapUp = { onSelect(); true }
        // Transform editing is owned by the visible axis gizmo below. Keeping direct
        // node editing disabled prevents object gestures from fighting camera gestures.
        isEditable = false
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

private enum class GizmoAxis(val x: Float, val y: Float, val z: Float) {
    X(1f, 0f, 0f),
    Y(0f, 1f, 0f),
    Z(0f, 0f, 1f)
}

private data class GizmoHandle(val tool: String, val axis: GizmoAxis)

private data class GizmoDragState(
    val part: Part,
    val handle: GizmoHandle,
    val startX: Float,
    val startY: Float,
    val screenAxisX: Float,
    val screenAxisY: Float,
    val pixelsPerWorldUnit: Float
) {
    fun updatedPart(event: MotionEvent): Part {
        val dx = event.x - startX
        val dy = event.y - startY
        val signedPixels = dx * screenAxisX + dy * screenAxisY
        val worldDelta = signedPixels / pixelsPerWorldUnit.coerceAtLeast(0.25f)
        val axis = handle.axis
        return when (handle.tool) {
            "MOVE" -> part.copy(
                position = com.example.models.Vector3(
                    part.position.x + axis.x * worldDelta,
                    part.position.y + axis.y * worldDelta,
                    part.position.z + axis.z * worldDelta
                )
            )
            "SCALE" -> part.copy(
                size = com.example.models.Vector3(
                    (part.size.x + axis.x * worldDelta).coerceAtLeast(0.05f),
                    (part.size.y + axis.y * worldDelta).coerceAtLeast(0.05f),
                    (part.size.z + axis.z * worldDelta).coerceAtLeast(0.05f)
                )
            )
            "ROTATE" -> {
                val degrees = signedPixels * 0.65f
                part.copy(
                    rotation = com.example.models.Vector3(
                        part.rotation.x + axis.x * degrees,
                        part.rotation.y + axis.y * degrees,
                        part.rotation.z + axis.z * degrees
                    )
                )
            }
            else -> part
        }
    }
}

private fun parseGizmoHandle(name: String): GizmoHandle? {
    if (!name.startsWith("gizmo:")) return null
    val fields = name.split(':')
    if (fields.size != 3) return null
    val tool = fields[1]
    val axis = runCatching { GizmoAxis.valueOf(fields[2]) }.getOrNull() ?: return null
    return GizmoHandle(tool, axis)
}

private fun createGizmoDragState(
    part: Part,
    handle: GizmoHandle,
    event: MotionEvent,
    cameraNode: io.github.sceneview.node.CameraNode
): GizmoDragState {
    val handleLength = gizmoLength(part)
    val origin = io.github.sceneview.collision.Vector3(
        part.position.x,
        part.position.y,
        part.position.z
    )
    val endpoint = io.github.sceneview.collision.Vector3(
        part.position.x + handle.axis.x * handleLength,
        part.position.y + handle.axis.y * handleLength,
        part.position.z + handle.axis.z * handleLength
    )
    @Suppress("DEPRECATION")
    val originScreen = cameraNode.worldToScreenPoint(origin)
    @Suppress("DEPRECATION")
    val endpointScreen = cameraNode.worldToScreenPoint(endpoint)
    val sx = endpointScreen.x - originScreen.x
    val sy = endpointScreen.y - originScreen.y
    val screenLength = sqrt(sx * sx + sy * sy).coerceAtLeast(1f)
    return GizmoDragState(
        part = part,
        handle = handle,
        startX = event.x,
        startY = event.y,
        screenAxisX = sx / screenLength,
        screenAxisY = sy / screenLength,
        pixelsPerWorldUnit = screenLength / handleLength.coerceAtLeast(0.1f)
    )
}

private fun gizmoLength(part: Part): Float =
    max(2.5f, max(part.size.x, max(part.size.y, part.size.z)) * 0.8f)

/** Visible Roblox-style world-axis transform handles for MOVE / SCALE / ROTATE. */
@Composable
private fun SceneScope.TransformGizmo(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    tool: String
) {
    val red = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(1f, 0.12f, 0.12f, 1f)) }
    val green = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(0.2f, 1f, 0.25f, 1f)) }
    val blue = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(0.15f, 0.45f, 1f, 1f)) }
    val center = Position(part.position.x, part.position.y, part.position.z)
    val length = gizmoLength(part)
    val rodRadius = (length * 0.035f).coerceIn(0.08f, 0.3f)
    val endpointSize = (length * 0.14f).coerceIn(0.3f, 1.2f)

    val axes = listOf(
        Triple(GizmoAxis.X, red, Rotation(0f, 0f, -90f)),
        Triple(GizmoAxis.Y, green, Rotation(0f, 0f, 0f)),
        Triple(GizmoAxis.Z, blue, Rotation(90f, 0f, 0f))
    )

    if (tool == "ROTATE") {
        axes.forEach { (axis, material, rotation) ->
            TorusNode(
                majorRadius = length * 0.68f,
                minorRadius = rodRadius,
                materialInstance = material,
                position = center,
                rotation = rotation,
                apply = { name = "gizmo:ROTATE:${axis.name}" }
            )
        }
        return
    }

    axes.forEach { (axis, material, rotation) ->
        val half = length * 0.5f
        val rodCenter = Position(
            center.x + axis.x * half,
            center.y + axis.y * half,
            center.z + axis.z * half
        )
        val endpoint = Position(
            center.x + axis.x * length,
            center.y + axis.y * length,
            center.z + axis.z * length
        )
        CylinderNode(
            radius = rodRadius,
            height = length,
            materialInstance = material,
            position = rodCenter,
            rotation = rotation,
            apply = { name = "gizmo:$tool:${axis.name}" }
        )
        if (tool == "MOVE") {
            ConeNode(
                radius = endpointSize * 0.45f,
                height = endpointSize,
                materialInstance = material,
                position = endpoint,
                rotation = rotation,
                apply = { name = "gizmo:MOVE:${axis.name}" }
            )
        } else {
            CubeNode(
                size = Size(endpointSize, endpointSize, endpointSize),
                materialInstance = material,
                position = endpoint,
                apply = { name = "gizmo:SCALE:${axis.name}" }
            )
        }
    }
}

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

    // The textured material (opaque_textured.mat / transparent_textured.mat) only has
    // a "texture" sampler + metallic/roughness/reflectance — no "color" or
    // "baseColorFactor" uniform. Tint must be baked into the texture itself (done at
    // load time in RobloxTextureLoader); do NOT call setParameter("color"/...) here,
    // it crashes with "uniform named ... not found".
    val material = remember(texture) {
        materialLoader.createTextureInstance(texture, isOpaque = decal.transparency <= 0f)
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

private fun orbitCameraPosition(yaw: Float, pitch: Float, distance: Float): Position {
    val yawR = Math.toRadians(yaw.toDouble())
    val pitchR = Math.toRadians(pitch.toDouble().coerceIn(-89.0, 89.0))
    val dist = distance.coerceAtLeast(0.5f)
    return Position(
        (dist * cos(pitchR) * sin(yawR)).toFloat(),
        (dist * sin(pitchR)).toFloat(),
        (dist * cos(pitchR) * cos(yawR)).toFloat()
    )
}
