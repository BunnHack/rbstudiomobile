package com.example.publish

import com.example.models.Part
import com.example.models.Vector3
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

object RobloxPlaceXmlSerializer {
    fun serialize(placeName: String, parts: List<Part>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("""<roblox xmlns:xmime="http://www.w3.org/2005/05/xmlmime" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://www.roblox.com/roblox.xsd" version="4">""")
        sb.appendLine("\t<External>null</External>")
        sb.appendLine("\t<External>nil</External>")
        sb.appendLine("""	<Item class="Workspace" referent="${referent("Workspace")}">""")
        sb.appendLine("\t\t<Properties>")
        stringProp(sb, 3, "Name", "Workspace")
        boolProp(sb, 3, "ExplicitAutoJoints", true)
        floatProp(sb, 3, "Gravity", 196.2f)
        floatProp(sb, 3, "FallenPartsDestroyHeight", -500f)
        binaryStringProp(sb, 3, "AttributesSerialize", "")
        binaryStringProp(sb, 3, "CollisionGroupData", "AQEABP////8HRGVmYXVsdA==")
        sb.appendLine("\t\t</Properties>")

        parts.forEach { part ->
            appendPart(sb, part)
        }

        sb.appendLine("\t</Item>")
        sb.appendLine("""	<Item class="Lighting" referent="${referent("Lighting")}">""")
        sb.appendLine("\t\t<Properties>")
        stringProp(sb, 3, "Name", "Lighting")
        floatProp(sb, 3, "Brightness", 2f)
        boolProp(sb, 3, "GlobalShadows", true)
        sb.appendLine("\t\t</Properties>")
        sb.appendLine("\t</Item>")
        sb.appendLine("""	<Item class="ReplicatedStorage" referent="${referent("ReplicatedStorage")}">""")
        sb.appendLine("\t\t<Properties>")
        stringProp(sb, 3, "Name", "ReplicatedStorage")
        sb.appendLine("\t\t</Properties>")
        sb.appendLine("\t</Item>")
        sb.appendLine("""	<Item class="ServerScriptService" referent="${referent("ServerScriptService")}">""")
        sb.appendLine("\t\t<Properties>")
        stringProp(sb, 3, "Name", "ServerScriptService")
        sb.appendLine("\t\t</Properties>")
        sb.appendLine("\t</Item>")
        sb.appendLine("\t<SharedStrings></SharedStrings>")
        sb.appendLine("</roblox>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun appendPart(sb: StringBuilder, part: Part) {
        val className = when (part.shape) {
            Part.SHAPE_WEDGE -> "WedgePart"
            Part.SHAPE_SPAWN_LOCATION -> "SpawnLocation"
            else -> "Part"
        }
        sb.appendLine("""		<Item class="$className" referent="${referent(part.id)}">""")
        sb.appendLine("\t\t\t<Properties>")
        boolProp(sb, 4, "Anchored", part.anchored)
        binaryStringProp(sb, 4, "AttributesSerialize", "")
        surfaceProps(sb, part)
        coordinateFrameProp(sb, 4, "CFrame", part.position, part.rotation)
        boolProp(sb, 4, "CanCollide", part.canCollide)
        boolProp(sb, 4, "CanQuery", part.canQuery)
        boolProp(sb, 4, "CanTouch", part.canTouch)
        boolProp(sb, 4, "CastShadow", part.castShadow)
        stringProp(sb, 4, "CollisionGroup", part.collisionGroup.ifBlank { "Default" })
        intProp(sb, 4, "CollisionGroupId", part.collisionGroupId)
        color3uint8Prop(sb, 4, "Color3uint8", part.colorHex)
        physicalPropertiesProp(sb, 4, "CustomPhysicalProperties")
        if (part.shape == Part.SHAPE_SPAWN_LOCATION) {
            boolProp(sb, 4, "AllowTeamChangeOnTouch", part.allowTeamChangeOnTouch)
            intProp(sb, 4, "Duration", part.duration)
            boolProp(sb, 4, "Enabled", part.spawnEnabled)
            boolProp(sb, 4, "Neutral", part.neutral)
            intProp(sb, 4, "TeamColor", part.teamColor)
        }
        uniqueIdProp(sb, 4, "HistoryId", part.historyId.ifBlank { ZERO_UUID })
        boolProp(sb, 4, "Locked", part.locked)
        boolProp(sb, 4, "Massless", part.massless)
        tokenProp(sb, 4, "Material", materialToken(part.material))
        stringProp(sb, 4, "MaterialVariantSerialized", part.materialVariant)
        stringProp(sb, 4, "Name", part.name)
        coordinateFrameProp(sb, 4, "PivotOffset", Vector3.Zero, Vector3.Zero)
        floatProp(sb, 4, "Reflectance", part.reflectance)
        intProp(sb, 4, "RootPriority", part.rootPriority)
        vector3Prop(sb, 4, "RotVelocity", part.rotVelocity)
        longProp(sb, 4, "SourceAssetId", part.sourceAssetId)
        binaryStringProp(sb, 4, "Tags", "")
        floatProp(sb, 4, "Transparency", part.transparency)
        uniqueIdProp(sb, 4, "UniqueId", part.uniqueId.ifBlank { hexUuid() })
        vector3Prop(sb, 4, "Velocity", part.velocity)
        tokenProp(sb, 4, "formFactorRaw", part.formFactorRaw)
        if (className == "Part") {
            tokenProp(sb, 4, "shape", shapeToken(part.shape))
        }
        vector3Prop(sb, 4, "size", part.size)
        sb.appendLine("\t\t\t</Properties>")
        appendScriptIfNeeded(sb, part)
        sb.appendLine("\t\t</Item>")
    }

    private fun appendScriptIfNeeded(sb: StringBuilder, part: Part) {
        if (part.script.isBlank()) return
        sb.appendLine("""			<Item class="Script" referent="${referent(part.id + ":Script")}">""")
        sb.appendLine("\t\t\t\t<Properties>")
        stringProp(sb, 5, "Name", "Script")
        protectedStringProp(sb, 5, "Source", part.script)
        boolProp(sb, 5, "Disabled", false)
        sb.appendLine("\t\t\t\t</Properties>")
        sb.appendLine("\t\t\t</Item>")
    }

    private fun surfaceProps(sb: StringBuilder, part: Part) {
        surfacePair(sb, "Back", part.backSurface)
        surfacePair(sb, "Bottom", part.bottomSurface)
        surfacePair(sb, "Front", part.frontSurface)
        surfacePair(sb, "Left", part.leftSurface)
        surfacePair(sb, "Right", part.rightSurface)
        surfacePair(sb, "Top", part.topSurface)
    }

    private fun surfacePair(sb: StringBuilder, side: String, surface: String) {
        floatProp(sb, 4, "${side}ParamA", -0.5f)
        floatProp(sb, 4, "${side}ParamB", 0.5f)
        tokenProp(sb, 4, "${side}Surface", surfaceToken(surface))
        tokenProp(sb, 4, "${side}SurfaceInput", 0)
    }

    private fun coordinateFrameProp(sb: StringBuilder, indent: Int, name: String, position: Vector3, rotation: Vector3) {
        val matrix = rotationMatrix(rotation)
        line(sb, indent, """<CoordinateFrame name="$name">""")
        vectorChildren(sb, indent + 1, position)
        matrix.forEachIndexed { i, value ->
            line(sb, indent + 1, "<R${i / 3}${i % 3}>${num(value)}</R${i / 3}${i % 3}>")
        }
        line(sb, indent, "</CoordinateFrame>")
    }

    private fun vector3Prop(sb: StringBuilder, indent: Int, name: String, value: Vector3) {
        line(sb, indent, """<Vector3 name="$name">""")
        vectorChildren(sb, indent + 1, value)
        line(sb, indent, "</Vector3>")
    }

    private fun vectorChildren(sb: StringBuilder, indent: Int, value: Vector3) {
        line(sb, indent, "<X>${num(value.x)}</X>")
        line(sb, indent, "<Y>${num(value.y)}</Y>")
        line(sb, indent, "<Z>${num(value.z)}</Z>")
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

    private fun stringProp(sb: StringBuilder, indent: Int, name: String, value: String) {
        line(sb, indent, """<string name="$name">${xml(value)}</string>""")
    }

    private fun protectedStringProp(sb: StringBuilder, indent: Int, name: String, value: String) {
        line(sb, indent, """<ProtectedString name="$name">${xml(value)}</ProtectedString>""")
    }

    private fun boolProp(sb: StringBuilder, indent: Int, name: String, value: Boolean) {
        line(sb, indent, """<bool name="$name">${value.toString()}</bool>""")
    }

    private fun intProp(sb: StringBuilder, indent: Int, name: String, value: Int) {
        line(sb, indent, """<int name="$name">$value</int>""")
    }

    private fun longProp(sb: StringBuilder, indent: Int, name: String, value: Long) {
        line(sb, indent, """<int64 name="$name">$value</int64>""")
    }

    private fun floatProp(sb: StringBuilder, indent: Int, name: String, value: Float) {
        line(sb, indent, """<float name="$name">${num(value)}</float>""")
    }

    private fun tokenProp(sb: StringBuilder, indent: Int, name: String, value: Int) {
        line(sb, indent, """<token name="$name">$value</token>""")
    }

    private fun uniqueIdProp(sb: StringBuilder, indent: Int, name: String, value: String) {
        line(sb, indent, """<UniqueId name="$name">${xml(value)}</UniqueId>""")
    }

    private fun binaryStringProp(sb: StringBuilder, indent: Int, name: String, value: String) {
        line(sb, indent, """<BinaryString name="$name">${xml(value)}</BinaryString>""")
    }

    private fun physicalPropertiesProp(sb: StringBuilder, indent: Int, name: String) {
        line(sb, indent, """<PhysicalProperties name="$name">""")
        line(sb, indent + 1, "<CustomPhysics>false</CustomPhysics>")
        line(sb, indent, "</PhysicalProperties>")
    }

    private fun color3uint8Prop(sb: StringBuilder, indent: Int, name: String, hex: String) {
        val rgb = hex.removePrefix("#").padEnd(6, '0').take(6)
        val packed = runCatching {
            0xFF000000L or rgb.toLong(16)
        }.getOrDefault(0xFFCCCCCCL)
        line(sb, indent, """<Color3uint8 name="$name">$packed</Color3uint8>""")
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

    private fun line(sb: StringBuilder, indent: Int, text: String) {
        repeat(indent) { sb.append('\t') }
        sb.appendLine(text)
    }

    private fun referent(seed: String): String = "RBX" + UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
        .replace("-", "")
        .uppercase(Locale.US)

    private fun hexUuid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun num(value: Float): String = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifBlank { "0" }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val ZERO_UUID = "00000000000000000000000000000000"
}
