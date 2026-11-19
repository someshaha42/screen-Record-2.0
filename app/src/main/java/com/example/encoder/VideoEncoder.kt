package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.muxer.MediaMuxerManager
import com.example.settings.BitrateModeOption
import com.example.settings.ColorDepthOption
import com.example.settings.ColorRangeOption
import com.example.settings.ColorStandardOption
import com.example.settings.GopIntervalOption
import com.example.settings.InternalRecordingConfig
import com.example.settings.RenderingModeOption
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoFpsOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
)

/**
 * Hardware-first H.265/HEVC (video/hevc) MediaCodec encoder using native hardware
 * COLOR_FormatSurface input (YUV_420_888 for 8-bit, P010 for 10-bit), supporting Asynchronous
 * Callback or Direct Synchronous draining, VBR/CQ bitrate modes, 8-bit/10-bit HEVC profiles,
 * BT.709 SDR / BT.2020 HLG HDR color metadata, Limited/Full range, and 1s/0.5s GOP intervals.
 */
class VideoEncoder(
  private val requestedWidth: Int,
  private val requestedHeight: Int,
  private val bitrateOption: VideoBitrateOption,
  private val muxerManager: MediaMuxerManager,
  private val fpsOption: VideoFpsOption = VideoFpsOption.FPS_30,
  private val bitrateMode: BitrateModeOption = BitrateModeOption.VBR,
  private val colorDepth: ColorDepthOption = ColorDepthOption.BIT_8,
  private val colorStandard: ColorStandardOption = ColorStandardOption.BT_709,
  private val colorRange: ColorRangeOption = ColorRangeOption.LIMITED,
  private val gopInterval: GopIntervalOption = GopIntervalOption.SEC_1,
  private val renderingMode: RenderingModeOption = RenderingModeOption.ASYNC_SURFACE,
) {
  private var mediaCodec: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var drainThread: Thread? = null
  private var callbackThread: HandlerThread? = null
  private val asyncEosLatch = CountDownLatch(1)
  private val isRunning = AtomicBoolean(false)
  private var selection: EncoderSelection? = null

  private var pauseStartUs = 0L
  private var totalPausedUs = 0L
  private var isPaused = false

  fun prepare(): EncoderSelection {
    var selected =
      selectBestEncoder(
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        bitrateOption = bitrateOption,
        fpsOption = fpsOption,
      )

    val codec =
      runCatching { MediaCodec.createByCodecName(selected.codecName) }
        .getOrElse {
          Log.w(TAG, "Preferred HEVC codec ${selected.codecName} failed to instantiate, using default", it)
          MediaCodec.createEncoderByType(InternalRecordingConfig.VIDEO_MIME_TYPE)
        }

    if (renderingMode == RenderingModeOption.ASYNC_SURFACE) {
      val cbThread = HandlerThread("FrameCut-VideoAsync").apply { start() }
      callbackThread = cbThread
      val cbHandler = Handler(cbThread.looper)
      codec.setCallback(createAsyncCallback(), cbHandler)
    }

    selected = configureHevcWithFallback(codec, selected)
    selection = selected

    inputSurface = codec.createInputSurface()
    codec.start()
    mediaCodec = codec
    isRunning.set(true)

    if (renderingMode == RenderingModeOption.DIRECT_SURFACE) {
      drainThread =
        Thread(::drainLoop, "FrameCut-VideoDrain").apply {
          priority = Thread.MAX_PRIORITY
          start()
        }
    }

    Log.i(
      TAG,
      "VideoEncoder started: codec=${selected.codecName} (H.265/HEVC), " +
        "mode=${renderingMode.label}, depth=${colorDepth.label}, std=${colorStandard.label}, " +
        "range=${colorRange.label}, bitrateMode=${bitrateMode.label}, gop=${gopInterval.label}, " +
        "fps=${fpsOption.fps}, hw=${selected.isHardwareAccelerated}, chipset=${selected.chipsetFamily}, " +
        "size=${selected.alignedWidth}x${selected.alignedHeight}, bitrate=${selected.effectiveBitrateBps / 1_000_000}Mbps",
    )
    return selected
  }

  private fun configureHevcWithFallback(
    codec: MediaCodec,
    initialSelection: EncoderSelection,
  ): EncoderSelection {
    var selected = initialSelection

    // Primary format built strictly from user selections
    val primaryFormat =
      buildHevcFormat(
        codecInfo = codec.codecInfo,
        selected = selected,
        use10Bit = colorDepth == ColorDepthOption.BIT_10,
        useBt2020 = colorStandard == ColorStandardOption.BT_2020,
        requestedRange = colorRange.mediaFormatRange,
        requestedGop = gopInterval,
        requestedBitrateMode = bitrateMode,
      )
    try {
      codec.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return selected
    } catch (e: Exception) {
      Log.w(TAG, "Primary HEVC format rejected; trying compatible hardware fallback", e)
    }

    // Fallback 1: Keep depth & standard, clamp range to LIMITED and GOP to 1s integer
    val rangeFallbackFormat =
      buildHevcFormat(
        codecInfo = codec.codecInfo,
        selected = selected,
        use10Bit = colorDepth == ColorDepthOption.BIT_10,
        useBt2020 = colorStandard == ColorStandardOption.BT_2020,
        requestedRange = MediaFormat.COLOR_RANGE_LIMITED,
        requestedGop = GopIntervalOption.SEC_1,
        requestedBitrateMode = BitrateModeOption.VBR,
      )
    try {
      codec.configure(rangeFallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return selected
    } catch (e: Exception) {
      Log.w(TAG, "10-bit/BT.2020 or secondary HEVC config rejected; falling back to 8-bit BT.709 Main", e)
    }

    // Fallback 2: Safe 8-bit BT.709 SDR HEVCProfileMain baseline
    val safe8BitFormat =
      buildHevcFormat(
        codecInfo = codec.codecInfo,
        selected = selected,
        use10Bit = false,
        useBt2020 = false,
        requestedRange = MediaFormat.COLOR_RANGE_LIMITED,
        requestedGop = GopIntervalOption.SEC_1,
        requestedBitrateMode = BitrateModeOption.VBR,
      )
    try {
      codec.configure(safe8BitFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return selected
    } catch (e: Exception) {
      Log.w(TAG, "8-bit high-bitrate config rejected; clamping bitrate to 50 Mbps baseline", e)
      selected = selected.copy(effectiveBitrateBps = selected.effectiveBitrateBps.coerceAtMost(50_000_000))
      val minimalFormat =
        MediaFormat.createVideoFormat(
            InternalRecordingConfig.VIDEO_MIME_TYPE,
            selected.alignedWidth,
            selected.alignedHeight,
          )
          .apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
            setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fpsOption.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
          }
      codec.configure(minimalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      return selected
    }
  }

  private fun buildHevcFormat(
    codecInfo: MediaCodecInfo,
    selected: EncoderSelection,
    use10Bit: Boolean,
    useBt2020: Boolean,
    requestedRange: Int,
    requestedGop: GopIntervalOption,
    requestedBitrateMode: BitrateModeOption,
  ): MediaFormat {
    return MediaFormat.createVideoFormat(
        InternalRecordingConfig.VIDEO_MIME_TYPE,
        selected.alignedWidth,
        selected.alignedHeight,
      )
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.SURFACE_COLOR_FORMAT)
        setInteger(MediaFormat.KEY_BIT_RATE, selected.effectiveBitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, fpsOption.fps)
        setInteger(MediaFormat.KEY_CAPTURE_RATE, fpsOption.fps)
        if (requestedGop == GopIntervalOption.SEC_0_5) {
          setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, requestedGop.secondsFloat)
        } else {
          setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            InternalRecordingConfig.I_FRAME_INTERVAL_SECONDS,
          )
        }

        if (useBt2020) {
          setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
          setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        } else {
          setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
          setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        setInteger(MediaFormat.KEY_COLOR_RANGE, requestedRange)

        configureBitrateModeAndHevcProfile(
          format = this,
          info = codecInfo,
          use10Bit = use10Bit,
          requestedBitrateMode = requestedBitrateMode,
        )
      }
  }

  private fun createAsyncCallback(): MediaCodec.Callback {
    var formatAdded = false
    return object : MediaCodec.Callback() {
      override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

      override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        if (!formatAdded) {
          muxerManager.addVideoTrack(format)
          formatAdded = true
        }
      }

      override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
      ) {
        try {
          val encodedData = codec.getOutputBuffer(index)
          if (
            encodedData != null &&
              info.size > 0 &&
              (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
          ) {
            if (muxerManager.isStarted() || muxerManager.awaitStarted(600L)) {
              muxerManager.writeVideoSample(encodedData, info)
            }
          }
          codec.releaseOutputBuffer(index, false)
        } catch (e: Exception) {
          Log.w(TAG, "Async output buffer processing error", e)
        }
        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          asyncEosLatch.countDown()
        }
      }

      override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        Log.e(TAG, "Async MediaCodec error", e)
        asyncEosLatch.countDown()
      }
    }
  }

  fun setPaused(paused: Boolean) {
    val codec = mediaCodec ?: return
    if (paused == isPaused) return
    val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
    if (paused) {
      isPaused = true
      pauseStartUs = nowUs
      runCatching {
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1) })
      }
    } else {
      if (pauseStartUs > 0L) {
        totalPausedUs += (nowUs - pauseStartUs).coerceAtLeast(0L)
        pauseStartUs = 0L
      }
      isPaused = false
      runCatching {
        codec.setParameters(
          Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
            putLong(MediaCodec.PARAMETER_KEY_OFFSET_TIME, -totalPausedUs)
          },
        )
      }
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
    if (isPaused) {
      setPaused(false)
    }
    try {
      mediaCodec?.signalEndOfInputStream()
    } catch (e: Exception) {
      Log.w(TAG, "signalEndOfInputStream failed", e)
    }
    if (renderingMode == RenderingModeOption.ASYNC_SURFACE) {
      runCatching { asyncEosLatch.await(2500L, TimeUnit.MILLISECONDS) }
    } else {
      try {
        drainThread?.join(2500L)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
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
    callbackThread?.quitSafely()
    runCatching { callbackThread?.join(1000) }
    callbackThread = null
    drainThread = null
  }

  private fun configureBitrateModeAndHevcProfile(
    format: MediaFormat,
    info: MediaCodecInfo,
    use10Bit: Boolean,
    requestedBitrateMode: BitrateModeOption,
  ) {
    val caps =
      runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
        .getOrNull() ?: return

    val encoderCaps = caps.encoderCapabilities
    if (encoderCaps != null) {
      when (requestedBitrateMode) {
        BitrateModeOption.CQ -> {
          if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
            format.setInteger(
              MediaFormat.KEY_BITRATE_MODE,
              MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            )
            val qualityRange = encoderCaps.qualityRange
            if (qualityRange != null && qualityRange.upper > qualityRange.lower) {
              val cqValue =
                qualityRange.lower + ((qualityRange.upper - qualityRange.lower) * 80) / 100
              format.setInteger(MediaFormat.KEY_QUALITY, cqValue)
            }
          } else if (
            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
          ) {
            format.setInteger(
              MediaFormat.KEY_BITRATE_MODE,
              MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
          }
        }
        BitrateModeOption.VBR -> {
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
      }
    }

    val targetProfile =
      if (use10Bit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
      else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain

    val matchedProfile =
      caps.profileLevels
        ?.filter { it.profile == targetProfile }
        ?.maxByOrNull { it.level }
        ?: if (use10Bit) {
          caps.profileLevels
            ?.filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
            ?.maxByOrNull { it.level }
        } else {
          null
        }

    if (matchedProfile != null) {
      format.setInteger(MediaFormat.KEY_PROFILE, matchedProfile.profile)
      format.setInteger(MediaFormat.KEY_LEVEL, matchedProfile.level)
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
    ): EncoderSelection {
      val hevcEncoders =
        availableCodecs.filter { info ->
          info.isEncoder &&
            info.supportedTypes.any {
              it.equals(InternalRecordingConfig.VIDEO_MIME_TYPE, ignoreCase = true)
            } &&
            supportsSurfaceInput(info)
        }

      val hardwareEncoders = hevcEncoders.filter { isHardwareCodec(it) }
      val pool = hardwareEncoders.ifEmpty { hevcEncoders }

      val preferredNames =
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

      val chosenInfo =
        preferredNames.firstNotNullOfOrNull { targetName ->
          pool.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        }
          ?: pool.firstOrNull { it.name.startsWith("c2.", ignoreCase = true) }
          ?: pool.firstOrNull()

      if (chosenInfo == null) {
        val evenW = alignTo(requestedWidth.coerceAtLeast(128), 2)
        val evenH = alignTo(requestedHeight.coerceAtLeast(128), 2)
        return EncoderSelection(
          codecName = "default-hevc",
          isHardwareAccelerated = false,
          chipsetFamily = chipsetFamily,
          effectiveBitrateBps = bitrateOption.targetBitsPerSecond.coerceAtMost(50_000_000),
          alignedWidth = evenW,
          alignedHeight = evenH,
        )
      }

      val caps =
        runCatching { chosenInfo.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
          .getOrNull()
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
        alignedW = alignedW.coerceIn(videoCaps.supportedWidths.lower, maxSupportedW)
        alignedH = alignedH.coerceIn(videoCaps.supportedHeights.lower, maxSupportedH)
        alignedW = alignTo(alignedW, wAlign)
        alignedH = alignTo(alignedH, hAlign)

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
      )
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo): Boolean {
      val caps =
        runCatching { info.getCapabilitiesForType(InternalRecordingConfig.VIDEO_MIME_TYPE) }
          .getOrNull() ?: return false
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
