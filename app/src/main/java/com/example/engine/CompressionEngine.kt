package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
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
import com.example.service.CompressionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns all queue/batch state and processing. Deliberately independent of any Android
 * component's lifecycle (unlike a ViewModel, which is cleared with its screen, or a Service,
 * which the OS can stop) so a long batch keeps running - and can be driven by
 * [CompressionForegroundService] - regardless of what the UI is doing. CompressorViewModel is
 * just a thin StateFlow/function passthrough onto this singleton.
 */
class CompressionEngine private constructor(private val appContext: Context) {

    private val db = AppDatabase.getInstance(appContext)
    private val historyDao = db.historyDao()
    private val presetDao = db.presetDao()

    private val downloader = UrlVideoDownloader(appContext)
    private val transcoder = VideoTranscoder(appContext)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // UI State - all read from the actual device at startup, not fixed numbers.
    val maxSystemCores: Int = Runtime.getRuntime().availableProcessors()
    val isGpuAvailable: Boolean = transcoder.isGpuHardwareAccelerationAvailable()
    val supportedCodecs: Set<VideoCodec> = VideoCodec.entries.filter { transcoder.isCodecSupported(it) }.toSet()
    val totalRamGb: Double = run {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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

    // Buffered so emit() never suspends a work coroutine indefinitely when no UI is collecting
    // (e.g. batch finishing while the app is backgrounded behind the foreground service).
    private val _snackMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
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

    companion object {
        @Volatile
        private var INSTANCE: CompressionEngine? = null

        fun getInstance(context: Context): CompressionEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CompressionEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun seedInitialSystemPresets() {
        engineScope.launch(Dispatchers.IO) {
            // Seed by name-diff, not by "any system preset exists": a count>0 guard would mean
            // installs seeded under an older app version never receive presets added later.
            val existingNames = presetDao.getSystemPresetNames().toSet()

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
                ),
                PresetEntity(
                    presetName = "WhatsApp / Messaging (Small & Fast)",
                    resolutionName = ResolutionPreset.RES_480P.name,
                    bitrateKbps = 700,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.SPEED.name,
                    isSystemPreset = true
                ),
                PresetEntity(
                    presetName = "Instagram / TikTok / Reels (1080p)",
                    resolutionName = ResolutionPreset.RES_1080P.name,
                    bitrateKbps = 3500,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.BALANCED.name,
                    isSystemPreset = true
                ),
                PresetEntity(
                    presetName = "YouTube Upload (High Quality)",
                    resolutionName = ResolutionPreset.RES_1080P.name,
                    bitrateKbps = 8000,
                    formatName = OutputFormat.MP4.name,
                    removeAudio = false,
                    resourceModeName = ResourceMode.SPEED.name,
                    isSystemPreset = true
                )
            )
            defaultPresets.filter { it.presetName !in existingNames }.forEach { presetDao.insertPreset(it) }
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
            // been individually customized via the per-item dialog. Trim is deliberately NOT
            // synced: a trim range is meaningful only against one specific video's duration,
            // so each item always keeps its own - otherwise trimming the first video would
            // silently clip every other queued video to the same window.
            if (_useGlobalSettings.value) {
                _queue.update { list ->
                    list.map { item ->
                        if (item.id in customizedItemIds) item
                        else item.copy(settings = updated.copy(
                            trimStartMs = item.settings.trimStartMs,
                            trimEndMs = item.settings.trimEndMs
                        ))
                    }
                }
            }
            updated
        }
    }

    /** Sets one item's trim range without marking it "customized" - trim is inherently
     * per-video, so using it must not detach the item from future global-settings sync the
     * way the per-item settings dialog does. */
    fun updateItemTrim(itemId: String, trimStartMs: Long, trimEndMs: Long?) {
        _queue.update { list ->
            list.map {
                if (it.id == itemId) it.copy(settings = it.settings.copy(trimStartMs = trimStartMs, trimEndMs = trimEndMs))
                else it
            }
        }
    }

    fun addLocalVideoUris(uris: List<Uri>) {
        engineScope.launch(Dispatchers.IO) {
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
                    // Trim never carries over from global settings - it belongs to one video.
                    settings = _globalSettings.value.copy(trimStartMs = 0L, trimEndMs = null)
                )
            }
            _queue.update { current -> current + newItems }
            _snackMessages.emit("Added ${newItems.size} video file(s) to queue")
        }
    }

    fun addUrlSource(url: String) {
        engineScope.launch(Dispatchers.IO) {
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
                settings = _globalSettings.value.copy(trimStartMs = 0L, trimEndMs = null)
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

        // Elevate process priority for the duration of the batch so a backgrounded/killed
        // app doesn't silently drop a long compression job; the service observes this
        // engine's state and stops itself once the batch finishes.
        ContextCompat.startForegroundService(appContext, Intent(appContext, CompressionForegroundService::class.java))

        batchJob = engineScope.launch(Dispatchers.IO) {
            // Pull the next item from the live queue on each pass (not a snapshot taken at
            // start) so videos added mid-batch are processed too, instead of sitting QUEUED
            // while the batch declares itself complete. attemptedIds guarantees each item is
            // tried at most once per run, so a FAILED item is retried once, not forever.
            val attemptedIds = mutableSetOf<String>()
            while (true) {
                val item = _queue.value.firstOrNull {
                    (it.status == CompressionItemState.QUEUED || it.status == CompressionItemState.PAUSED || it.status == CompressionItemState.FAILED) &&
                        it.id !in attemptedIds && it.id !in cancelledJobIds
                } ?: break
                attemptedIds.add(item.id)

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
                if (!cancelledJobIds.contains(item.id)) {
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

        // Step 2: Transcode / Compress. The 30%-for-download progress reservation only applies
        // to items that actually downloaded - a local file's bar must start at ~0, not jump
        // straight to 35% before any encoding work has happened.
        val isRemote = item.sourceType == VideoSourceType.DIRECT_URL || item.sourceType == VideoSourceType.YOUTUBE
        val progressBase = if (isRemote) 0.3f else 0f
        val progressSpan = 1f - progressBase
        updateItemInQueue(item.id) {
            it.copy(status = CompressionItemState.PROCESSING, progress = progressBase + 0.02f, etaSeconds = -1L, encodingSpeedFps = 0f)
        }

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
                val totalProgress = progressBase + (progress * progressSpan)
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
            val compressedBytes = outputFile.length()
            // Relocate out of private storage into the public Downloads collection so the file
            // is actually where the UI says it is; fall back to the private path (still fully
            // usable in-app) if that relocation itself fails for any reason.
            val publishedPath = transcoder.publishToPublicStorage(outputFile, updatedItem) ?: outputFile.absolutePath
            val finalItem = updatedItem.copy(
                status = CompressionItemState.COMPLETED,
                progress = 1.0f,
                compressedSizeBytes = compressedBytes,
                outputPath = publishedPath
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
        engineScope.launch { _snackMessages.emit("Applied Preset: ${preset.presetName}") }
    }

    fun saveCustomPreset(presetName: String) {
        engineScope.launch(Dispatchers.IO) {
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
        engineScope.launch(Dispatchers.IO) {
            presetDao.deletePresetById(presetId)
        }
    }

    fun deleteHistoryItem(id: String) {
        engineScope.launch(Dispatchers.IO) {
            historyDao.deleteHistoryById(id)
        }
    }

    fun clearHistory() {
        engineScope.launch(Dispatchers.IO) {
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
            durationMs = item.effectiveDurationMs(),
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
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
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
            val ringtone = RingtoneManager.getRingtone(appContext, notificationUri)
            ringtone?.play()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        } catch (_: Exception) {}
    }
}
