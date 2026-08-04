package com.example.ui.kool

import com.example.models.Part
import com.example.models.Vector3
import de.fabmax.kool.Assets
import de.fabmax.kool.MimeType
import de.fabmax.kool.loadTexture2dAsync
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.PointerState
import de.fabmax.kool.math.MutableQuatD
import de.fabmax.kool.math.MutableVec3d
import de.fabmax.kool.math.RayTest
import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.spatial.BoundingBoxF
import de.fabmax.kool.math.toEulers
import de.fabmax.kool.modules.gizmo.GizmoListener
import de.fabmax.kool.modules.gizmo.GizmoMode
import de.fabmax.kool.modules.gizmo.SimpleGizmo
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.modules.ksl.blocks.ColorBlockConfig
import de.fabmax.kool.pipeline.BlendMode
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.DepthCompareOp
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.ColorMesh
import de.fabmax.kool.scene.MeshRayTest
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrbitInputTransform
import de.fabmax.kool.scene.OrbitInputTransform.DragMethod as OrbitDragMethod
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.TrsTransformD
import de.fabmax.kool.scene.TriangulatedLineMesh
import de.fabmax.kool.scene.TextureMesh
import de.fabmax.kool.scene.TextureMeshLayout
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.scene.geometry.MeshBuilder
import de.fabmax.kool.scene.scene
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.toBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bridges the app's [Part] data model to a kool GPU scene graph.
 *
 * Each Part becomes a kool [ColorMesh] built at UNIT size (cube / icoSphere / cylinder /
 * wedge); the Part's position / rotation / size are applied entirely via the node
 * [Transform], so changing size or color never rebuilds geometry (which would flicker
 * and detach the gizmo).
 *
 * Transform tools follow the same authority pattern as three.js TransformControls and
 * kool's own editor: the [SimpleGizmo] is a permanent child of the scene (toggled via
 * [Node.isVisible], not added/removed, so it survives mesh rebuilds without jumping to
 * the origin). While a drag is in progress ([hasTransformAuthority] = true) the gizmo
 * directly manipulates the mesh transform via [GizmoClient.setTransformMatrix], and the
 * bridge neither writes back to the ViewModel nor re-applies the ViewModel transform to
 * that mesh — eliminating the fight that caused cross-axis dragging and scale snap-back.
 * The ViewModel is updated once on [GizmoListener.onManipulationFinished]. When idle,
 * [SimpleGizmo.updateGizmoFromClient] is called each frame so the gizmo tracks meshes
 * moved by scripts / camera.
 *
 * Clicking a part ray-tests the scene and selects it. All scene-graph mutations happen
 * on the GL thread (kool renders there) via a per-frame reconciler in [Scene.onUpdate];
 * the Compose thread only sets @Volatile pending state.
 */
class KoolSceneBridge(
    private val onPartTransformed: (Part) -> Unit = {},
    private val onPartPicked: (Part?) -> Unit = {}
) {

    private var orbit: OrbitInputTransform? = null

    val scene: Scene = scene {
        orbit = defaultOrbitCamera(yaw = 45f, pitch = -30f)
        lighting.singleDirectionalLight {
            setup(Vec3f(-0.6f, -1f, -0.4f))
            setColor(Color.WHITE, 3.5f)
        }
        lighting.addDirectionalLight {
            setup(Vec3f(0.5f, 0.35f, 0.6f))
            setColor(Color.WHITE, 0.8f)
        }
    }

    private val root: Node = Node("workspace").also { scene.addNode(it) }
    private val selectionBox = TriangulatedLineMesh("selection-box").also {
        it.color = Color(0.0f, 0.62f, 1.0f, 1f)
        it.width = 2.5f
        it.addBoundingBox(
            BoundingBoxF(
                Vec3f(-0.53f, -0.53f, -0.53f),
                Vec3f(0.53f, 0.53f, 0.53f)
            ),
            it.color,
            it.width
        )
        it.rayTest = MeshRayTest.nopTest()
        it.isVisible = false
        scene.addNode(it)
    }

    /** Transform gizmo — a permanent scene child, toggled via isVisible (never re-attached). */
    private val gizmo: SimpleGizmo = SimpleGizmo(
        name = "transform-gizmo",
        addOverlays = true,
        hideHandlesOnDrag = true
    ).also {
        it.isVisible = false
        // Keep the default speed initially; per-tool tuning is applied in applyTool().
        it.dragSpeedModifier.set(1.0f)
        // Increase gizmo visual size for easier axis selection (default 1.0 → 1.4)
        it.gizmoNode.gizmoSize = 1.4f
        scene.addNode(it)
    }
    /** True while the gizmo is dragging — the gizmo owns the mesh transform; we don't sync. */
    private var hasTransformAuthority = false
    /** whether the gizmo node is currently attached to the scene. */
    private var gizmoAttached = false

    private var gizmoPartId: String? = null
    private var gizmoPart: Part? = null
    private var currentTool: String = "SELECT"

    private val meshes: MutableMap<String, ColorMesh> = mutableMapOf()
    private val meshKeys: MutableMap<String, String> = mutableMapOf()
    private val meshToPartId: MutableMap<ColorMesh, String> = mutableMapOf()
    private val decalMeshes: MutableMap<String, TextureMesh> = mutableMapOf()
    private val decalKeys: MutableMap<String, String> = mutableMapOf()
    private val textureCache: MutableMap<String, Texture2d> = mutableMapOf()
    private val textureRequests: MutableMap<String, Deferred<Result<Texture2d>>> = mutableMapOf()
    private val textureRequestKeys: MutableMap<String, String> = mutableMapOf()
    private val missingTextureKeys: MutableSet<String> = mutableSetOf()
    private val textureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---- Pending state (Compose thread writes; GL thread applies) ----
    @Volatile private var pendingParts: List<Part> = emptyList()
    @Volatile private var pendingDecals: List<DecalRenderItem> = emptyList()
    @Volatile private var roblosecurityCookie: String = ""
    @Volatile private var pendingSelection: Part? = null
    @Volatile private var pendingTool: String = "SELECT"
    @Volatile private var pendingYaw: Float = 45f
    @Volatile private var pendingPitch: Float = 30f
    @Volatile private var pendingZoom: Float = 32f
    @Volatile private var partsDirty: Boolean = true
    @Volatile private var decalsDirty: Boolean = true
    @Volatile private var selectionDirty: Boolean = true
    @Volatile private var toolDirty: Boolean = true
    @Volatile private var cameraDirty: Boolean = true
    @Volatile private var pickedPartId: String? = null
    @Volatile private var pickDirty: Boolean = false
    private var pointerListener: InputStack.PointerListener? = null

    init {
        // Per-frame reconciler — runs on the GL thread.
        scene.onUpdate {
            if (harvestTextureRequests()) decalsDirty = true
            if (partsDirty) { applyParts(pendingParts); partsDirty = false }
            if (decalsDirty) { applyDecals(pendingDecals); decalsDirty = false }
            if (pickDirty) { applyPick(pickedPartId); pickDirty = false }
            if (selectionDirty) { applySelection(pendingSelection); selectionDirty = false }
            if (toolDirty) { applyTool(pendingTool); toolDirty = false }
            if (cameraDirty) { applyCamera(pendingYaw, pendingPitch, pendingZoom); cameraDirty = false }
            // When idle, keep the gizmo tracking the bound mesh (moved by scripts/camera).
            // While dragging (hasTransformAuthority) the gizmo owns the transform, so don't.
            if (gizmo.isVisible && !hasTransformAuthority && gizmoPartId != null) {
                gizmo.updateGizmoFromClient()
            }
        }

        // Gizmo lifecycle — authority pattern (three.js TransformControls / kool editor).
        // While dragging, the orbit camera is disabled (leftDragMethod = NONE) to prevent
        // it from consuming the same pointer events, which would rotate the camera and
        // cause the gizmo axes to shift on screen — manifesting as cross-axis wobble.
        var savedDragMethod: OrbitDragMethod? = null
        gizmo.gizmoNode.gizmoListeners += object : GizmoListener {
            override fun onManipulationStart(startTransform: TrsTransformD) {
                hasTransformAuthority = true
                savedDragMethod = orbit?.leftDragMethod
                orbit?.leftDragMethod = OrbitDragMethod.NONE
            }
            override fun onGizmoUpdate(transform: TrsTransformD) {
                // While dragging: the gizmo already wrote the mesh transform directly via
                // GizmoClient.setTransformMatrix. Do NOT write back to the ViewModel here.
            }
            override fun onManipulationFinished(startTransform: TrsTransformD, endTransform: TrsTransformD) {
                hasTransformAuthority = false
                savedDragMethod?.let { orbit?.leftDragMethod = it }
                savedDragMethod = null
                commitGizmoResult(endTransform)
            }
            override fun onManipulationCanceled(startTransform: TrsTransformD) {
                hasTransformAuthority = false
                savedDragMethod?.let { orbit?.leftDragMethod = it }
                savedDragMethod = null
                commitGizmoResult(startTransform)
            }
        }

        // Click-to-select via ray-test. Store the listener so it can be removed on dispose.
        pointerListener = InputStack.PointerListener { pointerState, _ ->
            handleViewportClick(pointerState)
        }
        InputStack.defaultInputHandler.pointerListeners += pointerListener!!
    }


    /**
     * Removes the pointer listener and releases gizmo resources. Call when the viewport
     * is disposed to prevent listener accumulation across screen rebuilds.
     */
    fun dispose() {
        pointerListener?.let { InputStack.defaultInputHandler.pointerListeners -= it }
        pointerListener = null
        textureScope.cancel()
        // SimpleGizmo pushes an InputStack handler in init; only release() pops it.
        // Without this, every tab switch leaked one handler onto the global input
        // stack and kept intercepting pointer events invisibly.
        gizmo.release()
        if (gizmoAttached) {
            scene.removeNode(gizmo)
            gizmoAttached = false
        }
        scene.release()
    }

    fun syncParts(parts: List<Part>) {
        if (parts !== pendingParts && parts != pendingParts) {
            pendingParts = parts
            partsDirty = true
            decalsDirty = true
        }
    }

    fun syncDecals(decals: List<DecalRenderItem>) {
        if (decals !== pendingDecals && decals != pendingDecals) {
            pendingDecals = decals
            decalsDirty = true
        }
    }

    fun setRoblosecurityCookie(cookie: String) {
        val normalized = normalizeRoblosecurityCookie(cookie)
        if (normalized != roblosecurityCookie) {
            roblosecurityCookie = normalized
            decalsDirty = true
        }
    }

    fun syncSelection(part: Part?) {
        if (part != pendingSelection) {
            pendingSelection = part
            selectionDirty = true
        }
    }

    fun updateCamera(yawDeg: Float, pitchDeg: Float, zoom: Float) {
        if (yawDeg != pendingYaw || pitchDeg != pendingPitch || zoom != pendingZoom) {
            pendingYaw = yawDeg
            pendingPitch = pitchDeg
            pendingZoom = zoom
            cameraDirty = true
        }
    }

    fun setGizmoMode(toolName: String) {
        if (toolName != pendingTool) {
            pendingTool = toolName
            toolDirty = true
        }
    }

    fun setSelectedPart(part: Part?) = syncSelection(part)

    private fun requestPick(partId: String?) {
        pickedPartId = partId
        pickDirty = true
    }

    // ---- GL-thread applicators ----

    private fun applyParts(parts: List<Part>) {
        val seen = HashSet<String>()
        for (part in parts) {
            seen += part.id
            val key = partKey(part)
            val existing = meshes[part.id]
            val existingKey = meshKeys[part.id]
            if (existing == null || existingKey != key) {
                existing?.let { root.removeNode(it); meshToPartId.remove(it) }
                val mesh = createMesh(part) ?: continue
                meshes[part.id] = mesh
                meshKeys[part.id] = key
                meshToPartId[mesh] = part.id
                root.addNode(mesh)
                applyTransform(mesh, part)
            } else {
                // Don't clobber a transform the gizmo is actively dragging.
                if (part.id != gizmoPartId || !hasTransformAuthority) {
                    applyTransform(existing, part)
                }
            }
        }
        val removed = meshes.keys - seen
        for (id in removed) {
            meshes.remove(id)?.let { root.removeNode(it); meshToPartId.remove(it) }
            meshKeys.remove(id)
            if (id == gizmoPartId) updateGizmoBinding()
        }
        updateSelectionBoxBinding()
    }

    private fun applyDecals(decals: List<DecalRenderItem>) {
        val partsById = pendingParts.associateBy { it.id }
        val seen = HashSet<String>()

        decals.sortedBy { it.zIndex }.forEach { decal ->
            seen += decal.id
            val part = partsById[decal.parentPartId]
            val assetPath = resolveRobloxTextureAssetPath(decal.textureUri)
            if (part == null || assetPath == null) {
                removeDecalMesh(decal.id)
                return@forEach
            }

            val textureCacheKey = textureCacheKey(assetPath, decal.isTexture)
            val texture = textureCache[textureCacheKey]
            if (texture == null) {
                requestTexture(assetPath, textureCacheKey, decal.isTexture)
                return@forEach
            }

            val key = decalKey(decal, part, textureCacheKey)
            val existing = decalMeshes[decal.id]
            val mesh = if (existing == null || decalKeys[decal.id] != key) {
                removeDecalMesh(decal.id)
                createDecalMesh(decal, part, texture).also {
                    decalMeshes[decal.id] = it
                    decalKeys[decal.id] = key
                    root.addNode(it)
                }
            } else {
                existing
            }
            applyDecalTransform(mesh, part)
        }

        (decalMeshes.keys - seen).toList().forEach { removeDecalMesh(it) }
    }

    private fun requestTexture(assetPath: String, cacheKey: String, repeating: Boolean) {
        val requestKey = "${textureRequestKey(assetPath)}|repeat=$repeating"
        if (cacheKey in textureCache || cacheKey in textureRequests || requestKey in missingTextureKeys) return

        val samplerSettings = if (repeating) SamplerSettings().repeating() else SamplerSettings().clamped()
        textureRequestKeys[cacheKey] = requestKey
        textureRequests[cacheKey] = if (isRobloxAssetDeliveryPath(assetPath)) {
            textureScope.async { loadRobloxAssetTexture(assetPath, roblosecurityCookie, samplerSettings) }
        } else {
            Assets.loadTexture2dAsync(
                assetPath = assetPath,
                mipMapping = MipMapping.Full,
                samplerSettings = samplerSettings
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun harvestTextureRequests(): Boolean {
        if (textureRequests.isEmpty()) return false
        var changed = false
        val completed = textureRequests.filterValues { it.isCompleted }.keys.toList()
        completed.forEach { path ->
            val request = textureRequests.remove(path) ?: return@forEach
            val requestKey = textureRequestKeys.remove(path) ?: path
            val texture = runCatching { request.getCompleted().getOrThrow() }.getOrNull()
            if (texture != null) {
                textureCache[path] = texture
            } else {
                missingTextureKeys += requestKey
            }
            changed = true
        }
        return changed
    }

    private fun removeDecalMesh(id: String) {
        decalMeshes.remove(id)?.let { root.removeNode(it) }
        decalKeys.remove(id)
    }

    private suspend fun loadRobloxAssetTexture(
        assetPath: String,
        cookie: String,
        samplerSettings: SamplerSettings
    ): Result<Texture2d> = runCatching {
        val assetId = assetPath.substringAfter("id=", "").takeWhile { it.isDigit() }
            .ifBlank { throw IOException("Missing Roblox asset id in $assetPath") }
        val bytes = downloadRobloxAssetBytes(assetId, cookie)
        val imageBytes = resolveRobloxImageBytes(bytes, cookie, linkedSetOf(assetId))
        val imageData = Assets.loadImageFromBuffer(
            imageBytes.toBuffer(),
            sniffImageMimeType(imageBytes)
        )
        Texture2d(
            imageData,
            mipMapping = MipMapping.Full,
            samplerSettings = samplerSettings,
            name = "roblox-asset-$assetId"
        )
    }

    private suspend fun resolveRobloxImageBytes(
        bytes: ByteArray,
        cookie: String,
        visitedAssetIds: MutableSet<String>
    ): ByteArray {
        if (looksLikeImage(bytes)) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        val nested = extractNestedTextureUri(text)
            ?: throw IOException("Roblox asset did not contain image bytes or nested texture url.")

        val nestedPath = resolveRobloxTextureAssetPath(nested)
            ?: throw IOException("Unsupported nested Roblox texture uri: $nested")

        if (!isRobloxAssetDeliveryPath(nestedPath)) {
            throw IOException("Nested texture is not an assetdelivery id: $nested")
        }

        val nestedId = nestedPath.substringAfter("id=", "").takeWhile { it.isDigit() }
            .ifBlank { throw IOException("Missing nested Roblox asset id in $nestedPath") }
        if (!visitedAssetIds.add(nestedId)) {
            throw IOException("Roblox texture asset loop detected: $nestedId")
        }

        return resolveRobloxImageBytes(downloadRobloxAssetBytes(nestedId, cookie), cookie, visitedAssetIds)
    }

    private fun downloadRobloxAssetBytes(assetId: String, cookie: String): ByteArray {
        val request = Request.Builder()
            .url("https://assetdelivery.roblox.com/v1/asset/?id=$assetId")
            .header("Accept", "*/*")
            .header("User-Agent", "RobloxStudio/WinInet RStudioApp/1.0")
            .apply {
                if (cookie.isNotBlank()) {
                    header("Cookie", ".ROBLOSECURITY=$cookie")
                }
            }
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val message = body.toString(Charsets.UTF_8).take(300)
                throw IOException("Roblox asset $assetId failed: HTTP ${response.code} $message")
            }
            if (body.isEmpty()) {
                throw IOException("Roblox asset $assetId returned an empty body.")
            }
            return body
        }
    }

    private fun extractNestedTextureUri(text: String): String? {
        val contentTexture = Regex(
            """<Content\s+name=["']Texture["'][\s\S]*?<url>(.*?)</url>""",
            setOf(RegexOption.IGNORE_CASE)
        ).find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!contentTexture.isNullOrBlank()) return contentTexture

        Regex("""rbxassetid://(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return "rbxassetid://$it" }

        Regex("""https?://(?:www\.)?roblox\.com/asset/\?id=(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return "rbxassetid://$it" }

        return null
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ||
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ||
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            )

    private fun sniffImageMimeType(bytes: ByteArray): MimeType =
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            MimeType.IMAGE_JPG
        } else {
            MimeType.IMAGE_PNG
        }

    private fun isRobloxAssetDeliveryPath(path: String): Boolean =
        path.startsWith("https://assetdelivery.roblox.com/v1/asset/?id=", ignoreCase = true)

    private fun textureRequestKey(path: String): String =
        if (isRobloxAssetDeliveryPath(path)) "$path|cookie=${roblosecurityCookie.hashCode()}" else path

    private fun textureCacheKey(path: String, repeating: Boolean): String =
        "$path|sampler=${if (repeating) "repeat" else "clamp"}"

    private fun normalizeRoblosecurityCookie(input: String): String {
        val trimmed = input.trim()
            .removePrefix("Cookie:")
            .trim()
        return when {
            trimmed.isBlank() -> ""
            ".ROBLOSECURITY=" in trimmed -> trimmed.substringAfter(".ROBLOSECURITY=").substringBefore(";").trim()
            trimmed.startsWith(".ROBLOSECURITY=", ignoreCase = true) -> trimmed.substringAfter("=").substringBefore(";").trim()
            else -> trimmed.substringBefore(";").trim()
        }
    }

    private fun applyPick(partId: String?) {
        val part = partId?.let { id -> pendingParts.firstOrNull { it.id == id } }
        onPartPicked(part)
    }

    private fun applySelection(part: Part?) {
        gizmoPart = part
        gizmoPartId = part?.id
        updateGizmoBinding()
    }

    private fun applyTool(toolName: String) {
        currentTool = toolName
        when (toolName) {
            "MOVE" -> {
                gizmo.mode = GizmoMode.TRANSLATE
                gizmo.dragSpeedModifier.set(1.8f)
            }
            "ROTATE" -> {
                gizmo.mode = GizmoMode.ROTATE
                gizmo.dragSpeedModifier.set(0.85f)
            }
            "SCALE" -> {
                gizmo.mode = GizmoMode.SCALE
                gizmo.dragSpeedModifier.set(1.2f)
            }
            else -> {
                gizmo.mode = GizmoMode.TRANSLATE
                gizmo.dragSpeedModifier.set(1.0f)
            }
        }
        updateGizmoBinding()
        updateSelectionBoxBinding()
    }

    private fun applyCamera(yawDeg: Float, pitchDeg: Float, zoom: Float) {
        // Don't apply camera changes while gizmo is actively dragging — the orbit camera
        // is disabled during manipulation to prevent cross-axis wobble.
        if (hasTransformAuthority) return
        val o = orbit ?: return
        o.setRotation(yawDeg, -pitchDeg)
        o.setZoom(zoomToRadius(zoom))
    }

    /**
     * Binds / shows / hides the gizmo. The gizmo stays permanently in the scene; we only
     * toggle [Node.isVisible] and (re)point [SimpleGizmo.setTransformNode] at the selected
     * mesh. Re-pointing each selection is safe because the gizmo is never removed.
     * 
     * IMPORTANT: Do NOT rebind the gizmo while it's actively dragging (hasTransformAuthority).
     * Rebinding mid-drag would interrupt the manipulation and cause the gizmo to disappear or
     * reset, leading to invisible drags and cross-axis interference.
     */
    private fun updateGizmoBinding() {
        // Skip rebinding during active drag to prevent gizmo disappearing/resetting.
        if (hasTransformAuthority) return
        
        val part = gizmoPart
        val mesh = part?.id?.let { meshes[it] }
        if (part != null && mesh != null && currentTool != "SELECT") {
            gizmo.setTransformNode(mesh)
            gizmo.isVisible = true
        } else {
            gizmo.isVisible = false
        }
        updateSelectionBoxBinding()
    }

    private fun updateSelectionBoxBinding() {
        val part = gizmoPart
        val mesh = part?.id?.let { meshes[it] }
        if (part == null || mesh == null || currentTool != "SELECT") {
            selectionBox.isVisible = false
            return
        }

        val pos = part.currentPosition
        val rot = part.currentRotation
        val size = part.size
        selectionBox.transform.setIdentity()
        selectionBox.transform.translate(pos.x, pos.y, pos.z)
        selectionBox.transform.rotate(rot.x.deg, rot.y.deg, rot.z.deg)
        selectionBox.transform.scale(Vec3f(size.x, size.y, size.z))
        selectionBox.isVisible = true
    }

    /** Ray-tests the scene on a left click and selects the hit part (or clears selection). */
    private fun handleViewportClick(pointerState: PointerState) {
        val ptr = pointerState.primaryPointer
        if (hasTransformAuthority || gizmo.gizmoNode.isManipulating) return
        if (!ptr.isLeftButtonClicked || ptr.isConsumed()) return
        val rayTest = RayTest()
        if (!scene.computePickRay(ptr, rayTest.ray)) return
        scene.rayTest(rayTest)
        var node: Node? = rayTest.hitNode
        while (node != null && node !in meshToPartId) {
            node = node.parent
        }
        val partId = (node as? ColorMesh)?.let { meshToPartId[it] }
        if (partId != null) ptr.consume()
        requestPick(partId)
    }

    /**
     * Reads the bound mesh's transform (written directly by the gizmo during the drag via
     * GizmoClient.setTransformMatrix) and commits it to the ViewModel once, on manipulation
     * finish / cancel. We decompose the MESH transform — not the passed [TrsTransformD]
     * (which is the gizmo's own pose and does NOT carry the mesh's scale, so reading
     * t.scale would lose size changes). This is the only place the gizmo result is written
     * back, per the authority pattern.
     */
    private fun commitGizmoResult(t: TrsTransformD) {
        val part = gizmoPart ?: return
        val mesh = gizmoPartId?.let { meshes[it] } ?: return
        // Decompose the mesh's current transform (set by the gizmo) into T/R/S.
        val pos = MutableVec3d()
        val rot = MutableQuatD()
        val scale = MutableVec3d()
        mesh.transform.decompose(pos, rot, scale)
        val euler = rot.toEulers()
        val updated = when (gizmo.mode) {
            GizmoMode.TRANSLATE -> part.copy(
                position = Vector3(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat()),
                currentPosition = Vector3(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
            )
            GizmoMode.SCALE -> part.copy(size = Vector3(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat()))
            GizmoMode.ROTATE -> {
                val deg = Vector3(
                    Math.toDegrees(euler.x).toFloat(),
                    Math.toDegrees(euler.y).toFloat(),
                    Math.toDegrees(euler.z).toFloat()
                )
                part.copy(rotation = deg, currentRotation = deg)
            }
            else -> part
        }
        onPartTransformed(updated)
    }

    /** Applies position / rotation / size entirely via the node transform (unit geometry). */
    private fun applyTransform(mesh: ColorMesh, part: Part) {
        val pos = part.currentPosition
        val rot = part.currentRotation
        val size = part.size
        mesh.transform.setIdentity()
        mesh.transform.translate(pos.x, pos.y, pos.z)
        mesh.transform.rotate(rot.x.deg, rot.y.deg, rot.z.deg)
        mesh.transform.scale(Vec3f(size.x, size.y, size.z))
    }

    private fun applyDecalTransform(mesh: TextureMesh, part: Part) {
        val pos = part.currentPosition
        val rot = part.currentRotation
        val size = part.size
        mesh.transform.setIdentity()
        mesh.transform.translate(pos.x, pos.y, pos.z)
        mesh.transform.rotate(rot.x.deg, rot.y.deg, rot.z.deg)
        mesh.transform.scale(Vec3f(size.x, size.y, size.z))
    }

    /** Rebuild key excludes size: size is transform-only, so scaling never rebuilds. */
    private fun partKey(part: Part): String =
        "${part.shape}|${part.colorHex}|${part.material}|${part.transparency}|${part.reflectance}|${part.castShadow}"

    private fun decalKey(decal: DecalRenderItem, part: Part, assetPath: String): String {
        val tilingKey = if (decal.isTexture) {
            "|tile=${decal.studsPerTileU},${decal.studsPerTileV}|offset=${decal.offsetStudsU},${decal.offsetStudsV}|size=${part.size.x},${part.size.y},${part.size.z}"
        } else {
            ""
        }
        return "${decal.parentPartId}|$assetPath|${decal.face}|${decal.transparency}|${decal.colorHex}|${decal.zIndex}|texture=${decal.isTexture}$tilingKey"
    }

    /** UNIT-size mesh; the Part's size is applied via [applyTransform]. */
    private fun createMesh(part: Part): ColorMesh? {
        val mesh = ColorMesh(name = part.name)
        val baseColor = parseColor(part.colorHex)
        mesh.generate {
            withColor(baseColor.toLinear()) {
                when (part.shape) {
                    Part.SHAPE_SPHERE -> icoSphere { radius = 0.5f; steps = 4 }
                    Part.SHAPE_CYLINDER -> cylinder { radius = 0.5f; height = 1f; steps = 24 }
                    Part.SHAPE_WEDGE -> wedgeGeometry()
                    else -> cube { }
                }
            }
        }
        mesh.shader = pbrShaderFor(part, baseColor)
        return mesh
    }

    private fun createDecalMesh(decal: DecalRenderItem, part: Part, texture: Texture2d): TextureMesh {
        val mesh = TextureMesh(name = "decal-${decal.id}")
        val alpha = (1f - decal.transparency).coerceIn(0f, 1f)
        val tint = parseColor(decal.colorHex).let { Color(it.r, it.g, it.b, alpha) }
        mesh.generate {
            decalQuad(decal, part)
        }
        mesh.shader = KslUnlitShader {
            color {
                textureColor(texture)
                constColor(tint.toLinear(), blendMode = ColorBlockConfig.BlendMode.Multiply)
            }
            pipeline {
                blendMode = BlendMode.BLEND_MULTIPLY_ALPHA
                cullMethod = CullMethod.CULL_BACK_FACES
                depthTest = DepthCompareOp.ALWAYS
                isWriteDepth = false
            }
        }
        mesh.rayTest = MeshRayTest.nopTest()
        return mesh
    }

    private fun pbrShaderFor(part: Part, baseColor: Color): KslPbrShader {
        // Transparency: Roblox transparency 0=opaque, 1=invisible → alpha = 1 - transparency.
        // Glass material has a base transparency of 0.55; user transparency stacks on top.
        val baseAlpha = if (part.material == Part.MATERIAL_GLASS) 0.55f else 1f
        val alpha = (baseAlpha * (1f - part.transparency)).coerceIn(0.05f, 1f)
        val color = if (alpha < 1f) Color(baseColor.r, baseColor.g, baseColor.b, alpha) else baseColor

        // Reflectance: Roblox 0..1 — fake by blending toward metallic with low roughness.
        // At reflectance=0, material's own metallic/roughness applies.
        // At reflectance=1, fully mirror-like (metallic=1, roughness=0.05).
        val reflectMix = part.reflectance.coerceIn(0f, 1f)

        // Base material metallic/roughness
        val (matMetallic, matRoughness, isNeon) = when (part.material) {
            Part.MATERIAL_METAL -> Triple(1f, 0.3f, false)
            Part.MATERIAL_NEON -> Triple(0f, 0.2f, true)
            Part.MATERIAL_GLASS -> Triple(0f, 0.05f, false)
            Part.MATERIAL_WOOD, Part.MATERIAL_FABRIC -> Triple(0f, 0.85f, false)
            Part.MATERIAL_BRICK, Part.MATERIAL_SLATE, Part.MATERIAL_MARBLE -> Triple(0f, 0.7f, false)
            else -> Triple(0f, 0.5f, false)
        }

        // Blend reflectance into metallic/roughness
        val metallic = (matMetallic * (1f - reflectMix) + 1f * reflectMix)
        val roughness = (matRoughness * (1f - reflectMix) + 0.05f * reflectMix)

        return KslPbrShader {
            color { uniformColor(color.toLinear()) }
            metallic(metallic)
            roughness(roughness)
            if (isNeon) {
                emission { uniformColor(color.toLinear()) }
            }
        }
    }

    private fun MeshBuilder<*>.wedgeGeometry() {
        val hx = 0.5f; val hy = 0.5f; val hz = 0.5f
        fun v(x: Float, y: Float, z: Float) = vertex(Vec3f(x, y, z), Vec3f.ZERO)
        val v0 = v(-hx, -hy, -hz); val v1 = v(hx, -hy, -hz)
        val v2 = v(hx, hy, -hz); val v3 = v(-hx, hy, -hz)
        val v4 = v(-hx, -hy, hz); val v5 = v(hx, -hy, hz)
        addTriIndices(v0, v1, v2); addTriIndices(v0, v2, v3)
        addTriIndices(v0, v4, v5); addTriIndices(v0, v5, v1)
        addTriIndices(v3, v2, v5); addTriIndices(v3, v5, v4)
        addTriIndices(v0, v3, v4); addTriIndices(v1, v5, v2)
    }

    private fun MeshBuilder<TextureMeshLayout>.decalQuad(decal: DecalRenderItem, part: Part) {
        // Keep decal geometry clearly in front of the parent face. A tiny offset tends
        // to z-fight at close zoom levels on mobile depth buffers.
        val h = 0.492f
        val o = 0.514f + decal.zIndex.coerceIn(0, 100) * 0.0012f

        val normalizedFace = decal.face.trim().lowercase()
        val (faceUStuds, faceVStuds) = decalFaceStudDimensions(normalizedFace, part.size)
        val u0 = if (decal.isTexture) decal.offsetStudsU / decal.studsPerTileU else 0f
        val v0 = if (decal.isTexture) decal.offsetStudsV / decal.studsPerTileV else 0f
        val u1 = if (decal.isTexture) u0 + faceUStuds / decal.studsPerTileU else 1f
        val v1 = if (decal.isTexture) v0 + faceVStuds / decal.studsPerTileV else 1f
        val uv0 = Vec2f(u0, v1)
        val uv1 = Vec2f(u1, v1)
        val uv2 = Vec2f(u1, v0)
        val uv3 = Vec2f(u0, v0)

        val normal: Vec3f
        val corners: Array<Vec3f>
        val reverseWinding: Boolean
        when (normalizedFace) {
            "top", "1" -> {
                normal = Vec3f.Y_AXIS
                corners = arrayOf(
                    Vec3f(-h, o, -h),
                    Vec3f(h, o, -h),
                    Vec3f(h, o, h),
                    Vec3f(-h, o, h)
                )
                reverseWinding = true
            }
            "bottom", "4" -> {
                normal = Vec3f.NEG_Y_AXIS
                corners = arrayOf(
                    Vec3f(-h, -o, h),
                    Vec3f(h, -o, h),
                    Vec3f(h, -o, -h),
                    Vec3f(-h, -o, -h)
                )
                reverseWinding = true
            }
            "right", "0" -> {
                normal = Vec3f.X_AXIS
                corners = arrayOf(
                    Vec3f(o, -h, h),
                    Vec3f(o, -h, -h),
                    Vec3f(o, h, -h),
                    Vec3f(o, h, h)
                )
                reverseWinding = false
            }
            "left", "3" -> {
                normal = Vec3f.NEG_X_AXIS
                corners = arrayOf(
                    Vec3f(-o, -h, -h),
                    Vec3f(-o, -h, h),
                    Vec3f(-o, h, h),
                    Vec3f(-o, h, -h)
                )
                reverseWinding = false
            }
            "back", "2" -> {
                normal = Vec3f.Z_AXIS
                corners = arrayOf(
                    Vec3f(h, -h, o),
                    Vec3f(-h, -h, o),
                    Vec3f(-h, h, o),
                    Vec3f(h, h, o)
                )
                reverseWinding = true
            }
            else -> {
                normal = Vec3f.NEG_Z_AXIS
                corners = arrayOf(
                    Vec3f(-h, -h, -o),
                    Vec3f(h, -h, -o),
                    Vec3f(h, h, -o),
                    Vec3f(-h, h, -o)
                )
                reverseWinding = true
            }
        }

        val i0 = vertex(corners[0], normal, uv0)
        val i1 = vertex(corners[1], normal, uv1)
        val i2 = vertex(corners[2], normal, uv2)
        val i3 = vertex(corners[3], normal, uv3)
        if (reverseWinding) {
            addTriIndices(i0, i1, i2)
            addTriIndices(i0, i2, i3)
        } else {
            addTriIndices(i0, i2, i1)
            addTriIndices(i0, i3, i2)
        }
    }

    private fun decalFaceStudDimensions(face: String, size: com.example.models.Vector3): Pair<Float, Float> =
        when (face) {
            "right", "0", "left", "3" -> size.z to size.y
            "top", "1", "bottom", "4" -> size.x to size.z
            else -> size.x to size.y
        }

    private fun parseColor(hex: String): Color {
        val clean = hex.removePrefix("#")
        // Validate: must be exactly 6 hex chars, otherwise fallback to grey
        if (clean.length != 6 || !clean.all { it in "0123456789abcdefABCDEF" }) {
            return Color(0.8f, 0.8f, 0.8f, 1f)
        }
        val rgb = clean.toLong(16)
        return Color(
            ((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f,
            (rgb and 0xFF) / 255f, 1f
        )
    }

    private fun zoomToRadius(zoom: Float): Double = (150.0 - zoom).coerceIn(15.0, 140.0)
}
