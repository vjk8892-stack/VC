package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetName: String,
    val resolutionName: String,
    val bitrateKbps: Int,
    val formatName: String,
    val removeAudio: Boolean,
    val resourceModeName: String,
    val isSystemPreset: Boolean = false
)
