package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BitratePreset
import com.example.data.model.ResolutionPreset
import com.example.data.model.VideoCodec
import com.example.data.model.VideoQueueItem
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SkyBlue60

@Composable
fun ItemSettingsDialog(
    item: VideoQueueItem,
    onDismiss: () -> Unit,
    onSaveSettings: (VideoQueueItem) -> Unit,
    supportedCodecs: Set<VideoCodec> = VideoCodec.entries.toSet()
) {
    var tempSettings by remember { mutableStateOf(item.settings) }

    val nativeBitrateKbps = item.sourceBitrateKbps()
    val basisItem = item.copy(settings = tempSettings)
    val maxSelectableBitrateKbps = basisItem.maxSelectableVideoBitrateKbps()

    // Keep customBitrateKbps - the single number used for display, slider and encoding - in
    // sync with this specific file's own cap, same as the global Compression Settings screen.
    LaunchedEffect(maxSelectableBitrateKbps, tempSettings.bitrate) {
        if (tempSettings.bitrate != BitratePreset.CUSTOM) {
            val computed = basisItem.bitrateForPreset(tempSettings.bitrate)
            if (tempSettings.customBitrateKbps != computed) {
                tempSettings = tempSettings.copy(customBitrateKbps = computed)
            }
        } else if (maxSelectableBitrateKbps != null && tempSettings.customBitrateKbps > maxSelectableBitrateKbps) {
            tempSettings = tempSettings.copy(customBitrateKbps = maxSelectableBitrateKbps)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Settings for '${item.title}'",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Codec Selector
                Text("Video Codec", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(VideoCodec.entries) { codec ->
                        val isSelected = tempSettings.videoCodec == codec
                        FilterChip(
                            selected = isSelected,
                            onClick = { tempSettings = tempSettings.copy(videoCodec = codec) },
                            label = { Text(codec.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SkyBlue60,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                if (tempSettings.videoCodec !in supportedCodecs) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No ${tempSettings.videoCodec.label} encoder on this device - will fall back automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Resolution
                Text("Target Resolution", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ResolutionPreset.entries) { res ->
                        val isSelected = tempSettings.resolution == res
                        FilterChip(
                            selected = isSelected,
                            onClick = { tempSettings = tempSettings.copy(resolution = res) },
                            label = { Text(res.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SkyBlue60,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bitrate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Target Bitrate", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Text("${tempSettings.customBitrateKbps} kbps", style = MaterialTheme.typography.labelMedium, color = SkyBlue60)
                }

                if (nativeBitrateKbps != null) {
                    Text(
                        text = "Native bitrate: %.1f Mbps - max selectable is capped below this".format(nativeBitrateKbps / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val sliderMaxKbps = (maxSelectableBitrateKbps ?: 12000).coerceAtLeast(300)
                Slider(
                    value = tempSettings.customBitrateKbps.toFloat().coerceAtMost(sliderMaxKbps.toFloat()),
                    onValueChange = { tempSettings = tempSettings.copy(bitrate = BitratePreset.CUSTOM, customBitrateKbps = it.toInt()) },
                    valueRange = 150f..sliderMaxKbps.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = SkyBlue60, activeTrackColor = SkyBlue60)
                )

                // Only offered once this file's real duration is known.
                if (item.durationMs > 0L) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TrimRangeControl(
                        durationMs = item.durationMs,
                        trimStartMs = tempSettings.trimStartMs,
                        trimEndMs = tempSettings.trimEndMs,
                        onTrimChanged = { start, end -> tempSettings = tempSettings.copy(trimStartMs = start, trimEndMs = end) },
                        headingStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Format & Audio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Output Format", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "MP4",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = SkyBlue60
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (tempSettings.removeAudio) "Remove Audio" else "Keep Audio", style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = !tempSettings.removeAudio,
                            onCheckedChange = { keep -> tempSettings = tempSettings.copy(removeAudio = !keep) },
                            colors = SwitchDefaults.colors(checkedThumbColor = EmeraldSuccess)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSettings(item.copy(settings = tempSettings))
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = SkyBlue60)
            ) {
                Text("Apply to File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Shared by the global Compressor Studio screen and the per-item settings dialog, so trim has
 * one implementation instead of drifting into two. Caller must only render this once a real
 * duration is known (durationMs > 0) - a zero-length range isn't a meaningful slider. */
@Composable
fun TrimRangeControl(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long?,
    onTrimChanged: (startMs: Long, endMs: Long?) -> Unit,
    headingStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trim", style = headingStyle)
            if (trimStartMs > 0L || trimEndMs != null) {
                TextButton(onClick = { onTrimChanged(0L, null) }) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val effectiveEndMs = trimEndMs ?: durationMs
        Text(
            text = "%.1fs - %.1fs of %.1fs".format(trimStartMs / 1000.0, effectiveEndMs / 1000.0, durationMs / 1000.0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RangeSlider(
            value = trimStartMs.toFloat()..effectiveEndMs.toFloat(),
            onValueChange = { range ->
                val start = range.start.toLong().coerceIn(0L, durationMs)
                val end = range.endInclusive.toLong().coerceIn(start, durationMs)
                onTrimChanged(start, end.takeIf { it < durationMs })
            },
            valueRange = 0f..durationMs.toFloat(),
            colors = SliderDefaults.colors(thumbColor = SkyBlue60, activeTrackColor = SkyBlue60),
            modifier = Modifier.fillMaxWidth().testTag("trim_range_slider")
        )
    }
}
