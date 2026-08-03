package com.example.ui.kool

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.createDefaultKoolContext
import de.fabmax.kool.platform.KoolContextAndroid

/**
 * Real GPU 3D viewport backed by the kool engine (OpenGL ES 3 on Android).
 *
 * Embeds kool's [KoolContextAndroid] surface view via [AndroidView]. kool must be
 * initialized ([KoolSystem.initialize]) BEFORE any [Scene] is constructed (the
 * scene DSL accesses KoolSystem.config), so the [KoolSceneBridge] — which builds the
 * scene in its constructor — is created inside the [AndroidView] factory, right after
 * [createDefaultKoolContext] runs the initialization.
 *
 * kool drives its own render loop; we push ViewModel state only when it *changes*
 * (via [LaunchedEffect]) so that kool's native touch-orbit / pinch-zoom is not reset
 * on every recomposition (e.g. when inserting a part). A transform gizmo is wired to
 * the ViewModel's selection and active tool (MOVE / ROTATE / SCALE); dragging it
 * writes the new transform back into the ViewModel.
 *
 * Material 3 UI (ribbon, explorer, properties) lives outside this composable.
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

    // The kool context is a process-wide singleton (KoolSystem throws if a second one
    // is created), so it survives tab switches / place switches. Each KoolViewport
    // composition gets its own SceneBridge (its own Scene), while the context and its
    // surface view are reused. The old bridge's scene must be removed from the context
    // when this composable leaves the composition.
    var bridge by remember { mutableStateOf<KoolSceneBridge?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val activity = unwrapActivity(ctx)
                ?: error("KoolViewport requires an Activity context")
            // 1) Boot kool once per process; reuse the existing context afterwards.
            //    createDefaultKoolContext() runs KoolSystem.initialize(config) BEFORE
            //    constructing the context — must happen before any Scene.
            val kctx = KoolSystem.getContextOrNull() as? KoolContextAndroid
                ?: activity.createDefaultKoolContext()
            // 2) KoolSystem is initialized; build this composition's scene bridge.
            //    The gizmo writeback updates the ViewModel part transform.
            val b = KoolSceneBridge(
                onPartTransformed = { updated -> viewModel.updatePartProperty(updated) },
                onPartPicked = { picked -> viewModel.selectPart(picked) }
            )
            kctx.scenes += b.scene
            kctx.run()
            bridge = b
            b.setRoblosecurityCookie(roblosecurityCookie)
            b.updateCamera(yaw, pitch, zoom)
            b.setGizmoMode(activeTool)
            b.syncParts(parts)
            // 3) Embed kool's surface view, sized to fill the Compose layout.
            kctx.surfaceView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            // Sync the parts list into the GPU scene every recomposition, then keep the
            // gizmo bound to the currently selected part's (possibly rebuilt) mesh.
            bridge?.let {
                it.syncParts(parts)
                it.syncDecals(decals)
                it.setRoblosecurityCookie(roblosecurityCookie)
                it.syncSelection(selectedPart)
            }
            // When this composable re-enters the composition after onRelease (tab
            // switch / place switch), AndroidView recycles the view returned by the
            // previous factory run: no new factory call happens, but this update block
            // DOES run. The surface view must be re-attached and a fresh scene bridge
            // must be registered, otherwise the context renders zero scenes -> black.
            if (bridge == null) {
                val kctx = KoolSystem.getContextOrNull() as? KoolContextAndroid ?: return@AndroidView
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
            }
        },
        onRelease = { view ->
            // AndroidView calls this when the composable leaves the composition. kool
            // keeps rendering in the background, so drop this composition's scene and
            // detach the surface view from its (dying) parent to prevent the render
            // thread from drawing into a destroyed surface (manifests as black screen
            // on return and click-through to invisible meshes).
            bridge?.let { b ->
                (KoolSystem.getContextOrNull() as? KoolContextAndroid)?.scenes?.minusAssign(b.scene)
                b.dispose()
            }
            bridge = null
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

    // Selection is synced each recomposition in the `update` block above (syncSelection),
    // which keeps the gizmo bound to the selected part's mesh after rebuilds.

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
            // Scene teardown happens in AndroidView.onRelease (called on the main
            // thread during composition disposal). The kool context itself is a
            // process singleton and must stay alive: creating a second
            // KoolContextAndroid throws "KoolContext was already created".
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
