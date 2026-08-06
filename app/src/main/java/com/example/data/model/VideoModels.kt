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

enum class OutputFormat(val extension: String, val codecName: String, val mimeType: String) {
    MP4("mp4", "MP4 Container", "video/mp4"),
    WEBM("webm", "WebM Container", "video/webm"),
    MKV("mkv", "MKV Container", "video/x-matroska"),
    MOV("mov", "QuickTime MOV", "video/quicktime"),
    AVI("avi", "AVI Container", "video/x-msvideo")
}

enum class ResourceMode(val title: String, val description: String, val ramPercentage: Float) {
    SPEED("Speed Mode", "Allocates max hardware resources for highest FPS encoding", 0.75f),
    BALANCED("Balanced Mode", "Optimal resource allocation for multi-tasking", 0.50f),
    LOW_RESOURCE("Low Resource Mode", "Eco power usage to prevent device heating", 0.25f)
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
    val customBitrateKbps: Int = 2000,
    val format: OutputFormat = OutputFormat.MP4,
    val removeAudio: Boolean = false,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16),
    val gpuAcceleration: Boolean = true,
    val resourceMode: ResourceMode = ResourceMode.BALANCED,
    val targetSizeBytes: Long? = null
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

    fun getEffectiveDimensions(): Pair<Int, Int> {
        val (rawW, rawH) = when (settings.resolution) {
            ResolutionPreset.ORIGINAL -> Pair(originalWidth, originalHeight)
            ResolutionPreset.CUSTOM -> Pair(settings.customWidth, settings.customHeight)
            else -> Pair(settings.resolution.width, settings.resolution.height)
        }
        
        // Maintain aspect ratio if raw dimensions match standard resolution preset
        if (originalWidth > 0 && originalHeight > 0 && rawW > 0 && rawH > 0) {
            val isPortrait = originalHeight > originalWidth
            val maxDim = rawW.coerceAtLeast(rawH)
            val minDim = rawW.coerceAtMost(rawH)
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

    fun estimateCompressedSizeBytes(): Long {
        val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 30.0
        val targetBitrateKbps = getEffectiveBitrateKbps()
        
        val codecFactor = when (settings.videoCodec) {
            VideoCodec.HEVC_H265 -> 0.65f
            VideoCodec.AV1 -> 0.55f
            VideoCodec.VP9 -> 0.80f
            VideoCodec.AVC_H264 -> 1.00f
        }
        
        val resFactor = when (settings.resolution) {
            ResolutionPreset.RES_360P -> 0.45f
            ResolutionPreset.RES_480P -> 0.60f
            ResolutionPreset.RES_720P -> 0.75f
            ResolutionPreset.RES_1080P -> 1.00f
            ResolutionPreset.RES_4K -> 1.80f
            ResolutionPreset.ORIGINAL -> 1.00f
            ResolutionPreset.CUSTOM -> {
                if (settings.customWidth > 0 && settings.customHeight > 0) {
                    ((settings.customWidth * settings.customHeight).toDouble() / (1920 * 1080)).toFloat().coerceIn(0.2f, 2.0f)
                } else 0.80f
            }
        }

        val effectiveBitrateKbps = (targetBitrateKbps * codecFactor * resFactor).toInt().coerceAtLeast(250)
        val audioKbps = if (settings.removeAudio) 0 else 128
        val totalBitrateKbps = effectiveBitrateKbps + audioKbps
        
        val estimatedBytes = (totalBitrateKbps * 1000L / 8.0 * durationSec).toLong()
        
        return if (originalSizeBytes > 0L) {
            estimatedBytes.coerceAtMost((originalSizeBytes * 0.92).toLong()).coerceAtLeast(100_000L)
        } else {
            estimatedBytes.coerceAtLeast(100_000L)
        }
    }
}
