package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.PresetEntity
import com.example.data.model.BitratePreset
import com.example.data.model.ResolutionPreset
import com.example.data.model.VideoCodec
import com.example.data.model.VideoCompressionSettings
import com.example.data.model.VideoQueueItem
import com.example.data.model.VideoSourceType
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SkyBlue60

@Composable
fun CompressionSettingsSection(
    settings: VideoCompressionSettings,
    onSettingsChanged: (VideoCompressionSettings) -> Unit,
    savedPresets: List<PresetEntity>,
    onApplyPreset: (PresetEntity) -> Unit,
    onSaveCurrentPreset: (String) -> Unit,
    useGlobalSettings: Boolean,
    onToggleUseGlobalSettings: (Boolean) -> Unit,
    supportedCodecs: Set<VideoCodec> = VideoCodec.entries.toSet(),
    activeVideoItem: VideoQueueItem? = null,
    modifier: Modifier = Modifier
) {
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Calculate dynamic estimated size using single source of truth model
    val sampleItem = (activeVideoItem ?: VideoQueueItem(
        title = "Sample Video",
        sourceType = VideoSourceType.LOCAL_FILE,
        sourcePathOrUrl = "",
        originalSizeBytes = 50_000_000L,
        durationMs = 30_000L
    )).copy(settings = settings)

    // Only a real queued item has a trustworthy source bitrate to lock the slider to - the
    // fallback sampleItem above is a rough stand-in for the size preview only.
    val nativeBitrateKbps = activeVideoItem?.copy(settings = settings)?.sourceBitrateKbps()
    val maxSelectableBitrateKbps = activeVideoItem?.copy(settings = settings)?.maxSelectableVideoBitrateKbps()

    // Basis for computing what LOW/MEDIUM/HIGH actually mean in kbps for this video: the real
    // active item's cap when known, else the same flat fallback bitrateForPreset() uses.
    val presetBasisItem = (activeVideoItem ?: VideoQueueItem(
        title = "", sourceType = VideoSourceType.LOCAL_FILE, sourcePathOrUrl = ""
    )).copy(settings = settings)

    // customBitrateKbps is the single number used everywhere (display, slider, encoder) - keep
    // it in sync with whichever preset is selected so a shown value can never silently diverge
    // from what actually gets requested.
    LaunchedEffect(maxSelectableBitrateKbps, settings.bitrate) {
        if (settings.bitrate != BitratePreset.CUSTOM) {
            val computed = presetBasisItem.bitrateForPreset(settings.bitrate)
            if (settings.customBitrateKbps != computed) {
                onSettingsChanged(settings.copy(customBitrateKbps = computed))
            }
        } else if (maxSelectableBitrateKbps != null && settings.customBitrateKbps > maxSelectableBitrateKbps) {
            onSettingsChanged(settings.copy(customBitrateKbps = maxSelectableBitrateKbps))
        }
    }

    val sampleOriginalBytes = if (sampleItem.originalSizeBytes > 0L) sampleItem.originalSizeBytes else 50_000_000L
    val estimatedOutputBytes = sampleItem.estimateCompressedSizeBytes()
    val estimatedOutputMb = estimatedOutputBytes / (1024.0 * 1024.0)
    val originalMb = sampleOriginalBytes / (1024.0 * 1024.0)
    val savingsPercent = (((sampleOriginalBytes - estimatedOutputBytes).toDouble() / sampleOriginalBytes) * 100).toInt().coerceIn(5, 95)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Section Header & Mode Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Compression Settings",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Apply settings to all files",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = useGlobalSettings,
                    onCheckedChange = onToggleUseGlobalSettings,
                    modifier = Modifier.testTag("global_mode_switch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Presets Bar
            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(savedPresets) { preset ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { onApplyPreset(preset) }
                            .testTag("preset_chip_${preset.id}")
                    ) {
                        Text(
                            text = preset.presetName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = SkyBlue60.copy(alpha = 0.15f),
                        modifier = Modifier
                            .clickable { showSavePresetDialog = true }
                            .testTag("save_preset_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "Save Preset",
                                tint = SkyBlue60,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Save Current Preset",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = SkyBlue60
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Advanced Settings Disclosure
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .testTag("advanced_settings_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Advanced Settings (codec, resolution, bitrate, audio)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (advancedExpanded) {
            Spacer(modifier = Modifier.height(16.dp))

            // Video Codec Selection (HEVC / H.265 / H.264 / VP9 / AV1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video Encoding Codec",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (settings.videoCodec == VideoCodec.HEVC_H265) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = EmeraldSuccess.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("50% Smaller", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = EmeraldSuccess, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(VideoCodec.entries) { codec ->
                    val isSelected = settings.videoCodec == codec
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSettingsChanged(settings.copy(videoCodec = codec)) },
                        label = { Text(codec.label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyBlue60,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("codec_chip_${codec.name}")
                    )
                }
            }
            if (settings.videoCodec !in supportedCodecs) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "No ${settings.videoCodec.label} encoder on this device - will fall back to a supported codec automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Resolution Selection
            Text(
                text = "Target Resolution",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ResolutionPreset.entries) { res ->
                    val isSelected = settings.resolution == res
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSettingsChanged(settings.copy(resolution = res)) },
                        label = { Text(res.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyBlue60,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("res_chip_${res.name}")
                    )
                }
            }

            if (settings.resolution == ResolutionPreset.CUSTOM) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = settings.customWidth.toString(),
                        onValueChange = {
                            val w = it.toIntOrNull() ?: settings.customWidth
                            onSettingsChanged(settings.copy(customWidth = w))
                        },
                        label = { Text("Width (px)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_width_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = settings.customHeight.toString(),
                        onValueChange = {
                            val h = it.toIntOrNull() ?: settings.customHeight
                            onSettingsChanged(settings.copy(customHeight = h))
                        },
                        label = { Text("Height (px)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_height_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bitrate Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video Bitrate Target",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${settings.customBitrateKbps} kbps (${(settings.customBitrateKbps / 1000.0).let { "%.1f".format(it) }} Mbps)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = SkyBlue60
                )
            }

            if (nativeBitrateKbps != null && activeVideoItem != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Native bitrate of \"${activeVideoItem.title}\": %.1f Mbps - max selectable is capped below this so compression always actually saves space".format(nativeBitrateKbps / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "This is the target sent to the encoder - real hardware commonly lands ~10-15% under it, which the size preview below already accounts for.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Bitrate Presets Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BitratePreset.entries) { preset ->
                    val isSelected = settings.bitrate == preset
                    val previewKbps = presetBasisItem.bitrateForPreset(preset)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onSettingsChanged(settings.copy(bitrate = preset, customBitrateKbps = previewKbps))
                        },
                        label = {
                            val labelText = if (preset == BitratePreset.CUSTOM) preset.label else "${preset.label} (~$previewKbps kbps)"
                            Text(labelText, fontSize = 12.sp)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkyBlue60,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Bitrate Slider - capped to this video's real max selectable bitrate once known
            val sliderMaxKbps = (maxSelectableBitrateKbps ?: 15000).coerceAtLeast(300)
            Slider(
                value = settings.customBitrateKbps.toFloat().coerceAtMost(sliderMaxKbps.toFloat()),
                onValueChange = {
                    onSettingsChanged(settings.copy(bitrate = BitratePreset.CUSTOM, customBitrateKbps = it.toInt()))
                },
                valueRange = 150f..sliderMaxKbps.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = SkyBlue60,
                    activeTrackColor = SkyBlue60
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bitrate_slider")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Format Selection & Audio Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Output Format",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = SkyBlue60,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "MP4 (H.264/HEVC compatible everywhere)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Audio Mute Switch
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (settings.removeAudio) "Audio Removed" else "Keep Audio",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Switch(
                        checked = !settings.removeAudio,
                        onCheckedChange = { keepAudio -> onSettingsChanged(settings.copy(removeAudio = !keepAudio)) },
                        thumbContent = {
                            Icon(
                                imageVector = if (!settings.removeAudio) Icons.Default.MusicNote else Icons.Default.MusicOff,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = EmeraldSuccess),
                        modifier = Modifier.testTag("audio_toggle_switch")
                    )
                }
            }
            } // end advancedExpanded

            Spacer(modifier = Modifier.height(16.dp))

            // Real-Time Estimation & Destination Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Preview for next compression",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "%.1f MB (Original %.1f MB)".format(estimatedOutputMb, originalMb),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldSuccess
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmeraldSuccess.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Save ~$savingsPercent%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldSuccess,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = SkyBlue60,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save Location: Internal Storage/Downloads/CompressedVideos/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    // Save Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Save Custom Preset") },
            text = {
                Column {
                    Text("Enter a name for your custom video compression configuration:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        label = { Text("Preset Name") },
                        placeholder = { Text("e.g. My Fast Web 720p") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("preset_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            onSaveCurrentPreset(presetNameInput.trim())
                            presetNameInput = ""
                            showSavePresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue60)
                ) {
                    Text("Save Preset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

