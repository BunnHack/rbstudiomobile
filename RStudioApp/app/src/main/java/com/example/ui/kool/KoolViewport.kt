package com.example.ui.kool

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.models.Part
import com.example.viewmodels.StudioViewModel
import de.fabmax.kool.KoolContext
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.createDefaultKoolContext
import de.fabmax.kool.platform.KoolContextAndroid

/**
 * Real GPU 3D viewport backed by the kool engine (OpenGL ES 3 on Android).
 *
 * kool's KoolContext is a process-wide singleton (a second one throws). The embedded
 * GLSurfaceView is created once with the context and re-attached on every viewport
 * session. Rendering survives tab switches because of two patches in kool itself:
 *  - KoolSurfaceView.preserveEGLContextOnPause = false (detach destroys the EGL
 *    context, so re-attach always recreates it)
 *  - RenderBackendGlImpl.onSurfaceCreated always re-initializes GL state (the old
 *    isGlContextInitialized guard skipped re-init, so the second surface rendered
 *    with dead GL object handles -> black viewport).
 */
@Composable
fun KoolViewport(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val parts by viewModel.parts.collectAsState()
    val yaw by viewModel.cameraYaw.collectAsState()
    val pitch by viewModel.cameraPitch.collectAsState()
    val zoom by viewModel.cameraZoom.collectAsState()
    val activePlace by viewModel.activePlace.collectAsState()
    val selectedPart by viewModel.selectedPart.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val nodes by viewModel.explorerNodes.collectAsState()
    val roblosecurityCookie by viewModel.roblosecurityCookie.collectAsState()
    val decals = remember(nodes, parts) { buildRenderableDecals(nodes, parts) }

    var bridge by remember { mutableStateOf<KoolSceneBridge?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val activity = unwrapActivity(ctx)
                ?: error("KoolViewport requires an Activity context")
            // 1) Boot kool once per process. On a reused context (returning from a
            //    script tab / reopening a place), scenes of previously disposed
            //    compositions may still be staged — purge them so only this
            //    composition's scene is ever rendered/input-routed. Without this the
            //    very first detached scene (created before the input-leak fix) keeps
            //    rendering forever and its dead surface shows as a black screen.
            val kctx = KoolSystem.getContextOrNull() as? KoolContextAndroid
                ?: activity.createDefaultKoolContext()
            kctx.scenes.clear()
            // 2) Build this composition's scene bridge and register its scene.
            val b = KoolSceneBridge(
                onPartTransformed = { updated -> viewModel.updatePartProperty(updated) },
                onPartPicked = { picked -> viewModel.selectPart(picked) }
            )
            kctx.scenes += b.scene
            bridge = b
            b.setRoblosecurityCookie(roblosecurityCookie)
            b.updateCamera(yaw, pitch, zoom)
            b.setGizmoMode(activeTool)
            b.syncParts(parts)
            // 3) Embed kool's surface view, sized to fill the Compose layout. The view
            //    is created once per KoolContext and lives as long as the context; tab
            //    switches only detach/reattach it, and with the patched
            //    preserveEGLContextOnPause=false + always-reinit onSurfaceCreated, the
            //    GL state is rebuilt on every re-attach.
            kctx.surfaceView.apply {
                (parent as? ViewGroup)?.removeView(this)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            // Sync ViewModel state into the GPU scene on recomposition.
            bridge?.let {
                it.syncParts(parts)
                it.syncDecals(decals)
                it.setRoblosecurityCookie(roblosecurityCookie)
                it.syncSelection(selectedPart)
            }
            // If AndroidView recycled this view after onRelease (bridge was torn down),
            // re-register a fresh scene bridge — otherwise zero scenes render (black).
            if (bridge == null) {
                val kctx = KoolSystem.getContextOrNull() as? KoolContextAndroid ?: return@AndroidView
                kctx.scenes.clear()
                (view.parent as? ViewGroup)?.removeView(view)
                val b = KoolSceneBridge(
                    onPartTransformed = { updated -> viewModel.updatePartProperty(updated) },
                    onPartPicked = { picked -> viewModel.selectPart(picked) }
                )
                kctx.scenes += b.scene
                bridge = b
                b.setRoblosecurityCookie(roblosecurityCookie)
                b.updateCamera(yaw, pitch, zoom)
                b.setGizmoMode(activeTool)
                b.syncParts(parts)
                b.syncDecals(decals)
                b.syncSelection(selectedPart)
                (view as? GLSurfaceView)?.onResume()
            }
        },
        onRelease = { view ->
            // Leaving the composition: drop this composition's scene and pause the GL
            // thread BEFORE the view detaches, so it never draws into a dead surface.
            bridge?.let { b ->
                (KoolSystem.getContextOrNull() as? KoolContextAndroid)?.scenes?.minusAssign(b.scene)
                b.dispose()
            }
            bridge = null
            (view as? GLSurfaceView)?.onPause()
            (view.parent as? ViewGroup)?.removeView(view)
        }
    )

    // Push camera only when the ViewModel camera values change — NOT on every
    // recomposition. This preserves kool's native touch-orbit / pinch-zoom between
    // part inserts and other state changes that don't touch the camera.
    LaunchedEffect(yaw, pitch, zoom) {
        bridge?.updateCamera(yaw, pitch, zoom)
    }

    // Switch the gizmo mode when the active tool changes (MOVE / ROTATE / SCALE / SELECT).
    LaunchedEffect(activeTool) {
        bridge?.setGizmoMode(activeTool)
    }

    // Hook kept for future sky/background tinting keyed on the active place template.
    LaunchedEffect(activePlace?.templateId) {
        // kool clears with the scene's configured background; nothing extra for now.
    }

    // Wire kool lifecycle (resume / pause) to the Compose lifecycle owner.
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                (KoolSystem.getContextOrNull() as? KoolContextAndroid)?.onResume()
            }
            override fun onPause(owner: LifecycleOwner) {
                (KoolSystem.getContextOrNull() as? KoolContextAndroid)?.onPause()
            }
            override fun onStop(owner: LifecycleOwner) {
                (KoolSystem.getContextOrNull() as? KoolContextAndroid)?.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

/** Unwrap a ContextWrapper (e.g. ContextThemeWrapper) to find the base Activity. */
private fun unwrapActivity(ctx: Context): Activity? {
    var c: Context = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}
