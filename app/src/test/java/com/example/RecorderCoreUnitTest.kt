package com.example

import android.media.MediaFormat
import com.example.region.RegionSelectionManager
import com.example.settings.AudioSourceOption
import com.example.settings.InternalRecordingConfig
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCoreUnitTest {

  @Test
  fun `default settings and fixed internal H265 30fps match specification`() {
    val defaults = RecordingSettings()
    assertEquals(RecordingMode.FULL_SCREEN, defaults.recordingMode)
    assertEquals(OrientationMode.PORTRAIT, defaults.orientationMode)
    assertEquals(listOf(OrientationMode.PORTRAIT, OrientationMode.LANDSCAPE), OrientationMode.entries)
    assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, InternalRecordingConfig.VIDEO_MIME_TYPE)
    assertEquals(30, InternalRecordingConfig.TARGET_FPS)
    assertEquals(AudioSourceOption.INTERNAL, defaults.audioSource)
    assertEquals(VideoBitrateOption.MBPS_150, defaults.videoBitrate)
    assertEquals(
      listOf(
        VideoBitrateOption.MBPS_100,
        VideoBitrateOption.MBPS_150,
        VideoBitrateOption.MBPS_200,
        VideoBitrateOption.MBPS_250,
        VideoBitrateOption.MBPS_300,
        VideoBitrateOption.MBPS_350,
      ),
      VideoBitrateOption.entries,
    )
    assertEquals(
      listOf(100_000_000, 150_000_000, 200_000_000, 250_000_000, 300_000_000, 350_000_000),
      VideoBitrateOption.entries.map { it.targetBitsPerSecond },
    )
    assertEquals(RegionAnimationOption.SHOW, defaults.regionAnimation)
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
  fun `pixel region produces even H264 dimensions within screen bounds`() {
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
