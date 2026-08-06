package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CompressionItemState
import com.example.data.model.VideoQueueItem
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyBlue60

@Composable
fun BatchQueueSection(
    queue: List<VideoQueueItem>,
    isBatchRunning: Boolean,
    isBatchPaused: Boolean,
    onStartBatch: () -> Unit,
    onPauseBatch: () -> Unit,
    onCancelBatch: () -> Unit,
    onClearQueue: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onReorderQueue: (Int, Int) -> Unit,
    onOpenItemSettings: (VideoQueueItem) -> Unit,
    onPlayVideo: ((filePath: String, title: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Queue Header & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ListAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "4. Compression Batch Queue (${queue.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (queue.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearQueue,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("clear_queue_button")
                    ) {
                        Icon(imageVector = Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Queue", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.LinearScale,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Batch Queue is Empty",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Add videos or URLs above to start batch compressing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Batch Control Buttons Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isBatchRunning) {
                        Button(
                            onClick = onStartBatch,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_batch_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SkyBlue60,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Batch Compression")
                        }
                    } else {
                        Button(
                            onClick = if (isBatchPaused) onStartBatch else onPauseBatch,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pause_resume_batch_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isBatchPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isBatchPaused) "Resume Batch" else "Pause Batch")
                        }

                        Button(
                            onClick = onCancelBatch,
                            modifier = Modifier.testTag("cancel_batch_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Queue Item List
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    queue.forEachIndexed { index, item ->
                        QueueItemCard(
                            item = item,
                            index = index,
                            totalItems = queue.size,
                            onRemove = { onRemoveItem(item.id) },
                            onMoveUp = { if (index > 0) onReorderQueue(index, index - 1) },
                            onMoveDown = { if (index < queue.size - 1) onReorderQueue(index, index + 1) },
                            onOpenSettings = { onOpenItemSettings(item) },
                            onPlayVideo = onPlayVideo
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueItemCard(
    item: VideoQueueItem,
    index: Int,
    totalItems: Int,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayVideo: ((filePath: String, title: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (statusColor, statusText) = when (item.status) {
        CompressionItemState.QUEUED -> Pair(Color.Gray, "Queued")
        CompressionItemState.DOWNLOADING -> Pair(SkyBlue60, "Downloading Stream")
        CompressionItemState.PROCESSING -> Pair(SkyBlue60, "Encoding ${ (item.progress * 100).toInt() }%")
        CompressionItemState.PAUSED -> Pair(AmberWarning, "Paused")
        CompressionItemState.COMPLETED -> Pair(EmeraldSuccess, "Completed")
        CompressionItemState.FAILED -> Pair(RoseError, "Failed")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .testTag("queue_item_$index")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = "${item.sourceType.label} • ${formatBytes(item.originalSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.status == CompressionItemState.COMPLETED && item.outputPath != null) {
                        IconButton(
                            onClick = { onPlayVideo?.invoke(item.outputPath, item.title) },
                            modifier = Modifier.size(28.dp).testTag("play_queue_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Compressed Video",
                                tint = SkyBlue60,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Customize Settings",
                            tint = SkyBlue60,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (index > 0) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move Up",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (index < totalItems - 1) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move Down",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = RoseError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Progress Bar & Stats
            if (item.status == CompressionItemState.PROCESSING || item.status == CompressionItemState.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = item.progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = SkyBlue60
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Speed: ${"%.1f".format(item.encodingSpeedFps)} FPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val etaText = when {
                        item.etaSeconds <= 0L -> "Finishing up..."
                        item.etaSeconds < 60L -> "${item.etaSeconds}s remaining"
                        else -> "${item.etaSeconds / 60}m ${item.etaSeconds % 60}s remaining"
                    }
                    Text(
                        text = "ETA: $etaText",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = SkyBlue60
                    )
                }
            }

            if (item.status == CompressionItemState.COMPLETED) {
                Spacer(modifier = Modifier.height(6.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Compressed to ${formatBytes(item.compressedSizeBytes)} (Saved ${ calculateSavingsPercent(item.originalSizeBytes, item.compressedSizeBytes) }%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldSuccess
                        )
                    }
                    if (item.outputPath != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Saved to: ${item.outputPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (item.status == CompressionItemState.FAILED && item.errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = RoseError, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = RoseError,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1000) {
        "%.2f GB".format(mb / 1024.0)
    } else {
        "%.1f MB".format(mb)
    }
}

fun calculateSavingsPercent(orig: Long, comp: Long): Int {
    if (orig <= 0 || comp <= 0 || orig <= comp) return 0
    return (((orig - comp).toDouble() / orig) * 100).toInt()
}
