package com.example.publish

import com.example.models.Part
import com.example.models.Vector3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object RobloxPlaceBinarySerializer {
    @Suppress("UNUSED_PARAMETER")
    fun serialize(placeName: String, parts: List<Part>): ByteArray {
        val instances = buildInstances(placeName, parts)
        val classes = linkedMapOf<String, MutableList<InstanceRecord>>()
        instances.forEach { instance ->
            classes.getOrPut(instance.className) { mutableListOf() }.add(instance)
        }
        val classIds = classes.keys.withIndex().associate { (index, className) -> className to index }

        val writer = BinaryWriter()
        writer.writeBytes(MAGIC)
        writer.writeBytes(SIGNATURE)
        writer.writeUInt16LE(0)
        writer.writeInt32LE(classes.size)
        writer.writeInt32LE(instances.size)
        writer.writeBytes(ByteArray(8))

        writeMetaChunk(writer)
        writeSharedStringsChunk(writer)

        classes.forEach { (className, classInstances) ->
            writeInstanceChunk(writer, classIds.getValue(className), className, classInstances)
        }
        classes.forEach { (className, classInstances) ->
            writePropertyChunks(writer, classIds.getValue(className), className, classInstances)
        }
        writeParentChunk(writer, instances)
        writeChunk(writer, "END", "</roblox>".toByteArray(Charsets.UTF_8))

        return writer.toByteArray()
    }

    private fun buildInstances(placeName: String, parts: List<Part>): List<InstanceRecord> {
        val instances = mutableListOf<InstanceRecord>()
        var nextId = 0
        fun add(className: String, name: String, parentId: Int = -1): InstanceRecord {
            val instance = InstanceRecord(nextId++, className, name, parentId, linkedMapOf())
            instances.add(instance)
            return instance
        }

        val workspace = add("Workspace", "Workspace")
        workspace.props["Gravity"] = 196.2f
        workspace.props["FallenPartsDestroyHeight"] = -500f

        val lighting = add("Lighting", "Lighting")
        lighting.props["Brightness"] = 2f
        lighting.props["GlobalShadows"] = true
        lighting.props["TimeOfDay"] = "14:30:00"

        add("ReplicatedStorage", "ReplicatedStorage")
        add("ServerScriptService", "ServerScriptService")
        add("ServerStorage", "ServerStorage")
        add("StarterGui", "StarterGui")
        add("StarterPack", "StarterPack")

        parts.forEachIndexed { index, part ->
            val instance = add(classNameFor(part), part.name.ifBlank { "Part${index + 1}" }, workspace.id)
            addBasePartProps(instance, part)
            if (instance.className == "Part") {
                instance.props["Shape"] = shapeToken(part.shape)
            }
            if (instance.className == "SpawnLocation") {
                instance.props["AllowTeamChangeOnTouch"] = part.allowTeamChangeOnTouch
                instance.props["Duration"] = part.duration
                instance.props["Enabled"] = part.spawnEnabled
                instance.props["Neutral"] = part.neutral
            }
            if (part.script.isNotBlank()) {
                val script = add("Script", "Script", instance.id)
                script.props["Source"] = part.script
                script.props["Disabled"] = false
            }
        }

        return instances
    }

    private fun classNameFor(part: Part): String = when (part.shape) {
        Part.SHAPE_WEDGE -> "WedgePart"
        Part.SHAPE_SPAWN_LOCATION -> "SpawnLocation"
        else -> "Part"
    }

    private fun addBasePartProps(instance: InstanceRecord, part: Part) {
        instance.props["Anchored"] = part.anchored
        instance.props["CanCollide"] = part.canCollide
        instance.props["CanQuery"] = part.canQuery
        instance.props["CanTouch"] = part.canTouch
        instance.props["CastShadow"] = part.castShadow
        instance.props["Locked"] = part.locked
        instance.props["Massless"] = part.massless
        instance.props["Transparency"] = part.transparency
        instance.props["Reflectance"] = part.reflectance
        instance.props["CollisionGroup"] = part.collisionGroup.ifBlank { "Default" }
        instance.props["CollisionGroupId"] = part.collisionGroupId
        instance.props["RootPriority"] = part.rootPriority
        instance.props["MaterialVariantSerialized"] = part.materialVariant
        instance.props["Material"] = materialToken(part.material)
        instance.props["Size"] = part.size
        instance.props["Color"] = colorFromHex(part.colorHex)
        instance.props["CFrame"] = CFrameValue(part.position, rotationMatrix(part.rotation))
        instance.props["Velocity"] = part.velocity
        instance.props["RotVelocity"] = part.rotVelocity
        instance.props["formFactorRaw"] = part.formFactorRaw
        instance.props["TopSurface"] = surfaceToken(part.topSurface)
        instance.props["BottomSurface"] = surfaceToken(part.bottomSurface)
        instance.props["LeftSurface"] = surfaceToken(part.leftSurface)
        instance.props["RightSurface"] = surfaceToken(part.rightSurface)
        instance.props["FrontSurface"] = surfaceToken(part.frontSurface)
        instance.props["BackSurface"] = surfaceToken(part.backSurface)
    }

    private fun writeMetaChunk(writer: BinaryWriter) {
        val chunk = BinaryWriter()
        val entries = listOf("ExplicitAutoJoints" to "true")
        chunk.writeUInt32LE(entries.size)
        entries.forEach { (key, value) ->
            chunk.writeString(key)
            chunk.writeString(value)
        }
        writeChunk(writer, "META", chunk.toByteArray())
    }

    private fun writeSharedStringsChunk(writer: BinaryWriter) {
        val chunk = BinaryWriter()
        chunk.writeUInt32LE(0)
        chunk.writeUInt32LE(0)
        writeChunk(writer, "SSTR", chunk.toByteArray())
    }

    private fun writeInstanceChunk(
        writer: BinaryWriter,
        classId: Int,
        className: String,
        instances: List<InstanceRecord>
    ) {
        val chunk = BinaryWriter()
        chunk.writeInt32LE(classId)
        chunk.writeString(className)
        val service = className in SERVICE_CLASSES
        chunk.writeUInt8(if (service) 1 else 0)
        chunk.writeInt32LE(instances.size)

        var lastReferent = 0
        val referents = IntArray(instances.size) { index ->
            val delta = instances[index].id - lastReferent
            lastReferent = instances[index].id
            zigZag32(delta)
        }
        chunk.writeInterleavedUInt32(referents)

        if (service) {
            chunk.writeBytes(ByteArray(instances.size) { 1 })
        }

        writeChunk(writer, "INST", chunk.toByteArray())
    }

    private fun writePropertyChunks(
        writer: BinaryWriter,
        classId: Int,
        className: String,
        instances: List<InstanceRecord>
    ) {
        writeStringProp(writer, classId, "Name", instances.map { it.name })

        when (className) {
            "Workspace" -> {
                writeFloatProp(writer, classId, "Gravity", instances.map { it.props["Gravity"] as Float })
                writeFloatProp(writer, classId, "FallenPartsDestroyHeight", instances.map { it.props["FallenPartsDestroyHeight"] as Float })
            }
            "Lighting" -> {
                writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as Float })
                writeBoolProp(writer, classId, "GlobalShadows", instances.map { it.props["GlobalShadows"] as Boolean })
                writeStringProp(writer, classId, "TimeOfDay", instances.map { it.props["TimeOfDay"] as String })
            }
            "Part", "WedgePart", "SpawnLocation" -> writeBasePartProps(writer, classId, instances, className == "Part")
            "Script", "LocalScript" -> {
                writeStringProp(writer, classId, "Source", instances.map { it.props["Source"] as? String ?: "" })
                writeBoolProp(writer, classId, "Disabled", instances.map { it.props["Disabled"] as? Boolean ?: false })
            }
        }

        if (className == "SpawnLocation") {
            writeBoolProp(writer, classId, "AllowTeamChangeOnTouch", instances.map { it.props["AllowTeamChangeOnTouch"] as Boolean })
            writeIntProp(writer, classId, "Duration", instances.map { it.props["Duration"] as Int })
            writeBoolProp(writer, classId, "Enabled", instances.map { it.props["Enabled"] as Boolean })
            writeBoolProp(writer, classId, "Neutral", instances.map { it.props["Neutral"] as Boolean })
        }
    }

    private fun writeBasePartProps(
        writer: BinaryWriter,
        classId: Int,
        instances: List<InstanceRecord>,
        includeShape: Boolean
    ) {
        writeBoolProp(writer, classId, "Anchored", instances.map { it.props["Anchored"] as Boolean })
        writeBoolProp(writer, classId, "CanCollide", instances.map { it.props["CanCollide"] as Boolean })
        writeBoolProp(writer, classId, "CanQuery", instances.map { it.props["CanQuery"] as Boolean })
        writeBoolProp(writer, classId, "CanTouch", instances.map { it.props["CanTouch"] as Boolean })
        writeBoolProp(writer, classId, "CastShadow", instances.map { it.props["CastShadow"] as Boolean })
        writeBoolProp(writer, classId, "Locked", instances.map { it.props["Locked"] as Boolean })
        writeBoolProp(writer, classId, "Massless", instances.map { it.props["Massless"] as Boolean })
        writeFloatProp(writer, classId, "Transparency", instances.map { it.props["Transparency"] as Float })
        writeFloatProp(writer, classId, "Reflectance", instances.map { it.props["Reflectance"] as Float })
        writeStringProp(writer, classId, "CollisionGroup", instances.map { it.props["CollisionGroup"] as String })
        writeIntProp(writer, classId, "CollisionGroupId", instances.map { it.props["CollisionGroupId"] as Int })
        writeIntProp(writer, classId, "RootPriority", instances.map { it.props["RootPriority"] as Int })
        writeStringProp(writer, classId, "MaterialVariantSerialized", instances.map { it.props["MaterialVariantSerialized"] as String })
        writeEnumProp(writer, classId, "Material", instances.map { it.props["Material"] as Int })
        writeVector3Prop(writer, classId, "Size", instances.map { it.props["Size"] as Vector3 })
        writeColor3Prop(writer, classId, "Color", instances.map { it.props["Color"] as Color3Value })
        writeCFrameProp(writer, classId, "CFrame", instances.map { it.props["CFrame"] as CFrameValue })
        writeVector3Prop(writer, classId, "Velocity", instances.map { it.props["Velocity"] as Vector3 })
        writeVector3Prop(writer, classId, "RotVelocity", instances.map { it.props["RotVelocity"] as Vector3 })
        writeIntProp(writer, classId, "formFactorRaw", instances.map { it.props["formFactorRaw"] as Int })
        listOf("TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface").forEach { name ->
            writeEnumProp(writer, classId, name, instances.map { it.props[name] as Int })
        }
        if (includeShape) {
            writeEnumProp(writer, classId, "Shape", instances.map { it.props["Shape"] as Int })
        }
    }

    private fun writeParentChunk(writer: BinaryWriter, instances: List<InstanceRecord>) {
        val chunk = BinaryWriter()
        chunk.writeUInt8(0)
        chunk.writeInt32LE(instances.size)

        var lastChild = 0
        val childReferents = IntArray(instances.size) { index ->
            val delta = instances[index].id - lastChild
            lastChild = instances[index].id
            zigZag32(delta)
        }

        var lastParent = 0
        val parentReferents = IntArray(instances.size) { index ->
            val parentId = instances[index].parentId
            val delta = parentId - lastParent
            lastParent = parentId
            zigZag32(delta)
        }

        chunk.writeInterleavedUInt32(childReferents)
        chunk.writeInterleavedUInt32(parentReferents)
        writeChunk(writer, "PRNT", chunk.toByteArray())
    }

    private fun writeStringProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_STRING) { chunk -> values.forEach { chunk.writeString(it) } }

    private fun writeBoolProp(writer: BinaryWriter, classId: Int, name: String, values: List<Boolean>) =
        writeProp(writer, classId, name, TYPE_BOOL) { chunk -> values.forEach { chunk.writeUInt8(if (it) 1 else 0) } }

    private fun writeIntProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_INT32) { chunk -> chunk.writeInterleavedUInt32(values.map { zigZag32(it) }.toIntArray()) }

    private fun writeFloatProp(writer: BinaryWriter, classId: Int, name: String, values: List<Float>) =
        writeProp(writer, classId, name, TYPE_FLOAT32) { chunk -> chunk.writeInterleavedUInt32(values.map { encodeFloat32(it) }.toIntArray()) }

    private fun writeEnumProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_ENUM) { chunk -> chunk.writeInterleavedUInt32(values.toIntArray()) }

    private fun writeVector3Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Vector3>) =
        writeProp(writer, classId, name, TYPE_VECTOR3) { chunk ->
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.x) }.toIntArray())
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.y) }.toIntArray())
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.z) }.toIntArray())
        }

    private fun writeColor3Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Color3Value>) =
        writeProp(writer, classId, name, TYPE_COLOR3) { chunk ->
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.r) }.toIntArray())
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.g) }.toIntArray())
            chunk.writeInterleavedUInt32(values.map { encodeFloat32(it.b) }.toIntArray())
        }

    private fun writeCFrameProp(writer: BinaryWriter, classId: Int, name: String, values: List<CFrameValue>) =
        writeProp(writer, classId, name, TYPE_CFRAME) { chunk ->
            val xs = IntArray(values.size)
            val ys = IntArray(values.size)
            val zs = IntArray(values.size)

            values.forEachIndexed { index, value ->
                val rotation = value.rotation
                if (isIdentity(rotation)) {
                    chunk.writeUInt8(0x02)
                } else {
                    chunk.writeUInt8(0x00)
                    rotation.forEach { chunk.writeFloat32LE(it) }
                }
                xs[index] = encodeFloat32(value.position.x)
                ys[index] = encodeFloat32(value.position.y)
                zs[index] = encodeFloat32(value.position.z)
            }

            chunk.writeInterleavedUInt32(xs)
            chunk.writeInterleavedUInt32(ys)
            chunk.writeInterleavedUInt32(zs)
        }

    private fun writeProp(
        writer: BinaryWriter,
        classId: Int,
        name: String,
        typeId: Int,
        writeValues: (BinaryWriter) -> Unit
    ) {
        val chunk = BinaryWriter()
        chunk.writeInt32LE(classId)
        chunk.writeString(name)
        chunk.writeUInt8(typeId)
        writeValues(chunk)
        writeChunk(writer, "PROP", chunk.toByteArray())
    }

    private fun writeChunk(writer: BinaryWriter, name: String, data: ByteArray) {
        writer.writeAscii4(name)
        writer.writeUInt32LE(0)
        writer.writeUInt32LE(data.size)
        writer.writeUInt32LE(0)
        writer.writeBytes(data)
    }

    private fun rotationMatrix(rotation: Vector3): FloatArray {
        val rx = Math.toRadians(rotation.x.toDouble())
        val ry = Math.toRadians(rotation.y.toDouble())
        val rz = Math.toRadians(rotation.z.toDouble())
        val cx = cos(rx).toFloat()
        val sx = sin(rx).toFloat()
        val cy = cos(ry).toFloat()
        val sy = sin(ry).toFloat()
        val cz = cos(rz).toFloat()
        val sz = sin(rz).toFloat()

        return floatArrayOf(
            cy * cz,
            cz * sx * sy - cx * sz,
            sx * sz + cx * cz * sy,
            cy * sz,
            cx * cz + sx * sy * sz,
            cx * sy * sz - cz * sx,
            -sy,
            cy * sx,
            cx * cy
        )
    }

    private fun isIdentity(values: FloatArray): Boolean {
        if (values.size != 9) return false
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        return values.indices.all { abs(values[it] - identity[it]) < 0.0001f }
    }

    private fun colorFromHex(hex: String): Color3Value {
        val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
        val value = runCatching { rgb.toInt(16) }.getOrDefault(0xCCCCCC)
        return Color3Value(
            r = ((value shr 16) and 0xFF) / 255f,
            g = ((value shr 8) and 0xFF) / 255f,
            b = (value and 0xFF) / 255f
        )
    }

    private fun materialToken(material: String): Int = when (material) {
        Part.MATERIAL_NEON -> 272
        Part.MATERIAL_WOOD -> 288
        Part.MATERIAL_GLASS -> 336
        Part.MATERIAL_MARBLE -> 400
        Part.MATERIAL_FABRIC -> 432
        Part.MATERIAL_BRICK -> 528
        Part.MATERIAL_METAL -> 784
        Part.MATERIAL_SLATE -> 1056
        else -> 256
    }

    private fun shapeToken(shape: String): Int = when (shape) {
        Part.SHAPE_SPHERE -> 0
        Part.SHAPE_CYLINDER -> 2
        else -> 1
    }

    private fun surfaceToken(surface: String): Int = when (surface) {
        Part.SURFACE_GLUE -> 1
        Part.SURFACE_WELD -> 2
        Part.SURFACE_STUDS -> 3
        Part.SURFACE_INLET -> 4
        Part.SURFACE_UNIVERSAL -> 5
        Part.SURFACE_HINGE -> 6
        Part.SURFACE_MOTOR -> 7
        Part.SURFACE_STEPPING_MOTOR -> 8
        Part.SURFACE_SMOOTH_NO_OUTLINES -> 10
        else -> 0
    }

    private fun zigZag32(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun encodeFloat32(value: Float): Int = Integer.rotateLeft(value.toRawBits(), 1)

    private data class InstanceRecord(
        val id: Int,
        val className: String,
        val name: String,
        val parentId: Int,
        val props: MutableMap<String, Any>
    )

    private data class Color3Value(val r: Float, val g: Float, val b: Float)

    private data class CFrameValue(val position: Vector3, val rotation: FloatArray)

    private class BinaryWriter {
        private val out = ByteArrayOutputStream()

        fun writeBytes(value: ByteArray) {
            out.write(value)
        }

        fun writeAscii4(value: String) {
            val bytes = ByteArray(4)
            value.toByteArray(Charsets.US_ASCII).copyInto(bytes, endIndex = minOf(4, value.length))
            writeBytes(bytes)
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeUInt32LE(bytes.size)
            writeBytes(bytes)
        }

        fun writeUInt8(value: Int) {
            out.write(value and 0xFF)
        }

        fun writeUInt16LE(value: Int) {
            writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }

        fun writeUInt32LE(value: Int) {
            writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun writeInt32LE(value: Int) = writeUInt32LE(value)

        fun writeFloat32LE(value: Float) {
            writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }

        fun writeInterleavedUInt32(values: IntArray) {
            if (values.isEmpty()) return
            val temp = ByteArray(values.size * 4)
            values.forEachIndexed { index, value ->
                val base = index * 4
                temp[base] = ((value ushr 24) and 0xFF).toByte()
                temp[base + 1] = ((value ushr 16) and 0xFF).toByte()
                temp[base + 2] = ((value ushr 8) and 0xFF).toByte()
                temp[base + 3] = (value and 0xFF).toByte()
            }
            repeat(4) { byteIndex ->
                values.indices.forEach { index ->
                    writeUInt8(temp[index * 4 + byteIndex].toInt())
                }
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val MAGIC = byteArrayOf(0x3C, 0x72, 0x6F, 0x62, 0x6C, 0x6F, 0x78, 0x21)
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val SERVICE_CLASSES = setOf(
        "Workspace",
        "Lighting",
        "ReplicatedStorage",
        "ServerScriptService",
        "ServerStorage",
        "StarterGui",
        "StarterPack",
        "Teams",
        "SoundService",
        "Chat",
        "Players",
        "ReplicatedFirst"
    )
    private const val TYPE_STRING = 0x01
    private const val TYPE_BOOL = 0x02
    private const val TYPE_INT32 = 0x03
    private const val TYPE_FLOAT32 = 0x04
    private const val TYPE_COLOR3 = 0x0C
    private const val TYPE_VECTOR3 = 0x0E
    private const val TYPE_CFRAME = 0x10
    private const val TYPE_ENUM = 0x12
}
