package com.example.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.HistoryEntity
import com.example.data.db.PresetEntity
import com.example.data.model.BitratePreset
import com.example.data.model.CompressionItemState
import com.example.data.model.OutputFormat
import com.example.data.model.ResolutionPreset
import com.example.data.model.ResourceMode
import com.example.data.model.VideoCodec
import com.example.data.model.VideoCompressionSettings
import com.example.data.model.VideoQueueItem
import com.example.data.model.VideoSourceType
import com.example.engine.TranscodeCancelledException
import com.example.engine.TranscodePausedException
import com.example.engine.UrlVideoDownloader
import com.example.engine.VideoTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CompressorViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val historyDao = db.historyDao()
    private val presetDao = db.presetDao()

    private val downloader = UrlVideoDownloader(application)
    private val transcoder = VideoTranscoder(application)

    // UI State - all read from the actual device at startup, not fixed numbers.
    val maxSystemCores: Int = Runtime.getRuntime().availableProcessors()
    val isGpuAvailable: Boolean = transcoder.isGpuHardwareAccelerationAvailable()
    val supportedCodecs: Set<VideoCodec> = VideoCodec.entries.filter { transcoder.isCodecSupported(it) }.toSet()
    val totalRamGb: Double = run {
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    private val _queue = MutableStateFlow<List<VideoQueueItem>>(emptyList())
    val queue: StateFlow<List<VideoQueueItem>> = _queue.asStateFlow()

    private val _globalSettings = MutableStateFlow(VideoCompressionSettings())
    val globalSettings: StateFlow<VideoCompressionSettings> = _globalSettings.asStateFlow()

    private val _useGlobalSettings = MutableStateFlow(true)
    val useGlobalSettings: StateFlow<Boolean> = _useGlobalSettings.asStateFlow()

    private val _isBatchRunning = MutableStateFlow(false)
    val isBatchRunning: StateFlow<Boolean> = _isBatchRunning.asStateFlow()

    private val _isBatchPaused = MutableStateFlow(false)
    val isBatchPaused: StateFlow<Boolean> = _isBatchPaused.asStateFlow()

    private val _soundNotificationEnabled = MutableStateFlow(true)
    val soundNotificationEnabled: StateFlow<Boolean> = _soundNotificationEnabled.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _snackMessages = MutableSharedFlow<String>()
    val snackMessages: SharedFlow<String> = _snackMessages.asSharedFlow()

    // History and Presets from Room
    val historyList = historyDao.getAllHistory()
    val savedPresets = presetDao.getAllPresets()

    // Currently processing job tracker
    private var batchJob: Job? = null
    private val pausedJobIds = mutableSetOf<String>()
    private val cancelledJobIds = mutableSetOf<String>()

    // Items whose settings were customized individually via the per-item dialog; excluded
    // from the global-settings sync below so they don't get silently reverted.
    private val customizedItemIds = mutableSetOf<String>()

    init {
        seedInitialSystemPresets()
    }

    private fun seedInitialSystemPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            if (presetDao.countSystemPresets() > 0) return@launch

            val defaultPresets = listOf(
                PresetEntity(
                    presetName = "Discord (Under 25MB)",
                    resolutionName = ResolutionPreset.RES_720P.name,
                    bitrateKbps = 1500,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.SPEED.name,
                    isSystemPreset = true
                ),
                PresetEntity(
                    presetName = "Email Attachment (<10MB)",
                    resolutionName = ResolutionPreset.RES_480P.name,
                    bitrateKbps = 800,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.BALANCED.name,
                    isSystemPreset = true
                ),
                PresetEntity(
                    presetName = "4K High Efficiency (HEVC/MP4)",
                    resolutionName = ResolutionPreset.RES_4K.name,
                    bitrateKbps = 6000,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.SPEED.name,
                    isSystemPreset = true
                )
            )
            defaultPresets.forEach { presetDao.insertPreset(it) }
        }
    }

    fun toggleDarkMode() {
        _isDarkMode.update { !it }
    }

    fun toggleSoundNotification() {
        _soundNotificationEnabled.update { !it }
    }

    fun toggleUseGlobalSettings(useGlobal: Boolean) {
        _useGlobalSettings.value = useGlobal
    }

    fun updateGlobalSettings(updateBlock: (VideoCompressionSettings) -> VideoCompressionSettings) {
        _globalSettings.update { current ->
            val updated = updateBlock(current)
            // If global settings mode is active, sync settings to queued items that haven't
            // been individually customized via the per-item dialog.
            if (_useGlobalSettings.value) {
                _queue.update { list ->
                    list.map { item -> if (item.id in customizedItemIds) item else item.copy(settings = updated) }
                }
            }
            updated
        }
    }

    fun addLocalVideoUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val newItems = uris.map { uri ->
                val uriString = uri.toString()
                val info = transcoder.extractVideoInfo(uriString)
                val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Video_${System.currentTimeMillis()}.mp4"

                VideoQueueItem(
                    title = fileName,
                    sourceType = VideoSourceType.LOCAL_FILE,
                    sourcePathOrUrl = uriString,
                    originalSizeBytes = getFileSize(uri),
                    durationMs = info.durationMs,
                    originalWidth = info.width,
                    originalHeight = info.height,
                    originalBitrateKbps = info.bitrateKbps,
                    originalFps = info.fps,
                    originalAudioBitrateKbps = info.audioBitrateKbps,
                    settings = _globalSettings.value
                )
            }
            _queue.update { current -> current + newItems }
            _snackMessages.emit("Added ${newItems.size} video file(s) to queue")
        }
    }

    fun addUrlSource(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return@launch

            val isYt = trimmed.contains("youtube.com") || trimmed.contains("youtu.be")
            val sourceType = if (isYt) VideoSourceType.YOUTUBE else VideoSourceType.DIRECT_URL
            val title = if (isYt) "YouTube Stream (${trimmed.takeLast(8)})" else "Web Video (${trimmed.takeLast(12)})"

            val item = VideoQueueItem(
                title = title,
                sourceType = sourceType,
                sourcePathOrUrl = trimmed,
                originalSizeBytes = if (isYt) 45_000_000L else 20_000_000L,
                durationMs = 30_000L,
                settings = _globalSettings.value
            )
            _queue.update { it + item }
            _snackMessages.emit("Added URL to queue: $title")
        }
    }

    fun updateItemSettings(itemId: String, settings: VideoCompressionSettings) {
        customizedItemIds.add(itemId)
        _queue.update { list ->
            list.map { if (it.id == itemId) it.copy(settings = settings) else it }
        }
    }

    fun removeItem(itemId: String) {
        cancelledJobIds.add(itemId)
        customizedItemIds.remove(itemId)
        _queue.update { list -> list.filterNot { it.id == itemId } }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        _queue.update { list ->
            if (fromIndex in list.indices && toIndex in list.indices) {
                val mutable = list.toMutableList()
                val item = mutable.removeAt(fromIndex)
                mutable.add(toIndex, item)
                mutable
            } else list
        }
    }

    fun clearQueue() {
        _queue.value.forEach { cancelledJobIds.add(it.id) }
        customizedItemIds.clear()
        _queue.value = emptyList()
    }

    fun startBatchProcessing() {
        if (_isBatchRunning.value) return
        _isBatchRunning.value = true
        _isBatchPaused.value = false
        cancelledJobIds.clear()

        batchJob = viewModelScope.launch(Dispatchers.IO) {
            val queuedItems = _queue.value.filter {
                it.status == CompressionItemState.QUEUED || it.status == CompressionItemState.PAUSED || it.status == CompressionItemState.FAILED
            }

            for ((index, item) in queuedItems.withIndex()) {
                if (cancelledJobIds.contains(item.id)) continue

                // A paused item keeps retrying (from the start; the encoder has no mid-export
                // resume) until the user resumes or cancels, holding the whole batch here.
                while (true) {
                    val outcome = processSingleItem(item)
                    if (outcome != ProcessOutcome.PAUSED) break
                    while (_isBatchPaused.value && !cancelledJobIds.contains(item.id)) {
                        delay(300)
                    }
                    if (cancelledJobIds.contains(item.id)) break
                }

                // Cool-down pacing between items per Resource Mode - a real effect (reduces
                // sustained thermal/battery load in Low Resource Mode) rather than a setting
                // that looked configurable but didn't change anything about the actual run.
                if (index < queuedItems.lastIndex && !cancelledJobIds.contains(item.id)) {
                    val cooldownMs = item.settings.resourceMode.interItemCooldownMs
                    if (cooldownMs > 0L) delay(cooldownMs)
                }
            }

            _isBatchRunning.value = false
            _isBatchPaused.value = false

            if (_soundNotificationEnabled.value) {
                playCompletionFeedback()
            }
            _snackMessages.emit("Batch processing complete!")
        }
    }

    private enum class ProcessOutcome { COMPLETED, FAILED, PAUSED, CANCELLED }

    private suspend fun processSingleItem(item: VideoQueueItem): ProcessOutcome {
        var currentSourcePath = item.sourcePathOrUrl

        // Step 1: Download if URL or YouTube
        if (item.sourceType == VideoSourceType.DIRECT_URL || item.sourceType == VideoSourceType.YOUTUBE) {
            updateItemStatus(item.id, CompressionItemState.DOWNLOADING, progress = 0.05f)

            val downloadResult = downloader.fetchAndDownload(
                urlStr = item.sourcePathOrUrl,
                isPaused = { pausedJobIds.contains(item.id) || _isBatchPaused.value },
                isCancelled = { cancelledJobIds.contains(item.id) }
            ) { progress, downloaded, total ->
                updateItemInQueue(item.id) {
                    it.copy(
                        status = CompressionItemState.DOWNLOADING,
                        progress = progress * 0.3f, // Downloading accounts for 30% total progress
                        originalSizeBytes = total
                    )
                }
            }

            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull()
                if (err is TranscodeCancelledException) return ProcessOutcome.CANCELLED
                val message = err?.message ?: "Download failed"
                updateItemStatus(item.id, CompressionItemState.FAILED, error = message)
                saveToHistory(item.copy(status = CompressionItemState.FAILED, errorMessage = message))
                return ProcessOutcome.FAILED
            }

            currentSourcePath = downloadResult.getOrThrow().absolutePath
        }

        // Step 2: Transcode / Compress
        updateItemStatus(item.id, CompressionItemState.PROCESSING, progress = 0.35f)

        var updatedItem = _queue.value.find { it.id == item.id }?.copy(sourcePathOrUrl = currentSourcePath) ?: item

        // URL/YouTube items only have placeholder duration/resolution/fps until the real file
        // is on disk; refresh from the actual downloaded video before scaling/encoding decisions
        // (aspect ratio, ETA, history duration) are made from it.
        if (item.sourceType == VideoSourceType.DIRECT_URL || item.sourceType == VideoSourceType.YOUTUBE) {
            val info = transcoder.extractVideoInfo(currentSourcePath)
            updatedItem = updatedItem.copy(
                durationMs = info.durationMs,
                originalWidth = info.width,
                originalHeight = info.height,
                originalBitrateKbps = info.bitrateKbps,
                originalFps = info.fps,
                originalAudioBitrateKbps = info.audioBitrateKbps
            )
            updateItemInQueue(item.id) { updatedItem }
        }

        val result = transcoder.transcodeVideo(
            item = updatedItem,
            onProgress = { progress, currentFps, etaSec, bytesWritten ->
                val totalProgress = 0.3f + (progress * 0.7f)
                updateItemInQueue(item.id) {
                    it.copy(
                        status = CompressionItemState.PROCESSING,
                        progress = totalProgress,
                        encodingSpeedFps = currentFps,
                        etaSeconds = etaSec,
                        compressedSizeBytes = bytesWritten
                    )
                }
            },
            isPaused = { pausedJobIds.contains(item.id) || _isBatchPaused.value },
            isCancelled = { cancelledJobIds.contains(item.id) }
        )

        if (result.isSuccess) {
            val outputFile = result.getOrThrow()
            val finalItem = updatedItem.copy(
                status = CompressionItemState.COMPLETED,
                progress = 1.0f,
                compressedSizeBytes = outputFile.length(),
                outputPath = outputFile.absolutePath
            )
            updateItemInQueue(item.id) { finalItem }
            saveToHistory(finalItem)
            return ProcessOutcome.COMPLETED
        }

        return when (val err = result.exceptionOrNull()) {
            is TranscodePausedException -> {
                updateItemInQueue(item.id) { it.copy(status = CompressionItemState.PAUSED) }
                ProcessOutcome.PAUSED
            }
            is TranscodeCancelledException -> {
                ProcessOutcome.CANCELLED
            }
            else -> {
                val failedItem = updatedItem.copy(status = CompressionItemState.FAILED, errorMessage = err?.message ?: "Compression failed")
                updateItemInQueue(item.id) { failedItem }
                saveToHistory(failedItem)
                ProcessOutcome.FAILED
            }
        }
    }

    fun pauseBatch() {
        _isBatchPaused.value = true
        _queue.update { list ->
            list.map {
                if (it.status == CompressionItemState.PROCESSING || it.status == CompressionItemState.DOWNLOADING) {
                    it.copy(status = CompressionItemState.PAUSED)
                } else it
            }
        }
    }

    fun resumeBatch() {
        _isBatchPaused.value = false
        if (!_isBatchRunning.value) {
            startBatchProcessing()
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _isBatchRunning.value = false
        _isBatchPaused.value = false
        _queue.update { list ->
            list.map {
                if (it.status == CompressionItemState.PROCESSING || it.status == CompressionItemState.DOWNLOADING) {
                    cancelledJobIds.add(it.id)
                    it.copy(status = CompressionItemState.QUEUED, progress = 0f)
                } else it
            }
        }
    }

    fun applyPreset(preset: PresetEntity) {
        val resolution = try { ResolutionPreset.valueOf(preset.resolutionName) } catch (_: Exception) { ResolutionPreset.RES_720P }
        val format = try { OutputFormat.valueOf(preset.formatName) } catch (_: Exception) { OutputFormat.MP4 }
        val resourceMode = try { ResourceMode.valueOf(preset.resourceModeName) } catch (_: Exception) { ResourceMode.BALANCED }

        val newSettings = VideoCompressionSettings(
            resolution = resolution,
            bitrate = BitratePreset.CUSTOM,
            customBitrateKbps = preset.bitrateKbps,
            format = format,
            removeAudio = preset.removeAudio,
            resourceMode = resourceMode
        )

        updateGlobalSettings { newSettings }
        viewModelScope.launch { _snackMessages.emit("Applied Preset: ${preset.presetName}") }
    }

    fun saveCustomPreset(presetName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _globalSettings.value
            val entity = PresetEntity(
                presetName = presetName,
                resolutionName = current.resolution.name,
                bitrateKbps = current.customBitrateKbps,
                formatName = current.format.name,
                removeAudio = current.removeAudio,
                resourceModeName = current.resourceMode.name,
                isSystemPreset = false
            )
            presetDao.insertPreset(entity)
            _snackMessages.emit("Saved preset '$presetName'")
        }
    }

    fun deletePreset(presetId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            presetDao.deletePresetById(presetId)
        }
    }

    fun deleteHistoryItem(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.deleteHistoryById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.clearHistory()
        }
    }

    private fun updateItemStatus(id: String, status: CompressionItemState, progress: Float = 0f, error: String? = null) {
        _queue.update { list ->
            list.map {
                if (it.id == id) it.copy(status = status, progress = progress, errorMessage = error) else it
            }
        }
    }

    private fun updateItemInQueue(id: String, block: (VideoQueueItem) -> VideoQueueItem) {
        _queue.update { list ->
            list.map { if (it.id == id) block(it) else it }
        }
    }

    private suspend fun saveToHistory(item: VideoQueueItem) {
        val (w, h) = item.getEffectiveDimensions()
        val entity = HistoryEntity(
            id = item.id,
            title = item.title,
            sourceType = item.sourceType.name,
            sourcePathOrUrl = item.sourcePathOrUrl,
            originalSizeBytes = item.originalSizeBytes,
            compressedSizeBytes = item.compressedSizeBytes,
            durationMs = item.durationMs,
            resolutionLabel = "${w}x${h}",
            bitrateKbps = item.getEffectiveVideoBitrateKbps(),
            format = item.settings.format.extension,
            outputPath = item.outputPath,
            isSuccessful = item.status == CompressionItemState.COMPLETED,
            errorMessage = item.errorMessage
        )
        historyDao.insertHistory(entity)
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            val pfd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
            val size = pfd?.statSize ?: 15_000_000L
            pfd?.close()
            size
        } catch (e: Exception) {
            15_000_000L
        }
    }

    private fun playCompletionFeedback() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(getApplication(), notificationUri)
            ringtone?.play()

            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        } catch (_: Exception) {}
    }
}
