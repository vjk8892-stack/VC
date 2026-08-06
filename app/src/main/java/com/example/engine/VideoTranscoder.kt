package com.example.engine

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.data.model.VideoQueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TranscodePausedException : Exception("Compression paused by user")
class TranscodeCancelledException : Exception("Compression cancelled by user")

class VideoTranscoder(private val context: Context) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val bitrateKbps: Int,
        val fps: Int
    )

    fun extractVideoInfo(sourcePathOrUri: String): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            if (sourcePathOrUri.startsWith("content://") || sourcePathOrUri.startsWith("file://")) {
                retriever.setDataSource(context, Uri.parse(sourcePathOrUri))
            } else if (sourcePathOrUri.startsWith("http://") || sourcePathOrUri.startsWith("https://")) {
                retriever.setDataSource(sourcePathOrUri, HashMap<String, String>())
            } else {
                retriever.setDataSource(sourcePathOrUri)
            }

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { it / 1000 } ?: 6000
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 30

            VideoInfo(width, height, duration, bitrate, fps)
        } catch (e: Exception) {
            VideoInfo(1920, 1080, 15_000L, 6000, 30)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Any hardware/software video encoder at all (used to decide whether compression can run). */
    fun isGpuHardwareAccelerationAvailable(): Boolean {
        return isEncoderAvailable(MimeTypes.VIDEO_H264) || isEncoderAvailable(MimeTypes.VIDEO_H265)
    }

    /** Whether this device exposes an HEVC encoder; if false, requests fall back to H.264 automatically. */
    fun isHevcEncodingSupported(): Boolean = isEncoderAvailable(MimeTypes.VIDEO_H265)

    private fun isEncoderAvailable(mime: String): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun transcodeVideo(
        item: VideoQueueItem,
        onProgress: (progress: Float, currentFps: Float, etaSeconds: Long, bytesWritten: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean
    ): Result<File> {
        if (isCancelled()) return Result.failure(TranscodeCancelledException())

        val outputFile = buildOutputFile(item)
        val (targetWidth, targetHeight) = item.getEffectiveDimensions()
        val targetBitrateBps = (item.getEffectiveVideoBitrateKbps() * 1000).coerceAtLeast(100_000)
        val totalDurationMs = if (item.durationMs > 0) item.durationMs else 15_000L
        val durationSec = (totalDurationMs / 1000.0).coerceAtLeast(1.0)

        return try {
            withContext(Dispatchers.Main) {
                runTransformerExport(
                    item = item,
                    outputFile = outputFile,
                    width = targetWidth,
                    height = targetHeight,
                    bitrateBps = targetBitrateBps,
                    durationSec = durationSec,
                    onProgress = onProgress,
                    isPaused = isPaused,
                    isCancelled = isCancelled
                )
            }
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            Result.failure(Exception("Compression failed: ${e.localizedMessage ?: e.javaClass.simpleName}"))
        }
    }

    // Must run on a thread with a Looper (Main): Transformer posts its callbacks there.
    private suspend fun runTransformerExport(
        item: VideoQueueItem,
        outputFile: File,
        width: Int,
        height: Int,
        bitrateBps: Int,
        durationSec: Double,
        onProgress: (progress: Float, currentFps: Float, etaSeconds: Long, bytesWritten: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean
    ): Result<File> = coroutineScope {
        val settings = item.settings
        val outcome = CompletableDeferred<Result<Unit>>()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrateBps).build())
            .setEnableFallback(true) // HEVC -> H.264 (or whatever the device actually supports) when unavailable
            .build()

        val transformationRequest = TransformationRequest.Builder()
            .setVideoMimeType(settings.videoCodec.mimeType)
            .build()

        val transformer = Transformer.Builder(context)
            .setTransformationRequest(transformationRequest)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    outcome.complete(Result.success(Unit))
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    outcome.complete(Result.failure(exportException))
                }
            })
            .build()

        val mediaItem = MediaItem.fromUri(resolveMediaUri(item.sourcePathOrUrl))
        val videoEffects: List<Effect> = listOf(
            Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        )
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(settings.removeAudio)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            return@coroutineScope Result.failure(Exception("Could not start encoder: ${e.localizedMessage}"))
        }

        val startTime = System.currentTimeMillis()
        val progressHolder = ProgressHolder()

        val monitorJob = launch {
            while (isActive) {
                delay(300)
                if (isCancelled()) {
                    transformer.cancel()
                    outcome.complete(Result.failure(TranscodeCancelledException()))
                    break
                }
                if (isPaused()) {
                    transformer.cancel()
                    outcome.complete(Result.failure(TranscodePausedException()))
                    break
                }
                val state = try { transformer.getProgress(progressHolder) } catch (e: Exception) { Transformer.PROGRESS_STATE_UNAVAILABLE }
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val prog = (progressHolder.progress / 100f).coerceIn(0f, 1f)
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                    val fps = ((prog * durationSec * item.originalFps) / elapsedSec).toFloat()
                    val etaSec = if (prog > 0.02f) ((elapsedSec / prog) - elapsedSec).toLong().coerceAtLeast(0L) else -1L
                    onProgress(prog, fps.coerceAtLeast(0f), etaSec, outputFile.length())
                }
            }
        }

        val result = outcome.await()
        monitorJob.cancel()

        result.fold(
            onSuccess = {
                onProgress(1.0f, item.originalFps.toFloat(), 0L, outputFile.length())
                Result.success(outputFile)
            },
            onFailure = { e ->
                if (outputFile.exists()) outputFile.delete()
                val message = when (e) {
                    is TranscodePausedException, is TranscodeCancelledException -> e.message
                    else -> "Compression failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
                Result.failure(if (e is TranscodePausedException || e is TranscodeCancelledException) e else Exception(message))
            }
        )
    }

    private fun resolveMediaUri(sourcePathOrUrl: String): Uri {
        return if (sourcePathOrUrl.startsWith("content://") ||
            sourcePathOrUrl.startsWith("file://") ||
            sourcePathOrUrl.startsWith("http://") ||
            sourcePathOrUrl.startsWith("https://")
        ) {
            Uri.parse(sourcePathOrUrl)
        } else {
            Uri.fromFile(File(sourcePathOrUrl))
        }
    }

    private fun buildOutputFile(item: VideoQueueItem): File {
        val downloadsPublicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val compressorSubDir = File(downloadsPublicDir, "CompressedVideos")
        val outputDir = try {
            if (!compressorSubDir.exists()) compressorSubDir.mkdirs()
            if (compressorSubDir.canWrite()) compressorSubDir else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        } catch (e: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        }

        val sanitizedTitle = item.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(25)
        val extension = item.settings.format.extension
        return File(outputDir, "Compressed_${sanitizedTitle}_${System.currentTimeMillis()}.$extension")
    }
}
