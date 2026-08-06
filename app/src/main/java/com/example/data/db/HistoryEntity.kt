package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "compression_history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceType: String,
    val sourcePathOrUrl: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val durationMs: Long,
    val resolutionLabel: String,
    val bitrateKbps: Int,
    val format: String,
    val outputPath: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccessful: Boolean,
    val errorMessage: String? = null
)
