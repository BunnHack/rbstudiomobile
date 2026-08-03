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
 * kool's KoolContext is a process-wide singleton (a second one throws), but its
 * embedded GLSurfaceView CANNOT be re-parented: once it is detached from the window
 * (tab switch / place switch), GLSurfaceView destroys its EGL surface and kool keeps
 * rendering into the dead one — the result is a black viewport that still accepts
 * clicks. So the surface view itself is owned by this holder, and a FRESH
 * GLSurfaceView is created every time the viewport enters the composition:
 *
 *   KoolContextAndroid (process singleton, survives everything)
 *     └─ backend (GLSurfaceView.Renderer, survives)
 *     └─ window.surfaceView  ← replaced per viewport session via reflection
 *
 * Each new surface view gets the SAME backend renderer, so when Android creates the
 * new EGL surface, onSurfaceCreated/onDrawFrame resume on the live context. GL object
 * handles (VBOs, textures) are EGL-context-local and are recreated by kool because
 * the new surface produces a new EGL context on this device path.
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
            // 1) Boot kool once per process.
            val kctx = KoolSystem.getContextOrNull() as? KoolContextAndroid
                ?: activity.createDefaultKoolContext()
            // 2) Fresh surface view for this viewport session (see class doc). The old
            //    one is defunct after detach, so we never re-parent it.
            val surfaceView = newKoolSurfaceView(kctx, activity)
            // 3) Build this composition's scene bridge and register its scene.
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
            // 4) Embed, sized to fill the Compose layout.
            surfaceView.apply {
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

/**
 * Creates a fresh kool surface view wired to the context's existing GL renderer and
 * points the context's window at it. kool's AndroidWindow holds `surfaceView` in a
 * public `val`, so we swap it via reflection — the window/backend/context survive,
 * only the view is replaced. The renderer is set again by hand because kool only does
 * it once in AndroidWindow.init.
 */
private fun newKoolSurfaceView(kctx: KoolContextAndroid, activity: Activity): GLSurfaceView {
    val view = de.fabmax.kool.platform.KoolSurfaceView(activity.applicationContext)
    view.setRenderer(kctx.backend)
    runCatching {
        val windowField = KoolContextAndroid::class.java.getDeclaredField("window")
        windowField.isAccessible = true
        val window = windowField.get(kctx)
        val surfaceField = window.javaClass.getDeclaredField("surfaceView")
        surfaceField.isAccessible = true
        surfaceField.set(window, view)
    }
    return view
}
