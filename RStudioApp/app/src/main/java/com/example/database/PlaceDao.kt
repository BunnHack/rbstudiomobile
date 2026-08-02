package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.models.Place
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY lastSaved DESC")
    fun getAllPlaces(): Flow<List<Place>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getPlaceById(id: Int): Place?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlace(place: Place): Long

    @Update
    suspend fun updatePlace(place: Place)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deletePlaceById(id: Int)
}
