package com.example.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(tableName = "places")
@JsonClass(generateAdapter = true)
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String = "",
    val partsJson: String = "[]",
    val lastSaved: Long = System.currentTimeMillis(),
    val templateId: String = "empty",
    val robloxUniverseId: Long? = null,
    val robloxPlaceId: Long? = null
)
