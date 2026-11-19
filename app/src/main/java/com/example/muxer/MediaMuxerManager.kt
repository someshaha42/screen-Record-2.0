package com.example.muxer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.example.diagnostics.RecordingDiagnostics
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

data class MuxerOutput(val uri: Uri, val displayName: String, val filePath: String? = null)

/**
 * Bounded, reusable direct-ByteBuffer async muxer queue that decouples MediaCodec output buffer
 * release (microseconds) from MediaMuxer / MediaStore storage I/O (which can stall 5-40ms on flash
 * flushes). Zero per-frame Kotlin/Java heap allocations after pool warmup.
 */
class MuxerPacketQueue(
  private val diagnostics: RecordingDiagnostics? = null,
  private val maxVideoPackets: Int = DEFAULT_MAX_VIDEO_PACKETS,
  private val maxAudioPackets: Int = DEFAULT_MAX_AUDIO_PACKETS,
  private val maxVideoPoolBytes: Long = DEFAULT_MAX_VIDEO_POOL_BYTES,
  private val sink: SampleWriterSink,
) {
  fun interface SampleWriterSink {
    fun onWriteSample(
      isVideo: Boolean,
      buffer: ByteBuffer,
      size: Int,
      ptsUs: Long,
      flags: Int,
    )
  }

  private class QueuedPacket {
    var isVideo: Boolean = false
    var buffer: ByteBuffer? = null
    var size: Int = 0
    var ptsUs: Long = 0L
    var flags: Int = 0
  }

  private val queueLock = Object()
  private val pendingQueue = ArrayDeque<QueuedPacket>(maxVideoPackets + 16)
  private val freePackets = ArrayDeque<QueuedPacket>(maxVideoPackets + 16)
  private val freeVideoBuffers = ArrayDeque<ByteBuffer>(maxVideoPackets)
  private val freeAudioBuffers = ArrayDeque<ByteBuffer>(maxAudioPackets)

  private var queuedVideoBufferBytes = 0L
  private var freeVideoBufferBytes = 0L
  private var inFlightVideoCount = 0
  private var inFlightAudioCount = 0

  private var sinkReady = false
  private var closing = false
  private var started = false
  private var writerThread: Thread? = null

  fun start() {
    synchronized(queueLock) {
      if (started || closing) return
      started = true
      writerThread =
        Thread(::drainLoop, "FrameCut-MuxerWriter").apply {
          start()
        }
    }
  }

  fun markSinkReady() {
    synchronized(queueLock) {
      sinkReady = true
      queueLock.notifyAll()
    }
  }

  fun submit(
    isVideo: Boolean,
    source: ByteBuffer,
    offset: Int,
    size: Int,
    ptsUs: Long,
    flags: Int,
  ): Boolean {
    if (size <= 0) return false
    var reserved = false
    var targetBuffer: ByteBuffer? = null
    var targetBufferCapacity = 0

    try {
      synchronized(queueLock) {
        while (
          !closing &&
            ((isVideo && inFlightVideoCount >= maxVideoPackets) ||
              (!isVideo && inFlightAudioCount >= maxAudioPackets))
        ) {
          diagnostics?.onMuxerBackpressureWait()
          try {
            queueLock.wait()
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
          }
        }
        if (closing || !started) return false

        if (isVideo) {
          inFlightVideoCount++
        } else {
          inFlightAudioCount++
        }
        reserved = true

        targetBuffer = popReusableBufferLocked(isVideo, size)
        targetBufferCapacity = targetBuffer?.capacity() ?: 0
      }

      // Direct buffer allocation (if needed) and memcpy happen OUTSIDE queueLock so audio/video
      // producers never contend with each other or the writer thread during byte copying.
      val dst =
        if (targetBuffer != null && targetBufferCapacity >= size) {
          targetBuffer!!
        } else {
          val allocSize =
            if (isVideo) {
              roundUpToBlock(size, VIDEO_BUFFER_ALIGN_BYTES)
            } else {
              size.coerceAtLeast(DEFAULT_AUDIO_BUFFER_BYTES)
            }
          diagnostics?.onMuxerPoolAllocation()
          ByteBuffer.allocateDirect(allocSize)
        }

      dst.clear()
      val dup = source.duplicate()
      dup.position(offset)
      dup.limit(offset + size)
      dst.put(dup)
      dst.flip()

      synchronized(queueLock) {
        if (closing) {
          releaseSlotAndBufferLocked(isVideo, dst, countVideoBytes = false)
          reserved = false
          return false
        }
        if (isVideo) {
          queuedVideoBufferBytes += dst.capacity().toLong()
        }
        val packet =
          if (freePackets.isEmpty()) QueuedPacket() else freePackets.removeFirst()
        packet.isVideo = isVideo
        packet.buffer = dst
        packet.size = size
        packet.ptsUs = ptsUs
        packet.flags = flags
        pendingQueue.addLast(packet)
        reserved = false
        diagnostics?.onMuxerQueued(pendingQueue.size)
        queueLock.notifyAll()
      }
      return true
    } finally {
      if (reserved) {
        synchronized(queueLock) {
          releaseSlotAndBufferLocked(isVideo, targetBuffer, countVideoBytes = false)
        }
      }
    }
  }

  fun closeAndDrain(timeoutMs: Long = 5000L) {
    val threadToJoin: Thread?
    synchronized(queueLock) {
      closing = true
      threadToJoin = writerThread
      queueLock.notifyAll()
    }
    if (threadToJoin != null && Thread.currentThread() !== threadToJoin) {
      try {
        threadToJoin.join(timeoutMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    synchronized(queueLock) {
      writerThread = null
      pendingQueue.clear()
      freePackets.clear()
      freeVideoBuffers.clear()
      freeAudioBuffers.clear()
      freeVideoBufferBytes = 0L
      queuedVideoBufferBytes = 0L
      inFlightVideoCount = 0
      inFlightAudioCount = 0
    }
  }

  private fun drainLoop() {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
    while (true) {
      var packet: QueuedPacket? = null
      synchronized(queueLock) {
        while (!closing && !sinkReady) {
          try {
            queueLock.wait()
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
          }
        }
        if (closing && !sinkReady) {
          return
        }
        while (pendingQueue.isEmpty() && !closing) {
          try {
            queueLock.wait()
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
          }
        }
        if (pendingQueue.isEmpty() && closing) {
          return
        }
        packet = pendingQueue.removeFirst()
        diagnostics?.onMuxerDequeued(pendingQueue.size)
      }

      val active = packet ?: continue
      val buf = active.buffer
      if (buf != null) {
        sink.onWriteSample(active.isVideo, buf, active.size, active.ptsUs, active.flags)
      }

      synchronized(queueLock) {
        releaseSlotAndBufferLocked(active.isVideo, buf, countVideoBytes = true)
        active.buffer = null
        freePackets.addLast(active)
        queueLock.notifyAll()
      }
    }
  }

  private fun popReusableBufferLocked(isVideo: Boolean, minCapacity: Int): ByteBuffer? {
    if (!isVideo) {
      val buf = freeAudioBuffers.pollFirst()
      if (buf != null && buf.capacity() >= minCapacity) return buf
      // If free audio buffer was too small, discard it and try the next, or let caller allocate
      return null
    }

    if (freeVideoBuffers.isEmpty()) return null

    // Find first buffer large enough; otherwise drop oldest smaller buffer if byte budget is tight
    val iterator = freeVideoBuffers.iterator()
    while (iterator.hasNext()) {
      val candidate = iterator.next()
      if (candidate.capacity() >= minCapacity) {
        iterator.remove()
        freeVideoBufferBytes -= candidate.capacity().toLong()
        return candidate
      }
    }

    // No buffer large enough; trim pool entries if adding a larger buffer would exceed byte cap
    while (
      freeVideoBuffers.isNotEmpty() &&
        queuedVideoBufferBytes + freeVideoBufferBytes + minCapacity > maxVideoPoolBytes
    ) {
      val evicted = freeVideoBuffers.removeFirst()
      freeVideoBufferBytes -= evicted.capacity().toLong()
    }
    return null
  }

  private fun releaseSlotAndBufferLocked(
    isVideo: Boolean,
    buffer: ByteBuffer?,
    countVideoBytes: Boolean,
  ) {
    if (isVideo) {
      inFlightVideoCount = (inFlightVideoCount - 1).coerceAtLeast(0)
      if (buffer != null) {
        val cap = buffer.capacity().toLong()
        if (countVideoBytes) {
          queuedVideoBufferBytes = (queuedVideoBufferBytes - cap).coerceAtLeast(0L)
        }
        if (
          !closing &&
            freeVideoBuffers.size < maxVideoPackets &&
            queuedVideoBufferBytes + freeVideoBufferBytes + cap <= maxVideoPoolBytes
        ) {
          freeVideoBuffers.addLast(buffer)
          freeVideoBufferBytes += cap
        }
      }
    } else {
      inFlightAudioCount = (inFlightAudioCount - 1).coerceAtLeast(0)
      if (!closing && buffer != null && freeAudioBuffers.size < maxAudioPackets) {
        freeAudioBuffers.addLast(buffer)
      }
    }
  }

  private fun roundUpToBlock(size: Int, blockBytes: Int): Int {
    val rem = size % blockBytes
    return if (rem == 0) size else size + (blockBytes - rem)
  }

  companion object {
    const val DEFAULT_MAX_VIDEO_PACKETS = 24
    const val DEFAULT_MAX_AUDIO_PACKETS = 96
    const val DEFAULT_MAX_VIDEO_POOL_BYTES = 40L * 1024L * 1024L
    private const val VIDEO_BUFFER_ALIGN_BYTES = 65_536
    private const val DEFAULT_AUDIO_BUFFER_BYTES = 8_192
  }
}

/**
 * Thread-safe native android.media.MediaMuxer coordinator writing Standard MP4 directly to
 * MediaStore (Movies/FrameCut). Uses a bounded zero-GC async MuxerPacketQueue so MediaCodec
 * output buffers are released in microseconds without blocking on flash/FUSE storage writes.
 */
class MediaMuxerManager(
  private val context: Context,
  private val expectAudioTrackInitial: Boolean,
  private val diagnostics: RecordingDiagnostics? = null,
) {
  private val lock = Object()
  private var mediaMuxer: MediaMuxer? = null
  private var parcelFd: ParcelFileDescriptor? = null
  private var outputUri: Uri? = null
  private var displayName: String = ""

  private var videoTrackIndex = -1
  private var audioTrackIndex = -1
  private var expectAudio = expectAudioTrackInitial
  @Volatile private var muxerStarted = false
  @Volatile private var isReleased = false

  // Accessed exclusively by the single FrameCut-MuxerWriter thread after start
  private var videoFramesWritten = 0L
  private var videoStartPresentationTimeUs = -1L
  private var audioStartPresentationTimeUs = -1L
  private var lastVideoPtsUs = -1L
  private var lastAudioPtsUs = -1L
  private val reusableVideoInfo = MediaCodec.BufferInfo()
  private val reusableAudioInfo = MediaCodec.BufferInfo()

  private val packetQueue =
    MuxerPacketQueue(diagnostics = diagnostics) { isVideo, buffer, size, ptsUs, flags ->
      writeQueuedPacketOnWriterThread(isVideo, buffer, size, ptsUs, flags)
    }

  fun prepare(): MuxerOutput {
    synchronized(lock) {
      val timestamp = FILENAME_FORMATTER.format(LocalDateTime.now())
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
      mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      packetQueue.start()
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

  fun isStarted(): Boolean = muxerStarted

  fun awaitStarted(timeoutMs: Long = 2500L): Boolean {
    if (muxerStarted) return true
    synchronized(lock) {
      if (muxerStarted) return true
      if (isReleased) return false
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!muxerStarted && !isReleased) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) break
        try {
          lock.wait(remaining)
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
      packetQueue.markSinkReady()
      lock.notifyAll()
      Log.d(
        TAG,
        "Standard MP4 MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)",
      )
    }
  }

  fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isReleased) return
    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
    if (info.size <= 0) return
    packetQueue.submit(
      isVideo = true,
      source = buffer,
      offset = info.offset,
      size = info.size,
      ptsUs = info.presentationTimeUs,
      flags = info.flags,
    )
  }

  fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isReleased || !expectAudio) return
    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
    if (info.size <= 0) return
    packetQueue.submit(
      isVideo = false,
      source = buffer,
      offset = info.offset,
      size = info.size,
      ptsUs = info.presentationTimeUs,
      flags = info.flags,
    )
  }

  private fun writeQueuedPacketOnWriterThread(
    isVideo: Boolean,
    buffer: ByteBuffer,
    size: Int,
    rawPtsUs: Long,
    flags: Int,
  ) {
    val muxer = mediaMuxer ?: return
    if (!muxerStarted || isReleased) return

    if (isVideo) {
      if (videoTrackIndex == -1) return
      if (videoStartPresentationTimeUs < 0L) {
        videoStartPresentationTimeUs = rawPtsUs
      }
      val normalizedPtsUs =
        normalizePresentationTimeUs(
          rawPtsUs = rawPtsUs,
          anchorPtsUs = videoStartPresentationTimeUs,
          lastWrittenPtsUs = lastVideoPtsUs,
        )
      lastVideoPtsUs = normalizedPtsUs

      reusableVideoInfo.set(0, size, normalizedPtsUs, flags)
      buffer.position(0)
      buffer.limit(size)
      val writeStartNs = SystemClock.elapsedRealtimeNanos()
      runCatching {
          muxer.writeSampleData(videoTrackIndex, buffer, reusableVideoInfo)
          videoFramesWritten++
          diagnostics?.onMuxerSampleWritten(size, SystemClock.elapsedRealtimeNanos() - writeStartNs)
        }
        .onFailure {
          diagnostics?.onMuxerError()
          Log.e(TAG, "writeVideoSample failed", it)
        }
    } else {
      if (audioTrackIndex == -1) return
      if (audioStartPresentationTimeUs < 0L) {
        audioStartPresentationTimeUs = rawPtsUs
      }
      val normalizedPtsUs =
        normalizePresentationTimeUs(
          rawPtsUs = rawPtsUs,
          anchorPtsUs = audioStartPresentationTimeUs,
          lastWrittenPtsUs = lastAudioPtsUs,
        )
      lastAudioPtsUs = normalizedPtsUs

      reusableAudioInfo.set(0, size, normalizedPtsUs, flags)
      buffer.position(0)
      buffer.limit(size)
      val writeStartNs = SystemClock.elapsedRealtimeNanos()
      runCatching {
          muxer.writeSampleData(audioTrackIndex, buffer, reusableAudioInfo)
          diagnostics?.onMuxerSampleWritten(size, SystemClock.elapsedRealtimeNanos() - writeStartNs)
        }
        .onFailure {
          diagnostics?.onMuxerError()
          Log.e(TAG, "writeAudioSample failed", it)
        }
    }
  }

  fun finishAndRelease(): MuxerOutput? {
    synchronized(lock) {
      if (isReleased) return null
      isReleased = true
      lock.notifyAll()
    }

    // Drain every already-queued frame cleanly to MediaMuxer before calling stop()
    packetQueue.closeAndDrain()

    synchronized(lock) {
      var validOutput = false
      try {
        if (muxerStarted && videoFramesWritten > 0) {
          mediaMuxer?.stop()
          validOutput = true
        }
      } catch (e: Exception) {
        diagnostics?.onMuxerError()
        Log.e(TAG, "Error stopping MediaMuxer", e)
      } finally {
        runCatching { mediaMuxer?.release() }
        mediaMuxer = null
      }

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

  companion object {
    private const val TAG = "MediaMuxerManager"
    private val FILENAME_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    fun normalizePresentationTimeUs(
      rawPtsUs: Long,
      anchorPtsUs: Long,
      lastWrittenPtsUs: Long,
    ): Long {
      val relativeUs = (rawPtsUs - anchorPtsUs).coerceAtLeast(0L)
      return if (relativeUs <= lastWrittenPtsUs) lastWrittenPtsUs + 1L else relativeUs
    }
  }
}
