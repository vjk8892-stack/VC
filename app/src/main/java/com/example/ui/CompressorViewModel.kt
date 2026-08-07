package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.example.data.db.PresetEntity
import com.example.data.model.VideoCodec
import com.example.data.model.VideoCompressionSettings
import com.example.engine.CompressionEngine

/**
 * Thin UI-facing layer: every StateFlow and function here just forwards to [CompressionEngine],
 * a singleton that isn't tied to this ViewModel's lifecycle. That's what lets a batch keep
 * running - tracked by [com.example.service.CompressionForegroundService] - even if this
 * ViewModel is cleared (e.g. the OS reclaims the backgrounded app's UI layer).
 */
class CompressorViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CompressionEngine.getInstance(application)

    val maxSystemCores: Int get() = engine.maxSystemCores
    val isGpuAvailable: Boolean get() = engine.isGpuAvailable
    val supportedCodecs: Set<VideoCodec> get() = engine.supportedCodecs
    val totalRamGb: Double get() = engine.totalRamGb

    val queue = engine.queue
    val globalSettings = engine.globalSettings
    val useGlobalSettings = engine.useGlobalSettings
    val isBatchRunning = engine.isBatchRunning
    val isBatchPaused = engine.isBatchPaused
    val soundNotificationEnabled = engine.soundNotificationEnabled
    val isDarkMode = engine.isDarkMode
    val snackMessages = engine.snackMessages

    val historyList = engine.historyList
    val savedPresets = engine.savedPresets

    fun toggleDarkMode() = engine.toggleDarkMode()

    fun toggleSoundNotification() = engine.toggleSoundNotification()

    fun toggleUseGlobalSettings(useGlobal: Boolean) = engine.toggleUseGlobalSettings(useGlobal)

    fun updateGlobalSettings(updateBlock: (VideoCompressionSettings) -> VideoCompressionSettings) =
        engine.updateGlobalSettings(updateBlock)

    fun addLocalVideoUris(uris: List<Uri>) = engine.addLocalVideoUris(uris)

    fun addUrlSource(url: String) = engine.addUrlSource(url)

    fun updateItemSettings(itemId: String, settings: VideoCompressionSettings) =
        engine.updateItemSettings(itemId, settings)

    fun removeItem(itemId: String) = engine.removeItem(itemId)

    fun reorderQueue(fromIndex: Int, toIndex: Int) = engine.reorderQueue(fromIndex, toIndex)

    fun clearQueue() = engine.clearQueue()

    fun startBatchProcessing() = engine.startBatchProcessing()

    fun pauseBatch() = engine.pauseBatch()

    fun resumeBatch() = engine.resumeBatch()

    fun cancelBatch() = engine.cancelBatch()

    fun applyPreset(preset: PresetEntity) = engine.applyPreset(preset)

    fun saveCustomPreset(presetName: String) = engine.saveCustomPreset(presetName)

    fun deletePreset(presetId: Long) = engine.deletePreset(presetId)

    fun deleteHistoryItem(id: String) = engine.deleteHistoryItem(id)

    fun clearHistory() = engine.clearHistory()
}
