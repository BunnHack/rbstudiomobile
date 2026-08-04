package com.example.ui.viewport

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.models.Part
import com.example.viewmodels.StudioViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
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
 * Each [Part] becomes a primitive node (cube / sphere / cylinder) declared inside the
 * SceneView content DSL. Tapping a node selects the backing part. The camera is an
 * orbit rig driven by the ViewModel's yaw/pitch/zoom.
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

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val cameraNode = rememberCameraNode(engine)

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
        onTouchEvent = { event, hitResult ->
            if (event.action == MotionEvent.ACTION_UP) {
                val partId = hitResult?.node?.name?.removePrefix("part:")
                viewModel.selectPart(parts.firstOrNull { it.id == partId })
            }
            true
        }
    ) {
        parts.forEach { part ->
            val material = remember(part.colorHex, part.transparency) {
                materialLoader.createColorInstance(
                    dev.romainguy.kotlin.math.Float4(
                        colorR(part.colorHex), colorG(part.colorHex), colorB(part.colorHex),
                        1f - part.transparency.coerceIn(0f, 1f)
                    )
                )
            }
            when (part.shape) {
                Part.SHAPE_SPHERE -> SphereNode(
                    radius = 0.5f,
                    materialInstance = material,
                    position = Position(part.position.x, part.position.y, part.position.z),
                    rotation = Rotation(part.rotation.x, part.rotation.y, part.rotation.z),
                    scale = Size(part.size.x, part.size.y, part.size.z),
                    apply = { name = "part:${part.id}" }
                )
                Part.SHAPE_CYLINDER -> CylinderNode(
                    radius = 0.5f,
                    height = 1f,
                    materialInstance = material,
                    position = Position(part.position.x, part.position.y, part.position.z),
                    rotation = Rotation(part.rotation.x, part.rotation.y, part.rotation.z),
                    scale = Size(part.size.x, part.size.y, part.size.z),
                    apply = { name = "part:${part.id}" }
                )
                else -> CubeNode(
                    size = Size(1f, 1f, 1f),
                    materialInstance = material,
                    position = Position(part.position.x, part.position.y, part.position.z),
                    rotation = Rotation(part.rotation.x, part.rotation.y, part.rotation.z),
                    scale = Size(part.size.x, part.size.y, part.size.z),
                    apply = { name = "part:${part.id}" }
                )
            }
        }
    }
}

private fun colorR(hex: String): Float = ((parseHex(hex) shr 16) and 0xFF) / 255f
private fun colorG(hex: String): Float = ((parseHex(hex) shr 8) and 0xFF) / 255f
private fun colorB(hex: String): Float = (parseHex(hex) and 0xFF) / 255f

private fun parseHex(hex: String): Int {
    val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
    return runCatching { rgb.toInt(16) }.getOrDefault(0xCCCCCC)
}
