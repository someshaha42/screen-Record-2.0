package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.muxer.MediaMuxerManager
import com.example.settings.ColorMode
import com.example.settings.InternalRecordingConfig
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoCodecOption
import com.example.settings.VideoFpsOption
import java.util.concurrent.atomic.AtomicBoolean

enum class ChipsetFamily {
  SNAPDRAGON,
  MEDIATEK,
  OTHER,
}

data class EncoderSelection(
  val codecName: String,
  val isHardwareAccelerated: Boolean,
  val chipsetFamily: ChipsetFamily,
  val effectiveBitrateBps: Int,
  val alignedWidth: Int,
  val alignedHeight: Int,
  val mimeType: String = InternalRecordingConfig.VIDEO_MIME_TYPE,
)

data class RgbColorCapabilityReport(
  val supportsSurfaceInput: Boolean,
  val supportsDirectRgbaBuffer: Boolean,
  val supportsVbr: Boolean,
  val maxProfile: Int?,
  val maxLevel: Int?,
)

/**
 * Hardware-first H.265/HEVC and H.264/AVC (30/60 FPS) MediaCodec encoder with chipset detection,
 * automatic capability clamping, dedicated surface input, and separate Normal vs RGB 8:8:8:8
 * color fidelity configurations.
 */
class VideoEncoder(
  private val requestedWidth: Int,
  private val requestedHeight: Int,
  private val bitrateOption: VideoBitrateOption,
  private val muxerManager: MediaMuxerManager,
  private val fpsOption: VideoFpsOption = VideoFpsOption.FPS_30,
  private val codecOption: VideoCodecOption = VideoCodecOption.H265,
  private val colorMode: ColorMode = ColorMode.NORMAL,
) {
  private var mediaCodec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drainThread: Thread? = null
  private val isRunning = AtomicBoolean(false)
  private var selection: EncoderSelection? = null

  fun prepare(): EncoderSelection {
    var selected =
      selectBestEncoder(
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        bitrateOption = bitrateOption,
        fpsOption = fpsOption,
        codecOption = codecOption,
      )

    val codec =
      runCatching { MediaCodec.createByCodecName(selected.codecName) }
        .getOrElse {
          Log.w(TAG, "Preferred codec ${selected.codecName} failed to instantiate, using default", it)
          MediaCodec.createEncoderByType(codecOption.mimeType)
        }

    if (colorMode == ColorMode.RGB_8888) {
      val report = inspectRgbColorCapabilities(codec.codecInfo, selected.mimeType)
      Log.i(
        TAG,
        "RGB 8:8:8:8 capability inspection: codec=${selected.codecName}, " +
          "surfaceInput=${report.supportsSurfaceInput}, directRgba=${report.supportsDirectRgbaBuffer}, " +
          "vbr=${report.supportsVbr}, maxProfile=${report.maxProfile}, " +
          "note=GPU RGBA8888 surface fed to hardware encoder CSC",
      )
      configureRgb8888Codec(codec, selected, report)
    } else {
      val primaryFormat = buildNormalFormat(codec.codecInfo, selected, includeAdvanced = true)
      try {
        codec.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      } catch (e: Exception) {
        Log.w(TAG, "Primary format config rejected; retrying safe baseline config", e)
        val safeBitrate = selected.effectiveBitrateBps.coerceAtMost(50_000_000)
        selected = selected.copy(effectiveBitrateBps = safeBitrate)
        val safeFormat = buildNormalFormat(codec.codecInfo, selected, includeAdvanced = false)
        codec.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      }
    }
    selection = selected

    inputSurface = codec.createInputSurface()
    codec.start()
    mediaCodec = codec

    isRunning.set(true)
    drainThread =
      Thread(::drainLoop, "FrameCut-VideoDrain").apply {
        priority = Thread.MAX_PRIORITY
        start()
      }

    Log.i(
      TAG,
      "VideoEncoder started: codec=${selected.codecName} (${codecOption.label}), " +
        "colorMode=${colorMode.label}, fps=${fpsOption.fps}, hw=${selected.isHardwareAccelerated}, " +
        "chipset=${selected.chipsetFamily}, size=${selected.alignedWidth}x${selected.alignedHeight}, " +
        "bitrate=${selected.effectiveBitrateBps / 1_000_000}Mbps",
    )
    return selected
  }

  private fun configureRgb8888Codec(
    codec: MediaCodec,
    selected: EncoderSelection,
    report: RgbColorCapabilityReport,
  ) {
    // Attempt 1: BT.709 + SDR transfer + full 0-255 color range + highest compatible profile (no resolution/FPS/bitrate reduction)
    val fullRangeFormat =
      buildRgb8888Format(
        codecInfo = codec.codecInfo,
        selected = selected,
        colorRange = MediaFormat.COLOR_RANGE_FULL,
        includeProfile = true,
        report = report,
      )
    try {
      codec.configure(fullRangeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return
    } catch (e: Exception) {
      Log.w(TAG, "RGB 8:8:8:8 full-range config rejected; falling back to BT.709 limited-range", e)
    }

    // Attempt 2: BT.709 + SDR transfer + limited color range (still preserving full resolution, FPS, and bitrate)
    val limitedRangeFormat =
      buildRgb8888Format(
        codecInfo = codec.codecInfo,
        selected = selected,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED,
        includeProfile = true,
        report = report,
      )
    try {
      codec.configure(limitedRangeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return
    } catch (e: Exception) {
      Log.w(TAG, "RGB 8:8:8:8 profiled limited-range rejected; retrying clean baseline without profile override", e)
    }

    // Attempt 3: Clean BT.709 baseline preserving user resolution, FPS, and bitrate
    val cleanBaselineFormat =
      buildRgb8888Format(
        codecInfo = codec.codecInfo,
        selected = selected,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED,
        includeProfile = false,
        report = report,
      )
    codec.configure(cleanBaselineFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
  }

  private fun buildNormalFormat(
    codecInfo: MediaCodecInfo,
    selected: EncoderSelection,
    includeAdvanced: Boolean,
  ): MediaFormat {
    return MediaFormat.createVideoFormat(
        selected.mimeType,
        selected.alignedWidth,
        selected.alignedHeight,
      )
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
        setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, fpsOption.fps)
        setInteger(
          MediaFormat.KEY_I_FRAME_INTERVAL,
          InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
        )
        if (includeAdvanced) {
          setInteger(MediaFormat.KEY_CAPTURE_RATE, fpsOption.fps)
          setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
          configureBitrateModeAndProfile(this, codecInfo, selected.mimeType, forceProfile = false)
        }
      }
  }

  private fun buildRgb8888Format(
    codecInfo: MediaCodecInfo,
    selected: EncoderSelection,
    colorRange: Int,
    includeProfile: Boolean,
    report: RgbColorCapabilityReport,
  ): MediaFormat {
    return MediaFormat.createVideoFormat(
        selected.mimeType,
        selected.alignedWidth,
        selected.alignedHeight,
      )
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
        setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, fpsOption.fps)
        setInteger(MediaFormat.KEY_CAPTURE_RATE, fpsOption.fps)
        setInteger(
          MediaFormat.KEY_I_FRAME_INTERVAL,
          InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
        )
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)
        configureBitrateModeAndProfile(
          format = this,
          info = codecInfo,
          mimeType = selected.mimeType,
          forceProfile = includeProfile,
          preferredProfile = report.maxProfile,
          preferredLevel = report.maxLevel,
        )
      }
  }

  fun getInputSurface(): Surface =
    inputSurface ?: throw IllegalStateException("VideoEncoder has not been prepared")

  fun getSelection(): EncoderSelection? = selection

  fun stopAndRelease() {
    if (!isRunning.getAndSet(false)) {
      releaseResources()
      return
    }
    try {
      mediaCodec?.signalEndOfInputStream()
    } catch (e: Exception) {
      Log.w(TAG, "signalEndOfInputStream failed", e)
    }
    try {
      drainThread?.join(2500L)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    releaseResources()
  }

  private fun drainLoop() {
    val codec = mediaCodec ?: return
    val bufferInfo = MediaCodec.BufferInfo()
    var formatAdded = false

    while (true) {
      val timeoutUs = if (isRunning.get()) 10_000L else 5_000L
      val index =
        try {
          codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        } catch (e: Exception) {
          Log.e(TAG, "dequeueOutputBuffer error", e)
          break
        }

      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!isRunning.get()) {
            break
          }
        }
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if (!formatAdded) {
            muxerManager.addVideoTrack(codec.outputFormat)
            formatAdded = true
          }
        }
        index >= 0 -> {
          val encodedData = codec.getOutputBuffer(index)
          if (
            encodedData != null &&
              bufferInfo.size > 0 &&
              (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
          ) {
            if (muxerManager.isStarted() || muxerManager.awaitStarted(600L)) {
              muxerManager.writeVideoSample(encodedData, bufferInfo)
            }
          }
          codec.releaseOutputBuffer(index, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break
          }
        }
      }
    }
  }

  private fun releaseResources() {
    runCatching { mediaCodec?.stop() }
    runCatching { mediaCodec?.release() }
    mediaCodec = null
    runCatching { inputSurface?.release() }
    inputSurface = null
    drainThread = null
  }

  private fun inspectRgbColorCapabilities(
    info: MediaCodecInfo,
    mimeType: String,
  ): RgbColorCapabilityReport {
    val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull()
    val formats = caps?.colorFormats?.toSet().orEmpty()
    val supportsSurface = formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    val supportsDirectRgba =
      formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888) ||
        formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible)
    val supportsVbr =
      caps?.encoderCapabilities?.isBitrateModeSupported(
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
      ) == true
    val matchedProfileLevel = selectBestProfileLevel(caps, mimeType)
    return RgbColorCapabilityReport(
      supportsSurfaceInput = supportsSurface,
      supportsDirectRgbaBuffer = supportsDirectRgba,
      supportsVbr = supportsVbr,
      maxProfile = matchedProfileLevel?.profile,
      maxLevel = matchedProfileLevel?.level,
    )
  }

  private fun selectBestProfileLevel(
    caps: MediaCodecInfo.CodecCapabilities?,
    mimeType: String,
  ): MediaCodecInfo.CodecProfileLevel? {
    val levels = caps?.profileLevels ?: return null
    return if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
      levels
        .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
        .maxByOrNull { it.level }
        ?: levels
          .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain }
          .maxByOrNull { it.level }
    } else {
      levels
        .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
        .maxByOrNull { it.level }
    }
  }

  private fun configureBitrateModeAndProfile(
    format: MediaFormat,
    info: MediaCodecInfo,
    mimeType: String,
    forceProfile: Boolean,
    preferredProfile: Int? = null,
    preferredLevel: Int? = null,
  ) {
    val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() ?: return

    val encoderCaps = caps.encoderCapabilities
    if (encoderCaps != null) {
      when {
        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
          format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
          )
        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
          format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
          )
      }
    }

    if (forceProfile) {
      if (preferredProfile != null && preferredLevel != null) {
        format.setInteger(MediaFormat.KEY_PROFILE, preferredProfile)
        format.setInteger(MediaFormat.KEY_LEVEL, preferredLevel)
      }
    } else if (bitrateOption.targetBitsPerSecond >= 200_000_000) {
      val matchedProfile = selectBestProfileLevel(caps, mimeType)
      if (matchedProfile != null) {
        format.setInteger(MediaFormat.KEY_PROFILE, matchedProfile.profile)
        format.setInteger(MediaFormat.KEY_LEVEL, matchedProfile.level)
      }
    }
  }

  companion object {
    private const val TAG = "VideoEncoder"

    fun detectChipsetFamily(): ChipsetFamily {
      val socManufacturer = Build.SOC_MANUFACTURER.orEmpty()
      val fingerprint =
        listOf(socManufacturer, Build.HARDWARE, Build.BOARD, Build.BRAND)
          .joinToString(" ")
          .lowercase()

      return when {
        fingerprint.contains("qcom") ||
          fingerprint.contains("qualcomm") ||
          fingerprint.contains("snapdragon") ||
          fingerprint.contains("qti") -> ChipsetFamily.SNAPDRAGON
        fingerprint.contains("mtk") ||
          fingerprint.contains("mediatek") ||
          fingerprint.contains("mt6") ||
          fingerprint.contains("mt8") -> ChipsetFamily.MEDIATEK
        else -> ChipsetFamily.OTHER
      }
    }

    fun selectBestEncoder(
      requestedWidth: Int,
      requestedHeight: Int,
      bitrateOption: VideoBitrateOption = VideoBitrateOption.MBPS_150,
      availableCodecs: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
      chipsetFamily: ChipsetFamily = detectChipsetFamily(),
      fpsOption: VideoFpsOption = VideoFpsOption.FPS_30,
      codecOption: VideoCodecOption = VideoCodecOption.H265,
    ): EncoderSelection {
      val targetMime = codecOption.mimeType
      val matchingEncoders =
        availableCodecs.filter { info ->
          info.isEncoder &&
            info.supportedTypes.any { it.equals(targetMime, ignoreCase = true) } &&
            supportsSurfaceInput(info, targetMime)
        }

      val hardwareEncoders = matchingEncoders.filter { isHardwareCodec(it) }
      val pool = hardwareEncoders.ifEmpty { matchingEncoders }

      val preferredNames =
        if (codecOption == VideoCodecOption.H264) {
          when (chipsetFamily) {
            ChipsetFamily.SNAPDRAGON ->
              listOf(
                InternalRecordingConfig.SNAPDRAGON_AVC_ENCODER,
                InternalRecordingConfig.SNAPDRAGON_LEGACY_AVC_ENCODER,
              )
            ChipsetFamily.MEDIATEK ->
              listOf(
                InternalRecordingConfig.MEDIATEK_AVC_ENCODER,
                InternalRecordingConfig.MEDIATEK_LEGACY_AVC_ENCODER,
              )
            ChipsetFamily.OTHER ->
              listOf(
                InternalRecordingConfig.SNAPDRAGON_AVC_ENCODER,
                InternalRecordingConfig.MEDIATEK_AVC_ENCODER,
              )
          }
        } else {
          when (chipsetFamily) {
            ChipsetFamily.SNAPDRAGON ->
              listOf(
                InternalRecordingConfig.SNAPDRAGON_HEVC_ENCODER,
                InternalRecordingConfig.SNAPDRAGON_LEGACY_HEVC_ENCODER,
              )
            ChipsetFamily.MEDIATEK ->
              listOf(
                InternalRecordingConfig.MEDIATEK_HEVC_ENCODER,
                InternalRecordingConfig.MEDIATEK_LEGACY_HEVC_ENCODER,
              )
            ChipsetFamily.OTHER ->
              listOf(
                InternalRecordingConfig.SNAPDRAGON_HEVC_ENCODER,
                InternalRecordingConfig.MEDIATEK_HEVC_ENCODER,
              )
          }
        }

      val chosenInfo =
        preferredNames.firstNotNullOfOrNull { targetName ->
          pool.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        }
          ?: pool.firstOrNull { it.name.startsWith("c2.", ignoreCase = true) }
          ?: pool.firstOrNull()

      if (chosenInfo == null) {
        val evenW = alignTo(requestedWidth.coerceAtLeast(128), 2)
        val evenH = alignTo(requestedHeight.coerceAtLeast(128), 2)
        val fallbackName = if (codecOption == VideoCodecOption.H264) "default-avc" else "default-hevc"
        return EncoderSelection(
          codecName = fallbackName,
          isHardwareAccelerated = false,
          chipsetFamily = chipsetFamily,
          effectiveBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
          alignedWidth = evenW,
          alignedHeight = evenH,
          mimeType = targetMime,
        )
      }

      val caps = runCatching { chosenInfo.getCapabilitiesForType(targetMime) }.getOrNull()
      val videoCaps = caps?.videoCapabilities

      val wAlign = (videoCaps?.widthAlignment ?: 2).coerceAtLeast(2)
      val hAlign = (videoCaps?.heightAlignment ?: 2).coerceAtLeast(2)
      var alignedW = alignTo(requestedWidth.coerceAtLeast(128), wAlign)
      var alignedH = alignTo(requestedHeight.coerceAtLeast(128), hAlign)

      if (videoCaps != null) {
        val maxSupportedW = videoCaps.supportedWidths.upper
        val maxSupportedH = videoCaps.supportedHeights.upper
        if (alignedW > maxSupportedW || alignedH > maxSupportedH) {
          val clampScale = minOf(maxSupportedW.toFloat() / alignedW, maxSupportedH.toFloat() / alignedH)
          alignedW = (alignedW * clampScale).toInt()
          alignedH = (alignedH * clampScale).toInt()
        }
        alignedW =
          alignedW.coerceIn(
            videoCaps.supportedWidths.lower,
            maxSupportedW,
          )
        alignedH =
          alignedH.coerceIn(
            videoCaps.supportedHeights.lower,
            maxSupportedH,
          )
        alignedW = alignTo(alignedW, wAlign)
        alignedH = alignTo(alignedH, hAlign)

        // On 4K Android TVs or ultra-high-res tablets, step down proportionally if the hardware
        // encoder cannot sustain 30 FPS at the full native pixel area.
        val scales = floatArrayOf(0.85f, 0.75f, 0.667f, 0.5f, 0.375f)
        val baseW = alignedW
        val baseH = alignedH
        var idx = 0
        while (
          idx < scales.size &&
            !isSizeAndRateCompatible(
              videoCaps,
              alignedW,
              alignedH,
              fpsOption.fps.toDouble(),
            )
        ) {
          val factor = scales[idx++]
          val candidateW =
            alignTo((baseW * factor).toInt().coerceAtLeast(128), wAlign).coerceIn(
              videoCaps.supportedWidths.lower,
              videoCaps.supportedWidths.upper,
            )
          val candidateH =
            alignTo((baseH * factor).toInt().coerceAtLeast(128), hAlign).coerceIn(
              videoCaps.supportedHeights.lower,
              videoCaps.supportedHeights.upper,
            )
          alignedW = alignTo(candidateW, wAlign)
          alignedH = alignTo(candidateH, hAlign)
        }
      }

      val effectiveBitrate =
        if (videoCaps != null) {
          val range = videoCaps.bitrateRange
          bitrateOption.targetBitsPerSecond.coerceIn(range.lower, range.upper)
        } else {
          bitrateOption.targetBitsPerSecond
        }

      return EncoderSelection(
        codecName = chosenInfo.name,
        isHardwareAccelerated = isHardwareCodec(chosenInfo),
        chipsetFamily = chipsetFamily,
        effectiveBitrateBps = effectiveBitrate,
        alignedWidth = alignedW,
        alignedHeight = alignedH,
        mimeType = targetMime,
      )
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo, mimeType: String): Boolean {
      val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() ?: return false
      return caps.colorFormats.contains(InternalRecordingConfig.SURFACE_COLOR_FORMAT)
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
      return !info.isSoftwareOnly
    }

    private fun isSizeAndRateCompatible(
      videoCaps: MediaCodecInfo.VideoCapabilities,
      width: Int,
      height: Int,
      fps: Double,
    ): Boolean {
      if (!videoCaps.isSizeSupported(width, height)) return false
      return runCatching { videoCaps.areSizeAndRateSupported(width, height, fps) }
        .getOrDefault(true)
    }

    private fun alignTo(value: Int, alignment: Int): Int {
      val rem = value % alignment
      return if (rem == 0) value else (value - rem).coerceAtLeast(alignment)
    }
  }
}
