package com.example.ui.viewport

import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RobloxMeshLoader(
    private val engine: Engine,
    private val httpClient: OkHttpClient
) {
    private val cache = mutableMapOf<String, Geometry>()
    private val missing = mutableSetOf<String>()

    @Volatile
    var roblosecurityCookie: String = ""

    suspend fun load(meshUri: String): Geometry? {
        val assetId = Regex("(?:rbxassetid://|[?&]id=)?(\\d+)", RegexOption.IGNORE_CASE)
            .find(meshUri.trim())?.groupValues?.getOrNull(1) ?: return null
        cache[assetId]?.let { return it }
        if (assetId in missing) return null
        return runCatching {
            val bytes = withContext(Dispatchers.IO) { download(assetId) }
            FileMeshParser.parse(bytes).toGeometry(engine)
        }.onSuccess { cache[assetId] = it }
            .onFailure { missing += assetId }
            .getOrNull()
    }

    private fun download(assetId: String): ByteArray {
        val request = Request.Builder()
            .url("https://assetdelivery.roblox.com/v1/asset/?id=$assetId")
            .header("Accept", "*/*")
            .header("User-Agent", "RobloxStudio/WinInet RStudioApp/1.0")
            .apply {
                if (roblosecurityCookie.isNotBlank()) header("Cookie", ".ROBLOSECURITY=$roblosecurityCookie")
            }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Mesh $assetId HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Empty mesh asset $assetId")
            if (bytes.size > MAX_MESH_BYTES) throw IOException("Mesh exceeds mobile size limit")
            return bytes
        }
    }

    companion object {
        private const val MAX_MESH_BYTES = 128 * 1024 * 1024
    }
}

internal data class ParsedRobloxMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val colors: ByteArray,
    val indices: IntArray
) {
    fun toGeometry(engine: Engine): Geometry {
        val vertices = List(positions.size / 3) { index ->
            val pi = index * 3
            val ui = index * 2
            val ci = index * 4
            Geometry.Vertex(
                position = Position(positions[pi], positions[pi + 1], positions[pi + 2]),
                normal = Direction(normals[pi], normals[pi + 1], normals[pi + 2]),
                uvCoordinate = Float2(uvs[ui], uvs[ui + 1]),
                color = Float4(
                    (colors[ci].toInt() and 0xFF) / 255f,
                    (colors[ci + 1].toInt() and 0xFF) / 255f,
                    (colors[ci + 2].toInt() and 0xFF) / 255f,
                    (colors[ci + 3].toInt() and 0xFF) / 255f
                )
            )
        }
        return Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(vertices)
            .indices(indices.toList())
            .build(engine)
    }
}

internal object FileMeshParser {
    private const val MAX_VERTICES = 1_000_000
    private const val MAX_FACES = 2_000_000

    fun parse(bytes: ByteArray): ParsedRobloxMesh {
        val newline = bytes.indexOf('\n'.code.toByte()).takeIf { it in 8..16 }
            ?: throw IOException("Invalid FileMesh version line")
        val version = bytes.copyOfRange(0, newline).toString(Charsets.US_ASCII)
        if (!version.startsWith("version ")) throw IOException("Not a Roblox FileMesh")
        return when (version.removePrefix("version ").trim()) {
            "1.00", "1.01" -> parseV1(bytes.copyOfRange(newline + 1, bytes.size), version.endsWith("1.00"))
            "2.00" -> parseV2(Cursor(bytes, newline + 1))
            "3.00", "3.01" -> parseV3(Cursor(bytes, newline + 1))
            "4.00", "4.01", "5.00" -> parseV4(Cursor(bytes, newline + 1))
            else -> throw IOException("Unsupported FileMesh $version")
        }
    }

    private fun parseV1(body: ByteArray, halfScale: Boolean): ParsedRobloxMesh {
        val text = body.toString(Charsets.US_ASCII)
        val faceCount = Regex("^\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Invalid V1 face count")
        requireCount(faceCount, MAX_FACES, "faces")
        val values = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
            .findAll(text.substringAfter(faceCount.toString()))
            .map { it.value.toFloat() }.toList()
        if (values.size < faceCount * 27) throw IOException("Truncated V1 mesh")
        val vertexCount = faceCount * 3
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        val colors = ByteArray(vertexCount * 4) { 0xFF.toByte() }
        for (vertex in 0 until vertexCount) {
            val source = vertex * 9
            for (component in 0..2) positions[vertex * 3 + component] = values[source + component] * if (halfScale) 0.5f else 1f
            for (component in 0..2) normals[vertex * 3 + component] = values[source + 3 + component]
            uvs[vertex * 2] = values[source + 6]
            uvs[vertex * 2 + 1] = 1f - values[source + 7]
        }
        return ParsedRobloxMesh(positions, normals, uvs, colors, IntArray(vertexCount) { it })
    }

    private fun parseV2(cursor: Cursor): ParsedRobloxMesh {
        if (cursor.u16() != 12) throw IOException("Invalid V2 header")
        val vertexStride = cursor.u8()
        if (cursor.u8() != 12 || vertexStride !in setOf(36, 40)) throw IOException("Invalid V2 strides")
        return parseCore(cursor, vertexStride, cursor.u32Count(MAX_VERTICES), cursor.u32Count(MAX_FACES))
    }

    private fun parseV3(cursor: Cursor): ParsedRobloxMesh {
        if (cursor.u16() != 16) throw IOException("Invalid V3 header")
        val vertexStride = cursor.u8()
        if (cursor.u8() != 12 || vertexStride !in setOf(36, 40)) throw IOException("Invalid V3 strides")
        if (cursor.u16() != 4) throw IOException("Invalid V3 LOD stride")
        val lodCount = cursor.u16()
        val mesh = parseCore(cursor, vertexStride, cursor.u32Count(MAX_VERTICES), cursor.u32Count(MAX_FACES))
        repeat(lodCount) { cursor.u32() }
        return mesh
    }

    private fun parseV4(cursor: Cursor): ParsedRobloxMesh {
        val headerSize = cursor.u16()
        if (headerSize !in setOf(24, 32)) throw IOException("Invalid V4/V5 header")
        cursor.u16()
        val vertexCount = cursor.u32Count(MAX_VERTICES)
        val faceCount = cursor.u32Count(MAX_FACES)
        val lodCount = cursor.u16()
        val boneCount = cursor.u16()
        val boneNamesLength = cursor.u32Count(8 * 1024 * 1024)
        val subsetCount = cursor.u16()
        cursor.u8(); cursor.u8()
        if (headerSize == 32) repeat(2) { cursor.u32() }
        val mesh = parseCore(cursor, 40, vertexCount, faceCount, includeFaces = false)
        if (boneCount > 0) cursor.skip(vertexCount * 8)
        val indices = IntArray(faceCount * 3) { cursor.u32Count(vertexCount - 1) }
        repeat(lodCount) { cursor.u32() }
        cursor.skip(boneCount * 60)
        cursor.skip(boneNamesLength)
        cursor.skip(subsetCount * 72)
        return mesh.copy(indices = indices)
    }

    private fun parseCore(
        cursor: Cursor,
        vertexStride: Int,
        vertexCount: Int,
        faceCount: Int,
        includeFaces: Boolean = true
    ): ParsedRobloxMesh {
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        val colors = ByteArray(vertexCount * 4)
        for (vertex in 0 until vertexCount) {
            for (component in 0..2) positions[vertex * 3 + component] = cursor.f32()
            for (component in 0..2) normals[vertex * 3 + component] = cursor.f32()
            uvs[vertex * 2] = cursor.f32()
            uvs[vertex * 2 + 1] = cursor.f32()
            cursor.skip(4)
            if (vertexStride == 40) {
                for (component in 0..3) colors[vertex * 4 + component] = cursor.u8().toByte()
            } else {
                for (component in 0..3) colors[vertex * 4 + component] = 0xFF.toByte()
            }
        }
        val indices = if (includeFaces) IntArray(faceCount * 3) { cursor.u32Count(vertexCount - 1) } else IntArray(0)
        return ParsedRobloxMesh(positions, normals, uvs, colors, indices)
    }

    private fun requireCount(value: Int, max: Int, label: String) {
        if (value !in 0..max) throw IOException("Invalid $label count $value")
    }

    private class Cursor(private val bytes: ByteArray, start: Int) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(start) }
        fun u8(): Int = requireRemaining(1).get().toInt() and 0xFF
        fun u16(): Int = requireRemaining(2).short.toInt() and 0xFFFF
        fun u32(): Long = requireRemaining(4).int.toLong() and 0xFFFFFFFFL
        fun u32Count(max: Int): Int = u32().also { if (it > max) throw IOException("Mesh count $it exceeds $max") }.toInt()
        fun f32(): Float = requireRemaining(4).float.also { if (!it.isFinite()) throw IOException("Non-finite mesh value") }
        fun skip(count: Int) { if (count < 0 || count > buffer.remaining()) throw IOException("Truncated mesh"); buffer.position(buffer.position() + count) }
        private fun requireRemaining(count: Int): ByteBuffer {
            if (buffer.remaining() < count) throw IOException("Truncated mesh")
            return buffer
        }
    }
}
