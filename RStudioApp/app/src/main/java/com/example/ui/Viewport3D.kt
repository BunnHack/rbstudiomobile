package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.models.Part
import com.example.models.Vector3
import com.example.viewmodels.StudioViewModel
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
@Deprecated("Use StudioViewport, which is backed by SceneView/Filament")
fun Viewport3D(
    viewModel: StudioViewModel,
    parts: List<Part>,
    selectedPart: Part?,
    modifier: Modifier = Modifier
) {
    // Camera settings from Viewmodel
    val yaw by viewModel.cameraYaw.collectAsState()
    val pitch by viewModel.cameraPitch.collectAsState()
    val zoom by viewModel.cameraZoom.collectAsState()
    val offsetX by viewModel.cameraOffsetX.collectAsState()
    val offsetY by viewModel.cameraOffsetY.collectAsState()

    val showGrid by viewModel.showGrid.collectAsState()
    val gridMaterial by viewModel.gridMaterial.collectAsState()
    val wireframe by viewModel.wireframe.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    // Interactive Drag tracker for gizmo or orbit
    var activeGizmoAxis by remember { mutableStateOf<String?>(null) }
    var dragStartOffset by remember { mutableStateOf(Offset.Zero) }
    var originalPartPosition by remember { mutableStateOf(Vector3.Zero) }
    var originalPartSize by remember { mutableStateOf(Vector3.Zero) }
    var originalPartRotation by remember { mutableStateOf(Vector3.Zero) }

    // Use rememberUpdatedState to capture latest parameters in non-restarting pointerInput blocks
    val currentYaw by rememberUpdatedState(yaw)
    val currentPitch by rememberUpdatedState(pitch)
    val currentZoom by rememberUpdatedState(zoom)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentSelectedPart by rememberUpdatedState(selectedPart)
    val currentParts by rememberUpdatedState(parts)
    val currentActiveTool by rememberUpdatedState(activeTool)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Simple time tracker for particle animations
    val animTime = remember { System.currentTimeMillis() / 1000f }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                // Handle Tap Selection
                detectTapGestures { tapOffset ->
                    val size = this.size
                    // 1. Check Gizmo tip hit test first!
                    var gizmoHit = false
                    val selPart = currentSelectedPart
                    if (selPart != null && !currentIsPlaying) {
                        val origin = selPart.currentPosition
                        
                        val screenX = project3D(origin + Vector3(3f, 0f, 0f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())
                        val screenY = project3D(origin + Vector3(0f, 3f, 0f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())
                        val screenZ = project3D(origin + Vector3(0f, 0f, 3f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())

                        if (tapOffset.distance(screenX) < dpToPx28()) {
                            viewModel.setActiveTool("MOVE") // Auto switch or focus
                            activeGizmoAxis = "X"
                            gizmoHit = true
                        } else if (tapOffset.distance(screenY) < dpToPx28()) {
                            activeGizmoAxis = "Y"
                            gizmoHit = true
                        } else if (tapOffset.distance(screenZ) < dpToPx28()) {
                            activeGizmoAxis = "Z"
                            gizmoHit = true
                        }
                    }

                    if (!gizmoHit) {
                        // 2. Scene hit test: Find part closest in screen space
                        var closestPart: Part? = null
                        var minDistance = Float.MAX_VALUE

                        for (part in currentParts) {
                            val screenPos = project3D(
                                part.currentPosition, currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY,
                                size.width.toFloat(), size.height.toFloat()
                            )
                            val dist = tapOffset.distance(screenPos)
                            // Approximate radius bounds
                            val approxRadius = (part.size.length() * currentZoom / 3f).coerceIn(20f, 150f)
                            if (dist < approxRadius && dist < minDistance) {
                                minDistance = dist
                                closestPart = part
                            }
                        }

                        if (closestPart != null) {
                            viewModel.selectPart(closestPart)
                            viewModel.logSystem("Selected Workspace.${closestPart.name}")
                        } else {
                            viewModel.selectPart(null)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // Orbit/Pan/Gizmo Dragging
                detectDragGestures(
                    onDragStart = { startOffset ->
                        dragStartOffset = startOffset
                        currentSelectedPart?.let {
                            originalPartPosition = it.currentPosition
                            originalPartSize = it.size
                            originalPartRotation = it.currentRotation
                        }

                        // Determine if dragging gizmo handles
                        val selPart = currentSelectedPart
                        if (selPart != null && !currentIsPlaying) {
                            val size = this.size
                            val origin = selPart.currentPosition
                            val screenX = project3D(origin + Vector3(3f, 0f, 0f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())
                            val screenY = project3D(origin + Vector3(0f, 3f, 0f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())
                            val screenZ = project3D(origin + Vector3(0f, 0f, 3f), currentYaw, currentPitch, currentZoom, currentOffsetX, currentOffsetY, size.width.toFloat(), size.height.toFloat())

                            activeGizmoAxis = when {
                                startOffset.distance(screenX) < dpToPx32() -> "X"
                                startOffset.distance(screenY) < dpToPx32() -> "Y"
                                startOffset.distance(screenZ) < dpToPx32() -> "Z"
                                else -> null
                            }
                        } else {
                            activeGizmoAxis = null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val axis = activeGizmoAxis
                        val selPart = currentSelectedPart
                        if (axis != null && selPart != null && !currentIsPlaying) {
                            // Gizmo dragging: Translate / Scale / Rotate
                            val factor = 0.1f * (currentZoom / 30f)
                            when (currentActiveTool) {
                                "MOVE" -> {
                                    val delta = when (axis) {
                                        "X" -> Vector3(dragAmount.x * factor, 0f, 0f)
                                        "Y" -> Vector3(0f, -dragAmount.y * factor, 0f) // Y screen is inverted
                                        "Z" -> Vector3(0f, 0f, dragAmount.x * factor)
                                        else -> Vector3.Zero
                                    }
                                    val snappedPos = snapVector(originalPartPosition + delta, 0.5f) // snap 0.5 studs
                                    val updated = selPart.copy(
                                        position = snappedPos,
                                        currentPosition = snappedPos
                                    )
                                    viewModel.updatePartProperty(updated)
                                }
                                "SCALE" -> {
                                    val deltaSize = when (axis) {
                                        "X" -> Vector3(dragAmount.x * factor, 0f, 0f)
                                        "Y" -> Vector3(0f, -dragAmount.y * factor, 0f)
                                        "Z" -> Vector3(0f, 0f, dragAmount.x * factor)
                                        else -> Vector3.Zero
                                    }
                                    val newSize = originalPartSize + deltaSize
                                    val snappedSize = snapVector(
                                        Vector3(
                                            newSize.x.coerceAtLeast(0.5f),
                                            newSize.y.coerceAtLeast(0.5f),
                                            newSize.z.coerceAtLeast(0.5f)
                                        ), 0.5f
                                    )
                                    val updated = selPart.copy(size = snappedSize)
                                    viewModel.updatePartProperty(updated)
                                }
                                "ROTATE" -> {
                                    val angleDelta = dragAmount.x * 2f
                                    val deltaRot = when (axis) {
                                        "X" -> Vector3(angleDelta, 0f, 0f)
                                        "Y" -> Vector3(0f, angleDelta, 0f)
                                        "Z" -> Vector3(0f, 0f, angleDelta)
                                        else -> Vector3.Zero
                                    }
                                    val snappedRot = snapVector(originalPartRotation + deltaRot, 15f) // 15deg snap
                                    val updated = selPart.copy(
                                        rotation = snappedRot,
                                        currentRotation = snappedRot
                                    )
                                    viewModel.updatePartProperty(updated)
                                }
                            }
                        } else {
                            // Camera Orbital Controls
                            if (change.pressed) {
                                viewModel.rotateCamera(-dragAmount.x * 0.4f, dragAmount.y * 0.4f)
                            }
                        }
                    },
                    onDragEnd = {
                        activeGizmoAxis = null
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height

        // 1. Draw Sky background (Roblox Studio style sky)
        val skyColor = if (viewModel.activePlace.value?.templateId == "mars") {
            Color(0xFF3D190E) // Dusty red mars sky
        } else {
            Color(0xFF1E2638) // Space twilight dark theme
        }
        drawRect(skyColor)

        // 2. Draw Ground Grid (Baseplate)
        if (showGrid) {
            drawGridMesh(yaw, pitch, zoom, offsetX, offsetY, width, height, gridMaterial)
        }

        // 3. Render 3D Parts with Painters Algorithm (Depth-sorting back-to-front)
        val sortedParts = parts.sortedByDescending { p ->
            getDepthValue(p.currentPosition, yaw, pitch)
        }

        for (part in sortedParts) {
            drawPart(part, yaw, pitch, zoom, offsetX, offsetY, width, height, wireframe, selectedPart?.id == part.id, animTime)
        }

        // 4. Draw Gizmo Handles on top of selected part
        if (selectedPart != null && !isPlaying) {
            drawGizmoHandles(selectedPart, yaw, pitch, zoom, offsetX, offsetY, width, height, activeTool)
        }
    }
}

// Projection Math: Maps 3D space to 2D Canvas offset with real 3D perspective projection
fun project3D(
    pos: Vector3,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    screenWidth: Float,
    screenHeight: Float
): Offset {
    val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
    val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()

    val cosY = cos(yawRad)
    val sinY = sin(yawRad)
    val cosP = cos(pitchRad)
    val sinP = sin(pitchRad)

    // Orbit Rotations around Y then X
    val x1 = pos.x * cosY - pos.z * sinY
    val z1 = pos.x * sinY + pos.z * cosY

    val y2 = pos.y * cosP - z1 * sinP
    val z2 = pos.y * sinP + z1 * cosP

    // Real Perspective 3D projection
    val cameraDistance = 150f
    val depth = cameraDistance + z2
    val safeDepth = if (depth < 10f) 10f else depth
    val scale = (zoom * cameraDistance) / safeDepth

    // Project coordinates
    val screenX = (screenWidth / 2f) + (x1 * scale) + offsetX
    val screenY = (screenHeight / 2f) - (y2 * scale) + offsetY

    return Offset(screenX, screenY)
}

// Get relative depth coordinate along camera ray (larger values = closer)
fun getDepthValue(pos: Vector3, yaw: Float, pitch: Float): Float {
    val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
    val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()

    val sinY = sin(yawRad)
    val cosY = cos(yawRad)
    val sinP = sin(pitchRad)
    val cosP = cos(pitchRad)

    val z1 = pos.x * sinY + pos.z * cosY
    return pos.y * sinP + z1 * cosP
}

// Helper: Snap coordinates for builders precision
private fun snapVector(vec: Vector3, snap: Float): Vector3 {
    fun snapVal(v: Float) = Math.round(v / snap) * snap
    return Vector3(snapVal(vec.x), snapVal(vec.y), snapVal(vec.z))
}

private fun dpToPx() = 12f // Approximate DP scale factor
private fun dpToPx28() = 70f
private fun dpToPx32() = 80f

private fun Offset.distance(other: Offset): Float {
    val dx = this.x - other.x
    val dy = this.y - other.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

// --- Custom 3D Grids ---
private fun DrawScope.drawGridMesh(
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    studsGrid: Boolean
) {
    val gridColor = Color(0x3300C3FF)
    val centerGridColor = Color(0x66FFCC00)
    val gridSize = 30
    val step = 4f // Grid cell size in studs

    // Draw horizontal grid lines
    for (i in -gridSize..gridSize) {
        val pStartRow = Vector3(-gridSize * step, 0f, i * step)
        val pEndRow = Vector3(gridSize * step, 0f, i * step)
        val pStartCol = Vector3(i * step, 0f, -gridSize * step)
        val pEndCol = Vector3(i * step, 0f, gridSize * step)

        val sRow = project3D(pStartRow, yaw, pitch, zoom, offsetX, offsetY, width, height)
        val eRow = project3D(pEndRow, yaw, pitch, zoom, offsetX, offsetY, width, height)
        val sCol = project3D(pStartCol, yaw, pitch, zoom, offsetX, offsetY, width, height)
        val eCol = project3D(pEndCol, yaw, pitch, zoom, offsetX, offsetY, width, height)

        val color = if (i == 0) centerGridColor else gridColor
        val stroke = if (i == 0) 2.5f else 1f
        
        drawLine(color, sRow, eRow, strokeWidth = stroke)
        drawLine(color, sCol, eCol, strokeWidth = stroke)
        
        // Draw studs circles for authentic texture
        if (studsGrid && i % 2 == 0) {
            for (j in -gridSize..gridSize step 2) {
                val circlePos = Vector3(j * step, 0f, i * step)
                val scrCircle = project3D(circlePos, yaw, pitch, zoom, offsetX, offsetY, width, height)
                drawCircle(Color(0x1500E5FF), radius = zoom * 0.15f, center = scrCircle)
            }
        }
    }
}

// --- 3D Part Rendering ---
private fun DrawScope.drawPart(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    wireframe: Boolean,
    isSelected: Boolean,
    time: Float
) {
    val color = try {
        Color(android.graphics.Color.parseColor(part.colorHex))
    } catch (e: Exception) {
        Color(0xFFCCCCCC) // fallback for invalid hex
    }
    val opacity = if (part.material == Part.MATERIAL_GLASS) 0.45f else 1f

    when (part.shape) {
        Part.SHAPE_BLOCK, Part.SHAPE_SPAWN_LOCATION -> {
            drawBlock3D(part, yaw, pitch, zoom, offsetX, offsetY, width, height, color, opacity, wireframe, isSelected)
        }
        Part.SHAPE_WEDGE -> {
            drawWedge3D(part, yaw, pitch, zoom, offsetX, offsetY, width, height, color, opacity, wireframe, isSelected)
        }
        Part.SHAPE_SPHERE -> {
            drawSphere3D(part, yaw, pitch, zoom, offsetX, offsetY, width, height, color, opacity, wireframe, isSelected)
        }
        Part.SHAPE_CYLINDER -> {
            drawCylinder3D(part, yaw, pitch, zoom, offsetX, offsetY, width, height, color, opacity, wireframe, isSelected)
        }
    }

    // Render visual effects overlay
    if (part.effect != Part.EFFECT_NONE) {
        drawParticleEffects(part, yaw, pitch, zoom, offsetX, offsetY, width, height, time)
    }
}

// 3D Block Rendering with flat shading on individual faces
private fun DrawScope.drawBlock3D(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    baseColor: Color,
    opacity: Float,
    wireframe: Boolean,
    isSelected: Boolean
) {
    val pos = part.currentPosition
    val sizeVec = part.size
    val rot = part.currentRotation

    // Calculate local corners of the block relative to its center, with rotation
    val halfX = sizeVec.x / 2
    val halfY = sizeVec.y / 2
    val halfZ = sizeVec.z / 2

    val localVertices = listOf(
        Vector3(-halfX, -halfY, -halfZ),
        Vector3(halfX, -halfY, -halfZ),
        Vector3(halfX, halfY, -halfZ),
        Vector3(-halfX, halfY, -halfZ),
        Vector3(-halfX, -halfY, halfZ),
        Vector3(halfX, -halfY, halfZ),
        Vector3(halfX, halfY, halfZ),
        Vector3(-halfX, halfY, halfZ)
    )

    // Rotate vertices around block local center
    val rotatedVertices = localVertices.map { v ->
        pos + rotateVertex(v, rot)
    }

    // Project corners to screen offsets
    val scr = rotatedVertices.map { v ->
        project3D(v, yaw, pitch, zoom, offsetX, offsetY, width, height)
    }

    // Define 6 faces: corner indexes back-to-front depth sorted
    // Shading multiplier based on surface light facing
    val faces = listOf(
        Face(listOf(0, 1, 2, 3), 0.85f), // Front
        Face(listOf(4, 5, 6, 7), 0.75f), // Back
        Face(listOf(0, 3, 7, 4), 0.65f), // Left
        Face(listOf(1, 2, 6, 5), 0.90f), // Right
        Face(listOf(3, 2, 6, 7), 1.05f), // Top (brightest)
        Face(listOf(0, 1, 5, 4), 0.50f)  // Bottom (darkest)
    )

    // Material Highlight (Neon glow, metal shine, etc.)
    val neonGlow = part.material == Part.MATERIAL_NEON

    // Sort block faces by depth in real-time to avoid internal overlap
    val sortedFaces = faces.sortedByDescending { f ->
        val faceCenter = f.indices.map { rotatedVertices[it] }.reduce { a, b -> a + b } / f.indices.size.toFloat()
        getDepthValue(faceCenter, yaw, pitch)
    }

    for (face in sortedFaces) {
        val path = Path().apply {
            moveTo(scr[face.indices[0]].x, scr[face.indices[0]].y)
            lineTo(scr[face.indices[1]].x, scr[face.indices[1]].y)
            lineTo(scr[face.indices[2]].x, scr[face.indices[2]].y)
            lineTo(scr[face.indices[3]].x, scr[face.indices[3]].y)
            close()
        }

        val shadedColor = if (neonGlow) {
            baseColor.copy(alpha = opacity) // Neon has full emission, no shadow
        } else {
            Color(
                red = (baseColor.red * face.shade).coerceIn(0f, 1f),
                green = (baseColor.green * face.shade).coerceIn(0f, 1f),
                blue = (baseColor.blue * face.shade).coerceIn(0f, 1f),
                alpha = opacity
            )
        }

        if (!wireframe) {
            drawPath(path, shadedColor)
        }
        
        // Draw wood or brick patterns on faces for premium visuals
        if (part.material == Part.MATERIAL_BRICK && !wireframe && !neonGlow) {
            drawPath(path, Color(0x22000000), style = Stroke(width = 1.5f))
        }

        // Draw outline
        val outlineColor = if (isSelected) Color(0xFF00C8FF) else Color(0x40000000)
        val outlineWidth = if (isSelected) 3f else 1f
        drawPath(path, outlineColor, style = Stroke(width = outlineWidth))
    }

    // Spawn point circular logo decal on Top face
    if (part.shape == Part.SHAPE_SPAWN_LOCATION && !wireframe) {
        // Center of top face: average corners 3, 2, 6, 7
        val topCenterPos = (rotatedVertices[3] + rotatedVertices[2] + rotatedVertices[6] + rotatedVertices[7]) / 4f
        val topCenterScr = project3D(topCenterPos, yaw, pitch, zoom, offsetX, offsetY, width, height)
        
        drawCircle(
            color = Color.White,
            radius = zoom * 0.5f,
            center = topCenterScr,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color(0xFFFFD700),
            radius = zoom * 0.25f,
            center = topCenterScr
        )
    }
}

// 3D Wedge Rendering (Prism with triangular sides)
private fun DrawScope.drawWedge3D(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    baseColor: Color,
    opacity: Float,
    wireframe: Boolean,
    isSelected: Boolean
) {
    val pos = part.currentPosition
    val s = part.size
    val rot = part.currentRotation

    val hx = s.x / 2
    val hy = s.y / 2
    val hz = s.z / 2

    // Wedge corner geometry
    val localVertices = listOf(
        Vector3(-hx, -hy, -hz), // 0: Bottom-left back
        Vector3(hx, -hy, -hz),  // 1: Bottom-right back
        Vector3(hx, hy, -hz),   // 2: Top-right back (Peak)
        Vector3(-hx, hy, -hz),  // 3: Top-left back (Peak)
        Vector3(-hx, -hy, hz),  // 4: Bottom-left front
        Vector3(hx, -hy, hz)    // 5: Bottom-right front
    )

    val rotatedVertices = localVertices.map { v ->
        pos + rotateVertex(v, rot)
    }

    val scr = rotatedVertices.map { v ->
        project3D(v, yaw, pitch, zoom, offsetX, offsetY, width, height)
    }

    // 5 Faces: indices + shading
    val faces = listOf(
        WedgeFace(listOf(0, 1, 2, 3), 0.70f), // Flat vertical back wall
        WedgeFace(listOf(0, 4, 5, 1), 0.50f), // Bottom floor
        WedgeFace(listOf(3, 2, 5, 4), 1.05f), // Slanted top slide
        WedgeFace(listOf(0, 3, 4), 0.60f),    // Left triangle
        WedgeFace(listOf(1, 2, 5), 0.90f)     // Right triangle
    )

    val sortedFaces = faces.sortedByDescending { f ->
        val faceCenter = f.indices.map { rotatedVertices[it] }.reduce { a, b -> a + b } / f.indices.size.toFloat()
        getDepthValue(faceCenter, yaw, pitch)
    }

    for (face in sortedFaces) {
        val path = Path().apply {
            moveTo(scr[face.indices[0]].x, scr[face.indices[0]].y)
            for (i in 1 until face.indices.size) {
                lineTo(scr[face.indices[i]].x, scr[face.indices[i]].y)
            }
            close()
        }

        val shadedColor = Color(
            red = (baseColor.red * face.shade).coerceIn(0f, 1f),
            green = (baseColor.green * face.shade).coerceIn(0f, 1f),
            blue = (baseColor.blue * face.shade).coerceIn(0f, 1f),
            alpha = opacity
        )

        if (!wireframe) {
            drawPath(path, shadedColor)
        }
        val outlineColor = if (isSelected) Color(0xFF00C8FF) else Color(0x30000000)
        val strokeW = if (isSelected) 3f else 1f
        drawPath(path, outlineColor, style = Stroke(width = strokeW))
    }
}

// Sphere rendering utilizing depth gradient shade layers
private fun DrawScope.drawSphere3D(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    baseColor: Color,
    opacity: Float,
    wireframe: Boolean,
    isSelected: Boolean
) {
    val scrCenter = project3D(part.currentPosition, yaw, pitch, zoom, offsetX, offsetY, width, height)
    val depthVal = getDepthValue(part.currentPosition, yaw, pitch)
    val cameraDistance = 150f
    val depth = cameraDistance + depthVal
    val safeDepth = if (depth < 10f) 10f else depth
    val perspectiveScale = (zoom * cameraDistance) / safeDepth
    val radius = (part.size.x / 2f) * perspectiveScale

    if (radius <= 0f) return

    if (!wireframe) {
        // Simulating lighting highlight offset
        val highlightOffset = Offset(-radius * 0.25f, -radius * 0.25f)
        
        // Draw primary body
        drawCircle(
            color = baseColor.copy(alpha = opacity),
            radius = radius,
            center = scrCenter
        )

        // Shade overlay
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f),
            radius = radius,
            center = scrCenter + Offset(radius * 0.15f, radius * 0.15f)
        )

        // Gloss highlights
        drawCircle(
            color = Color.White.copy(alpha = 0.4f),
            radius = radius * 0.35f,
            center = scrCenter + highlightOffset
        )
    }

    // Grid wire rings for spheres
    val outlineColor = if (isSelected) Color(0xFF00C8FF) else Color(0x40000000)
    val strokeW = if (isSelected) 3f else 1f
    
    drawCircle(
        color = outlineColor,
        radius = radius,
        center = scrCenter,
        style = Stroke(width = strokeW)
    )
    
    if (isSelected || wireframe) {
        // Draw vertical/horizontal wire equator
        drawOval(
            color = outlineColor,
            topLeft = Offset(scrCenter.x - radius, scrCenter.y - radius * 0.3f),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 0.6f),
            style = Stroke(width = 1f)
        )
    }
}

// Cylinder: Ellipses joined by rectangular tube
private fun DrawScope.drawCylinder3D(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    baseColor: Color,
    opacity: Float,
    wireframe: Boolean,
    isSelected: Boolean
) {
    val pos = part.currentPosition
    val s = part.size
    val rot = part.currentRotation

    val hx = s.x / 2
    val hy = s.y / 2
    val hz = s.z / 2

    // Cylinder orientation cap vertices
    val localVertices = listOf(
        // Top cap circle approximated by 4 quadrants
        Vector3(-hx, hy, -hz),
        Vector3(hx, hy, -hz),
        Vector3(hx, hy, hz),
        Vector3(-hx, hy, hz),
        // Bottom cap
        Vector3(-hx, -hy, -hz),
        Vector3(hx, -hy, -hz),
        Vector3(hx, -hy, hz),
        Vector3(-hx, -hy, hz)
    )

    val rotatedVertices = localVertices.map { v ->
        pos + rotateVertex(v, rot)
    }

    val scr = rotatedVertices.map { v ->
        project3D(v, yaw, pitch, zoom, offsetX, offsetY, width, height)
    }

    // Draw solid hulls
    if (!wireframe) {
        val pathCenter = Path().apply {
            moveTo(scr[0].x, scr[0].y)
            lineTo(scr[1].x, scr[1].y)
            lineTo(scr[5].x, scr[5].y)
            lineTo(scr[4].x, scr[4].y)
            close()
        }
        drawPath(pathCenter, baseColor.copy(alpha = opacity))
        drawPath(pathCenter, Color.Black.copy(alpha = 0.15f)) // shadow

        val pathSide = Path().apply {
            moveTo(scr[1].x, scr[1].y)
            lineTo(scr[2].x, scr[2].y)
            lineTo(scr[6].x, scr[6].y)
            lineTo(scr[5].x, scr[5].y)
            close()
        }
        drawPath(pathSide, baseColor.copy(alpha = opacity))
        drawPath(pathSide, Color.White.copy(alpha = 0.15f)) // reflection highlighted
    }

    // Outer outlines
    val outlineColor = if (isSelected) Color(0xFF00C8FF) else Color(0x35000000)
    val strokeW = if (isSelected) 3f else 1.5f

    val topFacePath = Path().apply {
        moveTo(scr[0].x, scr[0].y)
        lineTo(scr[1].x, scr[1].y)
        lineTo(scr[2].x, scr[2].y)
        lineTo(scr[3].x, scr[3].y)
        close()
    }
    
    val bottomFacePath = Path().apply {
        moveTo(scr[4].x, scr[4].y)
        lineTo(scr[5].x, scr[5].y)
        lineTo(scr[6].x, scr[6].y)
        lineTo(scr[7].x, scr[7].y)
        close()
    }

    if (!wireframe) {
        drawPath(topFacePath, baseColor)
    }
    drawPath(topFacePath, outlineColor, style = Stroke(strokeW))
    drawPath(bottomFacePath, outlineColor, style = Stroke(strokeW))

    // Joining struts
    drawLine(outlineColor, scr[0], scr[4], strokeWidth = strokeW)
    drawLine(outlineColor, scr[2], scr[6], strokeWidth = strokeW)
}

// Particle system renderer overlay
private fun DrawScope.drawParticleEffects(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    time: Float
) {
    val origin = part.currentPosition
    val screenOrigin = project3D(origin, yaw, pitch, zoom, offsetX, offsetY, width, height)
    val depthVal = getDepthValue(origin, yaw, pitch)
    val cameraDistance = 150f
    val depth = cameraDistance + depthVal
    val safeDepth = if (depth < 10f) 10f else depth
    val perspectiveScale = (zoom * cameraDistance) / safeDepth

    when (part.effect) {
        Part.EFFECT_FIRE -> {
            // Animate multiple flame particles rising
            for (i in 0..12) {
                val seed = i * 17.5f
                val life = (time + seed) % 1.5f
                val offset = Vector3(
                    sin(seed + time * 4f) * 0.8f,
                    life * 4.5f,
                    cos(seed + time * 3f) * 0.8f
                )
                val pPos = origin + offset
                val pScr = project3D(pPos, yaw, pitch, zoom, offsetX, offsetY, width, height)
                
                val pSize = (1.5f - life) * perspectiveScale * 0.25f
                if (pSize > 0f) {
                    val color = if (life < 0.5f) Color(0xFFFF5722) else if (life < 1.0f) Color(0xFFFF9800) else Color(0xFFFFEB3B)
                    drawCircle(color.copy(alpha = 0.8f), radius = pSize, center = pScr)
                }
            }
        }
        Part.EFFECT_SMOKE -> {
            // Rising puffy gray smoke
            for (i in 0..8) {
                val seed = i * 29.3f
                val life = (time + seed) % 2.5f
                val offset = Vector3(
                    sin(seed + time * 1.5f) * 1.5f,
                    life * 6.0f,
                    cos(seed + time * 1.2f) * 1.5f
                )
                val pScr = project3D(origin + offset, yaw, pitch, zoom, offsetX, offsetY, width, height)
                val pSize = (0.5f + life * 0.6f) * perspectiveScale * 0.3f
                drawCircle(Color.LightGray.copy(alpha = (1f - life/2.5f) * 0.35f), radius = pSize, center = pScr)
            }
        }
        Part.EFFECT_SPARKLES -> {
            // Glowing starbursts
            for (i in 0..6) {
                val seed = i * 53.1f
                val pulse = (time * 5f + seed) % 1f
                val angle = seed + time
                val dist = (seed % 2.5f) + 1.5f
                val offset = Vector3(
                    sin(angle) * dist,
                    cos(angle * 1.5f) * dist + 1f,
                    cos(angle) * dist
                )
                val pScr = project3D(origin + offset, yaw, pitch, zoom, offsetX, offsetY, width, height)
                val size = (1f - pulse) * perspectiveScale * 0.2f
                
                // Draw a cross spark
                if (size > 0f) {
                    val spColor = Color(0xFFFFEB3B)
                    drawLine(spColor, pScr - Offset(size, 0f), pScr + Offset(size, 0f), strokeWidth = 3f)
                    drawLine(spColor, pScr - Offset(0f, size), pScr + Offset(0f, size), strokeWidth = 3f)
                }
            }
        }
        Part.EFFECT_POINTLIGHT -> {
            // Soft gradient highlight surrounding the part
            drawCircle(
                color = Color(0x20FFE57F),
                radius = perspectiveScale * 4.5f,
                center = screenOrigin
            )
            drawCircle(
                color = Color(0x35FFFFFF),
                radius = perspectiveScale * 1.5f,
                center = screenOrigin
            )
        }
    }
}

// 3D Axis Handles for manipulating selection
private fun DrawScope.drawGizmoHandles(
    part: Part,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    activeTool: String
) {
    val origin = part.currentPosition
    val screenOrigin = project3D(origin, yaw, pitch, zoom, offsetX, offsetY, width, height)

    val gizmoLength = 4.0f // Axis length in studs

    // X Axis (Red)
    val tipX = origin + Vector3(gizmoLength, 0f, 0f)
    val screenX = project3D(tipX, yaw, pitch, zoom, offsetX, offsetY, width, height)
    drawLine(Color(0xFFFF2D55), screenOrigin, screenX, strokeWidth = 5f)
    drawGizmoTip(screenX, Color(0xFFFF2D55), activeTool)

    // Y Axis (Green)
    val tipY = origin + Vector3(0f, gizmoLength, 0f)
    val screenY = project3D(tipY, yaw, pitch, zoom, offsetX, offsetY, width, height)
    drawLine(Color(0xFF4CD964), screenOrigin, screenY, strokeWidth = 5f)
    drawGizmoTip(screenY, Color(0xFF4CD964), activeTool)

    // Z Axis (Blue)
    val tipZ = origin + Vector3(0f, 0f, gizmoLength)
    val screenZ = project3D(tipZ, yaw, pitch, zoom, offsetX, offsetY, width, height)
    drawLine(Color(0xFF007AFF), screenOrigin, screenZ, strokeWidth = 5f)
    drawGizmoTip(screenZ, Color(0xFF007AFF), activeTool)
}

private fun DrawScope.drawGizmoTip(center: Offset, color: Color, activeTool: String) {
    when (activeTool) {
        "MOVE" -> {
            // Draw spherical handle
            drawCircle(color, radius = 12f, center = center)
            drawCircle(Color.White, radius = 6f, center = center)
        }
        "SCALE" -> {
            // Draw cube handle
            drawRect(
                color = color,
                topLeft = center - Offset(10f, 10f),
                size = androidx.compose.ui.geometry.Size(20f, 20f)
            )
            drawRect(
                color = Color.White,
                topLeft = center - Offset(5f, 5f),
                size = androidx.compose.ui.geometry.Size(10f, 10f)
            )
        }
        "ROTATE" -> {
            // Draw double rings
            drawCircle(color, radius = 14f, center = center, style = Stroke(width = 4f))
            drawCircle(Color.White, radius = 8f, center = center)
        }
    }
}

// 3D Vertex Pitch/Yaw/Roll rotation around coordinate center
private fun rotateVertex(v: Vector3, rot: Vector3): Vector3 {
    val rx = Math.toRadians(rot.x.toDouble()).toFloat()
    val ry = Math.toRadians(rot.y.toDouble()).toFloat()
    val rz = Math.toRadians(rot.z.toDouble()).toFloat()

    // Rotate around X axis
    var y1 = v.y * cos(rx) - v.z * sin(rx)
    var z1 = v.y * sin(rx) + v.z * cos(rx)
    var x1 = v.x

    // Rotate around Y axis
    val x2 = x1 * cos(ry) + z1 * sin(ry)
    val z2 = -x1 * sin(ry) + z1 * cos(ry)
    val y2 = y1

    // Rotate around Z axis
    val x3 = x2 * cos(rz) - y2 * sin(rz)
    val y3 = x2 * sin(rz) + y2 * cos(rz)
    val z3 = z2

    return Vector3(x3, y3, z3)
}

// Simple Helper data classes
private data class Face(val indices: List<Int>, val shade: Float)
private data class WedgeFace(val indices: List<Int>, val shade: Float)
