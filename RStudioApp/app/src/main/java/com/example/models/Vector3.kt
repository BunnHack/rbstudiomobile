package com.example.models

import com.squareup.moshi.JsonClass
import kotlin.math.sqrt

@JsonClass(generateAdapter = true)
data class Vector3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Float) = Vector3(x * factor, y * factor, z * factor)
    operator fun div(factor: Float) = if (factor != 0f) Vector3(x / factor, y / factor, z / factor) else this

    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vector3 {
        val len = length()
        return if (len != 0f) this / len else this
    }

    fun distance(other: Vector3) = (this - other).length()

    override fun toString(): String {
        return "%.2f, %.2f, %.2f".format(x, y, z)
    }

    companion object {
        val Zero = Vector3(0f, 0f, 0f)
        val One = Vector3(1f, 1f, 1f)
    }
}
