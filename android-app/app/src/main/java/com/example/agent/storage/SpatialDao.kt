package com.example.agent.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpatialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SpatialRecord)

    @Query("DELETE FROM spatial_memory WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT * FROM spatial_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SpatialRecord>
}
