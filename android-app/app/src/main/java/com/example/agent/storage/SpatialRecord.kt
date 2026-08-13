package com.example.agent.storage

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "spatial_memory")
data class SpatialRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val covLidar: Float,
    val covMmwave: Float,
    val metadataJson: String,
)
