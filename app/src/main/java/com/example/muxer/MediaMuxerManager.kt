package com.example.muxer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.example.settings.ContainerMuxerOption
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MuxerOutput(val uri: Uri, val displayName: String, val filePath: String? = null)

/**
 * Thread-safe MediaMuxer coordinator supporting direct progressive MP4 output (Hybrid MP4) or
 * temporary staging capture with clean MediaExtractor -> MediaMuxer final MP4 remuxing, publishing
 * completed files via MediaStore Movies/FrameCut.
 */
class MediaMuxerManager(
  private val context: Context,
  private val expectAudioTrackInitial: Boolean,
  private val containerMuxer: ContainerMuxerOption = ContainerMuxerOption.HYBRID_MP4,
) {
  private val lock = Any()
  private var mediaMuxer: MediaMuxer? = null
  private var parcelFd: ParcelFileDescriptor? = null
  private var stagingFile: File? = null
  private var outputUri: Uri? = null
  private var displayName: String = ""

  private var videoTrackIndex = -1
  private var audioTrackIndex = -1
  private var expectAudio = expectAudioTrackInitial
  private var muxerStarted = false
  private var videoFramesWritten = 0L
  private var lastVideoPtsUs = -1L
  private var lastAudioPtsUs = -1L
  private var isReleased = false

  fun prepare(): MuxerOutput {
    synchronized(lock) {
      val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      displayName = "FrameCut_$timestamp.mp4"

      val resolver: ContentResolver = context.contentResolver
      val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(
            MediaStore.Video.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/FrameCut",
          )
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
      val uri =
        resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
          ?: throw IllegalStateException("Failed to create MediaStore MP4 entry")
      val pfd =
        resolver.openFileDescriptor(uri, "rw")
          ?: run {
            resolver.delete(uri, null, null)
            throw IllegalStateException("Failed to open MediaStore file descriptor")
          }
      outputUri = uri
      parcelFd = pfd

      if (containerMuxer == ContainerMuxerOption.MKV_REMUX_MP4) {
        val tempFile = File(context.cacheDir, "framecut_staging_$timestamp.mp4")
        stagingFile = tempFile
        mediaMuxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      } else {
        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      }
      return MuxerOutput(uri = uri, displayName = displayName)
    }
  }

  fun addVideoTrack(format: MediaFormat): Int {
    synchronized(lock) {
      val muxer = mediaMuxer ?: return -1
      if (videoTrackIndex == -1 && !muxerStarted) {
        videoTrackIndex = muxer.addTrack(format)
        maybeStartMuxerLocked()
      }
      return videoTrackIndex
    }
  }

  fun addAudioTrack(format: MediaFormat): Int {
    synchronized(lock) {
      val muxer = mediaMuxer ?: return -1
      if (!expectAudio) return -1
      if (audioTrackIndex == -1 && !muxerStarted) {
        audioTrackIndex = muxer.addTrack(format)
        maybeStartMuxerLocked()
      }
      return audioTrackIndex
    }
  }

  fun disableAudioTrack() {
    synchronized(lock) {
      expectAudio = false
      maybeStartMuxerLocked()
    }
  }

  fun isStarted(): Boolean = synchronized(lock) { muxerStarted }

  fun awaitStarted(timeoutMs: Long = 500L): Boolean {
    synchronized(lock) {
      if (muxerStarted) return true
      if (isReleased) return false
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!muxerStarted && !isReleased) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) break
        try {
          (lock as Object).wait(remaining)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        }
      }
      return muxerStarted
    }
  }

  private fun maybeStartMuxerLocked() {
    if (muxerStarted || isReleased) return
    val muxer = mediaMuxer ?: return
    val videoReady = videoTrackIndex != -1
    val audioReady = !expectAudio || audioTrackIndex != -1
    if (videoReady && audioReady) {
      muxer.start()
      muxerStarted = true
      (lock as Object).notifyAll()
      Log.d(
        TAG,
        "MediaMuxer started (mode=${containerMuxer.label}, videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)",
      )
    }
  }

  fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    synchronized(lock) {
      if (!muxerStarted || isReleased || videoTrackIndex == -1) return
      if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
      if (info.size <= 0) return

      val adjustedInfo = MediaCodec.BufferInfo().apply {
        val safePts =
          if (info.presentationTimeUs <= lastVideoPtsUs) lastVideoPtsUs + 1 else info.presentationTimeUs
        set(info.offset, info.size, safePts, info.flags)
        lastVideoPtsUs = safePts
      }
      buffer.position(adjustedInfo.offset)
      buffer.limit(adjustedInfo.offset + adjustedInfo.size)
      runCatching {
          mediaMuxer?.writeSampleData(videoTrackIndex, buffer, adjustedInfo)
          videoFramesWritten++
        }
        .onFailure { Log.e(TAG, "writeVideoSample failed", it) }
    }
  }

  fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    synchronized(lock) {
      if (!muxerStarted || isReleased || audioTrackIndex == -1) return
      if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
      if (info.size <= 0) return

      val adjustedInfo = MediaCodec.BufferInfo().apply {
        val safePts =
          if (info.presentationTimeUs <= lastAudioPtsUs) lastAudioPtsUs + 1 else info.presentationTimeUs
        set(info.offset, info.size, safePts, info.flags)
        lastAudioPtsUs = safePts
      }
      buffer.position(adjustedInfo.offset)
      buffer.limit(adjustedInfo.offset + adjustedInfo.size)
      runCatching { mediaMuxer?.writeSampleData(audioTrackIndex, buffer, adjustedInfo) }
        .onFailure { Log.e(TAG, "writeAudioSample failed", it) }
    }
  }

  fun finishAndRelease(): MuxerOutput? {
    synchronized(lock) {
      if (isReleased) return null
      isReleased = true
      var validOutput = false

      try {
        if (muxerStarted && videoFramesWritten > 0) {
          mediaMuxer?.stop()
          validOutput = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping MediaMuxer", e)
      } finally {
        runCatching { mediaMuxer?.release() }
        mediaMuxer = null
      }

      if (validOutput && containerMuxer == ContainerMuxerOption.MKV_REMUX_MP4) {
        val temp = stagingFile
        val pfd = parcelFd
        if (temp != null && temp.exists() && pfd != null) {
          validOutput = remuxStagingToMp4(temp, pfd)
        } else {
          validOutput = false
        }
      }

      runCatching { stagingFile?.delete() }
      stagingFile = null
      runCatching { parcelFd?.close() }
      parcelFd = null

      val uri = outputUri
      if (uri != null) {
        if (validOutput) {
          val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
          runCatching { context.contentResolver.update(uri, doneValues, null, null) }
        } else {
          runCatching { context.contentResolver.delete(uri, null, null) }
        }
      }

      return if (validOutput && uri != null) {
        MuxerOutput(uri = uri, displayName = displayName)
      } else {
        null
      }
    }
  }

  private fun remuxStagingToMp4(staging: File, destPfd: ParcelFileDescriptor): Boolean {
    val extractor = MediaExtractor()
    var destMuxer: MediaMuxer? = null
    return try {
      extractor.setDataSource(staging.absolutePath)
      destMuxer = MediaMuxer(destPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      val trackMap = HashMap<Int, Int>()
      var maxInputSize = 256 * 1024
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
          maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        }
        val destTrack = destMuxer.addTrack(format)
        trackMap[i] = destTrack
        extractor.selectTrack(i)
      }

      destMuxer.start()
      val buffer = ByteBuffer.allocateDirect(maxInputSize)
      val info = MediaCodec.BufferInfo()

      while (true) {
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break
        val trackIdx = extractor.sampleTrackIndex
        val destTrack = trackMap[trackIdx]
        if (destTrack != null) {
          info.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
          destMuxer.writeSampleData(destTrack, buffer, info)
        }
        extractor.advance()
      }

      destMuxer.stop()
      true
    } catch (e: Exception) {
      Log.e(TAG, "Remux pass failed", e)
      false
    } finally {
      runCatching { destMuxer?.release() }
      runCatching { extractor.release() }
    }
  }

  companion object {
    private const val TAG = "MediaMuxerManager"
  }
}
