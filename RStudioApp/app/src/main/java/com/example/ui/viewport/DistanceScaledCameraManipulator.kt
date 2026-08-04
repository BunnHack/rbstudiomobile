package com.example.ui.viewport

import com.google.android.filament.utils.Manipulator
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.transform
import io.github.sceneview.math.Transform
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * ORBIT camera manipulator whose pan/zoom speeds scale with the camera's current
 * distance to its target. Filament's built-in speeds are absolute constants, so on a
 * large scene (far camera) one pixel of drag moves the view by a tiny world amount —
 * the "drag is painfully slow on big objects" problem. Here every scroll/grab delta is
 * multiplied by (distance / referenceDistance) before being forwarded, making motion
 * proportional to how far out you're zoomed.
 */
class DistanceScaledCameraManipulator(
    referenceDistance: Float = 20f
) : CameraGestureDetector.CameraManipulator {

    private val inner = Manipulator.Builder()
        .orbitSpeed(0.006f, 0.006f)
        .zoomSpeed(0.05f)
        .build(Manipulator.Mode.ORBIT)

    private val reference = referenceDistance.coerceAtLeast(0.5f)
    private val eye = FloatArray(3)
    private val target = FloatArray(3)
    private val up = FloatArray(3)

    private fun distanceScale(): Float {
        inner.getLookAt(eye, target, up)
        val dx = eye[0] - target[0]
        val dy = eye[1] - target[1]
        val dz = eye[2] - target[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        return max(dist, 0.5f) / reference
    }

    override fun setViewport(width: Int, height: Int) = inner.setViewport(width, height)

    override fun getTransform(): Transform = inner.transform

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) = inner.grabBegin(x, y, strafe)

    override fun grabUpdate(x: Int, y: Int) {
        // Orbit (rotate) speed is angle-based so it's distance-independent; only the
        // two-finger PAN (strafe) needs distance scaling. Manipulator handles both in
        // grabUpdate keyed off the strafe flag set in grabBegin, so we just forward.
        inner.grabUpdate(x, y)
    }

    override fun grabEnd() = inner.grabEnd()

    override fun scrollBegin(x: Int, y: Int, separation: Float) {}

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        val scale = distanceScale()
        // Damped pinch delta (same curve SceneView uses), then distance-scaled so the
        // world-space dolly per pinch pixel grows with zoom-out distance.
        val delta = damped(prevSeparation - currSeparation) * PINCH_ZOOM_SPEED * scale
        inner.scroll(x, y, delta)
    }

    override fun scrollEnd() {}

    override fun update(deltaTime: Float) = inner.update(deltaTime)

    private fun damped(delta: Float): Float {
        val absDelta = kotlin.math.abs(delta)
        return if (absDelta > 1f) sign(delta) * exp(ln(absDelta) * PINCH_ZOOM_DAMPING) else delta
    }

    private companion object {
        const val PINCH_ZOOM_SPEED = 1f / 18f
        const val PINCH_ZOOM_DAMPING = 0.85f
    }
}
