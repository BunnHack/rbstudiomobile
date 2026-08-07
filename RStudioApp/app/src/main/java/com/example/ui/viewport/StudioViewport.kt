package com.example.ui.viewport

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.models.Part
import com.example.viewmodels.StudioViewModel
import com.google.android.filament.Texture
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.View
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.environment.Environment
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
import io.github.sceneview.node.TextNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.cos
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
    val context = LocalContext.current
    val decals = remember(nodes, parts) { buildRenderableDecals(nodes, parts) }
    val lights = remember(nodes, parts) { buildRenderableLights(nodes, parts) }
    val lightsByHostPart = remember(lights) { lights.groupBy { it.hostPartId } }
    val ribbons = remember(nodes, parts) { buildRibbonEffects(nodes, parts) }
    val particleEmitters = remember(nodes, parts) { buildParticleEmitters(nodes, parts) }
    val surfaceGuis = remember(nodes, parts) { buildSurfaceGuis(nodes, parts) }
    val highlights = remember(nodes, parts) { buildHighlights(nodes, parts) }
    val lightingNode = nodes.lastOrNull { it.className == com.example.models.StudioNode.CLASS_LIGHTING }
    val lightingBrightness = lightingNode?.nodeProperties?.entries
        ?.firstOrNull { it.key.equals("Brightness", true) }?.value?.toFloatOrNull()?.coerceIn(0f, 10f) ?: 2f
    val skyNode = nodes.lastOrNull { it.className == com.example.models.StudioNode.CLASS_SKY }
    val showSky = skyNode != null
    val skyFaces = remember(skyNode) {
        skyNode?.let { node ->
            listOf("SkyboxRt", "SkyboxLf", "SkyboxUp", "SkyboxDn", "SkyboxBk", "SkyboxFt")
                .map { keyName -> node.nodeProperties.entries.firstOrNull { it.key.equals(keyName, true) }?.value.orEmpty() }
                .takeIf { faces -> faces.all(String::isNotBlank) }
        }
    }

    val engine = rememberEngine()
    val view = rememberView(engine) {
        io.github.sceneview.createView(engine).apply {
            ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = false }
            antiAliasing = View.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
                enabled = true
                sampleCount = 4
            }
            dithering = View.Dithering.NONE
        }
    }
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        near = 0.1f
        far = 20_000f
    }
    val baseEnvironment = rememberEnvironment(environmentLoader, key = showSky) {
        environmentLoader.createKTX1Environment(
            "environments/neutral/neutral_ibl.ktx",
            if (showSky) "environments/neutral/neutral_skybox.ktx" else null
        ).also { it.indirectLight?.setIntensity((lightingBrightness * 5_000f).coerceAtLeast(1_000f)) }
    }
    val collisionSystem = rememberCollisionSystem(view)
    val cameraManipulator = remember {
        DistanceScaledCameraManipulator(
            initialEye = orbitCameraPosition(yaw = yaw, pitch = pitch, distance = zoom)
        )
    }
    var gizmoDrag by remember { mutableStateOf<GizmoDragState?>(null) }

    val scope = rememberCoroutineScope()
    val textureLoader = remember(engine, context) { RobloxTextureLoader(engine, OkHttpClient(), context.assets) }
    val meshLoader = remember(engine) { RobloxMeshLoader(engine, OkHttpClient()) }
    val meshGeometries = remember { mutableStateMapOf<String, io.github.sceneview.geometries.Geometry>() }
    val meshTextures = remember { mutableStateMapOf<String, Texture>() }
    var customSkybox by remember { mutableStateOf<Skybox?>(null) }
    // Loaded decal textures by "uri|repeat"; populated asynchronously.
    val decalTextures = remember { mutableStateMapOf<String, Texture>() }

    LaunchedEffect(roblosecurityCookie) {
        textureLoader.roblosecurityCookie = roblosecurityCookie
        meshLoader.roblosecurityCookie = roblosecurityCookie
    }

    parts.filter { it.shape == Part.SHAPE_MESH && it.meshId.isNotBlank() }.forEach { part ->
        if (part.meshId !in meshGeometries) {
            scope.launch { meshLoader.load(part.meshId)?.let { meshGeometries[part.meshId] = it } }
        }
        if (part.textureId.isNotBlank() && part.id !in meshTextures) {
            scope.launch {
                textureLoader.load(
                    textureUri = part.textureId,
                    repeating = false,
                    tint = RobloxTextureLoader.TintColor(colorR(part.colorHex), colorG(part.colorHex), colorB(part.colorHex)),
                    alpha = 1f - part.transparency
                )?.let { meshTextures[part.id] = it }
            }
        }
    }

    LaunchedEffect(skyFaces, roblosecurityCookie) {
        customSkybox = skyFaces?.let { textureLoader.loadSkybox(it) }
    }

    val environment = remember(baseEnvironment, customSkybox) {
        customSkybox?.let { Environment(baseEnvironment.indirectLight, it, baseEnvironment.sphericalHarmonics) }
            ?: baseEnvironment
    }

    // Kick off async texture loads for any decal that isn't loaded yet.
    decals.forEach { decal ->
        val key = decal.textureCacheKey()
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

    // External toolbar/reset camera values feed the same custom manipulator. User
    // gestures are internal and don't trigger this effect, so a panned target remains
    // stable until an explicit camera command is issued.
    LaunchedEffect(yaw, pitch, zoom) {
        cameraManipulator.setOrbit(yaw, pitch, zoom)
    }

    SceneView(
        modifier = modifier,
        surfaceType = SurfaceType.TextureSurface,
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        environmentLoader = environmentLoader,
        environment = environment,
        mainLightNode = null,
        fillLightNode = null,
        view = view,
        cameraNode = cameraNode,
        collisionSystem = collisionSystem,
        // Strict per-node placement — our parts are authored in world space.
        autoCenterContent = false,
        // Orbit/pan/zoom camera gestures with a distance-scaled speed: the default
        // fixed speeds feel frozen on large scenes. We scale grab (orbit/pan) and
        // pinch-zoom deltas by the current camera-to-target distance so dragging is
        // proportional to how far you're zoomed out.
        cameraManipulator = cameraManipulator,
        onTouchEvent = { event, hitResult ->
            // Prefer gizmo handles anywhere along the ray, even when a large part is
            // the closest hit. This makes both the rod and endpoint draggable.
            val handle = collisionSystem.hitTest(event)
                .firstNotNullOfOrNull { it.node.name?.let(::parseGizmoHandle) }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (handle != null && selectedPart != null) {
                        gizmoDrag = createGizmoDragState(
                            part = selectedPart!!,
                            handle = handle,
                            event = event,
                            cameraNode = cameraNode,
                            handleLength = GIZMO_LENGTH
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
            key(
                part.id, part.shape, part.trussStyle, part.currentPosition, part.currentRotation, part.size,
                part.colorHex, part.material, part.transparency, part.reflectance, part.castShadow
            ) {
                PartNode(
                    materialLoader = materialLoader,
                    part = part,
                    attachedLights = lightsByHostPart[part.id].orEmpty(),
                    meshGeometry = meshGeometries[part.meshId],
                    meshTexture = meshTextures[part.id],
                    onSelect = { viewModel.selectPart(part) }
                )
            }
        }

        // Decal / Texture overlays on part faces.
        val partsById = parts.associateBy { it.id }
        decals.sortedBy { it.zIndex }.forEach { decal ->
            val part = partsById[decal.parentPartId] ?: return@forEach
            val tex = decalTextures[decal.textureCacheKey()] ?: return@forEach
            key(decal, part.currentPosition, part.currentRotation, part.size, tex) {
                DecalNode(materialLoader, decal, part, tex)
            }
        }

        lights.forEach { light ->
            key(light) {
                RobloxLightNode(light)
            }
        }


        ribbons.filter { it.enabled }.forEach { ribbon ->
            val material = remember(ribbon.id, ribbon.colorHex, ribbon.transparency) {
                materialLoader.createUnlitColorInstance(
                    dev.romainguy.kotlin.math.Float4(
                        colorR(ribbon.colorHex), colorG(ribbon.colorHex), colorB(ribbon.colorHex),
                        1f - ribbon.transparency
                    )
                )
            }
            LineNode(
                start = Position(ribbon.start.x, ribbon.start.y, ribbon.start.z),
                end = Position(ribbon.end.x, ribbon.end.y, ribbon.end.z),
                materialInstance = material,
                apply = {
                    name = "ribbon:${ribbon.id}"
                    scale = Scale(1f, ribbon.width, ribbon.width)
                }
            )
        }

        particleEmitters.filter { it.enabled }.forEach { emitter ->
            val material = remember(emitter.id, emitter.colorHex, emitter.transparency) {
                materialLoader.createUnlitColorInstance(
                    dev.romainguy.kotlin.math.Float4(
                        colorR(emitter.colorHex), colorG(emitter.colorHex), colorB(emitter.colorHex),
                        1f - emitter.transparency
                    )
                )
            }
            repeat(emitter.count) { index ->
                val angle = index * 2.3999632f
                val radius = 0.25f + (index % 4) * 0.15f
                SphereNode(
                    radius = emitter.size * 0.08f,
                    materialInstance = material,
                    position = Position(
                        emitter.origin.x + cos(angle) * radius,
                        emitter.origin.y + 0.25f + index * 0.08f,
                        emitter.origin.z + sin(angle) * radius
                    ),
                    apply = { name = "particle:${emitter.id}:$index" }
                )
            }
        }

        surfaceGuis.filter { it.enabled }.forEach { gui ->
            TextNode(
                text = gui.text,
                fontSize = 64f,
                textColor = android.graphics.Color.rgb(
                    (colorR(gui.textColorHex) * 255).toInt(),
                    (colorG(gui.textColorHex) * 255).toInt(),
                    (colorB(gui.textColorHex) * 255).toInt()
                ),
                backgroundColor = android.graphics.Color.TRANSPARENT,
                widthMeters = 2f,
                heightMeters = gui.height,
                position = Position(gui.position.x, gui.position.y, gui.position.z),
                scale = Scale((gui.width / 2f).coerceAtLeast(0.05f), 1f, 1f),
                apply = {
                    name = "surface-gui:${gui.id}"
                    rotation = Rotation(gui.rotation.x, gui.rotation.y, gui.rotation.z)
                }
            )
        }

        highlights.filter { it.enabled }.forEach { highlight ->
            partsById[highlight.targetPartId]?.let { part -> HighlightNode(materialLoader, part, highlight) }
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
private fun SceneScope.HighlightNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    highlight: HighlightRenderItem
) {
    if (highlight.fillTransparency < 1f) {
        val fill = remember(highlight) {
            materialLoader.createUnlitColorInstance(
                dev.romainguy.kotlin.math.Float4(
                    colorR(highlight.fillColorHex), colorG(highlight.fillColorHex), colorB(highlight.fillColorHex),
                    1f - highlight.fillTransparency
                )
            ).apply { if (highlight.alwaysOnTop) setDepthCulling(false) }
        }
        CubeNode(
            size = Size(1.015f, 1.015f, 1.015f), materialInstance = fill,
            position = Position(part.currentPosition.x, part.currentPosition.y, part.currentPosition.z),
            rotation = Rotation(part.currentRotation.x, part.currentRotation.y, part.currentRotation.z),
            scale = Scale(part.size.x, part.size.y, part.size.z),
            apply = { isHittable = false; isTouchable = false; setPriority(6) }
        )
    }
    if (highlight.outlineTransparency < 1f) {
        SelectionNode(materialLoader, part, highlight.outlineColorHex, 1f - highlight.outlineTransparency, highlight.alwaysOnTop)
    }
}

@Composable
private fun SceneScope.RobloxLightNode(light: LocalLightRenderItem) {
    val type = when (light.type) {
        LocalLightType.POINT -> LightManager.Type.POINT
        LocalLightType.SPOT, LocalLightType.SURFACE -> LightManager.Type.SPOT
    }
    val outerCone = Math.toRadians((light.angleDegrees / 2f).toDouble()).toFloat()
    LightNode(
        type = type,
        apply = {
            color(
                colorR(light.colorHex),
                colorG(light.colorHex),
                colorB(light.colorHex)
            )
            if (type == LightManager.Type.POINT) {
                intensity(if (light.enabled) light.brightness * LOCAL_POINT_LIGHT_LUMENS else 0f, 1f)
            } else {
                intensityCandela(if (light.enabled) light.brightness * LOCAL_SPOT_LIGHT_CANDELAS else 0f)
            }
            falloff(light.range)
            castShadows(light.shadows)
            if (type == LightManager.Type.SPOT) {
                spotLightCone((outerCone * 0.8f).coerceAtLeast(0.01f), outerCone)
            }
        },
        nodeApply = {
            name = "light:${light.id}"
            position = Position(light.position.x, light.position.y, light.position.z)
            lightDirection = Direction(light.direction.x, light.direction.y, light.direction.z)
        }
    )
}

private const val LOCAL_POINT_LIGHT_LUMENS = 1_500f
private const val LOCAL_SPOT_LIGHT_CANDELAS = 12_000f

@Composable
private fun SceneScope.PartNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    attachedLights: List<LocalLightRenderItem>,
    meshGeometry: io.github.sceneview.geometries.Geometry?,
    meshTexture: Texture?,
    onSelect: () -> Unit
) {
    val appearance = remember(part.material, part.reflectance) { materialAppearance(part) }
    val previewColor = remember(part.colorHex, attachedLights) { previewPartColor(part.colorHex, attachedLights) }
    val material = remember(previewColor, part.transparency, appearance, meshTexture) {
        if (meshTexture != null) return@remember materialLoader.createTextureInstance(meshTexture, part.transparency <= 0f)
        val color = dev.romainguy.kotlin.math.Float4(
            previewColor.x, previewColor.y, previewColor.z,
            1f - part.transparency.coerceIn(0f, 1f)
        )
        if (appearance.unlit) {
            materialLoader.createUnlitColorInstance(color)
        } else {
            materialLoader.createColorInstance(color, appearance.metallic, appearance.roughness, appearance.reflectance)
        }
    }
    val position = Position(part.currentPosition.x, part.currentPosition.y, part.currentPosition.z)
    val rotation = Rotation(part.currentRotation.x, part.currentRotation.y, part.currentRotation.z)
    val scale = Scale(part.size.x, part.size.y, part.size.z)

    val editConfig: io.github.sceneview.node.Node.() -> Unit = {
        name = "part:${part.id}"
        onSingleTapUp = { onSelect(); true }
        // Transform editing is owned by the visible axis gizmo below. Keeping direct
        // node editing disabled prevents object gestures from fighting camera gestures.
        isEditable = false
        if (this is io.github.sceneview.node.RenderableNode) {
            isShadowCaster = part.castShadow
            isShadowReceiver = true
            setScreenSpaceContactShadows(false)
        }
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
        Part.SHAPE_CORNER_WEDGE -> CornerWedgeNode(
            engine = engine,
            materialInstance = material,
            position = position,
            rotation = rotation,
            scale = scale,
            apply = editConfig
        )
        Part.SHAPE_TRUSS -> TrussNode(
            engine = engine,
            materialInstance = material,
            part = part,
            position = position,
            rotation = rotation,
            apply = editConfig
        )
        Part.SHAPE_MESH -> if (meshGeometry != null) {
            MeshNode(
                primitiveType = com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer = meshGeometry.vertexBuffer,
                indexBuffer = meshGeometry.indexBuffer,
                boundingBox = meshGeometry.boundingBox,
                materialInstance = material,
                apply = {
                    this.position = position
                    this.rotation = rotation
                    this.scale = Scale(
                        part.size.x / part.initialSize.x.coerceAtLeast(0.0001f),
                        part.size.y / part.initialSize.y.coerceAtLeast(0.0001f),
                        part.size.z / part.initialSize.z.coerceAtLeast(0.0001f)
                    )
                    setCulling(!part.doubleSided)
                    editConfig()
                }
            )
        } else {
            CubeNode(size = Size(1f, 1f, 1f), materialInstance = material, position = position, rotation = rotation, scale = scale, apply = editConfig)
        }
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

private fun previewPartColor(
    baseHex: String,
    attachedLights: List<LocalLightRenderItem>
): dev.romainguy.kotlin.math.Float3 {
    var r = colorR(baseHex)
    var g = colorG(baseHex)
    var b = colorB(baseHex)
    attachedLights.filter { it.enabled && it.brightness > 0f && it.range > 0f }.forEach { light ->
        val strength = (0.08f + light.brightness * 0.035f + light.range * 0.003f).coerceIn(0.08f, 0.42f)
        r = (r * (1f - strength) + colorR(light.colorHex) * strength).coerceIn(0f, 1f)
        g = (g * (1f - strength) + colorG(light.colorHex) * strength).coerceIn(0f, 1f)
        b = (b * (1f - strength) + colorB(light.colorHex) * strength).coerceIn(0f, 1f)
    }
    return dev.romainguy.kotlin.math.Float3(r, g, b)
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
    val p0 = Position(-0.5f, -0.5f, -0.5f)
    val p1 = Position(0.5f, -0.5f, -0.5f)
    val p2 = Position(0.5f, -0.5f, 0.5f)
    val p3 = Position(-0.5f, -0.5f, 0.5f)
    val p4 = Position(-0.5f, 0.5f, -0.5f)
    val p5 = Position(0.5f, 0.5f, -0.5f)
    return GeometryMeshBuilder().apply {
        triangle(p0, p1, p2); triangle(p0, p2, p3)
        triangle(p0, p4, p5); triangle(p0, p5, p1)
        triangle(p1, p5, p2)
        triangle(p0, p3, p4)
        triangle(p3, p2, p5); triangle(p3, p5, p4)
    }.build(engine)
}

@Composable
private fun SceneScope.CornerWedgeNode(
    engine: com.google.android.filament.Engine,
    materialInstance: com.google.android.filament.MaterialInstance,
    position: Position,
    rotation: Rotation,
    scale: Scale,
    apply: io.github.sceneview.node.MeshNode.() -> Unit
) {
    val geometry = remember(engine) { buildCornerWedgeGeometry(engine) }
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

private fun buildCornerWedgeGeometry(engine: com.google.android.filament.Engine): io.github.sceneview.geometries.Geometry {
    val v0 = Position(0.5f, -0.5f, 0.5f)
    val v1 = Position(0.5f, -0.5f, -0.5f)
    val v2 = Position(-0.5f, -0.5f, -0.5f)
    val v3 = Position(-0.5f, -0.5f, 0.5f)
    val v4 = Position(0.5f, 0.5f, -0.5f)
    return GeometryMeshBuilder().apply {
        triangle(v0, v1, v4)
        triangle(v0, v4, v3)
        triangle(v2, v3, v4)
        triangle(v1, v0, v3); triangle(v1, v3, v2)
        triangle(v1, v2, v4)
    }.build(engine)
}

@Composable
private fun SceneScope.TrussNode(
    engine: com.google.android.filament.Engine,
    materialInstance: com.google.android.filament.MaterialInstance,
    part: Part,
    position: Position,
    rotation: Rotation,
    apply: io.github.sceneview.node.MeshNode.() -> Unit
) {
    val geometry = remember(engine, part.size, part.trussStyle) {
        buildTrussGeometry(engine, part.size, part.trussStyle)
    }
    MeshNode(
        primitiveType = com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = geometry.vertexBuffer,
        indexBuffer = geometry.indexBuffer,
        boundingBox = geometry.boundingBox,
        materialInstance = materialInstance,
        apply = {
            this.position = position
            this.rotation = rotation
            apply()
        }
    )
}

private fun buildTrussGeometry(
    engine: com.google.android.filament.Engine,
    size: com.example.models.Vector3,
    style: Int
): io.github.sceneview.geometries.Geometry {
    val builder = GeometryMeshBuilder()
    val width = size.x.coerceAtLeast(0.2f)
    val height = size.y.coerceAtLeast(0.2f)
    val depth = size.z.coerceAtLeast(0.2f)
    val thickness = minOf(0.28f, minOf(width, depth) * 0.16f).coerceAtLeast(0.06f)
    val railX = width / 2f - thickness / 2f
    val railZ = depth / 2f - thickness / 2f
    val bottom = -height / 2f
    val top = height / 2f

    listOf(-railX to -railZ, railX to -railZ, railX to railZ, -railX to railZ).forEach { (x, z) ->
        builder.boxBetween(Position(x, bottom, z), Position(x, top, z), thickness)
    }

    val cellCount = (height / 2f).toInt().coerceAtLeast(1)
    val cellHeight = height / cellCount
    for (i in 0..cellCount) {
        val y = bottom + cellHeight * i
        builder.boxBetween(Position(-railX, y, -railZ), Position(railX, y, -railZ), thickness)
        builder.boxBetween(Position(-railX, y, railZ), Position(railX, y, railZ), thickness)
        builder.boxBetween(Position(-railX, y, -railZ), Position(-railX, y, railZ), thickness)
        builder.boxBetween(Position(railX, y, -railZ), Position(railX, y, railZ), thickness)
    }

    if (style != Part.TRUSS_STYLE_NO_SUPPORTS) {
        for (i in 0 until cellCount) {
            val y0 = bottom + cellHeight * i
            val y1 = y0 + cellHeight
            val reverse = when (style) {
                Part.TRUSS_STYLE_BRIDGE_SUPPORTS -> i >= cellCount / 2
                else -> i % 2 == 1
            }
            val leftX = if (reverse) railX else -railX
            val rightX = -leftX
            val frontZ = if (reverse) railZ else -railZ
            val backZ = -frontZ
            builder.boxBetween(Position(leftX, y0, -railZ), Position(rightX, y1, -railZ), thickness)
            builder.boxBetween(Position(rightX, y0, railZ), Position(leftX, y1, railZ), thickness)
            builder.boxBetween(Position(-railX, y0, frontZ), Position(-railX, y1, backZ), thickness)
            builder.boxBetween(Position(railX, y0, backZ), Position(railX, y1, frontZ), thickness)
        }
    }
    return builder.build(engine)
}

private class GeometryMeshBuilder {
    private val vertices = mutableListOf<io.github.sceneview.geometries.Geometry.Vertex>()
    private val indices = mutableListOf<Int>()

    fun triangle(a: Position, b: Position, c: Position) {
        val normal = cross(subtract(b, a), subtract(c, a)).normalizedDirection()
        val start = vertices.size
        vertices += io.github.sceneview.geometries.Geometry.Vertex(position = a, normal = normal)
        vertices += io.github.sceneview.geometries.Geometry.Vertex(position = b, normal = normal)
        vertices += io.github.sceneview.geometries.Geometry.Vertex(position = c, normal = normal)
        indices += start; indices += start + 1; indices += start + 2
    }

    fun boxBetween(start: Position, end: Position, thickness: Float) {
        val axis = subtract(end, start).normalizedPosition()
        val reference = if (kotlin.math.abs(axis.y) < 0.9f) Position(0f, 1f, 0f) else Position(1f, 0f, 0f)
        val side = scalePosition(cross(axis, reference).normalizedPosition(), thickness / 2f)
        val up = scalePosition(cross(side, axis).normalizedPosition(), thickness / 2f)
        val s0 = addPosition(addPosition(start, side), up)
        val s1 = addPosition(subtract(start, side), up)
        val s2 = subtract(subtract(start, side), up)
        val s3 = subtract(addPosition(start, side), up)
        val e0 = addPosition(addPosition(end, side), up)
        val e1 = addPosition(subtract(end, side), up)
        val e2 = subtract(subtract(end, side), up)
        val e3 = subtract(addPosition(end, side), up)
        quad(s0, s3, s2, s1)
        quad(e0, e1, e2, e3)
        quad(s0, e0, e3, s3)
        quad(s1, s2, e2, e1)
        quad(s0, s1, e1, e0)
        quad(s3, e3, e2, s2)
    }

    private fun quad(a: Position, b: Position, c: Position, d: Position) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun build(engine: com.google.android.filament.Engine): io.github.sceneview.geometries.Geometry =
        io.github.sceneview.geometries.Geometry.Builder(
            com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES
        ).vertices(vertices).indices(indices).build(engine)
}

private fun addPosition(a: Position, b: Position) = Position(a.x + b.x, a.y + b.y, a.z + b.z)
private fun scalePosition(value: Position, scale: Float) = Position(value.x * scale, value.y * scale, value.z * scale)
private fun subtract(a: Position, b: Position) = Position(a.x - b.x, a.y - b.y, a.z - b.z)
private fun cross(a: Position, b: Position) = Position(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x
)
private fun Position.normalizedPosition(): Position {
    val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.000001f)
    return Position(x / length, y / length, z / length)
}
private fun Position.normalizedDirection(): Direction {
    val normalized = normalizedPosition()
    return Direction(normalized.x, normalized.y, normalized.z)
}

private enum class GizmoAxis(val x: Float, val y: Float, val z: Float) {
    X(1f, 0f, 0f),
    Y(0f, 1f, 0f),
    Z(0f, 0f, 1f)
}

private data class GizmoHandle(val tool: String, val axis: GizmoAxis, val sign: Float = 1f)

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
            "MOVE" -> {
                val position = com.example.models.Vector3(
                    part.position.x + axis.x * worldDelta * handle.sign,
                    part.position.y + axis.y * worldDelta * handle.sign,
                    part.position.z + axis.z * worldDelta * handle.sign
                )
                part.copy(position = position, currentPosition = position)
            }
            "SCALE" -> part.copy(
                size = com.example.models.Vector3(
                    (part.size.x + axis.x * worldDelta).coerceAtLeast(0.05f),
                    (part.size.y + axis.y * worldDelta).coerceAtLeast(0.05f),
                    (part.size.z + axis.z * worldDelta).coerceAtLeast(0.05f)
                )
            )
            "ROTATE" -> {
                val degrees = signedPixels * 0.65f
                val rotation = com.example.models.Vector3(
                    part.rotation.x + axis.x * degrees,
                    part.rotation.y + axis.y * degrees,
                    part.rotation.z + axis.z * degrees
                )
                part.copy(
                    rotation = rotation,
                    currentRotation = rotation
                )
            }
            else -> part
        }
    }
}

private fun parseGizmoHandle(name: String): GizmoHandle? {
    if (!name.startsWith("gizmo:")) return null
    val fields = name.split(':')
    if (fields.size !in 3..4) return null
    val tool = fields[1]
    val axis = runCatching { GizmoAxis.valueOf(fields[2]) }.getOrNull() ?: return null
    val sign = if (fields.getOrNull(3) == "NEG") -1f else 1f
    return GizmoHandle(tool, axis, sign)
}

private fun createGizmoDragState(
    part: Part,
    handle: GizmoHandle,
    event: MotionEvent,
    cameraNode: io.github.sceneview.node.CameraNode,
    handleLength: Float
): GizmoDragState {
    val origin = io.github.sceneview.collision.Vector3(
        part.position.x,
        part.position.y,
        part.position.z
    )
    val endpoint = io.github.sceneview.collision.Vector3(
        part.position.x + handle.axis.x * handleLength * handle.sign,
        part.position.y + handle.axis.y * handleLength * handle.sign,
        part.position.z + handle.axis.z * handleLength * handle.sign
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

private const val GIZMO_LENGTH = 4f
private const val GIZMO_ROD_RADIUS = 0.07f
private const val GIZMO_ENDPOINT_SIZE = 0.65f

/** Visible Roblox-style world-axis transform handles for MOVE / SCALE / ROTATE. */
@Composable
private fun SceneScope.TransformGizmo(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    tool: String
) {
    val red = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(1f, 0.12f, 0.12f, 1f)).apply { setDepthCulling(false) } }
    val green = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(0.2f, 1f, 0.25f, 1f)).apply { setDepthCulling(false) } }
    val blue = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(0.15f, 0.45f, 1f, 1f)).apply { setDepthCulling(false) } }
    val white = remember { materialLoader.createUnlitColorInstance(dev.romainguy.kotlin.math.Float4(1f, 1f, 1f, 1f)).apply { setDepthCulling(false) } }
    val center = Position(part.currentPosition.x, part.currentPosition.y, part.currentPosition.z)
    val length = GIZMO_LENGTH
    val rodRadius = GIZMO_ROD_RADIUS
    val endpointSize = GIZMO_ENDPOINT_SIZE

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
                apply = {
                    name = "gizmo:ROTATE:${axis.name}"
                    isTouchable = true
                    isHittable = true
                    setPriority(7)
                }
            )
        }
        return
    }

    SphereNode(
        radius = 0.14f,
        materialInstance = white,
        position = center,
        apply = {
            name = "gizmo-center"
            isTouchable = false
            isHittable = false
            setPriority(7)
        }
    )

    axes.forEach { (axis, material, rotation) ->
        val signs = if (tool == "MOVE") listOf(1f, -1f) else listOf(1f)
        signs.forEach { sign ->
            val half = length * 0.5f * sign
            val suffix = if (sign < 0f) ":NEG" else ""
            val rodCenter = Position(
                center.x + axis.x * half,
                center.y + axis.y * half,
                center.z + axis.z * half
            )
            val endpoint = Position(
                center.x + axis.x * length * sign,
                center.y + axis.y * length * sign,
                center.z + axis.z * length * sign
            )
            val signedRotation = if (sign < 0f) reverseAxisRotation(axis, rotation) else rotation
            CylinderNode(
                radius = rodRadius,
                height = length,
                materialInstance = material,
                position = rodCenter,
                rotation = signedRotation,
                apply = {
                    name = "gizmo:$tool:${axis.name}$suffix"
                    isTouchable = true
                    isHittable = true
                    setPriority(7)
                    materialInstance.setDepthCulling(false)
                }
            )
            if (tool == "MOVE") {
                ConeNode(
                    radius = endpointSize * 0.45f,
                    height = endpointSize,
                    materialInstance = material,
                    position = endpoint,
                    rotation = signedRotation,
                    apply = {
                        name = "gizmo:MOVE:${axis.name}$suffix"
                        isTouchable = true
                        isHittable = true
                        setPriority(7)
                        materialInstance.setDepthCulling(false)
                    }
                )
            } else {
                CubeNode(
                    size = Size(endpointSize, endpointSize, endpointSize),
                    materialInstance = material,
                    position = endpoint,
                    apply = {
                        name = "gizmo:SCALE:${axis.name}"
                        isTouchable = true
                        isHittable = true
                        setPriority(7)
                    }
                )
            }
        }
    }
}

private fun reverseAxisRotation(axis: GizmoAxis, rotation: Rotation): Rotation = when (axis) {
    GizmoAxis.X -> Rotation(rotation.x, rotation.y, 90f)
    GizmoAxis.Y -> Rotation(180f, rotation.y, rotation.z)
    GizmoAxis.Z -> Rotation(-90f, rotation.y, rotation.z)
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
        else -> Triple(Direction(0f, 0f, 1f), Size(part.size.x, part.size.y), part.size.z / 2f)
    }
    val epsilon = maxOf(0.01f, maxOf(part.size.x, part.size.y, part.size.z) * 0.001f) +
        decal.zIndex.coerceIn(0, 100) * 0.001f
    val worldOffset = rotateDirection(
        com.example.models.Vector3(
            normal.x * (faceOffset + epsilon),
            normal.y * (faceOffset + epsilon),
            normal.z * (faceOffset + epsilon)
        ),
        part.currentRotation
    )
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
            part.currentPosition.x + worldOffset.x,
            part.currentPosition.y + worldOffset.y,
            part.currentPosition.z + worldOffset.z
        ),
        rotation = Rotation(part.currentRotation.x, part.currentRotation.y, part.currentRotation.z),
        apply = {
            name = "decal:${decal.id}"
            setPriority((4 + decal.zIndex.coerceIn(0, 3)).coerceAtMost(7))
        }
    )
}

@Composable
private fun SceneScope.SelectionNode(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
    part: Part,
    colorHex: String = "#00B3FF",
    alpha: Float = 1f,
    alwaysOnTop: Boolean = false
) {
    val material = remember {
        materialLoader.createUnlitColorInstance(
            dev.romainguy.kotlin.math.Float4(colorR(colorHex), colorG(colorHex), colorB(colorHex), alpha)
        ).apply { if (alwaysOnTop) setDepthCulling(false) }
    }
    val h = 0.53f
    val corners = listOf(
        Position(-h, -h, -h), Position(h, -h, -h), Position(h, -h, h), Position(-h, -h, h),
        Position(-h, h, -h), Position(h, h, -h), Position(h, h, h), Position(-h, h, h)
    )
    val edges = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,
        4 to 5, 5 to 6, 6 to 7, 7 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7
    )
    val center = Position(part.currentPosition.x, part.currentPosition.y, part.currentPosition.z)
    val rot = Rotation(part.currentRotation.x, part.currentRotation.y, part.currentRotation.z)
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

private fun srgbToLinear(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

private data class MaterialAppearance(
    val metallic: Float,
    val roughness: Float,
    val reflectance: Float,
    val unlit: Boolean = false
)

private fun materialAppearance(part: Part): MaterialAppearance {
    val reflectance = part.reflectance.coerceIn(0f, 1f)
    return when (part.material) {
        Part.MATERIAL_METAL -> MaterialAppearance(1f, (0.42f - reflectance * 0.3f).coerceAtLeast(0.08f), 0.5f)
        Part.MATERIAL_GLASS -> MaterialAppearance(0f, 0.08f, maxOf(0.65f, reflectance))
        Part.MATERIAL_WOOD -> MaterialAppearance(0f, 0.78f, maxOf(0.28f, reflectance))
        Part.MATERIAL_SLATE -> MaterialAppearance(0f, 0.9f, maxOf(0.22f, reflectance))
        Part.MATERIAL_BRICK -> MaterialAppearance(0f, 0.86f, maxOf(0.25f, reflectance))
        Part.MATERIAL_FABRIC -> MaterialAppearance(0f, 1f, maxOf(0.18f, reflectance))
        Part.MATERIAL_MARBLE -> MaterialAppearance(0f, 0.22f, maxOf(0.55f, reflectance))
        Part.MATERIAL_NEON -> MaterialAppearance(0f, 0f, 0f, unlit = true)
        else -> MaterialAppearance(0f, (0.58f - reflectance * 0.35f).coerceAtLeast(0.12f), maxOf(0.35f, reflectance))
    }
}

private fun DecalRenderItem.textureCacheKey(): String =
    listOf(textureUri, isTexture, colorHex.uppercase(), transparency.coerceIn(0f, 1f)).joinToString("|")

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
