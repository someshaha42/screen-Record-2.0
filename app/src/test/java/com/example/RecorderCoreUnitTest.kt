package com.example

import android.media.MediaFormat
import com.example.diagnostics.RecordingDiagnostics
import com.example.encoder.VideoEncoder
import com.example.muxer.MediaMuxerManager
import com.example.muxer.MuxerPacketQueue
import com.example.region.RegionSelectionManager
import com.example.settings.AudioFormatOption
import com.example.settings.AudioSourceOption
import com.example.settings.BitrateModeOption
import com.example.settings.ColorDepthOption
import com.example.settings.ColorRangeOption
import com.example.settings.GopIntervalOption
import com.example.settings.InternalRecordingConfig
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoFpsOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

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
    assertEquals("BT.709 (SDR)", InternalRecordingConfig.COLOR_STANDARD_LABEL)
    assertEquals(
      "Asynchronous Rendering (Direct Surface + Async Callback)",
      InternalRecordingConfig.RENDERING_MODE_LABEL,
    )
    assertNull(defaults.darkThemeOverride)

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

  @Test
  fun `muxer packet queue preserves all 60fps burst packets, buffers pre-start frames, and reuses pool`() {
    val diagnostics =
      RecordingDiagnostics(
        targetFps = 60,
        clockMs = { System.nanoTime() / 1_000_000L },
        cpuTimeMs = { 0L },
      )
    val receivedVideoPts = mutableListOf<Long>()
    val receivedAudioPts = mutableListOf<Long>()
    val receivedPayloads = mutableListOf<Int>()
    val writeCounter = AtomicInteger(0)

    val queue =
      MuxerPacketQueue(
        diagnostics = diagnostics,
        maxVideoPackets = 8,
        maxAudioPackets = 16,
        maxVideoPoolBytes = 2L * 1024L * 1024L,
      ) { isVideo, buffer, size, ptsUs, _ ->
        val seq = writeCounter.incrementAndGet()
        if (seq % 15 == 0) {
          Thread.sleep(3L) // Simulate periodic FUSE/UFS storage flush stall
        }
        val firstInt = if (size >= 4) buffer.getInt(0) else -1
        synchronized(receivedVideoPts) {
          if (isVideo) {
            receivedVideoPts.add(ptsUs)
            receivedPayloads.add(firstInt)
          } else {
            receivedAudioPts.add(ptsUs)
          }
        }
      }

    queue.start()

    // Submit 5 early video frames BEFORE sink is ready (e.g., while AudioRecord finishes init)
    val videoBuf = ByteBuffer.allocateDirect(4096)
    val audioBuf = ByteBuffer.allocateDirect(512)
    for (i in 0 until 5) {
      videoBuf.clear()
      videoBuf.putInt(i)
      videoBuf.position(0)
      videoBuf.limit(1024)
      assertTrue(queue.submit(true, videoBuf, 0, 1024, i * 16_667L, 0))
    }

    // Mark sink ready after initial frames were already queued
    queue.markSinkReady()

    // Submit remaining 115 video frames + 60 audio packets in a burst
    for (i in 5 until 120) {
      videoBuf.clear()
      videoBuf.putInt(i)
      videoBuf.position(0)
      videoBuf.limit(1024)
      assertTrue(queue.submit(true, videoBuf, 0, 1024, i * 16_667L, 0))
      if (i % 2 == 0) {
        audioBuf.clear()
        audioBuf.putInt(i)
        audioBuf.position(0)
        audioBuf.limit(256)
        assertTrue(queue.submit(false, audioBuf, 0, 256, i * 16_667L, 0))
      }
    }

    queue.closeAndDrain(timeoutMs = 5000L)

    assertEquals(120, receivedVideoPts.size)
    assertEquals(57, receivedAudioPts.size)
    assertEquals((0 until 120).toList(), receivedPayloads)
    assertEquals((0 until 120).map { it * 16_667L }, receivedVideoPts)

    val snap = diagnostics.snapshot()
    // Bounded pool reused direct buffers across 178 total packets without per-frame allocation
    assertTrue(
      "Expected pool allocations (${snap.muxerPoolAllocations}) to stay bounded <= 24",
      snap.muxerPoolAllocations <= 24L,
    )
  }

  @Test
  fun `muxer packet queue closes cleanly without deadlock if stopped before sink becomes ready`() {
    val queue =
      MuxerPacketQueue(maxVideoPackets = 4, maxAudioPackets = 4) { _, _, _, _, _ -> }
    queue.start()
    val buf = ByteBuffer.allocateDirect(256)
    assertTrue(queue.submit(true, buf, 0, 128, 0L, 0))
    queue.closeAndDrain(timeoutMs = 1000L)
    assertFalse(queue.submit(true, buf, 0, 128, 16_667L, 0))
  }

  @Test
  fun `recording diagnostics computes 60fps intervals, pause-excluded drops, queue peaks, and errors`() {
    var fakeClockMs = 1_000L
    var fakeCpuMs = 100L
    val diag =
      RecordingDiagnostics(
        targetFps = 60,
        clockMs = { fakeClockMs },
        cpuTimeMs = { fakeCpuMs },
      )
    diag.configureEncoder(
      codecName = "c2.qti.hevc.encoder",
      isHardwareAccelerated = true,
      performancePointSupported = true,
      width = 1080,
      height = 2400,
      bitrateBps = 150_000_000,
    )

    // 60 steady frames at ~16.667ms (1000ms active)
    var ptsUs = 0L
    for (i in 0 until 60) {
      diag.onVideoOutputFrame(
        rawPtsUs = ptsUs,
        byteSize = 250_000,
        callbackDurationNs = 180_000L, // 180 us
        isKeyFrame = i == 0,
      )
      ptsUs += 16_667L
    }
    diag.onAudioOutputPacket(rawPtsUs = 980_000L)
    diag.onMuxerQueued(5)
    diag.onMuxerQueued(11)
    diag.onMuxerDequeued(4)
    diag.onMuxerSampleWritten(byteSize = 250_000, writeDurationNs = 2_400_000L)

    // Pause for 5 seconds and jump PTS by 5s -> must NOT count as dropped frames
    diag.onPauseStateChanged(true)
    fakeClockMs += 5_000L
    ptsUs += 5_000_000L
    diag.onPauseStateChanged(false)

    // Resume steady frame + 1 delayed frame interval (50ms -> 3x 16.667ms -> 2 estimated drops)
    fakeClockMs += 1_000L
    fakeCpuMs += 220L
    diag.onVideoOutputFrame(rawPtsUs = ptsUs, byteSize = 240_000, callbackDurationNs = 220_000L, isKeyFrame = false)
    ptsUs += 50_000L
    diag.onVideoOutputFrame(rawPtsUs = ptsUs, byteSize = 240_000, callbackDurationNs = 200_000L, isKeyFrame = false)

    diag.onCodecError()
    diag.onAudioError()

    val snap = diag.snapshot()
    assertEquals(62L, snap.videoOutputFrames)
    assertEquals(1L, snap.videoKeyFrames)
    assertEquals(1L, snap.audioPackets)
    assertEquals(1L, snap.delayedFrameIntervals)
    assertEquals(2L, snap.estimatedDroppedFrames)
    assertEquals(11, snap.muxerQueueDepthPeak)
    assertEquals(4, snap.muxerQueueDepthCurrent)
    assertEquals(250_000L, snap.totalBytesWritten)
    assertEquals(1L, snap.codecErrors)
    assertEquals(1L, snap.audioErrors)
    assertEquals(0L, snap.muxerErrors)
    assertEquals(220L, snap.cpuTimeDeltaMs)
    assertEquals(1_000L, snap.activeElapsedMs)
    assertEquals(true, snap.performancePointSupported)
    assertTrue(snap.avgEncoderCallbackUs in 180L..220L)
    assertEquals(2_400L, snap.maxMuxerWriteUs)
  }
}
