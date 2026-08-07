package com.example.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * An item's outputPath is either a content:// MediaStore Uri (the normal case on API 29+, where
 * the compressed file was published into the public Downloads collection - see
 * VideoTranscoder.publishToPublicStorage) or a raw filesystem path (API 24-28, or any device
 * where that publish step itself failed and the app fell back to keeping its own private copy).
 * Playback/share/open need to build a usable Uri from either form.
 */
fun resolveMediaUri(context: Context, outputPath: String): Uri {
    return if (outputPath.startsWith("content://")) {
        Uri.parse(outputPath)
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(outputPath))
    }
}

fun mediaFileExists(context: Context, outputPath: String): Boolean {
    if (outputPath.startsWith("content://")) {
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(outputPath), "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
    return File(outputPath).exists()
}
