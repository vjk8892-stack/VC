package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM saved_presets ORDER BY id ASC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Query("SELECT COUNT(*) FROM saved_presets WHERE isSystemPreset = 1")
    suspend fun countSystemPresets(): Int

    @Query("SELECT presetName FROM saved_presets WHERE isSystemPreset = 1")
    suspend fun getSystemPresetNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Query("DELETE FROM saved_presets WHERE id = :id AND isSystemPreset = 0")
    suspend fun deletePresetById(id: Long)
}
