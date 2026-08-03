package com.example.publish

import com.example.models.Part
import com.example.models.Vector3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Serializes the current scene into the Roblox binary place format (.rbxl).
 *
 * The layout follows the spec implemented by github.com/RobloxAPI/rbxfile:
 *  - Header: "<roblox!" + 0x89FF0D0A1A0A + version(0) + classCount + instanceCount + 8 reserved
 *  - Chunks: META, SSTR, INST (one per class), PROP (one per class+property), PRNT, END
 *  - Chunks are stored uncompressed (compressedLength = 0); the server accepts both.
 *  - Referents are sequential (0..n) assigned in DataModel-tree order, services first.
 *  - Referent/int32 arrays are zigzag + big-endian + byte-interleaved.
 *  - Float arrays use the Roblox float transform (rotl(bits,1)), big-endian, interleaved.
 *  - Vector3/Color3 write X,Y,Z as one interleaved block (not three separate blocks).
 *  - CFrame rotations are written as special orientation IDs (0x02 = identity, 0x00 = raw matrix).
 *  - Chunk order matches Studio: INST chunks sorted by class name, then PROP chunks per class.
 */
object RobloxPlaceBinarySerializer {
    fun serialize(placeName: String, parts: List<Part>): ByteArray {
        val instances = buildInstances(parts)
        // Sequential referents in declaration order (DataModel tree order).
        instances.forEachIndexed { index, instance -> instance.referent = index }

        // Group by class, chunks sorted by class name like Studio does.
        val classes = instances
            .groupBy { it.className }
            .toSortedMap()
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

    private fun buildInstances(parts: List<Part>): List<InstanceRecord> {
        val instances = mutableListOf<InstanceRecord>()
        fun add(className: String, name: String, parent: InstanceRecord?): InstanceRecord {
            val instance = InstanceRecord(className, name, parent)
            instances.add(instance)
            return instance
        }

        // Core services required for a place to load and run.
        val workspace = add("Workspace", "Workspace", null).apply {
            props["Gravity"] = 196.2f
            props["FallenPartsDestroyHeight"] = -500f
            props["StreamingEnabled"] = false
            props["DistributedGameTime"] = 0.0
            props["GlobalWind"] = Vector3.Zero
            props["ExplicitAutoJoints"] = true
        }
        add("Players", "Players", null).apply {
            props["MaxPlayersInternal"] = 6
            props["PreferredPlayersInternal"] = 6
            props["CharacterAutoLoads"] = true
            props["RespawnTime"] = 3f
        }
        val lighting = add("Lighting", "Lighting", null).apply {
            props["Brightness"] = 2f
            props["GlobalShadows"] = true
            props["TimeOfDay"] = "14:30:00"
            props["Technology"] = 3
        }
        add("ReplicatedFirst", "ReplicatedFirst", null)
        add("ReplicatedStorage", "ReplicatedStorage", null)
        add("ServerScriptService", "ServerScriptService", null)
        add("ServerStorage", "ServerStorage", null)
        add("StarterGui", "StarterGui", null)
        add("StarterPack", "StarterPack", null)
        add("StarterPlayer", "StarterPlayer", null)
        add("Teams", "Teams", null)
        add("SoundService", "SoundService", null)

        parts.forEachIndexed { index, part ->
            val instance = add(classNameFor(part), part.name.ifBlank { "Part${index + 1}" }, workspace)
            addBasePartProps(instance, part)
            if (instance.className == "Part" || instance.className == "SpawnLocation") {
                instance.props["Shape"] = shapeToken(part.shape)
            }
            if (instance.className == "SpawnLocation") {
                instance.props["AllowTeamChangeOnTouch"] = part.allowTeamChangeOnTouch
                instance.props["Duration"] = part.duration
                instance.props["Enabled"] = part.spawnEnabled
                instance.props["Neutral"] = part.neutral
            }
            if (part.script.isNotBlank()) {
                val script = add("Script", "Script", instance)
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
        instance.props["size"] = part.size
        instance.props["Color3uint8"] = colorFromHex(part.colorHex)
        instance.props["CFrame"] = CFrameValue(part.position, rotationMatrix(part.rotation))
        instance.props["PivotOffset"] = CFrameValue(Vector3.Zero, rotationMatrix(Vector3.Zero))
        instance.props["Velocity"] = part.velocity
        instance.props["RotVelocity"] = part.rotVelocity
        instance.props["formFactorRaw"] = part.formFactorRaw
        instance.props["TopSurface"] = surfaceToken(part.topSurface)
        instance.props["BottomSurface"] = surfaceToken(part.bottomSurface)
        instance.props["LeftSurface"] = surfaceToken(part.leftSurface)
        instance.props["RightSurface"] = surfaceToken(part.rightSurface)
        instance.props["FrontSurface"] = surfaceToken(part.frontSurface)
        instance.props["BackSurface"] = surfaceToken(part.backSurface)
        instance.props["CustomPhysicalProperties"] = 0.toByte() // PhysicalProperties: CustomPhysics = false
        instance.props["SourceAssetId"] = -1L
        instance.props["UniqueId"] = randomUniqueId()
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
        chunk.writeUInt32LE(0) // version
        chunk.writeUInt32LE(0) // count
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
        chunk.writeInterleavedInts(instances.map { it.referent }.toIntArray(), zigzag = true, accumulate = true)
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
                writeBoolProp(writer, classId, "StreamingEnabled", instances.map { it.props["StreamingEnabled"] as Boolean })
                writeDoubleProp(writer, classId, "DistributedGameTime", instances.map { it.props["DistributedGameTime"] as Double })
                writeVector3Prop(writer, classId, "GlobalWind", instances.map { it.props["GlobalWind"] as Vector3 })
                writeBoolProp(writer, classId, "ExplicitAutoJoints", instances.map { it.props["ExplicitAutoJoints"] as Boolean })
            }
            "Players" -> {
                writeIntProp(writer, classId, "MaxPlayersInternal", instances.map { it.props["MaxPlayersInternal"] as Int })
                writeIntProp(writer, classId, "PreferredPlayersInternal", instances.map { it.props["PreferredPlayersInternal"] as Int })
                writeBoolProp(writer, classId, "CharacterAutoLoads", instances.map { it.props["CharacterAutoLoads"] as Boolean })
                writeFloatProp(writer, classId, "RespawnTime", instances.map { it.props["RespawnTime"] as Float })
            }
            "Lighting" -> {
                writeFloatProp(writer, classId, "Brightness", instances.map { it.props["Brightness"] as Float })
                writeBoolProp(writer, classId, "GlobalShadows", instances.map { it.props["GlobalShadows"] as Boolean })
                writeStringProp(writer, classId, "TimeOfDay", instances.map { it.props["TimeOfDay"] as String })
                writeEnumProp(writer, classId, "Technology", instances.map { it.props["Technology"] as Int })
            }
            "Part", "WedgePart", "SpawnLocation" -> writeBasePartProps(writer, classId, instances, className != "WedgePart")
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
        writeVector3Prop(writer, classId, "size", instances.map { it.props["size"] as Vector3 })
        writeColor3uint8Prop(writer, classId, "Color3uint8", instances.map { it.props["Color3uint8"] as Int })
        writeCFrameProp(writer, classId, "CFrame", instances.map { it.props["CFrame"] as CFrameValue })
        writeCFrameProp(writer, classId, "PivotOffset", instances.map { it.props["PivotOffset"] as CFrameValue })
        writePhysicalPropertiesProp(writer, classId, "CustomPhysicalProperties", instances.size)
        writeVector3Prop(writer, classId, "Velocity", instances.map { it.props["Velocity"] as Vector3 })
        writeVector3Prop(writer, classId, "RotVelocity", instances.map { it.props["RotVelocity"] as Vector3 })
        writeEnumProp(writer, classId, "formFactorRaw", instances.map { it.props["formFactorRaw"] as Int })
        listOf("TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface").forEach { name ->
            writeEnumProp(writer, classId, name, instances.map { it.props[name] as Int })
        }
        if (includeShape) {
            writeEnumProp(writer, classId, "shape", instances.map { it.props["Shape"] as Int })
        }
        writeInt64Prop(writer, classId, "SourceAssetId", instances.map { it.props["SourceAssetId"] as Long })
        writeUniqueIdProp(writer, classId, "UniqueId", instances.map { it.props["UniqueId"] as String })
    }

    private fun writeParentChunk(writer: BinaryWriter, instances: List<InstanceRecord>) {
        val chunk = BinaryWriter()
        chunk.writeUInt8(0) // version
        chunk.writeInt32LE(instances.size)
        chunk.writeInterleavedInts(instances.map { it.referent }.toIntArray(), zigzag = true, accumulate = true)
        chunk.writeInterleavedInts(instances.map { it.parent?.referent ?: -1 }.toIntArray(), zigzag = true, accumulate = true)
        writeChunk(writer, "PRNT", chunk.toByteArray())
    }

    private fun writeStringProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_STRING) { chunk -> values.forEach { chunk.writeString(it) } }

    private fun writeBoolProp(writer: BinaryWriter, classId: Int, name: String, values: List<Boolean>) =
        writeProp(writer, classId, name, TYPE_BOOL) { chunk -> values.forEach { chunk.writeUInt8(if (it) 1 else 0) } }

    private fun writeIntProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_INT32) { chunk -> chunk.writeInterleavedInts(values.toIntArray(), zigzag = true) }

    private fun writeInt64Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Long>) =
        writeProp(writer, classId, name, TYPE_INT64) { chunk -> chunk.writeInterleavedInt64(values.toLongArray()) }

    private fun writeFloatProp(writer: BinaryWriter, classId: Int, name: String, values: List<Float>) =
        writeProp(writer, classId, name, TYPE_FLOAT32) { chunk -> chunk.writeInterleavedFloats(values.toFloatArray()) }

    private fun writeEnumProp(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_ENUM) { chunk -> chunk.writeInterleavedInts(values.toIntArray(), zigzag = false) }

    private fun writeVector3Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Vector3>) =
        writeProp(writer, classId, name, TYPE_VECTOR3) { chunk ->
            // Three separate interleaved blocks: all X, then all Y, then all Z.
            chunk.writeInterleavedFloats(values.map { it.x }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.y }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.z }.toFloatArray())
        }

    private fun writeColor3uint8Prop(writer: BinaryWriter, classId: Int, name: String, values: List<Int>) =
        writeProp(writer, classId, name, TYPE_COLOR3UINT8) { chunk ->
            values.forEach { value ->
                chunk.writeUInt8((value shr 16) and 0xFF)
                chunk.writeUInt8((value shr 8) and 0xFF)
                chunk.writeUInt8(value and 0xFF)
            }
        }

    private fun writeDoubleProp(writer: BinaryWriter, classId: Int, name: String, values: List<Double>) =
        writeProp(writer, classId, name, TYPE_DOUBLE) { chunk ->
            values.forEach { chunk.writeFloat64LE(it) }
        }

    private fun writePhysicalPropertiesProp(writer: BinaryWriter, classId: Int, name: String, count: Int) =
        writeProp(writer, classId, name, TYPE_PHYSICALPROPS) { chunk ->
            // 0x00 = default physical properties (no custom values follow)
            repeat(count) { chunk.writeUInt8(0) }
        }

    private fun writeUniqueIdProp(writer: BinaryWriter, classId: Int, name: String, values: List<String>) =
        writeProp(writer, classId, name, TYPE_UNIQUEID) { chunk ->
            // 16 bytes: version(4 LE) + variant(4 LE) + timestamp(8 LE). All zero is acceptable.
            values.forEach { chunk.writeBytes(ByteArray(16)) }
        }

    private fun writeCFrameProp(writer: BinaryWriter, classId: Int, name: String, values: List<CFrameValue>) =
        writeProp(writer, classId, name, TYPE_CFRAME) { chunk ->
            values.forEach { value ->
                val special = cframeSpecialId(value.rotation)
                chunk.writeUInt8(special)
                if (special == 0) {
                    value.rotation.forEach { chunk.writeFloat32LE(it) }
                }
            }
            // Positions are written as three separate interleaved blocks (X, then Y, then Z),
            // matching Roblox's reader which deinterleaves per axis.
            chunk.writeInterleavedFloats(values.map { it.position.x }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.position.y }.toFloatArray())
            chunk.writeInterleavedFloats(values.map { it.position.z }.toFloatArray())
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
        writer.writeUInt32LE(0) // compressedLength = 0 -> uncompressed
        writer.writeUInt32LE(data.size)
        writer.writeUInt32LE(0) // reserved
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

    /** Maps an axis-aligned rotation matrix to its Roblox special orientation ID (0x02..0x23), or 0 for a raw matrix. */
    private fun cframeSpecialId(rotation: FloatArray): Int {
        val index = CFRAME_SPECIAL_MATRICES.indexOfFirst { special ->
            rotation.indices.all { i -> kotlin.math.abs(rotation[i] - special[i]) < 0.0001f }
        }
        return if (index >= 0) CFRAME_SPECIAL_IDS[index] else 0
    }

    private fun colorFromHex(hex: String): Int {
        val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
        val value = runCatching { rgb.toInt(16) }.getOrDefault(0xCCCCCC)
        return (0xFF shl 24) or value
    }

    private fun randomUniqueId(): String = UUID.randomUUID().toString().replace("-", "")

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

    private class InstanceRecord(
        val className: String,
        val name: String,
        val parent: InstanceRecord?,
        val props: MutableMap<String, Any> = linkedMapOf()
    ) {
        var referent: Int = -1
    }

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

        fun writeFloat64LE(value: Double) {
            writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        }

        /**
         * Writes int32 values as big-endian, byte-interleaved.
         * [zigzag] applies zigzag encoding (referents, Int32 props).
         * [accumulate] converts values to delta-from-previous first (referent arrays).
         */
        fun writeInterleavedInts(values: IntArray, zigzag: Boolean, accumulate: Boolean = false) {
            if (values.isEmpty()) return
            val encoded = IntArray(values.size)
            var last = 0
            for (i in values.indices) {
                var v = values[i]
                if (accumulate) {
                    val delta = v - last
                    last = v
                    v = delta
                }
                encoded[i] = if (zigzag) (v shl 1) xor (v shr 31) else v
            }
            writeInterleaved(encoded)
        }

        /** Writes int64 values as zigzag + big-endian + byte-interleaved. */
        fun writeInterleavedInt64(values: LongArray) {
            if (values.isEmpty()) return
            val bytes = ByteArray(values.size * 8)
            values.forEachIndexed { index, value ->
                val encoded = (value shl 1) xor (value shr 63)
                val base = index * 8
                for (b in 0 until 8) {
                    bytes[base + b] = (encoded ushr (56 - b * 8)).toByte()
                }
            }
            repeat(8) { byteIndex ->
                values.indices.forEach { index -> writeUInt8(bytes[index * 8 + byteIndex].toInt()) }
            }
        }

        /** Writes floats with the Roblox float transform (rotl(bits, 1)), big-endian, interleaved. */
        fun writeInterleavedFloats(values: FloatArray) {
            if (values.isEmpty()) return
            val encoded = IntArray(values.size) { i -> Integer.rotateLeft(values[i].toRawBits(), 1) }
            writeInterleaved(encoded)
        }

        private fun writeInterleaved(encoded: IntArray) {
            repeat(4) { byteIndex ->
                encoded.forEach { value ->
                    writeUInt8((value ushr (24 - byteIndex * 8)) and 0xFF)
                }
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val MAGIC = byteArrayOf(0x3C, 0x72, 0x6F, 0x62, 0x6C, 0x6F, 0x78, 0x21)
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private val SERVICE_CLASSES = setOf(
        "Workspace",
        "Players",
        "Lighting",
        "ReplicatedFirst",
        "ReplicatedStorage",
        "ServerScriptService",
        "ServerStorage",
        "StarterGui",
        "StarterPack",
        "StarterPlayer",
        "Teams",
        "SoundService",
        "Chat",
        "MaterialService"
    )

    // Axis-aligned rotation matrices and their special orientation IDs (rbxfile spec).
    private val CFRAME_SPECIAL_IDS = intArrayOf(
        0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x10, 0x11,
        0x14, 0x15, 0x17, 0x18, 0x19, 0x1B, 0x1C, 0x1E, 0x1F, 0x20, 0x22, 0x23
    )
    private val CFRAME_SPECIAL_MATRICES = arrayOf(
        floatArrayOf(+1f, +0f, +0f, +0f, +1f, +0f, +0f, +0f, +1f),
        floatArrayOf(+1f, +0f, +0f, +0f, +0f, -1f, +0f, +1f, +0f),
        floatArrayOf(+1f, +0f, +0f, +0f, -1f, +0f, +0f, +0f, -1f),
        floatArrayOf(+1f, +0f, +0f, +0f, +0f, +1f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +1f, +0f, +0f, +0f, +0f, -1f),
        floatArrayOf(+0f, +0f, +1f, +1f, +0f, +0f, +0f, +1f, +0f),
        floatArrayOf(+0f, -1f, +0f, +1f, +0f, +0f, +0f, +0f, +1f),
        floatArrayOf(+0f, +0f, -1f, +1f, +0f, +0f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +0f, +0f, +1f, +1f, +0f, +0f),
        floatArrayOf(+0f, +0f, -1f, +0f, +1f, +0f, +1f, +0f, +0f),
        floatArrayOf(+0f, -1f, +0f, +0f, +0f, -1f, +1f, +0f, +0f),
        floatArrayOf(+0f, +0f, +1f, +0f, -1f, +0f, +1f, +0f, +0f),
        floatArrayOf(-1f, +0f, +0f, +0f, +1f, +0f, +0f, +0f, -1f),
        floatArrayOf(-1f, +0f, +0f, +0f, +0f, +1f, +0f, +1f, +0f),
        floatArrayOf(-1f, +0f, +0f, +0f, -1f, +0f, +0f, +0f, +1f),
        floatArrayOf(-1f, +0f, +0f, +0f, +0f, -1f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, -1f, +0f, +0f, +0f, +0f, +1f),
        floatArrayOf(+0f, +0f, -1f, -1f, +0f, +0f, +0f, +1f, +0f),
        floatArrayOf(+0f, -1f, +0f, -1f, +0f, +0f, +0f, +0f, -1f),
        floatArrayOf(+0f, +0f, +1f, -1f, +0f, +0f, +0f, -1f, +0f),
        floatArrayOf(+0f, +1f, +0f, +0f, +0f, -1f, -1f, +0f, +0f),
        floatArrayOf(+0f, +0f, +1f, +0f, +1f, +0f, -1f, +0f, +0f),
        floatArrayOf(+0f, -1f, +0f, +0f, +0f, +1f, -1f, +0f, +0f),
        floatArrayOf(+0f, +0f, -1f, +0f, -1f, +0f, -1f, +0f, +0f)
    )

    private const val TYPE_STRING = 0x01
    private const val TYPE_BOOL = 0x02
    private const val TYPE_INT32 = 0x03
    private const val TYPE_FLOAT32 = 0x04
    private const val TYPE_DOUBLE = 0x05
    private const val TYPE_PHYSICALPROPS = 0x19
    private const val TYPE_COLOR3UINT8 = 0x1A
    private const val TYPE_VECTOR3 = 0x0E
    private const val TYPE_CFRAME = 0x10
    private const val TYPE_ENUM = 0x12
    private const val TYPE_INT64 = 0x1B
    private const val TYPE_UNIQUEID = 0x1F
}
