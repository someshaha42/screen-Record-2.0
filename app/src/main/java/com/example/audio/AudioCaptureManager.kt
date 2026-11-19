package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.muxer.MediaMuxerManager
import com.example.settings.AudioSourceOption
import com.example.settings.InternalRecordingConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class AudioCaptureStatus(
  val isCapturing: Boolean,
  val activeDeviceLabel: String,
  val warningMessage: String? = null,
)

/**
 * Captures Internal Audio (via AudioPlaybackCapture on Android 10+) or Microphone audio with
 * automatic external mic prioritization and hot-plug/unplug handling via AudioDeviceCallback,
 * encoding PCM16 into AAC-LC for MediaMuxerManager.
 */
class AudioCaptureManager(
  private val context: Context,
  private val audioSource: AudioSourceOption,
  private val mediaProjection: MediaProjection?,
  private val muxerManager: MediaMuxerManager,
  private val onStatusChanged: (AudioCaptureStatus) -> Unit = {},
) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val isRunning = AtomicBoolean(false)
  private val isPaused = AtomicBoolean(false)

  private var audioRecord: AudioRecord? = null
  private var audioEncoder: MediaCodec? = null
  private var captureThread: Thread? = null
  private var drainThread: Thread? = null
  private var deviceCallback: AudioDeviceCallback? = null

  private var sampleRate = 48_000
  private var channelCount = 2
  private var pauseStartNs = 0L
  private var pausedAccumulatedNs = 0L
  private var startTimeNs = 0L
  private val pauseLock = Any()

  fun start(): AudioCaptureStatus {
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      muxerManager.disableAudioTrack()
      return AudioCaptureStatus(
        isCapturing = false,
        activeDeviceLabel = "Muted (No audio permission)",
        warningMessage = "Audio permission not granted; recording video only.",
      ).also(onStatusChanged)
    }

    val recordSetup = createAudioRecord()
    if (recordSetup == null) {
      muxerManager.disableAudioTrack()
      return AudioCaptureStatus(
        isCapturing = false,
        activeDeviceLabel = "Audio unavailable",
        warningMessage = "Could not initialize audio capture; recording video only.",
      ).also(onStatusChanged)
    }

    val (record, bufferSize, initialLabel) = recordSetup
    audioRecord = record

    val encoder =
      try {
        createAacEncoder(sampleRate, channelCount)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to configure AAC audio encoder", e)
        runCatching { record.release() }
        audioRecord = null
        muxerManager.disableAudioTrack()
        return AudioCaptureStatus(
          isCapturing = false,
          activeDeviceLabel = "Audio encoder unavailable",
          warningMessage = "AAC encoder unavailable; recording video only.",
        ).also(onStatusChanged)
      }

    audioEncoder = encoder

    if (audioSource == AudioSourceOption.MICROPHONE) {
      registerMicDeviceCallback(record)
    }

    return try {
      record.startRecording()
      if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        throw IllegalStateException("AudioRecord failed to enter recording state")
      }
      encoder.start()
      startTimeNs = SystemClock.elapsedRealtimeNanos()
      isRunning.set(true)

      drainThread =
        Thread(::drainEncoderLoop, "FrameCut-AudioDrain").apply { start() }
      captureThread =
        Thread({ capturePcmLoop(bufferSize) }, "FrameCut-AudioCapture").apply {
          priority = Thread.MAX_PRIORITY
          start()
        }

      AudioCaptureStatus(isCapturing = true, activeDeviceLabel = initialLabel).also(onStatusChanged)
    } catch (e: Exception) {
      Log.e(TAG, "Audio capture start failed", e)
      stopAndRelease()
      muxerManager.disableAudioTrack()
      AudioCaptureStatus(
        isCapturing = false,
        activeDeviceLabel = "Audio failed",
        warningMessage = "Audio capture restricted or unavailable; recording video only.",
      ).also(onStatusChanged)
    }
  }

  fun setPaused(paused: Boolean) {
    synchronized(pauseLock) {
      if (paused == isPaused.get()) return
      val now = SystemClock.elapsedRealtimeNanos()
      if (paused) {
        pauseStartNs = now
        isPaused.set(true)
      } else {
        if (pauseStartNs > 0L) {
          pausedAccumulatedNs += (now - pauseStartNs).coerceAtLeast(0L)
        }
        pauseStartNs = 0L
        isPaused.set(false)
      }
    }
  }

  fun stopAndRelease() {
    val wasRunning = isRunning.getAndSet(false)
    unregisterMicDeviceCallback()

    if (wasRunning) {
      runCatching { audioRecord?.stop() }
      try {
        captureThread?.join(1500L)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      try {
        drainThread?.join(1500L)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }

    runCatching { audioRecord?.release() }
    audioRecord = null

    runCatching { audioEncoder?.stop() }
    runCatching { audioEncoder?.release() }
    audioEncoder = null

    captureThread = null
    drainThread = null
  }

  private fun createAudioRecord(): Triple<AudioRecord, Int, String>? {
    val candidateRates = intArrayOf(48_000, 44_100)
    val channelMask =
      if (audioSource == AudioSourceOption.INTERNAL) {
        channelCount = 2
        AudioFormat.CHANNEL_IN_STEREO
      } else {
        channelCount = 1
        AudioFormat.CHANNEL_IN_MONO
      }

    for (rate in candidateRates) {
      val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf <= 0) continue
      val bufferSize = (minBuf * 2).coerceAtLeast(4096)

      try {
        val audioFormat =
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(channelMask)
            .build()

        val record =
          if (audioSource == AudioSourceOption.INTERNAL) {
            val projection = mediaProjection ?: return null
            val captureConfig =
              AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
              .setAudioFormat(audioFormat)
              .setBufferSizeInBytes(bufferSize)
              .setAudioPlaybackCaptureConfig(captureConfig)
              .build()
          } else {
            AudioRecord.Builder()
              .setAudioSource(MediaRecorder.AudioSource.MIC)
              .setAudioFormat(audioFormat)
              .setBufferSizeInBytes(bufferSize)
              .build()
          }

        if (record.state == AudioRecord.STATE_INITIALIZED) {
          sampleRate = rate
          val label =
            if (audioSource == AudioSourceOption.MICROPHONE) {
              applyBestMicrophoneDevice(record)
            } else {
              "Internal Audio"
            }
          return Triple(record, bufferSize, label)
        }
        record.release()
      } catch (e: SecurityException) {
        Log.e(TAG, "Missing RECORD_AUDIO permission", e)
        return null
      } catch (e: Exception) {
        Log.w(TAG, "AudioRecord init failed at $rate Hz", e)
      }
    }
    return null
  }

  private fun applyBestMicrophoneDevice(record: AudioRecord): String {
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val prioritized = selectPrioritizedMicrophone(inputs.toList())
    if (prioritized != null) {
      val applied = record.setPreferredDevice(prioritized)
      Log.d(
        TAG,
        "Selected input mic: type=${prioritized.type}, name=${prioritized.productName}, applied=$applied",
      )
      return describeDevice(prioritized)
    }
    record.setPreferredDevice(null)
    return "Built-in Mic"
  }

  private fun registerMicDeviceCallback(record: AudioRecord) {
    val callback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
          if (!isRunning.get() || record.state != AudioRecord.STATE_INITIALIZED) return
          val label = applyBestMicrophoneDevice(record)
          onStatusChanged(AudioCaptureStatus(isCapturing = true, activeDeviceLabel = label))
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
          if (!isRunning.get() || record.state != AudioRecord.STATE_INITIALIZED) return
          val label = applyBestMicrophoneDevice(record)
          onStatusChanged(AudioCaptureStatus(isCapturing = true, activeDeviceLabel = label))
        }
      }
    deviceCallback = callback
    audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
  }

  private fun unregisterMicDeviceCallback() {
    deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
    deviceCallback = null
  }

  private fun createAacEncoder(rate: Int, channels: Int): MediaCodec {
    val format =
      MediaFormat.createAudioFormat(InternalRecordingConfig.AUDIO_MIME_TYPE, rate, channels).apply {
        setInteger(
          MediaFormat.KEY_AAC_PROFILE,
          MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, InternalRecordingConfig.AUDIO_BITRATE_BPS)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
      }
    return MediaCodec.createEncoderByType(InternalRecordingConfig.AUDIO_MIME_TYPE).apply {
      configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }
  }

  private fun capturePcmLoop(bufferSize: Int) {
    val record = audioRecord ?: return
    val encoder = audioEncoder ?: return
    val pcmBuffer = ByteArray(bufferSize.coerceAtMost(8192))

    while (isRunning.get()) {
      val bytesRead =
        try {
          record.read(pcmBuffer, 0, pcmBuffer.size)
        } catch (e: Exception) {
          Log.e(TAG, "AudioRecord.read error", e)
          break
        }

      if (bytesRead < 0) {
        Log.w(TAG, "AudioRecord.read returned error=$bytesRead; stopping audio capture safely")
        onStatusChanged(
          AudioCaptureStatus(
            isCapturing = false,
            activeDeviceLabel = "Audio interrupted",
            warningMessage = "Audio source stopped or was restricted; continuing video recording.",
          ),
        )
        break
      }
      if (bytesRead == 0) {
        continue
      }

      if (isPaused.get()) {
        continue
      }

      val inputIndex =
        try {
          encoder.dequeueInputBuffer(10_000L)
        } catch (_: Exception) {
          break
        }

      if (inputIndex >= 0) {
        val inputBuffer: ByteBuffer = encoder.getInputBuffer(inputIndex) ?: continue
        inputBuffer.clear()
        val toCopy = bytesRead.coerceAtMost(inputBuffer.remaining())
        inputBuffer.put(pcmBuffer, 0, toCopy)

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val ptsUs =
          synchronized(pauseLock) {
            ((nowNs - startTimeNs - pausedAccumulatedNs).coerceAtLeast(0L)) / 1000L
          }
        runCatching { encoder.queueInputBuffer(inputIndex, 0, toCopy, ptsUs, 0) }
      }
    }

    // Send EOS to audio encoder cleanly
    val eosIndex = runCatching { encoder.dequeueInputBuffer(50_000L) }.getOrDefault(-1)
    if (eosIndex >= 0) {
      val nowNs = SystemClock.elapsedRealtimeNanos()
      val ptsUs =
        synchronized(pauseLock) {
          ((nowNs - startTimeNs - pausedAccumulatedNs).coerceAtLeast(0L)) / 1000L
        }
      runCatching {
        encoder.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }
    }
  }

  private fun drainEncoderLoop() {
    val encoder = audioEncoder ?: return
    val bufferInfo = MediaCodec.BufferInfo()
    var trackAdded = false

    while (true) {
      val index =
        try {
          encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
        } catch (e: Exception) {
          Log.e(TAG, "Audio encoder dequeueOutputBuffer error", e)
          if (!trackAdded) muxerManager.disableAudioTrack()
          break
        }

      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!isRunning.get()) break
        }
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if (!trackAdded) {
            muxerManager.addAudioTrack(encoder.outputFormat)
            trackAdded = true
          }
        }
        index >= 0 -> {
          val encoded = encoder.getOutputBuffer(index)
          if (
            encoded != null &&
              bufferInfo.size > 0 &&
              (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
          ) {
            if (muxerManager.isStarted() || muxerManager.awaitStarted(600L)) {
              muxerManager.writeAudioSample(encoded, bufferInfo)
            }
          }
          encoder.releaseOutputBuffer(index, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break
          }
        }
      }
    }
    if (!trackAdded) {
      muxerManager.disableAudioTrack()
    }
  }

  companion object {
    private const val TAG = "AudioCaptureManager"

    fun selectPrioritizedMicrophone(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
      val sources = devices.filter { it.isSource }
      val priorityOrder =
        listOf(
          AudioDeviceInfo.TYPE_USB_HEADSET,
          AudioDeviceInfo.TYPE_USB_DEVICE,
          AudioDeviceInfo.TYPE_WIRED_HEADSET,
          AudioDeviceInfo.TYPE_BLE_HEADSET,
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
          AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )
      for (type in priorityOrder) {
        sources.firstOrNull { it.type == type }?.let { return it }
      }
      return sources.firstOrNull()
    }

    private fun describeDevice(device: AudioDeviceInfo): String {
      return when (device.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Mic"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "External Mic"
      }
    }
  }
}
