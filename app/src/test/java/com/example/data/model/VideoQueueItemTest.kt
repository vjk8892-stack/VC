package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQueueItemTest {

    private fun item(
        originalSizeBytes: Long = 0L,
        durationMs: Long = 0L,
        originalWidth: Int = 1920,
        originalHeight: Int = 1080,
        originalAudioBitrateKbps: Int? = null,
        settings: VideoCompressionSettings = VideoCompressionSettings()
    ) = VideoQueueItem(
        title = "test.mp4",
        sourceType = VideoSourceType.LOCAL_FILE,
        sourcePathOrUrl = "/tmp/test.mp4",
        originalSizeBytes = originalSizeBytes,
        durationMs = durationMs,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        originalAudioBitrateKbps = originalAudioBitrateKbps,
        settings = settings
    )

    // --- effectiveDurationMs (trim) ---

    @Test
    fun `effectiveDurationMs is unchanged when no trim is set`() {
        assertEquals(20_000L, item(durationMs = 20_000L).effectiveDurationMs())
    }

    @Test
    fun `effectiveDurationMs reflects the trim range`() {
        val settings = VideoCompressionSettings(trimStartMs = 2_000L, trimEndMs = 12_000L)
        assertEquals(10_000L, item(durationMs = 20_000L, settings = settings).effectiveDurationMs())
    }

    @Test
    fun `effectiveDurationMs falls back to full duration for a degenerate trim range`() {
        // end at or before start must not produce a zero/negative duration downstream math
        // (progress %, ETA, size estimate) can't handle.
        val settings = VideoCompressionSettings(trimStartMs = 15_000L, trimEndMs = 10_000L)
        assertEquals(20_000L, item(durationMs = 20_000L, settings = settings).effectiveDurationMs())
    }

    @Test
    fun `effectiveDurationMs is unaffected by trim before the real duration is known`() {
        val settings = VideoCompressionSettings(trimStartMs = 2_000L, trimEndMs = 5_000L)
        assertEquals(0L, item(durationMs = 0L, settings = settings).effectiveDurationMs())
    }

    // --- bitrateForTargetSizeBytes ---

    @Test
    fun `bitrateForTargetSizeBytes yields an estimate close to the requested target`() {
        // Large, high-bitrate source so the safe-ceiling cap never kicks in here - this test is
        // only about whether the back-solved bitrate actually reproduces the target size once
        // run back through the same estimate the UI preview uses.
        val source = item(originalSizeBytes = 500_000_000L, durationMs = 60_000L)
        val targetBytes = 20L * 1024 * 1024
        val requestedKbps = source.bitrateForTargetSizeBytes(targetBytes)
        val withRequestedBitrate = source.copy(settings = source.settings.copy(customBitrateKbps = requestedKbps))
        val estimatedBytes = withRequestedBitrate.estimateCompressedSizeBytes()

        val diffRatio = kotlin.math.abs(estimatedBytes - targetBytes).toDouble() / targetBytes
        assertTrue("estimate $estimatedBytes should be close to target $targetBytes", diffRatio < 0.02)
    }

    @Test
    fun `bitrateForTargetSizeBytes is capped by maxSelectableVideoBitrateKbps`() {
        // Low-bitrate source, unreasonably large target: the ideal solve would ask for far more
        // than this source can safely be given, so the result must not exceed the safety cap.
        val source = item(originalSizeBytes = 5_000_000L, durationMs = 60_000L)
        val cap = source.maxSelectableVideoBitrateKbps()!!
        val hugeTargetBytes = 200L * 1024 * 1024
        assertEquals(cap, source.bitrateForTargetSizeBytes(hugeTargetBytes))
    }

    // --- sourceBitrateKbps ---

    @Test
    fun `sourceBitrateKbps is null when size or duration are unknown`() {
        assertNull(item(originalSizeBytes = 0L, durationMs = 10_000L).sourceBitrateKbps())
        assertNull(item(originalSizeBytes = 10_000_000L, durationMs = 0L).sourceBitrateKbps())
    }

    @Test
    fun `sourceBitrateKbps derives real bitrate from size and duration`() {
        // 10,000,000 bytes over 10s = 8,000,000 bits/s = 8000 kbps
        val kbps = item(originalSizeBytes = 10_000_000L, durationMs = 10_000L).sourceBitrateKbps()
        assertEquals(8000, kbps)
    }

    // --- maxSelectableVideoBitrateKbps / getEffectiveVideoBitrateKbps: the core
    // "compression must not grow the file" guarantee ---

    @Test
    fun `maxSelectableVideoBitrateKbps is null when source bitrate is unknown`() {
        assertNull(item().maxSelectableVideoBitrateKbps())
    }

    @Test
    fun `effective video bitrate is never above what keeps output smaller than source`() {
        // Source averages 8000 kbps; requesting way more than that must still be capped.
        val settings = VideoCompressionSettings(bitrate = BitratePreset.CUSTOM, customBitrateKbps = 50_000)
        val i = item(originalSizeBytes = 10_000_000L, durationMs = 10_000L, settings = settings)

        val effective = i.getEffectiveVideoBitrateKbps()
        val max = i.maxSelectableVideoBitrateKbps()

        assertTrue("effective ($effective) must not exceed the safe cap ($max)", effective <= (max ?: Int.MAX_VALUE))
        // 85% safety margin below the source's own bitrate, minus audio (default 128 kbps assumed).
        assertEquals(((8000 * 0.85).toInt() - 128), max)
    }

    @Test
    fun `effective video bitrate passes through the request when source is unknown`() {
        val settings = VideoCompressionSettings(bitrate = BitratePreset.CUSTOM, customBitrateKbps = 1234)
        val i = item(settings = settings) // no size/duration yet (e.g. URL item pre-download)
        assertEquals(1234, i.getEffectiveVideoBitrateKbps())
    }

    @Test
    fun `removing audio frees up the full safety margin for video`() {
        val withAudio = item(
            originalSizeBytes = 10_000_000L, durationMs = 10_000L,
            settings = VideoCompressionSettings(removeAudio = false)
        ).maxSelectableVideoBitrateKbps()!!
        val withoutAudio = item(
            originalSizeBytes = 10_000_000L, durationMs = 10_000L,
            settings = VideoCompressionSettings(removeAudio = true)
        ).maxSelectableVideoBitrateKbps()!!

        assertTrue(withoutAudio > withAudio)
    }

    // --- bitrateForPreset: LOW/MEDIUM/HIGH are fractions of the source's own real max ---

    @Test
    fun `bitrate presets scale as fractions of the source's own max, not fixed numbers`() {
        val settings = VideoCompressionSettings()
        val i = item(originalSizeBytes = 10_000_000L, durationMs = 10_000L, settings = settings)
        val cap = i.maxSelectableVideoBitrateKbps()!!

        assertEquals(cap, i.bitrateForPreset(BitratePreset.HIGH))
        assertEquals((cap / 2).coerceAtLeast(150), i.bitrateForPreset(BitratePreset.MEDIUM))
        assertEquals((cap / 3).coerceAtLeast(150), i.bitrateForPreset(BitratePreset.LOW))
        assertEquals(cap, i.bitrateForPreset(BitratePreset.CUSTOM))
    }

    @Test
    fun `bitrateForPreset falls back to a flat assumption when source is unknown`() {
        val i = item() // no size/duration yet
        assertEquals(8000, i.bitrateForPreset(BitratePreset.HIGH))
        assertEquals(4000, i.bitrateForPreset(BitratePreset.MEDIUM))
    }

    // --- getEffectiveDimensions ---

    @Test
    fun `custom resolution is honored as-is, just rounded to even`() {
        val settings = VideoCompressionSettings(resolution = ResolutionPreset.CUSTOM, customWidth = 641, customHeight = 359)
        val (w, h) = item(settings = settings).getEffectiveDimensions()
        assertEquals(642, w)
        assertEquals(360, h)
    }

    @Test
    fun `original resolution preset keeps the source's own dimensions`() {
        val settings = VideoCompressionSettings(resolution = ResolutionPreset.ORIGINAL)
        val (w, h) = item(originalWidth = 1920, originalHeight = 1080, settings = settings).getEffectiveDimensions()
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `landscape source scaled to a resolution preset preserves aspect ratio`() {
        val settings = VideoCompressionSettings(resolution = ResolutionPreset.RES_720P)
        // 16:9 source, scaled down: width becomes 1280, height should stay 16:9 (720).
        val (w, h) = item(originalWidth = 1920, originalHeight = 1080, settings = settings).getEffectiveDimensions()
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun `portrait source scaled to a resolution preset preserves aspect ratio`() {
        val settings = VideoCompressionSettings(resolution = ResolutionPreset.RES_720P)
        // 9:16 source: the preset's max dimension (1280) becomes the height, not the width.
        val (w, h) = item(originalWidth = 1080, originalHeight = 1920, settings = settings).getEffectiveDimensions()
        assertEquals(1280, h)
        assertEquals(720, w)
    }

    @Test
    fun `dimensions are always even and never below the floor`() {
        val settings = VideoCompressionSettings(resolution = ResolutionPreset.CUSTOM, customWidth = 10, customHeight = 10)
        val (w, h) = item(settings = settings).getEffectiveDimensions()
        assertTrue(w % 2 == 0)
        assertTrue(h % 2 == 0)
        assertTrue(w >= 160)
        assertTrue(h >= 120)
    }

    // --- estimateCompressedSizeBytes ---

    @Test
    fun `size estimate mirrors the real encoder's effective bitrate, not a naive guess`() {
        val settings = VideoCompressionSettings(bitrate = BitratePreset.CUSTOM, customBitrateKbps = 2000, removeAudio = true)
        val i = item(durationMs = 10_000L, settings = settings)

        // No source bitrate known here, so effective == requested (2000 kbps), no audio.
        // REAL_WORLD_CBR_EFFICIENCY (0.88) is applied on top, matching real hardware-encoder
        // undershoot rather than promising savings the actual pass won't deliver.
        val expectedVideoKbps = (2000 * 0.88).toInt()
        val expectedBytes = (expectedVideoKbps * 1000L / 8.0 * 10.0).toLong()

        assertEquals(expectedBytes, i.estimateCompressedSizeBytes())
    }

    @Test
    fun `size estimate never goes below the floor`() {
        val settings = VideoCompressionSettings(bitrate = BitratePreset.CUSTOM, customBitrateKbps = 1, removeAudio = true)
        val i = item(durationMs = 1L, settings = settings)
        assertTrue(i.estimateCompressedSizeBytes() >= 50_000L)
    }
}
