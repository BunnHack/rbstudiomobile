package com.example.ui.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RobloxMeshLoaderTest {
    @Test
    fun parsesStaticVersion2Mesh() {
        val body = ByteBuffer.allocate(12 + 3 * 40 + 12).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(12)
        body.put(40)
        body.put(12)
        body.putInt(3)
        body.putInt(1)
        listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f)
        ).forEachIndexed { index, position ->
            position.forEach(body::putFloat)
            floatArrayOf(0f, 0f, 1f).forEach(body::putFloat)
            body.putFloat(if (index == 1) 1f else 0f)
            body.putFloat(if (index == 2) 1f else 0f)
            body.putInt(0)
            body.put(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()))
        }
        body.putInt(0); body.putInt(1); body.putInt(2)
        val mesh = FileMeshParser.parse("version 2.00\n".toByteArray() + body.array())

        assertEquals(9, mesh.positions.size)
        assertEquals(listOf(0, 1, 2), mesh.indices.toList())
        assertTrue(mesh.normals.all(Float::isFinite))
    }
}
