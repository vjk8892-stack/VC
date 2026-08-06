package com.example.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BitratePreset
import com.example.data.model.OutputFormat
import com.example.data.model.ResolutionPreset
import com.example.data.model.VideoCodec
import com.example.data.model.VideoQueueItem
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SkyBlue60

@Composable
fun ItemSettingsDialog(
    item: VideoQueueItem,
    onDismiss: () -> Unit,
    onSaveSettings: (VideoQueueItem) -> Unit
) {
    var tempSettings by remember { mutableStateOf(item.settings) }

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

                Slider(
                    value = tempSettings.customBitrateKbps.toFloat(),
                    onValueChange = { tempSettings = tempSettings.copy(bitrate = BitratePreset.CUSTOM, customBitrateKbps = it.toInt()) },
                    valueRange = 300f..12000f,
                    colors = SliderDefaults.colors(thumbColor = SkyBlue60, activeTrackColor = SkyBlue60)
                )

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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(OutputFormat.entries) { fmt ->
                                val isSelected = tempSettings.format == fmt
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) SkyBlue60 else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { tempSettings = tempSettings.copy(format = fmt) }
                                ) {
                                    Text(
                                        text = fmt.extension.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
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
