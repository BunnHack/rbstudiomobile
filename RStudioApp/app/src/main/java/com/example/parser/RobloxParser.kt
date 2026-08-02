package com.example.parser

import com.example.models.Part
import com.example.models.StudioNode
import com.example.models.Vector3
import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.jpountz.lz4.LZ4Factory

/**
 * Kotlin port of robloxParser.ts — parses Roblox binary (.rbxm/.rbxl) and XML
 * (.rbxmx/.rbxlx) files into [Part] objects the app can use.
 *
 * 100% faithful port: interleaved byte deinterleaving, zigzag encoding, Roblox
 * float-rotation bit trick, referent delta decoding, CFrame orientation-id preset
 * euler table, color uint8/float auto-detect, material enum map, zstd + lz4
 * decompression, INST/PROP/PRNT/END chunk parsing, XML DOM traversal.
 */
object RobloxParser {

    // ---- Output schema (mirrors RobloxInstance from types.ts) ----

    data class RobloxInstance(
        val id: String,
        val name: String,
        val className: String,
        val parentId: String?,
        val rawProperties: Map<String, Any?>,
        val properties: MappedProps
    )

    data class MappedProps(
        val name: String? = null,
        val anchored: Boolean? = null,
        val canCollide: Boolean? = null,
        val canQuery: Boolean? = null,
        val canTouch: Boolean? = null,
        val locked: Boolean? = null,
        val massless: Boolean? = null,
        val castShadow: Boolean? = null,
        val transparency: Float? = null,
        val reflectance: Float? = null,
        val collisionGroup: String? = null,
        val collisionGroupId: Int? = null,
        val rootPriority: Int? = null,
        val customPhysicalProperties: String? = null,
        val materialVariant: String? = null,
        val topSurface: String? = null,
        val bottomSurface: String? = null,
        val leftSurface: String? = null,
        val rightSurface: String? = null,
        val frontSurface: String? = null,
        val backSurface: String? = null,
        val formFactorRaw: Int? = null,
        val sourceAssetId: Long? = null,
        val uniqueId: String? = null,
        val historyId: String? = null,
        val tags: List<String>? = null,
        val source: String? = null,
        val enabled: Boolean? = null,
        val neutral: Boolean? = null,
        val allowTeamChangeOnTouch: Boolean? = null,
        val duration: Int? = null,
        val teamColor: Int? = null,
        val brightness: Float? = null,
        val timeOfDay: String? = null,
        val globalShadows: Boolean? = null,
        val size: Vector3? = null,
        val position: Vector3? = null,
        val material: String? = null,
        val color: String? = null,
        val rotation: Vector3? = null,
        val velocity: Vector3? = null,
        val rotVelocity: Vector3? = null,
        val partShape: String? = null
    )

    // ---- Binary reader ----

    private class BinaryReader(private val data: ByteArray) {
        private var offset = 0
        val remaining: Int get() = data.size - offset

        fun skip(bytes: Int) { offset = (offset + bytes).coerceAtMost(data.size) }
        fun seek(pos: Int) { offset = pos.coerceIn(0, data.size) }

        val pos: Int get() = offset

        val u8: Int get() = if (offset >= data.size) { 0 } else { data[offset++].toInt() and 0xFF }
        val u16: Int get() = if (offset + 2 > data.size) { offset = data.size; 0 } else { (data[offset++].toInt() and 0xFF) or ((data[offset++].toInt() and 0xFF) shl 8) }
        val i32: Int get() = if (offset + 4 > data.size) { offset = data.size; 0 } else { val v = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int; offset += 4; v }
        val u32: Long get() = if (offset + 4 > data.size) { offset = data.size; 0L } else { val v = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL; offset += 4; v }
        val f32: Float get() = if (offset + 4 > data.size) { offset = data.size; 0f } else { val v = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float; offset += 4; v }
        val f64: Double get() = if (offset + 8 > data.size) { offset = data.size; 0.0 } else { val v = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double; offset += 8; v }

        val string: String get() {
            val len = u32.toInt()
            if (len <= 0 || offset + len > data.size) return ""
            val s = String(data, offset, len, Charsets.UTF_8)
            offset += len
            return s
        }

        val binaryString: ByteArray get() {
            val len = u32.toInt()
            if (len <= 0 || offset + len > data.size) return ByteArray(0)
            val b = data.copyOfRange(offset, offset + len)
            offset += len
            return b
        }

        fun bytes(len: Int): ByteArray {
            val n = len.coerceIn(0, remaining)
            val b = data.copyOfRange(offset, offset + n)
            offset += n
            return b
        }
    }

    // ---- Interleaved byte deinterleaving ----

    private fun decodeInterleaved(src: ByteArray, count: Int, size: Int): ByteArray {
        val expected = count * size
        val out = ByteArray(expected)
        var p = 0
        for (byteIndex in 0 until size) {
            for (itemIndex in 0 until count) {
                val dst = itemIndex * size + byteIndex
                if (p < src.size && dst < out.size) out[dst] = src[p++]
            }
        }
        return out
    }

    // ---- Zigzag decode ----

    private fun zigZagDecode32(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun zigZagDecode64(n: Long): Long = (n shr 1) xor (-(n and 1L))

    private fun readBEUint64(buf: ByteArray, off: Int): Long {
        val hi = ((buf[off].toLong() and 0xFF) shl 24) or ((buf[off+1].toLong() and 0xFF) shl 16) or ((buf[off+2].toLong() and 0xFF) shl 8) or (buf[off+3].toLong() and 0xFF)
        val lo = ((buf[off+4].toLong() and 0xFF) shl 24) or ((buf[off+5].toLong() and 0xFF) shl 16) or ((buf[off+6].toLong() and 0xFF) shl 8) or (buf[off+7].toLong() and 0xFF)
        return (hi shl 32) or lo
    }

    // ---- Roblox float bit-rotation trick ----

    private fun decodeRobloxFloat32Bits(encoded: Int): Float {
        val rotated = (encoded ushr 1) or (encoded shl 31)
        return Float.fromBits(rotated)
    }

    // ---- Array decoders ----

    private fun decodeInt64Array(reader: BinaryReader, count: Int): Array<Long> {
        val raw = reader.bytes(count * 8)
        val de = decodeInterleaved(raw, count, 8)
        return Array(count) { i ->
            val encoded = readBEUint64(de, i * 8)
            zigZagDecode64(encoded)
        }
    }

    private fun decodeReferentArray(reader: BinaryReader, count: Int): IntArray {
        val raw = reader.bytes(count * 4)
        val de = decodeInterleaved(raw, count, 4)
        val out = IntArray(count)
        var last = 0
        for (i in 0 until count) {
            val encoded = ((de[i*4].toInt() and 0xFF) shl 24) or ((de[i*4+1].toInt() and 0xFF) shl 16) or ((de[i*4+2].toInt() and 0xFF) shl 8) or (de[i*4+3].toInt() and 0xFF)
            last += zigZagDecode32(encoded)
            out[i] = last
        }
        return out
    }

    private fun decodeInt32Array(reader: BinaryReader, count: Int): IntArray {
        val raw = reader.bytes(count * 4)
        val de = decodeInterleaved(raw, count, 4)
        return IntArray(count) { i ->
            val encoded = ((de[i*4].toInt() and 0xFF) shl 24) or ((de[i*4+1].toInt() and 0xFF) shl 16) or ((de[i*4+2].toInt() and 0xFF) shl 8) or (de[i*4+3].toInt() and 0xFF)
            zigZagDecode32(encoded)
        }
    }

    private fun decodeUint32Array(reader: BinaryReader, count: Int): LongArray {
        val raw = reader.bytes(count * 4)
        val de = decodeInterleaved(raw, count, 4)
        return LongArray(count) { i ->
            ((de[i*4].toLong() and 0xFF) shl 24) or ((de[i*4+1].toLong() and 0xFF) shl 16) or ((de[i*4+2].toLong() and 0xFF) shl 8) or (de[i*4+3].toLong() and 0xFF)
        }
    }

    private fun decodeFloat32Array(reader: BinaryReader, count: Int): FloatArray {
        val raw = reader.bytes(count * 4)
        val de = decodeInterleaved(raw, count, 4)
        return FloatArray(count) { i ->
            val encoded = ((de[i*4].toInt() and 0xFF) shl 24) or ((de[i*4+1].toInt() and 0xFF) shl 16) or ((de[i*4+2].toInt() and 0xFF) shl 8) or (de[i*4+3].toInt() and 0xFF)
            decodeRobloxFloat32Bits(encoded)
        }
    }

    private fun decodeBoolArray(reader: BinaryReader, count: Int): BooleanArray {
        val b = reader.bytes(count)
        return BooleanArray(count) { b[it] != 0.toByte() }
    }

    private data class RGB(val r: Float, val g: Float, val b: Float)

    private fun decodeColor3uint8Array(reader: BinaryReader, count: Int): Array<RGB> {
        val rs = reader.bytes(count)
        val gs = reader.bytes(count)
        val bs = reader.bytes(count)
        return Array(count) { i -> RGB((rs[i].toInt() and 0xFF).toFloat(), (gs[i].toInt() and 0xFF).toFloat(), (bs[i].toInt() and 0xFF).toFloat()) }
    }

    private fun decodeColor3Array(reader: BinaryReader, count: Int): Array<RGB> {
        val rs = decodeFloat32Array(reader, count)
        val gs = decodeFloat32Array(reader, count)
        val bs = decodeFloat32Array(reader, count)
        return Array(count) { i -> RGB(rs[i], gs[i], bs[i]) }
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)

    private fun decodeVector3Array(reader: BinaryReader, count: Int): Array<Vec3> {
        val xs = decodeFloat32Array(reader, count)
        val ys = decodeFloat32Array(reader, count)
        val zs = decodeFloat32Array(reader, count)
        return Array(count) { i -> Vec3(xs[i], ys[i], zs[i]) }
    }

    // ---- CFrame decoding ----

    private data class CFrameEuler(val x: Float, val y: Float, val z: Float)

    private val CFRAME_EULER_BY_ID: Map<Int, CFrameEuler> = mapOf(
        0x02 to CFrameEuler(0f, 0f, 0f),
        0x03 to CFrameEuler(90f, 0f, 0f),
        0x05 to CFrameEuler(0f, 180f, 180f),
        0x06 to CFrameEuler(-90f, 0f, 0f),
        0x07 to CFrameEuler(0f, 180f, 90f),
        0x09 to CFrameEuler(0f, 90f, 90f),
        0x0a to CFrameEuler(0f, 0f, 90f),
        0x0c to CFrameEuler(0f, -90f, 90f),
        0x0d to CFrameEuler(-90f, -90f, 0f),
        0x0e to CFrameEuler(0f, -90f, 0f),
        0x10 to CFrameEuler(90f, -90f, 0f),
        0x11 to CFrameEuler(0f, 90f, 180f),
        0x14 to CFrameEuler(0f, 180f, 0f),
        0x15 to CFrameEuler(-90f, -180f, 0f),
        0x17 to CFrameEuler(0f, 0f, 180f),
        0x18 to CFrameEuler(90f, 180f, 0f),
        0x19 to CFrameEuler(0f, 0f, -90f),
        0x1b to CFrameEuler(0f, -90f, -90f),
        0x1c to CFrameEuler(0f, -180f, -90f),
        0x1e to CFrameEuler(0f, 90f, -90f),
        0x1f to CFrameEuler(90f, 90f, 0f),
        0x20 to CFrameEuler(0f, 90f, 0f),
        0x22 to CFrameEuler(-90f, 90f, 0f),
        0x23 to CFrameEuler(0f, -90f, 180f)
    )

    private data class CFrameResult(val position: FloatArray, val rotation: FloatArray, val orientationId: Int, val euler: CFrameEuler?)

    private fun decodeCFrameArray(reader: BinaryReader, count: Int): Array<CFrameResult> {
        val rotations = arrayOfNulls<FloatArray>(count)
        val eulers = arrayOfNulls<CFrameEuler>(count)
        val orientationIds = IntArray(count)
        for (i in 0 until count) {
            val orientationId = reader.u8
            orientationIds[i] = orientationId
            if (orientationId == 0) {
                val rotation = FloatArray(9)
                for (r in 0 until 9) rotation[r] = reader.f32
                rotations[i] = rotation
                eulers[i] = null
            } else {
                rotations[i] = FloatArray(0)
                eulers[i] = CFRAME_EULER_BY_ID[orientationId] ?: CFrameEuler(0f, 0f, 0f)
            }
        }
        val xs = decodeFloat32Array(reader, count)
        val ys = decodeFloat32Array(reader, count)
        val zs = decodeFloat32Array(reader, count)
        return Array(count) { i -> CFrameResult(floatArrayOf(xs[i], ys[i], zs[i]), rotations[i] ?: FloatArray(0), orientationIds[i], eulers[i]) }
    }

    // ---- Tags blob ----

    private fun parseTagsBlob(tagBytes: ByteArray): List<String> {
        val r = BinaryReader(tagBytes)
        val tags = mutableListOf<String>()
        while (r.remaining > 0) {
            val tagLen = r.u8
            if (tagLen <= 0 || tagLen > r.remaining) break
            tags.add(String(r.bytes(tagLen), Charsets.UTF_8))
        }
        return tags
    }

    // ---- Property value decoding ----

    @Suppress("UNCHECKED_CAST")
    private fun decodePropValues(reader: BinaryReader, dataType: Int, count: Int): Array<Any?> {
        return when (dataType) {
            0x01 -> { Array(count) { reader.string as Any? } }
            0x02 -> { val v = decodeBoolArray(reader, count); Array(count) { v[it] as Any? } }
            0x03 -> { val v = decodeInt32Array(reader, count); Array(count) { v[it] as Any? } }
            0x04 -> { val v = decodeFloat32Array(reader, count); Array(count) { v[it] as Any? } }
            0x05 -> { Array(count) { reader.f64 as Any? } }
            0x06 -> {
                val scales = decodeFloat32Array(reader, count)
                val offsets = decodeInt32Array(reader, count)
                Array(count) { i -> mapOf("scale" to scales[i], "offset" to offsets[i]) as Any? }
            }
            0x07 -> {
                val sX = decodeFloat32Array(reader, count); val sY = decodeFloat32Array(reader, count)
                val oX = decodeInt32Array(reader, count); val oY = decodeInt32Array(reader, count)
                Array(count) { i -> mapOf("scaleX" to sX[i], "scaleY" to sY[i], "offsetX" to oX[i], "offsetY" to oY[i]) as Any? }
            }
            0x0B -> { val v = decodeUint32Array(reader, count); Array(count) { v[it] as Any? } }
            0x0C -> { val v = decodeColor3Array(reader, count); Array(count) { v[it] as Any? } }
            0x0D -> {
                val xs = decodeFloat32Array(reader, count); val ys = decodeFloat32Array(reader, count)
                Array(count) { i -> mapOf("x" to xs[i], "y" to ys[i]) as Any? }
            }
            0x0E -> { val v = decodeVector3Array(reader, count); Array(count) { v[it] as Any? } }
            0x10 -> { val v = decodeCFrameArray(reader, count); Array(count) { v[it] as Any? } }
            0x12 -> { val v = decodeUint32Array(reader, count); Array(count) { v[it] as Any? } }
            0x13 -> { val v = decodeReferentArray(reader, count); Array(count) { v[it] as Any? } }
            0x19 -> {
                Array(count) {
                    val flags = reader.u8
                    val isCustom = (flags and 0x01) != 0
                    val hasAcoustic = (flags and 0x02) != 0
                    if (!isCustom) {
                        mapOf("custom" to false, "acousticAbsorption" to if (hasAcoustic) 1.0 else null) as Any?
                    } else {
                        val density = reader.f32; val friction = reader.f32; val elasticity = reader.f32
                        val frictionWeight = reader.f32; val elasticityWeight = reader.f32
                        val acoustic = if (hasAcoustic) reader.f32 else 1.0f
                        mapOf("custom" to true, "density" to density, "friction" to friction, "elasticity" to elasticity, "frictionWeight" to frictionWeight, "elasticityWeight" to elasticityWeight, "acousticAbsorption" to acoustic) as Any?
                    }
                }
            }
            0x1A -> { val v = decodeColor3uint8Array(reader, count); Array(count) { v[it] as Any? } }
            0x1B -> { val v = decodeInt64Array(reader, count); Array(count) { v[it] as Any? } }
            0x20 -> {
                Array(count) {
                    val family = reader.string; val weight = reader.u16; val style = reader.u8; val cachedFaceId = reader.string
                    mapOf("family" to family, "weight" to weight, "style" to style, "cachedFaceId" to cachedFaceId.ifEmpty { null }) as Any?
                }
            }
            0x21 -> {
                Array(count) {
                    val totalSize = reader.i32
                    if (totalSize <= 0 || totalSize > reader.remaining) mapOf<String,Any?>() as Any? else mapOf("_raw" to reader.bytes(totalSize).toList()) as Any?
                }
            }
            0x29 -> {
                Array(count) {
                    val totalSize = reader.i32
                    if (totalSize <= 0 || totalSize > reader.remaining) emptyList<String>() as Any? else parseTagsBlob(reader.bytes(totalSize)) as Any?
                }
            }
            else -> Array(count) { null }
        }
    }

    private fun decodeBinaryStringArray(reader: BinaryReader, count: Int): Array<ByteArray> =
        Array(count) { reader.binaryString }

    // ---- Chunk types ----

    private data class ClassDef(val typeId: Int, val className: String, val category: Int, val instanceIds: IntArray)
    private data class PropDef(val typeId: Int, val propertyName: String, val dataType: Int, val values: Array<Any?>)
    private data class TempInst(val referentId: Int, val className: String, val properties: MutableMap<String, Any?>, var parentId: Int? = null)

    private fun isZstd(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x28.toByte() && data[1] == 0xB5.toByte() && data[2] == 0x2F.toByte() && data[3] == 0xFD.toByte()

    private val lz4Factory = LZ4Factory.safeInstance() // pure Java — works on Android (no native/Unsafe)

    // ---- Binary parser ----

    fun parseRobloxBinary(arrayBuffer: ByteArray): List<RobloxInstance> {
        val reader = BinaryReader(arrayBuffer)
        val magic = String(arrayBuffer.copyOfRange(0, 8), Charsets.UTF_8)
        require(magic == "<roblox!") { "Not a valid Roblox binary file (invalid magic)." }

        reader.seek(16)
        val classCount = reader.u32.toInt()
        val instanceCount = reader.u32.toInt()
        reader.skip(8)

        val classes = mutableMapOf<Int, ClassDef>()
        val properties = mutableListOf<PropDef>()
        var relations: Pair<IntArray, IntArray>? = null

        while (reader.remaining > 4) {
            val nameBytes = reader.bytes(4)
            val chunkName = String(nameBytes, Charsets.UTF_8).replace("\u0000", "")
            if (chunkName.isEmpty()) break

            val compressedLen = reader.u32.toInt()
            val uncompressedLen = reader.u32.toInt()
            reader.i32 // reserved

            val decompressed: ByteArray = if (compressedLen == 0) {
                reader.bytes(uncompressedLen)
            } else {
                val compressedData = reader.bytes(compressedLen)
                if (isZstd(compressedData)) {
                    try { Zstd.decompress(compressedData, uncompressedLen) }
                    catch (e: Throwable) { ByteArray(uncompressedLen) }
                } else {
                    try {
                        val out = ByteArray(uncompressedLen)
                        lz4Factory.fastDecompressor().decompress(compressedData, 0, out, 0, uncompressedLen)
                        out
                    } catch (e: Throwable) { compressedData }
                }
            }

            val chunkReader = BinaryReader(decompressed)

            when (chunkName) {
                "INST" -> {
                    val typeId = chunkReader.i32
                    val className = chunkReader.string
                    val objectFormat = chunkReader.u8
                    val count = chunkReader.i32
                    val instanceIds = decodeReferentArray(chunkReader, count)
                    if (objectFormat == 1 && chunkReader.remaining >= count) {
                        chunkReader.bytes(count)
                    }
                    classes[typeId] = ClassDef(typeId, className, objectFormat, instanceIds)
                }
                "PROP" -> {
                    val typeId = chunkReader.i32
                    val propertyName = chunkReader.string
                    val dataType = chunkReader.u8
                    val classDef = classes[typeId]
                    val count = classDef?.instanceIds?.size ?: 0
                    val values: Array<Any?> = when {
                        propertyName == "AttributesSerialize" && dataType == 0x01 ->
                            decodeBinaryStringArray(chunkReader, count).map { mapOf("_raw" to it.toList()) as Any? }.toTypedArray()
                        propertyName == "Tags" && dataType == 0x01 ->
                            decodeBinaryStringArray(chunkReader, count).map { parseTagsBlob(it) as Any? }.toTypedArray()
                        else -> decodePropValues(chunkReader, dataType, count)
                    }
                    properties.add(PropDef(typeId, propertyName, dataType, values))
                }
                "PRNT" -> {
                    chunkReader.u8 // version
                    val count = chunkReader.i32
                    val childIds = decodeReferentArray(chunkReader, count)
                    val parentIds = decodeReferentArray(chunkReader, count)
                    relations = childIds to parentIds
                }
                "END" -> break
            }
        }

        // Build temp instances
        val tempInstances = mutableMapOf<Int, TempInst>()

        classes.values.forEach { classDef ->
            classDef.instanceIds.forEach { id ->
                tempInstances[id] = TempInst(id, classDef.className, mutableMapOf())
            }
        }

        properties.forEach { prop ->
            val classDef = classes[prop.typeId] ?: return@forEach
            classDef.instanceIds.forEachIndexed { index, id ->
                val inst = tempInstances[id]
                if (inst != null && index < prop.values.size) {
                    inst.properties[prop.propertyName] = prop.values[index]
                }
            }
        }

        relations?.let { (childIds, parentIds) ->
            for (i in childIds.indices) {
                tempInstances[childIds[i]]?.parentId = parentIds[i].takeIf { it >= 0 }
            }
        }

        return tempInstances.values.map { temp -> mapInstance(temp) }
    }

    // ---- Instance → RobloxInstance mapping ----

    private val MATERIAL_ENUM_MAP: Map<Long, String> = mapOf(
        256L to "Plastic", 272L to "Neon", 288L to "Wood", 304L to "WoodPlanks",
        336L to "Glass", 352L to "Asphalt", 368L to "Concrete", 384L to "Granite",
        400L to "Marble", 416L to "Sand", 432L to "Fabric", 448L to "DiamondPlate",
        464L to "Foil", 480L to "Ice", 512L to "SmoothPlastic", 528L to "Brick",
        544L to "Cobblestone", 560L to "Sand", 784L to "Metal", 816L to "Brick",
        1040L to "Metal", 1056L to "Slate", 1264L to "Grass", 1280L to "LeafyGrass",
        1296L to "Salt", 1312L to "Snow", 1328L to "Mud", 1344L to "Pavement"
    )

    private fun pickProp(props: Map<String, Any?>, vararg names: String): Any? {
        for (n in names) if (props[n] != null) return props[n]
        return null
    }

    private fun propAsBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase().let { it == "true" || it == "1" }
        is Number -> value.toInt() != 0
        else -> null
    }

    private fun propAsInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun propAsLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun propAsFloat(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

    private fun propAsString(value: Any?): String? = when (value) {
        is ByteArray -> String(value, Charsets.UTF_8)
        is String -> value
        else -> value?.toString()
    }

    private fun vector3FromProp(value: Any?): Vector3? = (value as? Vec3)?.let {
        Vector3(it.x, it.y, it.z)
    }

    private fun surfaceFromProp(value: Any?): String? {
        return when (value) {
            is Number -> Part.surfaceFromToken(value.toInt())
            is String -> Part.SURFACE_TYPES.firstOrNull { it.equals(value, ignoreCase = true) } ?: value
            else -> null
        }
    }

    private fun tagsFromProp(value: Any?): List<String>? {
        return when (value) {
            is ByteArray -> parseTagsBlob(value)
            is List<*> -> value.mapNotNull { it as? String }
            is String -> value.split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> null
        }
    }

    private fun customPhysicalPropertiesLabel(value: Any?): String? {
        val map = value as? Map<*, *> ?: return propAsString(value)?.ifBlank { "Default" }
        val isCustom = propAsBoolean(map["custom"] ?: map["CustomPhysics"]) ?: false
        if (!isCustom) return "Default"

        val parts = listOfNotNull(
            propAsFloat(map["density"] ?: map["Density"])?.let { "Density %.2f".format(it) },
            propAsFloat(map["friction"] ?: map["Friction"])?.let { "Friction %.2f".format(it) },
            propAsFloat(map["elasticity"] ?: map["Elasticity"])?.let { "Elasticity %.2f".format(it) },
            propAsFloat(map["frictionWeight"] ?: map["FrictionWeight"])?.let { "FrictionWeight %.2f".format(it) },
            propAsFloat(map["elasticityWeight"] ?: map["ElasticityWeight"])?.let { "ElasticityWeight %.2f".format(it) }
        )
        return if (parts.isEmpty()) "Custom" else parts.joinToString(", ")
    }

    private fun partShapeFromToken(value: Any?): String? {
        return when ((value as? Number)?.toInt()) {
            0 -> "Ball"
            1 -> "Block"
            2 -> "Cylinder"
            else -> null
        }
    }

    private fun parseColorToHex(colorObj: Any?): String? {
        if (colorObj == null) return null
        if (colorObj is String) return colorObj
        if (colorObj is RGB) {
            val r = colorObj.r; val g = colorObj.g; val b = colorObj.b
            val isUint8 = r > 1f || g > 1f || b > 1f
            fun toHex(v: Float): String {
                val val2 = if (isUint8) Math.round(v) else Math.round(v * 255)
                val clamped = val2.coerceIn(0, 255)
                val hex = clamped.toString(16)
                return if (hex.length == 1) "0$hex" else hex
            }
            return "#" + toHex(r) + toHex(g) + toHex(b)
        }
        return null
    }

    private fun buildMappedProps(props: Map<String, Any?>): MappedProps {
        return MappedProps(
            name = pickProp(props, "Name", "name") as? String,
            anchored = propAsBoolean(pickProp(props, "Anchored", "anchored")),
            canCollide = propAsBoolean(pickProp(props, "CanCollide", "canCollide")),
            canQuery = propAsBoolean(pickProp(props, "CanQuery", "canQuery")),
            canTouch = propAsBoolean(pickProp(props, "CanTouch", "canTouch")),
            locked = propAsBoolean(pickProp(props, "Locked", "locked")),
            massless = propAsBoolean(pickProp(props, "Massless", "massless")),
            castShadow = propAsBoolean(pickProp(props, "CastShadow", "castShadow")),
            transparency = propAsFloat(pickProp(props, "Transparency", "transparency")),
            reflectance = propAsFloat(pickProp(props, "Reflectance", "reflectance")),
            collisionGroup = propAsString(pickProp(props, "CollisionGroup", "collisionGroup")),
            collisionGroupId = propAsInt(pickProp(props, "CollisionGroupId", "collisionGroupId")),
            rootPriority = propAsInt(pickProp(props, "RootPriority", "rootPriority")),
            customPhysicalProperties = customPhysicalPropertiesLabel(pickProp(props, "CustomPhysicalProperties", "customPhysicalProperties")),
            materialVariant = propAsString(pickProp(props, "MaterialVariantSerialized", "materialVariantSerialized", "MaterialVariant", "materialVariant")),
            topSurface = surfaceFromProp(pickProp(props, "TopSurface", "topSurface")),
            bottomSurface = surfaceFromProp(pickProp(props, "BottomSurface", "bottomSurface")),
            leftSurface = surfaceFromProp(pickProp(props, "LeftSurface", "leftSurface")),
            rightSurface = surfaceFromProp(pickProp(props, "RightSurface", "rightSurface")),
            frontSurface = surfaceFromProp(pickProp(props, "FrontSurface", "frontSurface")),
            backSurface = surfaceFromProp(pickProp(props, "BackSurface", "backSurface")),
            formFactorRaw = propAsInt(pickProp(props, "formFactorRaw", "FormFactorRaw", "formFactor")),
            sourceAssetId = propAsLong(pickProp(props, "SourceAssetId", "sourceAssetId")),
            uniqueId = propAsString(pickProp(props, "UniqueId", "uniqueId")),
            historyId = propAsString(pickProp(props, "HistoryId", "historyId")),
            tags = tagsFromProp(pickProp(props, "Tags", "tags")),
            source = propAsString(pickProp(props, "Source", "source")),
            enabled = propAsBoolean(pickProp(props, "Enabled", "enabled")),
            neutral = propAsBoolean(pickProp(props, "Neutral", "neutral")),
            allowTeamChangeOnTouch = propAsBoolean(pickProp(props, "AllowTeamChangeOnTouch", "allowTeamChangeOnTouch")),
            duration = propAsInt(pickProp(props, "Duration", "duration")),
            teamColor = propAsInt(pickProp(props, "TeamColor", "teamColor")),
            brightness = propAsFloat(pickProp(props, "Brightness", "brightness")),
            timeOfDay = propAsString(pickProp(props, "TimeOfDay", "timeOfDay")),
            globalShadows = propAsBoolean(pickProp(props, "GlobalShadows", "globalShadows")),
            partShape = partShapeFromToken(pickProp(props, "Shape", "shape"))
        )
    }

    private fun mapInstance(temp: TempInst): RobloxInstance {
        val props = temp.properties
        val mapped = buildMappedProps(props)

        // Size
        val sizeProp = pickProp(props, "Size", "size") as? Vec3
        val size = sizeProp?.let { Vector3(it.x, it.y, it.z) }

        // Position (from Position prop or CFrame)
        val positionProp = pickProp(props, "Position", "position") as? Vec3
        val cframeProp = pickProp(props, "CFrame", "cframe") as? CFrameResult
        val position = positionProp?.let { Vector3(it.x, it.y, it.z) }
            ?: cframeProp?.position?.let { Vector3(it[0], it[1], it[2]) }

        // Material
        val materialProp = props["Material"] ?: props["material"]
        val material = when (materialProp) {
            is Number -> MATERIAL_ENUM_MAP[materialProp.toLong()] ?: "Plastic"
            is String -> materialProp
            else -> null
        }

        // Color
        val parsedColor = parseColorToHex(props["Color"] ?: props["color"] ?: props["Color3"] ?: props["color3"] ?: props["Color3uint8"] ?: props["color3uint8"])

        // Rotation
        val rotation: Vector3 = when {
            cframeProp?.euler != null -> Vector3(cframeProp.euler.x, cframeProp.euler.y, cframeProp.euler.z)
            cframeProp?.rotation != null && cframeProp.rotation.size == 9 -> {
                val r = cframeProp.rotation
                val sinY = (-r[6]).coerceIn(-1f, 1f)
                val angleY = Math.asin(sinY.toDouble())
                val angleX = Math.atan2(r[7].toDouble(), r[8].toDouble())
                val angleZ = Math.atan2(r[3].toDouble(), r[0].toDouble())
                Vector3(
                    if (angleX.isNaN()) 0f else Math.toDegrees(angleX).toFloat(),
                    if (angleY.isNaN()) 0f else Math.toDegrees(angleY).toFloat(),
                    if (angleZ.isNaN()) 0f else Math.toDegrees(angleZ).toFloat()
                )
            }
            else -> Vector3(0f, 0f, 0f)
        }
        val velocity = vector3FromProp(pickProp(props, "Velocity", "velocity"))
        val rotVelocity = vector3FromProp(pickProp(props, "RotVelocity", "rotVelocity"))

        return RobloxInstance(
            id = temp.referentId.toString(),
            name = (mapped.name ?: temp.className),
            className = temp.className,
            parentId = temp.parentId?.toString(),
            rawProperties = props.toMap(),
            properties = mapped.copy(
                size = size,
                position = position,
                material = material,
                color = parsedColor,
                rotation = rotation,
                velocity = velocity,
                rotVelocity = rotVelocity
            )
        )
    }

    // ---- XML parser ----

    fun parseRobloxXml(xmlText: String): List<RobloxInstance> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlText.byteInputStream(Charsets.UTF_8))
        val root = doc.documentElement
        require(root.tagName == "roblox") { "Not a valid Roblox XML file (invalid root tag)." }

        val results = mutableListOf<RobloxInstance>()
        val rootItems = root.childNodes
        for (i in 0 until rootItems.length) {
            val el = rootItems.item(i)
            if (el.nodeType == org.w3c.dom.Node.ELEMENT_NODE && el.nodeName == "Item") {
                results.addAll(parseXmlItem(el, null))
            }
        }
        return results
    }

    private fun parseXmlItem(element: org.w3c.dom.Node, parentId: String?): List<RobloxInstance> {
        val el = element as org.w3c.dom.Element
        val className = el.getAttribute("class") ?: "Folder"
        val referent = el.getAttribute("referent") ?: java.util.UUID.randomUUID().toString()

        val properties = mutableMapOf<String, Any?>()
        val children = el.childNodes
        var propElement: org.w3c.dom.Element? = null
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.nodeType == org.w3c.dom.Node.ELEMENT_NODE && c.nodeName == "Properties") {
                propElement = c as org.w3c.dom.Element
                break
            }
        }

        propElement?.let { pe ->
            val childProps = pe.childNodes
            for (i in 0 until childProps.length) {
                val propNode = childProps.item(i) as? org.w3c.dom.Element ?: continue
                val name = propNode.getAttribute("name") ?: continue
                val tagName = propNode.tagName.lowercase()
                when (tagName) {
                    "string", "protectedstring" -> properties[name] = propNode.textContent ?: ""
                    "bool" -> properties[name] = propNode.textContent?.trim()?.lowercase() == "true"
                    "float" -> properties[name] = propNode.textContent?.trim()?.toFloatOrNull() ?: 0f
                    "double" -> properties[name] = propNode.textContent?.trim()?.toDoubleOrNull() ?: 0.0
                    "int" -> properties[name] = propNode.textContent?.trim()?.toIntOrNull() ?: 0
                    "int64" -> properties[name] = propNode.textContent?.trim()?.toLongOrNull() ?: 0L
                    "binarystring" -> {
                        val bytes = parseXmlBinaryString(propNode.textContent ?: "")
                        properties[name] = if (name.equals("Tags", ignoreCase = true)) parseTagsBlob(bytes) else bytes
                    }
                    "uniqueid" -> properties[name] = propNode.textContent?.trim().orEmpty()
                    "physicalproperties" -> properties[name] = parseXmlPhysicalProperties(propNode)
                    "vector3" -> {
                        properties[name] = Vec3(
                            getChildText(propNode, "X") ?: 0f,
                            getChildText(propNode, "Y") ?: 0f,
                            getChildText(propNode, "Z") ?: 0f
                        )
                    }
                    "coordinateframe", "cframe" -> {
                        val pos = Vec3(getChildText(propNode, "X") ?: 0f, getChildText(propNode, "Y") ?: 0f, getChildText(propNode, "Z") ?: 0f)
                        val rotation = FloatArray(9)
                        for (ri in 0 until 3) for (rj in 0 until 3) rotation[ri*3+rj] = getChildText(propNode, "R$ri$rj") ?: 0f
                        properties[name] = CFrameResult(floatArrayOf(pos.x, pos.y, pos.z), rotation, 0, null)
                    }
                    "color3", "color3uint8" -> {
                        val packed = propNode.textContent?.trim()?.toLongOrNull()
                        if (tagName == "color3uint8" && packed != null) {
                            properties[name] = RGB(
                                ((packed shr 16) and 0xFF).toFloat(),
                                ((packed shr 8) and 0xFF).toFloat(),
                                (packed and 0xFF).toFloat()
                            )
                        } else {
                            var r = getChildText(propNode, "R") ?: 0f
                            var g = getChildText(propNode, "G") ?: 0f
                            var b = getChildText(propNode, "B") ?: 0f
                            if (tagName == "color3uint8" || r > 1f || g > 1f || b > 1f) {
                                r = r.coerceIn(0f, 255f) / 255f
                                g = g.coerceIn(0f, 255f) / 255f
                                b = b.coerceIn(0f, 255f) / 255f
                            }
                            properties[name] = RGB(r, g, b)
                        }
                    }
                    "token" -> properties[name] = propNode.textContent?.trim()?.toIntOrNull() ?: 0
                }
            }
        }

        // Map to MappedProps (same as binary)
        val mapped = buildMappedProps(properties)
        val size = (pickProp(properties, "Size", "size") as? Vec3)?.let { Vector3(it.x, it.y, it.z) }
        val cframe = pickProp(properties, "CFrame", "cframe") as? CFrameResult
        val position = (pickProp(properties, "Position", "position") as? Vec3)?.let { Vector3(it.x, it.y, it.z) }
            ?: cframe?.position?.let { Vector3(it[0], it[1], it[2]) }
        val materialProp = pickProp(properties, "Material", "material")
        val material = when (materialProp) {
            is Number -> MATERIAL_ENUM_MAP[materialProp.toLong()] ?: "Plastic"
            is String -> materialProp
            else -> null
        }
        val color = parseColorToHex(pickProp(properties, "Color", "color", "Color3", "color3", "Color3uint8", "color3uint8"))
        val rotation = if (cframe?.rotation?.size == 9) {
            val r = cframe.rotation
            val sinY = (-r[6]).coerceIn(-1f, 1f)
            val angleY = Math.asin(sinY.toDouble())
            val angleX = Math.atan2(r[7].toDouble(), r[8].toDouble())
            val angleZ = Math.atan2(r[3].toDouble(), r[0].toDouble())
            Vector3(
                if (angleX.isNaN()) 0f else Math.toDegrees(angleX).toFloat(),
                if (angleY.isNaN()) 0f else Math.toDegrees(angleY).toFloat(),
                if (angleZ.isNaN()) 0f else Math.toDegrees(angleZ).toFloat()
            )
        } else Vector3(0f, 0f, 0f)
        val velocity = vector3FromProp(pickProp(properties, "Velocity", "velocity"))
        val rotVelocity = vector3FromProp(pickProp(properties, "RotVelocity", "rotVelocity"))

        val inst = RobloxInstance(
            id = referent,
            name = mapped.name ?: className,
            className = className,
            parentId = parentId,
            rawProperties = properties.toMap(),
            properties = mapped.copy(
                size = size,
                position = position,
                material = material,
                color = color,
                rotation = rotation,
                velocity = velocity,
                rotVelocity = rotVelocity
            )
        )

        val results = mutableListOf(inst)
        val childItems = el.childNodes
        for (i in 0 until childItems.length) {
            val c = childItems.item(i) as? org.w3c.dom.Element ?: continue
            if (c.nodeName == "Item") results.addAll(parseXmlItem(c, referent))
        }
        return results
    }

    private fun parseXmlBinaryString(text: String): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        return runCatching {
            java.util.Base64.getDecoder().decode(trimmed)
        }.getOrElse {
            trimmed.toByteArray(Charsets.UTF_8)
        }
    }

    private fun parseXmlPhysicalProperties(propNode: org.w3c.dom.Element): Map<String, Any?> {
        fun childString(tag: String): String? {
            val children = propNode.childNodes
            for (i in 0 until children.length) {
                val c = children.item(i)
                if (c.nodeType == org.w3c.dom.Node.ELEMENT_NODE && (c as org.w3c.dom.Element).tagName == tag) {
                    return c.textContent?.trim()
                }
            }
            return null
        }

        return mapOf(
            "custom" to (childString("CustomPhysics")?.lowercase() == "true"),
            "density" to childString("Density")?.toFloatOrNull(),
            "friction" to childString("Friction")?.toFloatOrNull(),
            "elasticity" to childString("Elasticity")?.toFloatOrNull(),
            "frictionWeight" to childString("FrictionWeight")?.toFloatOrNull(),
            "elasticityWeight" to childString("ElasticityWeight")?.toFloatOrNull()
        )
    }

    private fun getChildText(parent: org.w3c.dom.Element, tag: String): Float? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.nodeType == org.w3c.dom.Node.ELEMENT_NODE && (c as org.w3c.dom.Element).tagName == tag) {
                return c.textContent?.trim()?.toFloatOrNull()
            }
        }
        return null
    }

    // ---- Universal entrypoint ----

    fun parseRobloxFile(data: ByteArray): List<RobloxInstance> {
        val textStart = String(data.copyOfRange(0, minOf(100, data.size)), Charsets.UTF_8)
        return when {
            textStart.startsWith("<roblox!") -> parseRobloxBinary(data)
            textStart.contains("<roblox") -> parseRobloxXml(String(data, Charsets.UTF_8))
            else -> throw IllegalArgumentException("Unsupported Roblox format. Must be binary rbxm/rbxl or XML rbxmx/rbxlx.")
        }
    }

    // ---- RobloxInstance → Part conversion ----

    /**
     * Converts parsed [RobloxInstance]s into app [Part]s. Only instances with geometry
     * (Part, MeshPart, WedgePart, CornerWedgePart, TrussPart, SpawnLocation, BallPart)
     * become visible parts; other instances (scripts, folders, lights) are skipped.
     *
     * For "Part" class, the Shape token property (Ball=0, Block=1, Cylinder=2) is read
     * to determine the actual shape, not just the className.
     */
    fun instancesToParts(instances: List<RobloxInstance>): List<Part> {
        return instances.mapNotNull { inst ->
            val cls = inst.className
            val shape = when (cls) {
                "Part" -> when (inst.properties.partShape) {
                    "Ball" -> Part.SHAPE_SPHERE
                    "Cylinder" -> Part.SHAPE_CYLINDER
                    else -> Part.SHAPE_BLOCK // "Block" or null → default
                }
                "MeshPart", "TrussPart", "UnionOperation", "NegateOperation" -> Part.SHAPE_BLOCK
                "WedgePart", "CornerWedgePart" -> Part.SHAPE_WEDGE
                "BallPart" -> Part.SHAPE_SPHERE
                "SpawnLocation" -> Part.SHAPE_SPAWN_LOCATION
                else -> null
            } ?: return@mapNotNull null

            val p = inst.properties
            val size = p.size ?: Vector3(2f, 1f, 2f)
            val pos = p.position ?: Vector3(0f, 0f, 0f)
            val rot = p.rotation ?: Vector3(0f, 0f, 0f)
            val color = p.color ?: "#CCCCCC"
            val material = normalizeMaterial(p.material)
            val anchored = p.anchored ?: true
            val canCollide = p.canCollide ?: true
            val canQuery = p.canQuery ?: true
            val canTouch = p.canTouch ?: true
            val locked = p.locked ?: false
            val massless = p.massless ?: false
            val castShadow = p.castShadow ?: true
            val transparency = (p.transparency ?: 0f).coerceIn(0f, 1f)
            val reflectance = (p.reflectance ?: 0f).coerceIn(0f, 1f)
            val source = p.source ?: ""
            val parentId = inst.parentId
            val velocity = p.velocity ?: Vector3.Zero
            val rotVelocity = p.rotVelocity ?: Vector3.Zero

            // Try to get a BrickColor name from the BrickColor property (enum value)
            val brickColorName: String = runCatching {
                val bcNum = inst.rawProperties["BrickColor"] as? Number
                bcNum?.let { num -> BRICKCOLOR_NAMES[num.toInt()] } ?: "Medium stone grey"
            }.getOrDefault("Medium stone grey")
            val brickColorHex = Part.brickColorToHex(brickColorName) ?: color

            Part(
                id = inst.id,  // preserve original Roblox instance id so parentId matches
                name = inst.name,
                shape = shape,
                position = pos,
                size = size,
                rotation = rot,
                colorHex = if (brickColorHex != "#CCCCCC") brickColorHex else color,
                brickColor = brickColorName,
                material = material,
                anchored = anchored,
                canCollide = canCollide,
                canQuery = canQuery,
                canTouch = canTouch,
                locked = locked,
                massless = massless,
                castShadow = castShadow,
                transparency = transparency,
                reflectance = reflectance,
                collisionGroup = p.collisionGroup ?: "Default",
                collisionGroupId = p.collisionGroupId ?: 0,
                rootPriority = p.rootPriority ?: 0,
                customPhysicalProperties = p.customPhysicalProperties ?: "Default",
                materialVariant = p.materialVariant ?: "",
                topSurface = p.topSurface ?: Part.SURFACE_SMOOTH,
                bottomSurface = p.bottomSurface ?: Part.SURFACE_SMOOTH,
                leftSurface = p.leftSurface ?: Part.SURFACE_SMOOTH,
                rightSurface = p.rightSurface ?: Part.SURFACE_SMOOTH,
                frontSurface = p.frontSurface ?: Part.SURFACE_SMOOTH,
                backSurface = p.backSurface ?: Part.SURFACE_SMOOTH,
                formFactorRaw = p.formFactorRaw ?: 0,
                sourceAssetId = p.sourceAssetId ?: -1L,
                uniqueId = p.uniqueId ?: "",
                historyId = p.historyId ?: "",
                tags = p.tags ?: emptyList(),
                rotVelocity = rotVelocity,
                spawnEnabled = p.enabled ?: true,
                neutral = p.neutral ?: true,
                allowTeamChangeOnTouch = p.allowTeamChangeOnTouch ?: false,
                duration = p.duration ?: 0,
                teamColor = p.teamColor ?: 194,
                parentId = parentId,
                script = source,
                currentPosition = pos,
                currentRotation = rot,
                velocity = velocity
            )
        }
    }

    fun instancesToStudioNodes(
        instances: List<RobloxInstance>,
        parts: List<Part> = instancesToParts(instances)
    ): List<StudioNode> {
        val partsById = parts.associateBy { it.id }
        return instances.map { inst ->
            val part = partsById[inst.id]
            val className = if (part != null) {
                when (part.shape) {
                    Part.SHAPE_SPAWN_LOCATION -> StudioNode.CLASS_SPAWN_LOCATION
                    Part.SHAPE_WEDGE -> StudioNode.CLASS_WEDGE_PART
                    Part.SHAPE_SPHERE -> StudioNode.CLASS_BALL_PART
                    else -> inst.className
                }
            } else {
                inst.className
            }

            StudioNode(
                id = inst.id,
                name = inst.name,
                className = className,
                parentId = inst.parentId,
                part = part,
                scriptSource = inst.properties.source.orEmpty(),
                nodeProperties = formatNodeProperties(inst)
            )
        }
    }

    private fun formatNodeProperties(inst: RobloxInstance): Map<String, String> {
        val result = linkedMapOf<String, String>()
        result["ClassName"] = inst.className
        result["Name"] = inst.name
        result["ParentId"] = inst.parentId ?: "Workspace"
        inst.rawProperties.forEach { (name, value) ->
            result[name] = formatPropertyValue(name, value)
        }
        return result
    }

    private fun formatPropertyValue(name: String, value: Any?): String {
        return when (value) {
            null -> ""
            is Boolean, is Number, is String -> {
                if (name.equals("Face", ignoreCase = true) && value is Number) {
                    faceFromToken(value.toInt())
                } else {
                    value.toString()
                }
            }
            is ByteArray -> "BinaryString ${value.size} bytes"
            is RGB -> parseColorToHex(value) ?: value.toString()
            is Vec3 -> "%.3f, %.3f, %.3f".format(value.x, value.y, value.z)
            is CFrameResult -> {
                val pos = value.position
                val rot = value.euler?.let { "%.1f, %.1f, %.1f".format(it.x, it.y, it.z) }
                    ?: if (value.rotation.size == 9) "matrix9" else "identity"
                "pos %.3f, %.3f, %.3f; rot $rot".format(pos[0], pos[1], pos[2])
            }
            is Map<*, *> -> {
                if (value.containsKey("_raw")) {
                    val raw = value["_raw"] as? List<*>
                    "Binary ${raw?.size ?: 0} bytes"
                } else if (value.containsKey("scaleX") && value.containsKey("scaleY") && value.containsKey("offsetX") && value.containsKey("offsetY")) {
                    val sx = (value["scaleX"] as? Number)?.toFloat() ?: 0f
                    val sy = (value["scaleY"] as? Number)?.toFloat() ?: 0f
                    val ox = (value["offsetX"] as? Number)?.toInt() ?: 0
                    val oy = (value["offsetY"] as? Number)?.toInt() ?: 0
                    "scaleX=%.3f, scaleY=%.3f, offsetX=%d, offsetY=%d".format(sx, sy, ox, oy)
                } else if (value.containsKey("scale") && value.containsKey("offset")) {
                    val scale = (value["scale"] as? Number)?.toFloat() ?: 0f
                    val offset = (value["offset"] as? Number)?.toInt() ?: 0
                    "scale=%.3f, offset=%d".format(scale, offset)
                } else if (value.containsKey("x") && value.containsKey("y")) {
                    val x = (value["x"] as? Number)?.toFloat() ?: 0f
                    val y = (value["y"] as? Number)?.toFloat() ?: 0f
                    "x=%.3f, y=%.3f".format(x, y)
                } else {
                    value.entries.joinToString(", ") { "${it.key}=${it.value}" }
                }
            }
            is List<*> -> {
                if (value.all { it is String }) value.joinToString(", ")
                else "${value.size} items"
            }
            else -> value.toString()
        }
    }

    private fun faceFromToken(token: Int): String = when (token) {
        0 -> "Right"
        1 -> "Top"
        2 -> "Back"
        3 -> "Left"
        4 -> "Bottom"
        5 -> "Front"
        else -> token.toString()
    }

    /** Roblox BrickColor enum IDs → names (common subset). */
    private val BRICKCOLOR_NAMES: Map<Int, String> = mapOf(
        1 to "White", 2 to "Grey", 3 to "Light yellow", 5 to "Bright red",
        6 to "Bright blue", 9 to "Bright green", 11 to "Bright violet",
        12 to "Bright orange", 18 to "Navy blue", 21 to "Bright yellow",
        22 to "White", 23 to "Light grey", 24 to "Black", 26 to "Medium red",
        27 to "Medium green", 28 to "Medium blue", 29 to "Sand red",
        30 to "Sand green", 37 to "Earth green", 38 to "Earth orange",
        39 to "Earth blue", 40 to "Sand blue", 45 to "Dirt brown",
        46 to "Brick yellow", 47 to "Reddish brown", 48 to "Dark stone grey",
        49 to "Really black", 50 to "Dark grey", 100 to "Medium stone grey",
        101 to "Medium grey", 102 to "Light grey", 103 to "Light blue",
        104 to "Medium blue", 105 to "Really blue", 106 to "Bright blue",
        107 to "Dark blue", 108 to "Navy blue", 109 to "Pink",
        110 to "Magenta", 111 to "Cyan", 112 to "Teal",
        113 to "Bright green", 119 to "Bright red", 125 to "Really red",
        151 to "Sand green", 153 to "Sand red", 178 to "Dark red",
        208 to "Pastel green", 209 to "Pastel blue", 210 to "Pastel yellow",
        211 to "Pastel red", 212 to "Pastel brown", 216 to "Lily white",
        217 to "Cool yellow", 218 to "Light orange", 219 to "Daisy orange",
        221 to "Lime green", 222 to "Hot pink", 223 to "Alder",
        224 to "Gold", 225 to "Silver", 226 to "Copper",
        227 to "Carbon", 228 to "Grime", 229 to "Olive"
    )

    private fun normalizeMaterial(m: String?): String {
        if (m == null) return Part.MATERIAL_PLASTIC
        return when (m) {
            "Plastic", "SmoothPlastic" -> Part.MATERIAL_PLASTIC
            "Wood", "WoodPlanks" -> Part.MATERIAL_WOOD
            "Slate", "Cobblestone" -> Part.MATERIAL_SLATE
            "Brick" -> Part.MATERIAL_BRICK
            "Neon" -> Part.MATERIAL_NEON
            "Metal", "DiamondPlate", "Foil" -> Part.MATERIAL_METAL
            "Glass", "Ice" -> Part.MATERIAL_GLASS
            "Fabric" -> Part.MATERIAL_FABRIC
            "Marble", "Granite", "Concrete" -> Part.MATERIAL_MARBLE
            else -> Part.MATERIAL_PLASTIC
        }
    }
}
