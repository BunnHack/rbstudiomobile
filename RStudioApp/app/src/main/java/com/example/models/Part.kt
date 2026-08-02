package com.example.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Part(
    val id: String,
    val name: String,
    val shape: String = SHAPE_BLOCK,
    val position: Vector3 = Vector3(0f, 0f, 0f),
    val size: Vector3 = Vector3(2f, 2f, 2f),
    val rotation: Vector3 = Vector3(0f, 0f, 0f),
    val colorHex: String = "#CCCCCC",
    val brickColor: String = "Medium stone grey",
    val material: String = MATERIAL_PLASTIC,
    val anchored: Boolean = true,
    val canCollide: Boolean = true,
    val canQuery: Boolean = true,
    val canTouch: Boolean = true,
    val locked: Boolean = false,
    val massless: Boolean = false,
    val castShadow: Boolean = true,
    val reflectance: Float = 0f,
    val transparency: Float = 0f,
    val collisionGroup: String = "Default",
    val collisionGroupId: Int = 0,
    val rootPriority: Int = 0,
    val customPhysicalProperties: String = "Default",
    val materialVariant: String = "",
    val topSurface: String = SURFACE_SMOOTH,
    val bottomSurface: String = SURFACE_SMOOTH,
    val leftSurface: String = SURFACE_SMOOTH,
    val rightSurface: String = SURFACE_SMOOTH,
    val frontSurface: String = SURFACE_SMOOTH,
    val backSurface: String = SURFACE_SMOOTH,
    val formFactorRaw: Int = 0,
    val sourceAssetId: Long = -1L,
    val uniqueId: String = "",
    val historyId: String = "",
    val tags: List<String> = emptyList(),
    val rotVelocity: Vector3 = Vector3(0f, 0f, 0f),
    val spawnEnabled: Boolean = true,
    val neutral: Boolean = true,
    val allowTeamChangeOnTouch: Boolean = false,
    val duration: Int = 0,
    val teamColor: Int = 194,
    val parentId: String? = null,
    val script: String = "",
    val effect: String = EFFECT_NONE,
    // Transient physics states
    val currentPosition: Vector3 = position,
    val currentRotation: Vector3 = rotation,
    val velocity: Vector3 = Vector3(0f, 0f, 0f)
) {
    companion object {
        const val SHAPE_BLOCK = "BLOCK"
        const val SHAPE_SPHERE = "SPHERE"
        const val SHAPE_CYLINDER = "CYLINDER"
        const val SHAPE_WEDGE = "WEDGE"
        const val SHAPE_SPAWN_LOCATION = "SPAWN_LOCATION"

        const val MATERIAL_PLASTIC = "Plastic"
        const val MATERIAL_WOOD = "Wood"
        const val MATERIAL_SLATE = "Slate"
        const val MATERIAL_BRICK = "Brick"
        const val MATERIAL_NEON = "Neon"
        const val MATERIAL_METAL = "Metal"
        const val MATERIAL_GLASS = "Glass"
        const val MATERIAL_FABRIC = "Fabric"
        const val MATERIAL_MARBLE = "Marble"

        const val EFFECT_NONE = "NONE"
        const val EFFECT_FIRE = "FIRE"
        const val EFFECT_SMOKE = "SMOKE"
        const val EFFECT_SPARKLES = "SPARKLES"
        const val EFFECT_POINTLIGHT = "POINTLIGHT"

        const val SURFACE_SMOOTH = "Smooth"
        const val SURFACE_GLUE = "Glue"
        const val SURFACE_WELD = "Weld"
        const val SURFACE_STUDS = "Studs"
        const val SURFACE_INLET = "Inlet"
        const val SURFACE_UNIVERSAL = "Universal"
        const val SURFACE_HINGE = "Hinge"
        const val SURFACE_MOTOR = "Motor"
        const val SURFACE_STEPPING_MOTOR = "SteppingMotor"
        const val SURFACE_SMOOTH_NO_OUTLINES = "SmoothNoOutlines"

        val SURFACE_TYPES: List<String> = listOf(
            SURFACE_SMOOTH,
            SURFACE_GLUE,
            SURFACE_WELD,
            SURFACE_STUDS,
            SURFACE_INLET,
            SURFACE_UNIVERSAL,
            SURFACE_HINGE,
            SURFACE_MOTOR,
            SURFACE_STEPPING_MOTOR,
            SURFACE_SMOOTH_NO_OUTLINES
        )

        fun surfaceFromToken(token: Int): String = when (token) {
            0 -> SURFACE_SMOOTH
            1 -> SURFACE_GLUE
            2 -> SURFACE_WELD
            3 -> SURFACE_STUDS
            4 -> SURFACE_INLET
            5 -> SURFACE_UNIVERSAL
            6 -> SURFACE_HINGE
            7 -> SURFACE_MOTOR
            8 -> SURFACE_STEPPING_MOTOR
            10 -> SURFACE_SMOOTH_NO_OUTLINES
            else -> SURFACE_SMOOTH
        }

        /** Roblox BrickColor name → hex color. Common subset. */
        val BRICK_COLORS: List<Pair<String, String>> = listOf(
            "White" to "#F2F3F3",
            "Ghost grey" to "#FBF5DD",
            "Light stone grey" to "#E5E5E5",
            "Medium stone grey" to "#CCCCCC",
            "Dark stone grey" to "#7A7A7A",
            "Black" to "#1B2A34",
            "Really black" to "#0A0A0A",
            "Bright red" to "#FF0000",
            "Really red" to "#C40000",
            "Bright blue" to "#0055A5",
            "Really blue" to "#0033CC",
            "Bright green" to "#00AA00",
            "Lime green" to "#A5FF00",
            "Bright yellow" to "#FFFF00",
            "New Yeller" to "#FFD700",
            "Bright orange" to "#FF8C00",
            "Really orange" to "#FF5500",
            "Bright violet" to "#AA00FF",
            "Magenta" to "#FF00FF",
            "Pink" to "#FF00AA",
            "Hot pink" to "#FF66CC",
            "Cyan" to "#00FFFF",
            "Teal" to "#00AAFF",
            "Institutional white" to "#F8F8F8",
            "Brick yellow" to "#E5A100",
            "Navy blue" to "#0033AA",
            "Earth green" to "#228B22",
            "Earth orange" to "#8B4513",
            "Sand red" to "#AA5500",
            "Sand green" to "#55AA55",
            "Dark green" to "#1B5E20",
            "Dark red" to "#8B0000",
            "Dark blue" to "#001F5F",
            "Medium red" to "#D11515",
            "Medium green" to "#7CB342",
            "Medium blue" to "#1976D2",
            "Grime" to "#5A5A5A",
            "Lily white" to "#F0E6D2",
            "Pastel green" to "#A5C785",
            "Pastel blue" to "#A5C8E0",
            "Pastel yellow" to "#FAE5A0",
            "Pastel red" to "#E8A0A0",
            "Pastel brown" to "#A0855A",
            "Brown" to "#5C4033",
            "Dark brown" to "#3B2F2F",
            "Dirt brown" to "#8B7355",
            "Reddish brown" to "#6B4423",
            "Light reddish violet" to "#C8A2C8",
            "Medium reddish violet" to "#A050A0",
            "Dark reddish violet" to "#5F2A5F",
            "Light orange" to "#FFCC80",
            "Cool yellow" to "#FFEE58",
            "Alder" to "#8B4789",
            "Daisy orange" to "#E8A040",
            "Olive" to "#808000",
            "Gold" to "#FFD700",
            "Quill grey" to "#9DA2A6",
            "Silver" to "#C0C0C0",
            "Copper" to "#B87333",
            "Carbon" to "#333333"
        )

        /** Map a BrickColor name to its hex color. */
        fun brickColorToHex(name: String): String? =
            BRICK_COLORS.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    fun toRuntimeReset(): Part {
        return this.copy(
            currentPosition = position,
            currentRotation = rotation,
            velocity = Vector3.Zero
        )
    }

    fun applyPhysicsStep(pos: Vector3, rot: Vector3, vel: Vector3): Part {
        return this.copy(
            currentPosition = pos,
            currentRotation = rot,
            velocity = vel
        )
    }
}
