package com.example.diagnostics

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DiagnosticsSnapshot(
  val targetFps: Int,
  val codecName: String,
  val isHardwareAccelerated: Boolean,
  val performancePointSupported: Boolean?,
  val width: Int,
  val height: Int,
  val bitrateMbps: Int,
  val activeElapsedMs: Long,
  val videoOutputFrames: Long,
  val videoKeyFrames: Long,
  val audioPackets: Long,
  val delayedFrameIntervals: Long,
  val estimatedDroppedFrames: Long,
  val averageOutputFps: Double,
  val avgFrameIntervalMs: Double,
  val peakFrameIntervalMs: Double,
  val avgEncoderCallbackUs: Long,
  val maxEncoderCallbackUs: Long,
  val avgMuxerWriteUs: Long,
  val maxMuxerWriteUs: Long,
  val muxerQueueDepthCurrent: Int,
  val muxerQueueDepthPeak: Int,
  val muxerPoolAllocations: Long,
  val muxerBackpressureWaits: Long,
  val totalBytesWritten: Long,
  val avSyncDriftMs: Long,
  val codecErrors: Long,
  val muxerErrors: Long,
  val audioErrors: Long,
  val gcCountDelta: Long,
  val blockingGcCountDelta: Long,
  val cpuTimeDeltaMs: Long,
  val javaHeapUsedMb: Long,
  val nativeHeapAllocatedMb: Long,
  val thermalStatus: Int,
  val thermalHeadroom: Float,
)

/**
 * Low-overhead internal recording diagnostics using atomic counters and primitive timings (zero
 * per-frame heap allocations, no per-frame logging, and no sensitive user content). Periodic
 * summaries run at low frequency (~10s) and on session completion.
 */
class RecordingDiagnostics(
  private val targetFps: Int,
  private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
  private val cpuTimeMs: () -> Long = { Process.getElapsedCpuTime() },
) {
  @Volatile var codecName: String = ""
  @Volatile var isHardwareAccelerated: Boolean = false
  @Volatile var performancePointSupported: Boolean? = null
  @Volatile var width: Int = 0
  @Volatile var height: Int = 0
  @Volatile var bitrateMbps: Int = 0

  private val expectedFrameIntervalUs: Long =
    (1_000_000L / targetFps.coerceAtLeast(1)).coerceAtLeast(1_000L)
  private val delayedThresholdUs: Long = (expectedFrameIntervalUs * 3L) / 2L

  private val sessionStartMs = clockMs()
  private val initialCpuMs = cpuTimeMs()
  private val initialGcCount = readRuntimeStat("art.gc.gc-count")
  private val initialBlockingGcCount = readRuntimeStat("art.gc.blocking-gc-count")

  private var pauseStartedMs = 0L
  private var pausedAccumulatedMs = 0L
  @Volatile private var isPaused = false

  // Video output tracking (single callback writer thread -> fast plain writes after CAS/init)
  private val videoOutputFrames = AtomicLong(0L)
  private val videoKeyFrames = AtomicLong(0L)
  private val delayedFrameIntervals = AtomicLong(0L)
  private val estimatedDroppedFrames = AtomicLong(0L)
  private val videoIntervalSumUs = AtomicLong(0L)
  private val videoIntervalCount = AtomicLong(0L)
  private val peakFrameIntervalUs = AtomicLong(0L)
  private val lastVideoPtsUs = AtomicLong(-1L)
  private val resetIntervalBaseline = AtomicInteger(1)

  private val encoderCallbackSumUs = AtomicLong(0L)
  private val encoderCallbackCount = AtomicLong(0L)
  private val maxEncoderCallbackUs = AtomicLong(0L)

  // Audio & muxer tracking
  private val audioPackets = AtomicLong(0L)
  private val lastAudioPtsUs = AtomicLong(-1L)
  private val totalBytesWritten = AtomicLong(0L)
  private val muxerWriteSumUs = AtomicLong(0L)
  private val muxerWriteCount = AtomicLong(0L)
  private val maxMuxerWriteUs = AtomicLong(0L)
  private val muxerQueueDepthCurrent = AtomicInteger(0)
  private val muxerQueueDepthPeak = AtomicInteger(0)
  private val muxerPoolAllocations = AtomicLong(0L)
  private val muxerBackpressureWaits = AtomicLong(0L)

  // Errors
  private val codecErrors = AtomicLong(0L)
  private val muxerErrors = AtomicLong(0L)
  private val audioErrors = AtomicLong(0L)

  // Thermal guard (ADPF recommends >= 10s between getThermalHeadroom calls from one thread)
  private var lastThermalQueryMs = 0L
  private var thermalApiChecked = false
  private var thermalHeadroomSupported = false

  fun configureEncoder(
    codecName: String,
    isHardwareAccelerated: Boolean,
    performancePointSupported: Boolean?,
    width: Int,
    height: Int,
    bitrateBps: Int,
  ) {
    this.codecName = codecName
    this.isHardwareAccelerated = isHardwareAccelerated
    this.performancePointSupported = performancePointSupported
    this.width = width
    this.height = height
    this.bitrateMbps = (bitrateBps / 1_000_000).coerceAtLeast(1)
  }

  fun onPauseStateChanged(paused: Boolean) {
    val now = clockMs()
    if (paused && !isPaused) {
      isPaused = true
      pauseStartedMs = now
    } else if (!paused && isPaused) {
      if (pauseStartedMs > 0L) {
        pausedAccumulatedMs += (now - pauseStartedMs).coerceAtLeast(0L)
        pauseStartedMs = 0L
      }
      isPaused = false
      resetIntervalBaseline.set(1)
    }
  }

  fun onVideoOutputFrame(
    rawPtsUs: Long,
    byteSize: Int,
    callbackDurationNs: Long,
    isKeyFrame: Boolean,
  ) {
    videoOutputFrames.incrementAndGet()
    if (isKeyFrame) {
      videoKeyFrames.incrementAndGet()
    }
    if (callbackDurationNs >= 0L) {
      val durationUs = callbackDurationNs / 1000L
      encoderCallbackSumUs.addAndGet(durationUs)
      encoderCallbackCount.incrementAndGet()
      updateMax(maxEncoderCallbackUs, durationUs)
    }

    val prevPts = lastVideoPtsUs.getAndSet(rawPtsUs)
    if (resetIntervalBaseline.compareAndSet(1, 0) || prevPts < 0L) {
      return
    }

    val deltaUs = rawPtsUs - prevPts
    if (deltaUs > 0L) {
      videoIntervalSumUs.addAndGet(deltaUs)
      videoIntervalCount.incrementAndGet()
      updateMax(peakFrameIntervalUs, deltaUs)

      if (deltaUs > delayedThresholdUs) {
        delayedFrameIntervals.incrementAndGet()
        val missed = ((deltaUs / expectedFrameIntervalUs) - 1L).coerceAtLeast(1L)
        estimatedDroppedFrames.addAndGet(missed)
      }
    }
    if (byteSize > 0) {
      // Actual written byte accounting also recorded at muxer drain; keeping frame path O(1)
    }
  }

  fun onAudioOutputPacket(rawPtsUs: Long) {
    audioPackets.incrementAndGet()
    lastAudioPtsUs.set(rawPtsUs)
  }

  fun onMuxerQueued(newQueueDepth: Int) {
    muxerQueueDepthCurrent.set(newQueueDepth)
    var currentPeak = muxerQueueDepthPeak.get()
    while (newQueueDepth > currentPeak) {
      if (muxerQueueDepthPeak.compareAndSet(currentPeak, newQueueDepth)) break
      currentPeak = muxerQueueDepthPeak.get()
    }
  }

  fun onMuxerDequeued(remainingQueueDepth: Int) {
    muxerQueueDepthCurrent.set(remainingQueueDepth)
  }

  fun onMuxerPoolAllocation() {
    muxerPoolAllocations.incrementAndGet()
  }

  fun onMuxerBackpressureWait() {
    muxerBackpressureWaits.incrementAndGet()
  }

  fun onMuxerSampleWritten(byteSize: Int, writeDurationNs: Long) {
    if (byteSize > 0) {
      totalBytesWritten.addAndGet(byteSize.toLong())
    }
    if (writeDurationNs >= 0L) {
      val durationUs = writeDurationNs / 1000L
      muxerWriteSumUs.addAndGet(durationUs)
      muxerWriteCount.incrementAndGet()
      updateMax(maxMuxerWriteUs, durationUs)
    }
  }

  fun onCodecError() {
    codecErrors.incrementAndGet()
  }

  fun onMuxerError() {
    muxerErrors.incrementAndGet()
  }

  fun onAudioError() {
    audioErrors.incrementAndGet()
  }

  fun snapshot(context: Context? = null): DiagnosticsSnapshot {
    val nowMs = clockMs()
    val livePausedMs =
      if (isPaused && pauseStartedMs > 0L) (nowMs - pauseStartedMs).coerceAtLeast(0L) else 0L
    val activeElapsedMs = (nowMs - sessionStartMs - pausedAccumulatedMs - livePausedMs).coerceAtLeast(1L)

    val outFrames = videoOutputFrames.get()
    val avgFps = (outFrames.toDouble() * 1000.0) / activeElapsedMs.toDouble()

    val intervalSamples = videoIntervalCount.get()
    val avgIntervalMs =
      if (intervalSamples > 0L) {
        (videoIntervalSumUs.get().toDouble() / intervalSamples.toDouble()) / 1000.0
      } else {
        0.0
      }
    val peakIntervalMs = peakFrameIntervalUs.get().toDouble() / 1000.0

    val cbCount = encoderCallbackCount.get()
    val avgCbUs = if (cbCount > 0L) encoderCallbackSumUs.get() / cbCount else 0L

    val muxCount = muxerWriteCount.get()
    val avgMuxUs = if (muxCount > 0L) muxerWriteSumUs.get() / muxCount else 0L

    val vPts = lastVideoPtsUs.get()
    val aPts = lastAudioPtsUs.get()
    val driftMs = if (vPts >= 0L && aPts >= 0L) (vPts - aPts) / 1000L else 0L

    val currentGc = readRuntimeStat("art.gc.gc-count")
    val currentBlockingGc = readRuntimeStat("art.gc.blocking-gc-count")
    val cpuDelta = (cpuTimeMs() - initialCpuMs).coerceAtLeast(0L)

    val runtime = Runtime.getRuntime()
    val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
    val nativeHeapMb = runCatching { Debug.getNativeHeapAllocatedSize() / (1024L * 1024L) }.getOrDefault(0L)

    val (thermalStatus, headroom) = sampleThermal(context, nowMs)

    return DiagnosticsSnapshot(
      targetFps = targetFps,
      codecName = codecName,
      isHardwareAccelerated = isHardwareAccelerated,
      performancePointSupported = performancePointSupported,
      width = width,
      height = height,
      bitrateMbps = bitrateMbps,
      activeElapsedMs = activeElapsedMs,
      videoOutputFrames = outFrames,
      videoKeyFrames = videoKeyFrames.get(),
      audioPackets = audioPackets.get(),
      delayedFrameIntervals = delayedFrameIntervals.get(),
      estimatedDroppedFrames = estimatedDroppedFrames.get(),
      averageOutputFps = avgFps,
      avgFrameIntervalMs = avgIntervalMs,
      peakFrameIntervalMs = peakIntervalMs,
      avgEncoderCallbackUs = avgCbUs,
      maxEncoderCallbackUs = maxEncoderCallbackUs.get(),
      avgMuxerWriteUs = avgMuxUs,
      maxMuxerWriteUs = maxMuxerWriteUs.get(),
      muxerQueueDepthCurrent = muxerQueueDepthCurrent.get(),
      muxerQueueDepthPeak = muxerQueueDepthPeak.get(),
      muxerPoolAllocations = muxerPoolAllocations.get(),
      muxerBackpressureWaits = muxerBackpressureWaits.get(),
      totalBytesWritten = totalBytesWritten.get(),
      avSyncDriftMs = driftMs,
      codecErrors = codecErrors.get(),
      muxerErrors = muxerErrors.get(),
      audioErrors = audioErrors.get(),
      gcCountDelta = (currentGc - initialGcCount).coerceAtLeast(0L),
      blockingGcCountDelta = (currentBlockingGc - initialBlockingGcCount).coerceAtLeast(0L),
      cpuTimeDeltaMs = cpuDelta,
      javaHeapUsedMb = javaHeapMb,
      nativeHeapAllocatedMb = nativeHeapMb,
      thermalStatus = thermalStatus,
      thermalHeadroom = headroom,
    )
  }

  fun logPeriodicSummary(context: Context?) {
    val snap = snapshot(context)
    Log.i(TAG, formatSummaryLine("PERIODIC", snap))
  }

  fun logFinalSummary(context: Context?) {
    val snap = snapshot(context)
    Log.i(TAG, formatSummaryLine("SESSION_FINAL", snap))
  }

  private fun sampleThermal(context: Context?, nowMs: Long): Pair<Int, Float> {
    if (context == null) return -1 to Float.NaN
    val powerManager =
      runCatching { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
        .getOrNull() ?: return -1 to Float.NaN
    val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(-1)
    var headroom = Float.NaN
    if (nowMs - lastThermalQueryMs >= THERMAL_MIN_INTERVAL_MS) {
      lastThermalQueryMs = nowMs
      val queried = runCatching { powerManager.getThermalHeadroom(30) }.getOrDefault(Float.NaN)
      if (!thermalApiChecked) {
        thermalApiChecked = true
        thermalHeadroomSupported = !queried.isNaN() && queried > 0f
      }
      if (thermalHeadroomSupported) {
        headroom = queried
      }
    }
    return status to headroom
  }

  private fun formatSummaryLine(stage: String, s: DiagnosticsSnapshot): String {
    val fpsFormatted = ((s.averageOutputFps * 10.0).toLong()) / 10.0
    val avgMsFormatted = ((s.avgFrameIntervalMs * 10.0).toLong()) / 10.0
    val peakMsFormatted = ((s.peakFrameIntervalMs * 10.0).toLong()) / 10.0
    val writtenMb = s.totalBytesWritten / (1024L * 1024L)
    val headroomStr = if (s.thermalHeadroom.isNaN()) "na" else "${((s.thermalHeadroom * 100f).toInt()) / 100f}"
    return "[$stage] codec=${s.codecName}(hw=${s.isHardwareAccelerated},perfPt=${s.performancePointSupported}) " +
      "${s.width}x${s.height}@${s.targetFps}fps(${s.bitrateMbps}Mbps) | " +
      "outFps=$fpsFormatted frames=${s.videoOutputFrames}(idr=${s.videoKeyFrames}) " +
      "delayed=${s.delayedFrameIntervals}(estDrop=${s.estimatedDroppedFrames}) " +
      "intervalMs=${avgMsFormatted}/${peakMsFormatted} | " +
      "cbUs=${s.avgEncoderCallbackUs}/${s.maxEncoderCallbackUs} " +
      "muxWriteUs=${s.avgMuxerWriteUs}/${s.maxMuxerWriteUs} " +
      "qDepth=${s.muxerQueueDepthCurrent}/peak=${s.muxerQueueDepthPeak} " +
      "poolAllocs=${s.muxerPoolAllocations} waits=${s.muxerBackpressureWaits} | " +
      "audioPkts=${s.audioPackets} avDriftMs=${s.avSyncDriftMs} writtenMB=$writtenMb | " +
      "cpuMs=${s.cpuTimeDeltaMs} gc=${s.gcCountDelta}(blk=${s.blockingGcCountDelta}) " +
      "heapMB=${s.javaHeapUsedMb}/${s.nativeHeapAllocatedMb} " +
      "thermal=${s.thermalStatus}/hdr=$headroomStr " +
      "errs(c=${s.codecErrors},m=${s.muxerErrors},a=${s.audioErrors})"
  }

  private fun updateMax(target: AtomicLong, candidate: Long) {
    var prev = target.get()
    while (candidate > prev) {
      if (target.compareAndSet(prev, candidate)) break
      prev = target.get()
    }
  }

  private fun readRuntimeStat(key: String): Long {
    return runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L }.getOrDefault(0L)
  }

  companion object {
    private const val TAG = "FrameCutDiagnostics"
    private const val THERMAL_MIN_INTERVAL_MS = 10_000L
  }
}
