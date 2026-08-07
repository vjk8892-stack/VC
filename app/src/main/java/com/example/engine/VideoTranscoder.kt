package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.example.data.model.VideoCodec
import com.example.data.model.VideoQueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TranscodePausedException : Exception("Paused by user")
class TranscodeCancelledException : Exception("Cancelled by user")

class VideoTranscoder(private val context: Context) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val bitrateKbps: Int,
        val fps: Int,
        val audioBitrateKbps: Int? = null
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
            val retrieverDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { it / 1000 } ?: 6000
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 30
            val extractorInfo = extractExtractorInfo(sourcePathOrUri)
            // MediaMetadataRetriever silently fails to report a duration for some content://
            // sources (certain gallery/document providers); falling straight to a fixed 15s in
            // that case would make every such file's derived bitrate look the same. Cross-check
            // against MediaExtractor's own track duration before giving up on a real number.
            val duration = retrieverDurationMs ?: extractorInfo.durationMs ?: 15_000L

            VideoInfo(width, height, duration, bitrate, fps, extractorInfo.audioBitrateKbps)
        } catch (e: Exception) {
            VideoInfo(1920, 1080, 15_000L, 6000, 30)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private data class ExtractorInfo(val durationMs: Long?, val audioBitrateKbps: Int?)

    /** Reads the source's real duration and its audio track's own encoded bitrate straight from
     * the container via MediaExtractor - used as a cross-check/fallback for duration (since
     * MediaMetadataRetriever can fail silently for some content:// sources) and as the only
     * source for audio bitrate, since the encoder passes audio through unchanged when no audio
     * effects are requested (i.e. it does not re-encode audio to a fixed rate). */
    private fun extractExtractorInfo(sourcePathOrUri: String): ExtractorInfo {
        val extractor = MediaExtractor()
        return try {
            if (sourcePathOrUri.startsWith("content://") || sourcePathOrUri.startsWith("file://")) {
                extractor.setDataSource(context, Uri.parse(sourcePathOrUri), null)
            } else if (sourcePathOrUri.startsWith("http://") || sourcePathOrUri.startsWith("https://")) {
                extractor.setDataSource(sourcePathOrUri, HashMap<String, String>())
            } else {
                extractor.setDataSource(sourcePathOrUri)
            }

            var durationMs: Long? = null
            var audioBitrateKbps: Int? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val trackDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    if (trackDurationMs > (durationMs ?: 0L)) durationMs = trackDurationMs
                }
                if (mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    audioBitrateKbps = (format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000).coerceAtLeast(1)
                }
            }
            ExtractorInfo(durationMs?.takeIf { it > 0L }, audioBitrateKbps)
        } catch (e: Exception) {
            ExtractorInfo(null, null)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Any hardware/software video encoder at all (used to decide whether compression can run). */
    fun isGpuHardwareAccelerationAvailable(): Boolean {
        return isEncoderAvailable(MimeTypes.VIDEO_H264) || isEncoderAvailable(MimeTypes.VIDEO_H265)
    }

    /** Whether this device exposes an HEVC encoder; if false, requests fall back to H.264 automatically. */
    fun isHevcEncodingSupported(): Boolean = isEncoderAvailable(MimeTypes.VIDEO_H265)

    /** Whether this device has a real hardware/software encoder for the given codec, so the UI
     * can warn when a pick will silently fall back to something else at encode time. */
    fun isCodecSupported(codec: VideoCodec): Boolean = isEncoderAvailable(codec.mimeType)

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
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(bitrateBps)
                    // CBR instead of the default VBR: VBR only targets an average, and hardware
                    // encoders commonly overshoot it on complex content, which is exactly why
                    // real output kept coming in bigger than the size estimate predicted.
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    .build()
            )
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
                onProgress(1.0f, item.originalFps.toFloat(), 0L, stabilizedFileLength(outputFile))
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

    // MP4 muxers finalize trailing metadata (the moov atom) as their last write; File.length()
    // read right at the completion callback can catch that mid-flush and under-report the true
    // final size. Poll until two consecutive reads agree before trusting it.
    private suspend fun stabilizedFileLength(file: File): Long {
        var previous = file.length()
        repeat(10) {
            delay(150)
            val current = file.length()
            if (current == previous && current > 0L) return current
            previous = current
        }
        return previous
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

    // Encodes into this app's own private external storage - always writable on every API level,
    // no permission needed - rather than straight into a public directory. Android 10+ blocks (or
    // silently redirects) direct java.io.File writes into a public directory without the
    // legacy-storage opt-out this app doesn't request, which used to make the encoder either fail
    // outright or quietly save where the user could never find it. publishToPublicStorage() below
    // relocates the finished file into the real public Downloads folder afterward.
    private fun buildOutputFile(item: VideoQueueItem): File {
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val sanitizedTitle = item.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(25)
        val extension = item.settings.format.extension
        return File(outputDir, "Compressed_${sanitizedTitle}_${System.currentTimeMillis()}.$extension")
    }

    /** Moves a just-encoded private-storage file into the public Downloads collection so it's
     * actually visible in the Files app / other apps, matching what the UI already promises.
     * Returns the new location as a String: a content:// MediaStore Uri on API 29+ (a direct
     * java.io.File write to a public directory is blocked there without the legacy-storage
     * opt-out this app doesn't request), or a real file path on API 24-28 where direct public-
     * directory writes still work with WRITE_EXTERNAL_STORAGE granted. Returns null if the
     * publish step itself fails for any reason (e.g. that permission denied on API 24-28) - the
     * caller should then keep pointing at the original private file, which still works for
     * in-app playback/sharing, it just won't show up in the user's Downloads folder. */
    fun publishToPublicStorage(privateFile: File, item: VideoQueueItem): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(privateFile, privateFile.name, item.settings.format.mimeType)?.toString()
            } else {
                publishViaLegacyPublicFile(privateFile, privateFile.name)?.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun publishViaMediaStore(privateFile: File, displayName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CompressedVideos")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        val copied = try {
            resolver.openOutputStream(itemUri)?.use { out ->
                privateFile.inputStream().use { input -> input.copyTo(out) }
            } != null
        } catch (e: Exception) {
            false
        }

        if (!copied) {
            resolver.delete(itemUri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        privateFile.delete()
        return itemUri
    }

    private fun publishViaLegacyPublicFile(privateFile: File, displayName: String): File? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outDir = File(downloadsDir, "CompressedVideos")
        if (!outDir.exists() && !outDir.mkdirs()) return null
        val destFile = File(outDir, displayName)
        privateFile.inputStream().use { input -> destFile.outputStream().use { out -> input.copyTo(out) } }
        privateFile.delete()
        return destFile
    }
}
