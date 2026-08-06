package com.example.engine

import android.content.Context
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class UrlVideoDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAndDownload(
        urlStr: String,
        isPaused: () -> Boolean = { false },
        isCancelled: () -> Boolean = { false },
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val trimmed = urlStr.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("URL string cannot be empty."))
        }

        if (!Patterns.WEB_URL.matcher(trimmed).matches() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid URL format. Please include http:// or https://"))
        }

        // Check if YouTube link
        val isYouTube = trimmed.contains("youtube.com") || trimmed.contains("youtu.be")
        if (isYouTube) {
            return@withContext handleYouTubeLink(trimmed, onProgress)
        }

        if (isCancelled()) return@withContext Result.failure(TranscodeCancelledException())

        // Standard direct video URL download
        var tempFile: File? = null
        try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Android; VideoCompressorApp)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP Error ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body from server."))
            val contentLength = body.contentLength()
            val fileExt = determineExtension(trimmed, response.header("Content-Type"))

            tempFile = File(context.cacheDir, "downloaded_${System.currentTimeMillis()}.$fileExt")
            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled()) {
                    outputStream.close(); inputStream.close(); tempFile.delete()
                    return@withContext Result.failure(TranscodeCancelledException())
                }
                while (isPaused() && !isCancelled()) {
                    Thread.sleep(200)
                }
                if (isCancelled()) {
                    outputStream.close(); inputStream.close(); tempFile.delete()
                    return@withContext Result.failure(TranscodeCancelledException())
                }

                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                val progress = if (contentLength > 0) (totalRead.toFloat() / contentLength).coerceIn(0f, 1f) else 0.5f
                onProgress(progress, totalRead, if (contentLength > 0) contentLength else totalRead)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            Result.success(tempFile)
        } catch (e: Exception) {
            tempFile?.delete()
            Result.failure(Exception("Download failed: ${e.localizedMessage ?: "Network error"}"))
        }
    }

    private suspend fun handleYouTubeLink(
        url: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        // Downloading a YouTube video requires resolving its page into a real playable
        // media stream (e.g. via a yt-dlp/youtube-extractor library), which this project
        // does not depend on. Fail clearly instead of feeding the page URL to the video
        // decoder, which can only ever produce a broken or unrelated output file.
        Result.failure(
            UnsupportedOperationException(
                "YouTube downloads aren't supported yet: no video extractor is wired up. Use a direct video file URL or a local file instead."
            )
        )
    }

    private fun determineExtension(url: String, contentType: String?): String {
        return when {
            contentType?.contains("webm") == true || url.endsWith(".webm") -> "webm"
            contentType?.contains("mkv") == true || url.endsWith(".mkv") -> "mkv"
            contentType?.contains("quicktime") == true || url.endsWith(".mov") -> "mov"
            else -> "mp4"
        }
    }
}
