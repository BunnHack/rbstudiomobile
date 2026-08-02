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

    // Bridge + context are created together inside the factory (correct init order).
    var koolCtx by remember { mutableStateOf<KoolContextAndroid?>(null) }
    var bridge by remember { mutableStateOf<KoolSceneBridge?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val activity = unwrapActivity(ctx)
                ?: error("KoolViewport requires an Activity context")
            // 1) Boot kool: createDefaultKoolContext() runs KoolSystem.initialize(config)
            //    BEFORE constructing the context — must happen before any Scene.
            val kctx = activity.createDefaultKoolContext()
            // 2) Now KoolSystem is initialized; build the scene bridge (Scene DSL safe).
            //    The gizmo writeback updates the ViewModel part transform.
            val b = KoolSceneBridge(
                onPartTransformed = { updated -> viewModel.updatePartProperty(updated) },
                onPartPicked = { picked -> viewModel.selectPart(picked) }
            )
            kctx.scenes += b.scene
            kctx.run()
            koolCtx = kctx
            bridge = b
            b.setRoblosecurityCookie(roblosecurityCookie)
            b.updateCamera(yaw, pitch, zoom)
            b.setGizmoMode(activeTool)
            // 3) Embed kool's surface view, sized to fill the Compose layout.
            kctx.surfaceView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = {
            // Sync the parts list into the GPU scene every recomposition, then keep the
            // gizmo bound to the currently selected part's (possibly rebuilt) mesh.
            bridge?.let {
                it.syncParts(parts)
                it.syncDecals(decals)
                it.setRoblosecurityCookie(roblosecurityCookie)
                it.syncSelection(selectedPart)
            }
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

    // Wire kool lifecycle (resume / pause / destroy) to the Compose lifecycle owner.
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { koolCtx?.onResume() }
            override fun onPause(owner: LifecycleOwner) { koolCtx?.onPause() }
            override fun onStop(owner: LifecycleOwner) { koolCtx?.onPause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bridge?.dispose()
            koolCtx?.onDestroy()
            koolCtx = null
            bridge = null
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
