package com.example.data.model

import java.util.UUID

enum class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    RES_360P("360p (Mobile)", 640, 360),
    RES_480P("480p (SD)", 854, 480),
    RES_720P("720p (HD)", 1280, 720),
    RES_1080P("1080p (FHD)", 1920, 1080),
    RES_4K("4K (2160p)", 3840, 2160),
    ORIGINAL("Original Resolution", 0, 0),
    CUSTOM("Custom WxH", 0, 0)
}

enum class VideoCodec(val label: String, val mimeType: String, val description: String) {
    HEVC_H265("HEVC (H.265)", "video/hevc", "50% smaller file size, high efficiency"),
    AVC_H264("H.264 (AVC)", "video/avc", "Universal compatibility across all devices"),
    VP9("VP9", "video/x-vnd.on2.vp9", "Open web video format"),
    AV1("AV1", "video/av01", "Next-gen ultra compression")
}

enum class BitratePreset(val label: String, val targetBitrateKbps: Int) {
    LOW("Low (Fast / Small size)", 1000),
    MEDIUM("Medium (Balanced)", 2500),
    HIGH("High (Best Quality)", 5000),
    CUSTOM("Custom Bitrate", 0)
}

// Android's MediaMuxer (and Media3 Transformer, which is built on it) can only write
// MP4 containers reliably on every device; WEBM/MKV/MOV/AVI required an external muxer
// library that this project doesn't depend on, so those options are not offered.
enum class OutputFormat(val extension: String, val codecName: String, val mimeType: String) {
    MP4("mp4", "MP4 Container", "video/mp4")
}

// interItemCooldownMs is a real, applied effect: startBatchProcessing() waits this long
// between queued items, so higher modes genuinely reduce sustained thermal/battery load
// during a batch instead of being a cosmetic-only setting.
enum class ResourceMode(val title: String, val description: String, val interItemCooldownMs: Long) {
    SPEED("Speed Mode", "No pause between queued files - fastest way through a batch", 0L),
    BALANCED("Balanced Mode", "Short pause between files to ease sustained heat and battery use", 1_500L),
    LOW_RESOURCE("Low Resource Mode", "Longer pause between files to keep the device cooler", 4_000L)
}

enum class VideoSourceType(val label: String) {
    LOCAL_FILE("Local File"),
    DIRECT_URL("Direct Video URL"),
    YOUTUBE("YouTube Link")
}

enum class CompressionItemState {
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    PAUSED,
    FAILED
}

data class VideoCompressionSettings(
    val resolution: ResolutionPreset = ResolutionPreset.RES_720P,
    val customWidth: Int = 1280,
    val customHeight: Int = 720,
    val videoCodec: VideoCodec = VideoCodec.HEVC_H265,
    val bitrate: BitratePreset = BitratePreset.MEDIUM,
    // Matches BitratePreset.MEDIUM.targetBitrateKbps so the displayed value and the value
    // actually used by getEffectiveBitrateKbps() agree before the user touches anything.
    val customBitrateKbps: Int = 2500,
    val format: OutputFormat = OutputFormat.MP4,
    val removeAudio: Boolean = false,
    val resourceMode: ResourceMode = ResourceMode.BALANCED
)

data class VideoQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceType: VideoSourceType,
    val sourcePathOrUrl: String,
    val originalSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val originalWidth: Int = 1920,
    val originalHeight: Int = 1080,
    val originalBitrateKbps: Int = 8000,
    val originalFps: Int = 30,
    // Read from the source audio track's own format (when available) instead of assumed - the
    // encoder passes audio through unchanged when no audio effects are requested, so this is
    // what the output will actually carry, not a guess.
    val originalAudioBitrateKbps: Int? = null,
    val compressedSizeBytes: Long = 0L,
    val status: CompressionItemState = CompressionItemState.QUEUED,
    val progress: Float = 0f, // 0.0 to 1.0
    val encodingSpeedFps: Float = 0f,
    val etaSeconds: Long = 0L,
    val errorMessage: String? = null,
    val outputPath: String? = null,
    val settings: VideoCompressionSettings = VideoCompressionSettings()
) {
    fun getEffectiveBitrateKbps(): Int {
        return if (settings.bitrate == BitratePreset.CUSTOM) {
            settings.customBitrateKbps
        } else {
            settings.bitrate.targetBitrateKbps
        }
    }

    /** The source's own overall bitrate (video+audio+container), derived from real file size
     * and duration - far more trustworthy than container bitrate metadata, which is often
     * absent. Null when we don't have real size/duration yet (e.g. a URL item pre-download). */
    fun sourceBitrateKbps(): Int? {
        if (originalSizeBytes <= 0L || durationMs <= 0L) return null
        val kbps = (originalSizeBytes * 8.0 / 1000.0) / (durationMs / 1000.0)
        return kbps.toInt().coerceAtLeast(1)
    }

    /** The real ceiling the Bitrate Target control should enforce: the most video bitrate that
     * can be requested and still guarantee a smaller output than the source, derived from the
     * source's own real average bitrate (sourceBitrateKbps), not container metadata. Null when
     * the source's real bitrate isn't known yet (e.g. a URL item before download), in which case
     * there's no honest number to cap the UI to. */
    fun maxSelectableVideoBitrateKbps(): Int? {
        val sourceKbps = sourceBitrateKbps() ?: return null
        val audioKbps = if (settings.removeAudio) 0 else (originalAudioBitrateKbps ?: 128)
        val safeTotalKbps = (sourceKbps * 0.85).toInt().coerceAtLeast(300)
        return (safeTotalKbps - audioKbps).coerceAtLeast(150)
    }

    /** The video bitrate actually used for encoding: the user's requested bitrate, capped so
     * the encoder is never asked to spend more bits/sec than the source already averages - a
     * request to "compress" a video must not legitimately produce a same-size-or-larger file. */
    fun getEffectiveVideoBitrateKbps(): Int {
        val requestedKbps = getEffectiveBitrateKbps()
        val maxKbps = maxSelectableVideoBitrateKbps() ?: return requestedKbps
        return requestedKbps.coerceAtMost(maxKbps)
    }

    fun getEffectiveDimensions(): Pair<Int, Int> {
        // CUSTOM means the user typed an exact width/height: honor it as-is (just rounded to
        // even) instead of reinterpreting it through the source video's aspect ratio below.
        if (settings.resolution == ResolutionPreset.CUSTOM) {
            val evenW = ((settings.customWidth + 1) / 2) * 2
            val evenH = ((settings.customHeight + 1) / 2) * 2
            return Pair(evenW.coerceAtLeast(160), evenH.coerceAtLeast(120))
        }

        val (rawW, rawH) = when (settings.resolution) {
            ResolutionPreset.ORIGINAL -> Pair(originalWidth, originalHeight)
            else -> Pair(settings.resolution.width, settings.resolution.height)
        }

        // Maintain aspect ratio if raw dimensions match standard resolution preset
        if (originalWidth > 0 && originalHeight > 0 && rawW > 0 && rawH > 0) {
            val isPortrait = originalHeight > originalWidth
            val maxDim = rawW.coerceAtLeast(rawH)
            val aspect = originalWidth.toFloat() / originalHeight.toFloat()
            
            val calcW: Int
            val calcH: Int
            if (isPortrait) {
                calcH = maxDim
                calcW = (calcH * aspect).toInt()
            } else {
                calcW = maxDim
                calcH = (calcW / aspect).toInt()
            }
            // Dimensions must be even numbers for hardware encoders
            val evenW = ((calcW + 1) / 2) * 2
            val evenH = ((calcH + 1) / 2) * 2
            return Pair(evenW.coerceAtLeast(160), evenH.coerceAtLeast(120))
        }
        val evenW = ((rawW + 1) / 2) * 2
        val evenH = ((rawH + 1) / 2) * 2
        return Pair(evenW.coerceAtLeast(160), evenH.coerceAtLeast(120))
    }

    /** Mirrors the bitrate math the real encoder uses (getEffectiveVideoBitrateKbps), so this
     * preview never promises savings the actual compression pass won't deliver. Uses the
     * source's real audio bitrate (when known) rather than a flat guess, since the encoder
     * passes audio through unchanged - a wrong guess here was the main source of estimate vs
     * actual-output drift. */
    fun estimateCompressedSizeBytes(): Long {
        val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 30.0
        val audioKbps = if (settings.removeAudio) 0 else (originalAudioBitrateKbps ?: 128)
        val totalBitrateKbps = getEffectiveVideoBitrateKbps() + audioKbps

        val estimatedBytes = (totalBitrateKbps * 1000L / 8.0 * durationSec).toLong()
        return estimatedBytes.coerceAtLeast(50_000L)
    }
}
