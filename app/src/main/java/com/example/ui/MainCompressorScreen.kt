package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.VideoQueueItem
import com.example.ui.components.BatchQueueSection
import com.example.ui.components.BatchQueueSummaryCard
import com.example.ui.components.CompressionSettingsSection
import com.example.ui.components.HeaderBar
import com.example.ui.components.HistoryLogsSection
import com.example.ui.components.InputSourceSection
import com.example.ui.components.ItemSettingsDialog
import com.example.ui.components.ResourceControlsSection
import com.example.ui.theme.SkyBlue60
import kotlinx.coroutines.flow.collectLatest

import com.example.ui.components.VideoPlayerDialog

@Composable
fun MainCompressorScreen(
    viewModel: CompressorViewModel,
    modifier: Modifier = Modifier
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val globalSettings by viewModel.globalSettings.collectAsStateWithLifecycle()
    val useGlobalSettings by viewModel.useGlobalSettings.collectAsStateWithLifecycle()
    val isBatchRunning by viewModel.isBatchRunning.collectAsStateWithLifecycle()
    val isBatchPaused by viewModel.isBatchPaused.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundNotificationEnabled.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

    val historyList by viewModel.historyList.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedPresets by viewModel.savedPresets.collectAsStateWithLifecycle(initialValue = emptyList())

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Studio, 1: Queue, 2: History

    var editingItem by remember { mutableStateOf<VideoQueueItem?>(null) }
    var activeVideoPlayer by remember { mutableStateOf<Pair<String, String>?>(null) } // (filePath, title)

    LaunchedEffect(Unit) {
        viewModel.snackMessages.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            HeaderBar(
                maxCores = viewModel.maxSystemCores,
                isGpuAvailable = viewModel.isGpuAvailable,
                totalRamGb = viewModel.totalRamGb,
                isDarkMode = isDarkMode,
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                soundEnabled = soundEnabled,
                onToggleSound = { viewModel.toggleSoundNotification() }
            )
        },
        floatingActionButton = {
            if (queue.isNotEmpty() && !isBatchRunning) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.startBatchProcessing()
                        // Jump to the Batch Queue tab so the user immediately sees progress
                        // bars/status instead of wondering whether the tap registered.
                        selectedTab = 1
                    },
                    icon = { Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text("Compress Batch (${queue.size})") },
                    containerColor = SkyBlue60,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_start_batch")
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Navigation Bar Tabs
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    val tabs = listOf("Compressor Studio", "Batch Queue", "History & Logs")
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SkyBlue60 else Color.Transparent)
                                .clickable { selectedTab = index }
                                .padding(vertical = 10.dp)
                                .testTag("main_tab_$index"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BadgedBox(
                                    badge = {
                                        if (index == 1 && queue.isNotEmpty()) {
                                            Badge { Text("${queue.size}") }
                                        } else if (index == 2 && historyList.isNotEmpty()) {
                                            Badge { Text("${historyList.size}") }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = when (index) {
                                            0 -> Icons.Default.Compress
                                            1 -> Icons.Default.ListAlt
                                            else -> Icons.Default.History
                                        },
                                        contentDescription = title,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Tab Content Body
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> { // Studio Tab (Input + Compression Settings + Hardware Allocation)
                        InputSourceSection(
                            onAddLocalVideos = { viewModel.addLocalVideoUris(it) },
                            onAddUrlSource = { viewModel.addUrlSource(it) },
                            queueItems = queue
                        )

                        CompressionSettingsSection(
                            settings = globalSettings,
                            onSettingsChanged = { viewModel.updateGlobalSettings { _ -> it } },
                            savedPresets = savedPresets,
                            onApplyPreset = { viewModel.applyPreset(it) },
                            onSaveCurrentPreset = { viewModel.saveCustomPreset(it) },
                            useGlobalSettings = useGlobalSettings,
                            onToggleUseGlobalSettings = { viewModel.toggleUseGlobalSettings(it) },
                            supportedCodecs = viewModel.supportedCodecs,
                            activeVideoItem = queue.firstOrNull()
                        )

                        ResourceControlsSection(
                            settings = globalSettings,
                            onSettingsChanged = { viewModel.updateGlobalSettings { _ -> it } }
                        )

                        if (queue.isNotEmpty()) {
                            BatchQueueSummaryCard(
                                queue = queue,
                                onViewQueue = { selectedTab = 1 }
                            )
                        }
                    }

                    1 -> { // Queue Tab
                        BatchQueueSection(
                            queue = queue,
                            isBatchRunning = isBatchRunning,
                            isBatchPaused = isBatchPaused,
                            onStartBatch = { viewModel.startBatchProcessing() },
                            onPauseBatch = { viewModel.pauseBatch() },
                            onCancelBatch = { viewModel.cancelBatch() },
                            onClearQueue = { viewModel.clearQueue() },
                            onRemoveItem = { viewModel.removeItem(it) },
                            onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) },
                            onOpenItemSettings = { editingItem = it },
                            onPlayVideo = { path, title -> activeVideoPlayer = Pair(path, title) }
                        )
                    }

                    2 -> { // History & Logs Tab
                        HistoryLogsSection(
                            historyList = historyList,
                            onDeleteHistoryItem = { viewModel.deleteHistoryItem(it) },
                            onClearAllHistory = { viewModel.clearHistory() },
                            onPlayVideo = { path, title -> activeVideoPlayer = Pair(path, title) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Modal Item Settings Dialog
    editingItem?.let { item ->
        ItemSettingsDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSaveSettings = { updated ->
                viewModel.updateItemSettings(updated.id, updated.settings)
                editingItem = null
            },
            supportedCodecs = viewModel.supportedCodecs
        )
    }

    // Video Player Dialog
    activeVideoPlayer?.let { (filePath, title) ->
        VideoPlayerDialog(
            filePath = filePath,
            title = title,
            onDismiss = { activeVideoPlayer = null }
        )
    }
}
