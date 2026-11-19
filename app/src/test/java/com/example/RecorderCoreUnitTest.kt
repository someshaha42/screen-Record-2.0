package com.example

import android.media.MediaFormat
import com.example.encoder.VideoEncoder
import com.example.muxer.MediaMuxerManager
import com.example.region.RegionSelectionManager
import com.example.settings.AudioFormatOption
import com.example.settings.AudioSourceOption
import com.example.settings.BitrateModeOption
import com.example.settings.ColorDepthOption
import com.example.settings.ColorRangeOption
import com.example.settings.ColorStandardOption
import com.example.settings.GopIntervalOption
import com.example.settings.InternalRecordingConfig
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RegionAnimationOption
import com.example.settings.RenderingModeOption
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoFpsOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCoreUnitTest {

  @Test
  fun `default settings and hardcoded H265 HEVC hardware surface config match specification`() {
    val defaults = RecordingSettings()
    assertEquals(RecordingMode.FULL_SCREEN, defaults.recordingMode)
    assertEquals(OrientationMode.PORTRAIT, defaults.orientationMode)
    assertEquals(listOf(OrientationMode.PORTRAIT, OrientationMode.LANDSCAPE), OrientationMode.entries)
    assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, InternalRecordingConfig.VIDEO_MIME_TYPE)
    assertEquals(48_000, InternalRecordingConfig.AUDIO_SAMPLE_RATE_HZ)
    assertEquals(128_000, InternalRecordingConfig.AUDIO_BITRATE_BPS)
    assertEquals(1, InternalRecordingConfig.AUDIO_CHANNEL_COUNT)
    assertEquals("4:2:0", InternalRecordingConfig.CHROMA_SUBSAMPLING_LABEL)
    assertEquals("Standard MP4", InternalRecordingConfig.OUTPUT_CONTAINER_LABEL)

    assertEquals(AudioSourceOption.INTERNAL, defaults.audioSource)
    assertEquals(VideoBitrateOption.MBPS_150, defaults.videoBitrate)
    assertEquals(RegionAnimationOption.SHOW, defaults.regionAnimation)

    // Verify all configurable settings default to option A
    assertEquals(BitrateModeOption.VBR, defaults.bitrateMode)
    assertEquals(listOf(BitrateModeOption.VBR, BitrateModeOption.CQ), BitrateModeOption.entries)

    assertEquals(ColorDepthOption.BIT_8, defaults.colorDepth)
    assertEquals(listOf(8, 10), ColorDepthOption.entries.map { it.bitDepth })

    assertEquals(VideoFpsOption.FPS_30, defaults.videoFps)
    assertEquals(listOf(30, 60), VideoFpsOption.entries.map { it.fps })

    assertEquals(ColorStandardOption.BT_709, defaults.colorStandard)
    assertEquals(
      listOf(ColorStandardOption.BT_709, ColorStandardOption.BT_2020),
      ColorStandardOption.entries,
    )

    assertEquals(RenderingModeOption.ASYNC_SURFACE, defaults.renderingMode)
    assertEquals(
      listOf(RenderingModeOption.ASYNC_SURFACE, RenderingModeOption.DIRECT_SURFACE),
      RenderingModeOption.entries,
    )

    assertEquals(GopIntervalOption.SEC_1, defaults.gopInterval)
    assertEquals(listOf(1.0f, 0.5f), GopIntervalOption.entries.map { it.secondsFloat })

    assertEquals(ColorRangeOption.LIMITED, defaults.colorRange)
    assertEquals(
      listOf(MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_RANGE_FULL),
      ColorRangeOption.entries.map { it.mediaFormatRange },
    )

    assertEquals(AudioFormatOption.MONO_AAC, defaults.audioFormat)
    assertEquals(
      listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_MPEG),
      AudioFormatOption.entries.map { it.mimeType },
    )

    val hevcSelection =
      VideoEncoder.selectBestEncoder(
        requestedWidth = 1080,
        requestedHeight = 2400,
        availableCodecs = emptyList(),
      )
    assertEquals("default-hevc", hevcSelection.codecName)
  }

  @Test
  fun `muxer timestamp normalization anchors 59-hour boot timestamps cleanly at zero`() {
    val bootEpochPtsUs = 214_748_000_000L // ~59h 39m 08s since device boot
    val firstPts =
      MediaMuxerManager.normalizePresentationTimeUs(
        rawPtsUs = bootEpochPtsUs,
        anchorPtsUs = bootEpochPtsUs,
        lastWrittenPtsUs = -1L,
      )
    assertEquals(0L, firstPts)

    val secondPts =
      MediaMuxerManager.normalizePresentationTimeUs(
        rawPtsUs = bootEpochPtsUs + 33_333L,
        anchorPtsUs = bootEpochPtsUs,
        lastWrittenPtsUs = firstPts,
      )
    assertEquals(33_333L, secondPts)

    // Duplicate/jitter timestamp clamps strictly monotonic
    val duplicatePts =
      MediaMuxerManager.normalizePresentationTimeUs(
        rawPtsUs = bootEpochPtsUs + 33_333L,
        anchorPtsUs = bootEpochPtsUs,
        lastWrittenPtsUs = secondPts,
      )
    assertEquals(33_334L, duplicatePts)
  }

  @Test
  fun `orientation mode resolves portrait and landscape dimensions for full screen and region`() {
    val portraitFromLandscape =
      RegionSelectionManager.resolveFullScreenDimensions(2401, 1081, OrientationMode.PORTRAIT)
    assertEquals(1080 to 2400, portraitFromLandscape)

    val landscapeFromPortrait =
      RegionSelectionManager.resolveFullScreenDimensions(1081, 2401, OrientationMode.LANDSCAPE)
    assertEquals(2400 to 1080, landscapeFromPortrait)

    val norm = NormalizedRegion(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.7f)
    val portraitRegion =
      RegionSelectionManager.toPixelRegion(
        norm,
        portraitFromLandscape.first,
        portraitFromLandscape.second,
      )
    val landscapeRegion =
      RegionSelectionManager.toPixelRegion(
        norm,
        landscapeFromPortrait.first,
        landscapeFromPortrait.second,
      )

    assertTrue(portraitRegion.height > portraitRegion.width)
    assertTrue(landscapeRegion.width > landscapeRegion.height)
    assertEquals(0, portraitRegion.width % 2)
    assertEquals(0, portraitRegion.height % 2)
    assertEquals(0, landscapeRegion.width % 2)
    assertEquals(0, landscapeRegion.height % 2)
  }

  @Test
  fun `pixel region produces even HEVC dimensions within screen bounds`() {
    val region = NormalizedRegion(left = 0.133f, top = 0.217f, right = 0.871f, bottom = 0.779f)
    val pixel = RegionSelectionManager.toPixelRegion(region, screenWidth = 1081, screenHeight = 2401)

    assertEquals(0, pixel.width % 2)
    assertEquals(0, pixel.height % 2)
    assertTrue(pixel.left >= 0)
    assertTrue(pixel.top >= 0)
    assertTrue(pixel.right <= pixel.screenWidth)
    assertTrue(pixel.bottom <= pixel.screenHeight)
    assertTrue(pixel.width >= 128)
    assertTrue(pixel.height >= 128)
  }

  @Test
  fun `normalized region clamps invalid coordinates safely`() {
    val invalid = NormalizedRegion(left = -0.5f, top = 0.95f, right = 0.0f, bottom = 1.4f)
    val clean = invalid.normalized()

    assertTrue(clean.left >= 0f)
    assertTrue(clean.top >= 0f)
    assertTrue(clean.right <= 1f)
    assertTrue(clean.bottom <= 1f)
    assertTrue(clean.right - clean.left >= 0.119f)
    assertTrue(clean.bottom - clean.top >= 0.119f)
  }
}
