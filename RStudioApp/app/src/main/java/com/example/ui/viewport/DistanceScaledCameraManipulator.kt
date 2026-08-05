package com.example.ui.viewport

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Predictable orbit camera controller using screen-space motion math.
 *
 * Filament's ORBIT panning raycasts a ground plane. At high camera elevations that
 * projection can become near-parallel and two-finger pan slows dramatically. This
 * implementation instead maps one pixel to the world-space size of one pixel at the
 * target depth, so pan speed remains consistent at every angle and scales naturally
 * with camera distance. Pinch zoom is multiplicative, so large scenes zoom as quickly
 * as small ones.
 */
class DistanceScaledCameraManipulator(
    initialEye: Position,
    initialTarget: Position = Position(0f, 0f, 0f)
) : CameraGestureDetector.CameraManipulator {

    private enum class GrabMode { NONE, ORBIT, PAN }

    private var eyeX = initialEye.x
    private var eyeY = initialEye.y
    private var eyeZ = initialEye.z
    private var targetX = initialTarget.x
    private var targetY = initialTarget.y
    private var targetZ = initialTarget.z

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var grabMode = GrabMode.NONE
    private var grabX = 0
    private var grabY = 0
    private var startEyeX = eyeX
    private var startEyeY = eyeY
    private var startEyeZ = eyeZ
    private var startTargetX = targetX
    private var startTargetY = targetY
    private var startTargetZ = targetZ

    val distance: Float
        get() {
            val dx = eyeX - targetX
            val dy = eyeY - targetY
            val dz = eyeZ - targetZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

    fun setOrbit(yawDegrees: Float, pitchDegrees: Float, distance: Float) {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble().coerceIn(-89.0, 89.0))
        val d = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        eyeX = targetX + (d * cos(pitch) * sin(yaw)).toFloat()
        eyeY = targetY + (d * sin(pitch)).toFloat()
        eyeZ = targetZ + (d * cos(pitch) * cos(yaw)).toFloat()
    }

    override fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun getTransform(): Transform = lookAt(
        eye = Float3(eyeX, eyeY, eyeZ),
        target = Float3(targetX, targetY, targetZ),
        up = Float3(0f, 1f, 0f)
    )

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        grabMode = if (strafe) GrabMode.PAN else GrabMode.ORBIT
        grabX = x
        grabY = y
        startEyeX = eyeX
        startEyeY = eyeY
        startEyeZ = eyeZ
        startTargetX = targetX
        startTargetY = targetY
        startTargetZ = targetZ
    }

    override fun grabUpdate(x: Int, y: Int) {
        val dxPixels = x - grabX
        val dyPixels = y - grabY
        when (grabMode) {
            GrabMode.ORBIT -> updateOrbit(dxPixels, dyPixels)
            GrabMode.PAN -> updatePan(dxPixels, dyPixels)
            GrabMode.NONE -> Unit
        }
    }

    private fun updateOrbit(dxPixels: Int, dyPixels: Int) {
        val ox = startEyeX - startTargetX
        val oy = startEyeY - startTargetY
        val oz = startEyeZ - startTargetZ
        val d = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(MIN_DISTANCE)
        val startYaw = atan2(ox, oz)
        val startPitch = asin((oy / d).coerceIn(-1f, 1f))
        val yaw = startYaw - dxPixels * ORBIT_RADIANS_PER_PIXEL
        val pitch = (startPitch + dyPixels * ORBIT_RADIANS_PER_PIXEL)
            .coerceIn(MIN_PITCH_RADIANS, MAX_PITCH_RADIANS)
        eyeX = startTargetX + d * cos(pitch) * sin(yaw)
        eyeY = startTargetY + d * sin(pitch)
        eyeZ = startTargetZ + d * cos(pitch) * cos(yaw)
        targetX = startTargetX
        targetY = startTargetY
        targetZ = startTargetZ
    }

    private fun updatePan(dxPixels: Int, dyPixels: Int) {
        val fx = startTargetX - startEyeX
        val fy = startTargetY - startEyeY
        val fz = startTargetZ - startEyeZ
        val distance = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(MIN_DISTANCE)
        val invForward = 1f / distance
        val nx = fx * invForward
        val ny = fy * invForward
        val nz = fz * invForward

        // right = normalize(forward x worldUp). Near vertical, keep a stable X axis.
        var rx = -nz
        var rz = nx
        val rightLength = sqrt(rx * rx + rz * rz)
        if (rightLength < 0.001f) {
            rx = 1f
            rz = 0f
        } else {
            rx /= rightLength
            rz /= rightLength
        }
        // cameraUp = right x forward
        val ux = -rz * ny
        val uy = rz * nx - rx * nz
        val uz = rx * ny

        val worldPerPixel = (
            2f * distance * tan(FOV_RADIANS * 0.5f) / viewportHeight
        ) * PAN_SENSITIVITY
        val tx = (-rx * dxPixels + ux * dyPixels) * worldPerPixel
        val ty = uy * dyPixels * worldPerPixel
        val tz = (-rz * dxPixels + uz * dyPixels) * worldPerPixel
        eyeX = startEyeX + tx
        eyeY = startEyeY + ty
        eyeZ = startEyeZ + tz
        targetX = startTargetX + tx
        targetY = startTargetY + ty
        targetZ = startTargetZ + tz
    }

    override fun grabEnd() {
        grabMode = GrabMode.NONE
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        if (prevSeparation <= 0f || currSeparation <= 0f) return
        val currentDistance = distance.coerceAtLeast(MIN_DISTANCE)
        val zoomFactor = (prevSeparation / currSeparation).pow(PINCH_EXPONENT)
        val newDistance = (currentDistance * zoomFactor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val scale = newDistance / currentDistance
        eyeX = targetX + (eyeX - targetX) * scale
        eyeY = targetY + (eyeY - targetY) * scale
        eyeZ = targetZ + (eyeZ - targetZ) * scale
    }

    override fun scrollEnd() = Unit
    override fun update(deltaTime: Float) = Unit

    private companion object {
        const val ORBIT_RADIANS_PER_PIXEL = 0.008f
        const val PAN_SENSITIVITY = 1.75f
        const val PINCH_EXPONENT = 2.2f
        const val MIN_DISTANCE = 0.5f
        const val MAX_DISTANCE = 10_000f
        val FOV_RADIANS = Math.toRadians(33.0).toFloat()
        val MIN_PITCH_RADIANS = Math.toRadians(-89.0).toFloat()
        val MAX_PITCH_RADIANS = Math.toRadians(89.0).toFloat()
    }
}
